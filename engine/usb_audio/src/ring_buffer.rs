//! Lock-free Single-Producer Single-Consumer (SPSC) byte ring buffer.
//! Bounded to <= 1 MB. Zero locks, zero allocations in audio path.

use std::alloc::{alloc_zeroed, dealloc, Layout};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

pub const DEFAULT_RING_CAPACITY: usize = 1048576; // 1 MB (power of 2)

pub struct SpscRingBuffer {
    buffer: *mut u8,
    layout: Layout,
    capacity: usize,
    mask: usize,
    write_pos: AtomicUsize,
    read_pos: AtomicUsize,
    pub frames_consumed: AtomicU64,
    pub underruns: AtomicU64,
    pub urb_errors: AtomicU64,
}

unsafe impl Send for SpscRingBuffer {}
unsafe impl Sync for SpscRingBuffer {}

impl SpscRingBuffer {
    pub fn new(capacity: usize) -> Self {
        assert!(capacity > 0 && capacity <= 1048576, "Capacity must be <= 1 MB");
        let power_of_two = capacity.next_power_of_two();
        let actual_cap = if power_of_two > 1048576 {
            1048576
        } else {
            power_of_two
        };

        let layout = Layout::from_size_align(actual_cap, 64)
            .expect("Valid ring buffer layout");
        let buffer = unsafe { alloc_zeroed(layout) };
        assert!(!buffer.is_null(), "Failed to allocate ring buffer memory");

        Self {
            buffer,
            layout,
            capacity: actual_cap,
            mask: actual_cap - 1,
            write_pos: AtomicUsize::new(0),
            read_pos: AtomicUsize::new(0),
            frames_consumed: AtomicU64::new(0),
            underruns: AtomicU64::new(0),
            urb_errors: AtomicU64::new(0),
        }
    }

    #[inline]
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    #[inline]
    pub fn available_read(&self) -> usize {
        let w = self.write_pos.load(Ordering::Acquire);
        let r = self.read_pos.load(Ordering::Relaxed);
        w.wrapping_sub(r)
    }

    #[inline]
    pub fn available_write(&self) -> usize {
        let r = self.read_pos.load(Ordering::Acquire);
        let w = self.write_pos.load(Ordering::Relaxed);
        self.capacity.saturating_sub(w.wrapping_sub(r))
    }

    #[inline]
    pub fn clear(&self) {
        let w = self.write_pos.load(Ordering::Acquire);
        self.read_pos.store(w, Ordering::Release);
    }

    /// Producer: write bytes into the ring buffer. Zero locks, zero allocation.
    pub fn write(&self, src: &[u8]) -> usize {
        let r = self.read_pos.load(Ordering::Acquire);
        let w = self.write_pos.load(Ordering::Relaxed);
        let occupied = w.wrapping_sub(r);
        let space = self.capacity.saturating_sub(occupied);

        let to_write = src.len().min(space);
        if to_write == 0 {
            return 0;
        }

        let start_idx = w & self.mask;
        let first_chunk = (self.capacity - start_idx).min(to_write);
        let second_chunk = to_write - first_chunk;

        unsafe {
            std::ptr::copy_nonoverlapping(
                src.as_ptr(),
                self.buffer.add(start_idx),
                first_chunk,
            );
            if second_chunk > 0 {
                std::ptr::copy_nonoverlapping(
                    src.as_ptr().add(first_chunk),
                    self.buffer,
                    second_chunk,
                );
            }
        }

        self.write_pos.store(w.wrapping_add(to_write), Ordering::Release);
        to_write
    }

    /// Producer: write raw memory into ring buffer (e.g. from direct ByteBuffer).
    ///
    /// # Safety
    /// `src` must point to valid memory for at least `len` bytes.
    pub unsafe fn write_raw(&self, src: *const u8, len: usize) -> usize {
        if src.is_null() || len == 0 {
            return 0;
        }
        let slice = std::slice::from_raw_parts(src, len);
        self.write(slice)
    }

    /// Consumer: read up to `dst.len()` bytes into `dst`.
    /// Zero locks, zero allocation. Returns number of actual bytes read.
    pub fn read(&self, dst: &mut [u8]) -> usize {
        let w = self.write_pos.load(Ordering::Acquire);
        let r = self.read_pos.load(Ordering::Relaxed);
        let available = w.wrapping_sub(r);

        let to_read = dst.len().min(available);
        if to_read == 0 {
            return 0;
        }

        let start_idx = r & self.mask;
        let first_chunk = (self.capacity - start_idx).min(to_read);
        let second_chunk = to_read - first_chunk;

        unsafe {
            std::ptr::copy_nonoverlapping(
                self.buffer.add(start_idx),
                dst.as_mut_ptr(),
                first_chunk,
            );
            if second_chunk > 0 {
                std::ptr::copy_nonoverlapping(
                    self.buffer,
                    dst.as_mut_ptr().add(first_chunk),
                    second_chunk,
                );
            }
        }

        self.read_pos.store(r.wrapping_add(to_read), Ordering::Release);
        to_read
    }

    /// Reset positions and counters.
    pub fn reset(&self) {
        self.write_pos.store(0, Ordering::Release);
        self.read_pos.store(0, Ordering::Release);
        self.frames_consumed.store(0, Ordering::Release);
        self.underruns.store(0, Ordering::Release);
        self.urb_errors.store(0, Ordering::Release);
    }
}

impl Drop for SpscRingBuffer {
    fn drop(&mut self) {
        if !self.buffer.is_null() {
            unsafe { dealloc(self.buffer, self.layout) };
            self.buffer = std::ptr::null_mut();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lockfree_ring_buffer() {
        let rb = SpscRingBuffer::new(1024);
        assert_eq!(rb.capacity(), 1024);
        assert_eq!(rb.available_write(), 1024);
        assert_eq!(rb.available_read(), 0);

        let data = [10u8, 20, 30, 40, 50, 60, 70, 80];
        let written = rb.write(&data);
        assert_eq!(written, 8);
        assert_eq!(rb.available_read(), 8);

        let mut out = [0u8; 8];
        let read = rb.read(&mut out);
        assert_eq!(read, 8);
        assert_eq!(out, data);
        assert_eq!(rb.available_read(), 0);
    }

    #[test]
    fn ring_buffer_bounded_wraparound_and_clear() {
        let rb = SpscRingBuffer::new(1024 * 1024);
        assert!(rb.capacity() <= 1_048_576);

        let small = SpscRingBuffer::new(64);
        let first = vec![0xAAu8; 48];
        assert_eq!(small.write(&first), 48);
        let mut out1 = vec![0u8; 32];
        assert_eq!(small.read(&mut out1), 32);
        assert!(out1.iter().all(|&b| b == 0xAA));

        // Write past the internal wrap-around boundary
        let wrap_in: Vec<u8> = (0u8..40).collect();
        assert_eq!(small.write(&wrap_in), 40);
        let mut out2 = vec![0u8; 16];
        assert_eq!(small.read(&mut out2), 16);
        assert!(out2.iter().all(|&b| b == 0xAA));
        let mut out3 = vec![0u8; 40];
        assert_eq!(small.read(&mut out3), 40);
        assert_eq!(out3, wrap_in);

        // clear() zeroes readable byte count while preserving ring capacity
        assert_eq!(small.write(&wrap_in), 40);
        assert_eq!(small.available_read(), 40);
        small.clear();
        assert_eq!(small.available_read(), 0);
        assert_eq!(small.capacity(), 64);
    }
}
