//! Lock-free Single-Producer Single-Consumer (SPSC) byte ring buffer.
//! Bounded to <= 1 MB. Zero locks, zero allocations in audio path.

use std::alloc::{alloc_zeroed, dealloc, Layout};
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, AtomicUsize, Ordering};

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
    pub pkt_errors: AtomicU64,
    pub pkt_shorts: AtomicU64,
    pub last_pkt_err_status: AtomicI32,
    pub late_callbacks: AtomicU64,
    pub max_cb_gap_us: AtomicU64,
    pub max_slots_per_wakeup: AtomicU32,
    pub min_ring_fill: AtomicUsize,
    pub active_slots: AtomicU32,
    pub cushion_escalations: AtomicU32,
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
            pkt_errors: AtomicU64::new(0),
            pkt_shorts: AtomicU64::new(0),
            last_pkt_err_status: AtomicI32::new(0),
            late_callbacks: AtomicU64::new(0),
            max_cb_gap_us: AtomicU64::new(0),
            max_slots_per_wakeup: AtomicU32::new(0),
            min_ring_fill: AtomicUsize::new(actual_cap),
            active_slots: AtomicU32::new(0),
            cushion_escalations: AtomicU32::new(0),
        }
    }

    #[inline]
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    #[inline]
    pub fn available_read(&self) -> usize {
        // Load read_pos before write_pos so a concurrent write + clear cannot make r > w.
        let r = self.read_pos.load(Ordering::Acquire);
        let w = self.write_pos.load(Ordering::Acquire);
        // A clear and a write between the two loads leave r behind: never more than fits.
        w.wrapping_sub(r).min(self.capacity)
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
        let mut r = self.read_pos.load(Ordering::Acquire);
        while r != w {
            match self.read_pos.compare_exchange_weak(
                r,
                w,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => break,
                Err(actual) => r = actual,
            }
        }
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
        self.read_inner(dst, || {})
    }

    #[inline(always)]
    fn read_inner<F: FnOnce()>(&self, dst: &mut [u8], before_commit: F) -> usize {
        // Load read_pos before write_pos so a concurrent write + clear cannot make r > w.
        let r = self.read_pos.load(Ordering::Acquire);
        let w = self.write_pos.load(Ordering::Acquire);
        let available = w.wrapping_sub(r).min(self.capacity);

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

        before_commit();
        let next_r = r.wrapping_add(to_read);
        if self
            .read_pos
            .compare_exchange(r, next_r, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            to_read
        } else {
            // clear() advanced read_pos concurrently: keep the cleared position and discard stale bytes.
            dst[..to_read].fill(0);
            0
        }
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

    #[test]
    fn clear_during_in_progress_read_does_not_move_read_pos_backwards() {
        let rb = SpscRingBuffer::new(64);
        assert_eq!(rb.write(&[0xAAu8; 48]), 48);

        let mut mid = [0u8; 16];
        let n = rb.read_inner(&mut mid, || {
            rb.clear();
        });

        assert_eq!(n, 0, "interrupted read must not return stale pre-clear bytes");
        assert_eq!(
            rb.available_read(),
            0,
            "ring must remain empty after clear even if a read was in progress"
        );
        assert_eq!(
            rb.available_write(),
            64,
            "full capacity must be available after clear"
        );

        assert_eq!(rb.write(&[0xBBu8; 24]), 24);
        assert_eq!(rb.available_read(), 24);
        let mut post = [0u8; 32];
        let got = rb.read(&mut post);
        assert_eq!(got, 24);
        assert!(
            post[..got].iter().all(|&b| b == 0xBB),
            "subsequent reads must consume only post-flush data"
        );
    }

    #[test]
    fn clear_and_write_during_in_progress_read_does_not_make_ring_appear_full() {
        let rb = SpscRingBuffer::new(64);
        assert_eq!(rb.write(&[0xAAu8; 48]), 48);

        let mut mid = [0u8; 16];
        let n = rb.read_inner(&mut mid, || {
            rb.clear();
            assert_eq!(rb.write(&[0xBBu8; 48]), 48);
        });

        assert_eq!(n, 0);
        assert_eq!(rb.available_read(), 48);
        assert_eq!(
            rb.available_write(),
            16,
            "ring must not appear full after clear + post-flush write"
        );

        let mut post = [0u8; 64];
        let got = rb.read(&mut post);
        assert_eq!(got, 48);
        assert!(
            post[..got].iter().all(|&b| b == 0xBB),
            "only post-flush bytes may be readable"
        );
        assert_eq!(rb.available_read(), 0);
    }

    #[test]
    fn concurrent_read_write_clear_preserves_ring_invariants() {
        use std::sync::atomic::AtomicBool;
        use std::sync::Arc;
        use std::thread;

        let rb = Arc::new(SpscRingBuffer::new(256));
        let done = Arc::new(AtomicBool::new(false));
        let rb_cons = Arc::clone(&rb);
        let done_cons = Arc::clone(&done);

        let consumer = thread::spawn(move || {
            let mut buf = [0u8; 32];
            while !done_cons.load(Ordering::Acquire) {
                let _ = rb_cons.read(&mut buf);
                assert!(rb_cons.available_read() <= rb_cons.capacity());
            }
        });

        let chunk = [0x5Au8; 48];
        for i in 0..5000u32 {
            let _ = rb.write(&chunk);
            if i % 7 == 0 {
                rb.clear();
            }
            assert!(rb.available_write() <= rb.capacity());
        }
        done.store(true, Ordering::Release);
        consumer.join().expect("consumer thread completed");

        rb.clear();
        assert_eq!(rb.available_read(), 0);
        assert_eq!(rb.available_write(), rb.capacity());
    }
}
