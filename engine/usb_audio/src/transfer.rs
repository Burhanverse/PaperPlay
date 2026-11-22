//! Isochronous USB transfer abstractions backed by libusb-1.0 via libusb1-sys.
//! Zero allocations after initialization, lock-free completion handling.

use std::ffi::c_void;
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicUsize, Ordering};
use std::sync::Arc;

use libusb1_sys::{
    libusb_alloc_transfer, libusb_cancel_transfer, libusb_fill_iso_transfer,
    libusb_free_transfer, libusb_submit_transfer, libusb_transfer,
};

/// Lock-free transfer completion state.
pub struct IsoTransferCompletion {
    pub completed: AtomicBool,
    pub status: AtomicI32,
    pub actual_length: AtomicUsize,
}

impl IsoTransferCompletion {
    pub fn new() -> Self {
        Self {
            completed: AtomicBool::new(false),
            status: AtomicI32::new(0),
            actual_length: AtomicUsize::new(0),
        }
    }

    pub fn reset(&self) {
        self.completed.store(false, Ordering::Release);
        self.status.store(0, Ordering::Release);
        self.actual_length.store(0, Ordering::Release);
    }
}

/// User data structure passed into libusb callbacks.
pub struct IsoTransferUserData {
    pub completion: Arc<IsoTransferCompletion>,
    pub buffer: Vec<u8>,
}

/// Managed wrapper around a raw `libusb_transfer` for isochronous endpoints.
pub struct IsoTransferSlot {
    transfer: NonNull<libusb_transfer>,
    user_data: *mut IsoTransferUserData,
    pub completion: Arc<IsoTransferCompletion>,
    pub packet_count: usize,
    pub max_packet_size: usize,
    pub in_flight: bool,
}

unsafe impl Send for IsoTransferSlot {}
unsafe impl Sync for IsoTransferSlot {}

impl IsoTransferSlot {
    pub fn new(packet_count: usize, max_packet_size: usize) -> Result<Self, i32> {
        let completion = Arc::new(IsoTransferCompletion::new());
        let total_capacity = packet_count * max_packet_size;
        let user_data = Box::new(IsoTransferUserData {
            completion: Arc::clone(&completion),
            buffer: vec![0u8; total_capacity.max(4)],
        });
        let user_data_ptr = Box::into_raw(user_data);

        let transfer = unsafe { libusb_alloc_transfer(packet_count as i32) };
        let Some(transfer_nonnull) = NonNull::new(transfer) else {
            unsafe {
                drop(Box::from_raw(user_data_ptr));
            }
            return Err(-1);
        };

        Ok(Self {
            transfer: transfer_nonnull,
            user_data: user_data_ptr,
            completion,
            packet_count,
            max_packet_size,
            in_flight: false,
        })
    }

    #[inline]
    pub fn buffer_mut(&mut self) -> &mut [u8] {
        unsafe { &mut (*self.user_data).buffer }
    }

    #[inline]
    pub fn buffer(&self) -> &[u8] {
        unsafe { &(*self.user_data).buffer }
    }

    /// Submit an isochronous transfer to the device.
    ///
    /// # Safety
    /// The caller must ensure `dev_handle` is valid and the interface is claimed.
    pub unsafe fn submit(
        &mut self,
        dev_handle: *mut libusb1_sys::libusb_device_handle,
        endpoint: u8,
        payload_len: usize,
        packet_sizes: &[usize],
        timeout_ms: u32,
    ) -> Result<(), i32> {
        if self.in_flight {
            return Err(-16); // -EBUSY
        }
        if (endpoint & 0x80) != 0 {
            return Err(-22); // -EINVAL: expected OUT endpoint for audio stream
        }
        let user_data = &mut *self.user_data;
        self.completion.reset();

        libusb_fill_iso_transfer(
            self.transfer.as_ptr(),
            dev_handle,
            endpoint,
            user_data.buffer.as_mut_ptr(),
            payload_len as i32,
            packet_sizes.len() as i32,
            iso_transfer_callback,
            self.user_data as *mut c_void,
            timeout_ms,
        );

        let desc_ptr = (*self.transfer.as_ptr()).iso_packet_desc.as_mut_ptr();
        for (i, &size) in packet_sizes.iter().enumerate() {
            let pkt_desc = &mut *desc_ptr.add(i);
            pkt_desc.length = size.min(self.max_packet_size) as u32;
            pkt_desc.actual_length = 0;
            pkt_desc.status = libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED;
        }

        let ret = libusb_submit_transfer(self.transfer.as_ptr());
        if ret == 0 {
            self.in_flight = true;
            Ok(())
        } else {
            Err(ret)
        }
    }

    /// Submit an isochronous IN transfer (e.g. for feedback endpoint).
    pub unsafe fn submit_in(
        &mut self,
        dev_handle: *mut libusb1_sys::libusb_device_handle,
        endpoint: u8,
        packet_size: usize,
        timeout_ms: u32,
    ) -> Result<(), i32> {
        if self.in_flight {
            return Err(-16); // -EBUSY
        }
        let user_data = &mut *self.user_data;
        self.completion.reset();

        libusb_fill_iso_transfer(
            self.transfer.as_ptr(),
            dev_handle,
            endpoint,
            user_data.buffer.as_mut_ptr(),
            packet_size as i32,
            1,
            iso_transfer_callback,
            self.user_data as *mut c_void,
            timeout_ms,
        );

        let desc_ptr = (*self.transfer.as_ptr()).iso_packet_desc.as_mut_ptr();
        (*desc_ptr).length = packet_size as u32;

        let ret = libusb_submit_transfer(self.transfer.as_ptr());
        if ret == 0 {
            self.in_flight = true;
            Ok(())
        } else {
            Err(ret)
        }
    }

    /// Cancel in-flight transfer.
    pub fn cancel(&mut self) -> Result<(), i32> {
        if !self.in_flight {
            return Ok(());
        }
        let ret = unsafe { libusb_cancel_transfer(self.transfer.as_ptr()) };
        if ret == 0 || ret == libusb1_sys::constants::LIBUSB_ERROR_NOT_FOUND {
            Ok(())
        } else {
            Err(ret)
        }
    }

    pub fn mark_completed(&mut self) {
        self.in_flight = false;
    }
}

impl Drop for IsoTransferSlot {
    fn drop(&mut self) {
        if self.in_flight {
            let _ = self.cancel();
        }
        unsafe {
            libusb_free_transfer(self.transfer.as_ptr());
            drop(Box::from_raw(self.user_data));
        }
    }
}

/// Real-time callback triggered when a transfer completes or fails.
/// Catches unwinds to prevent panic across FFI boundaries. Zero locks.
extern "system" fn iso_transfer_callback(transfer: *mut libusb_transfer) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if transfer.is_null() {
            return;
        }
        unsafe {
            let user_data_ptr = (*transfer).user_data as *mut IsoTransferUserData;
            if user_data_ptr.is_null() {
                return;
            }
            let user_data = &*user_data_ptr;
            user_data.completion.status.store((*transfer).status, Ordering::Release);
            user_data.completion.actual_length.store((*transfer).actual_length as usize, Ordering::Release);
            user_data.completion.completed.store(true, Ordering::Release);
        }
    }));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_iso_transfer_slot_allocation_and_buffer() {
        let mut slot = IsoTransferSlot::new(8, 256).expect("Slot allocation succeeded");
        assert_eq!(slot.packet_count, 8);
        assert_eq!(slot.max_packet_size, 256);
        assert!(slot.buffer().len() >= 8 * 256);
        assert!(!slot.in_flight);
        assert!(!slot.completion.completed.load(Ordering::Acquire));

        slot.buffer_mut()[0] = 0xAA;
        slot.buffer_mut()[2047] = 0x55;
        assert_eq!(slot.buffer()[0], 0xAA);
        assert_eq!(slot.buffer()[2047], 0x55);

        slot.completion.status.store(0, Ordering::Release);
        slot.completion.completed.store(true, Ordering::Release);
        assert!(slot.completion.completed.swap(false, Ordering::AcqRel));
        assert!(!slot.completion.completed.load(Ordering::Acquire));
    }
}
