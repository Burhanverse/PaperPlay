//! Isochronous USB transfer abstractions backed by libusb-1.0 via libusb1-sys.
//! Zero allocations after initialization, lock-free completion handling.

use std::ffi::c_void;
use std::ptr::NonNull;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU8, AtomicUsize, Ordering};
use std::sync::Arc;

use libusb1_sys::{
    libusb_alloc_transfer, libusb_cancel_transfer, libusb_fill_iso_transfer,
    libusb_free_transfer, libusb_iso_packet_descriptor, libusb_submit_transfer, libusb_transfer,
};

const STATE_IDLE: u8 = 0;
const STATE_IN_FLIGHT: u8 = 1;
const STATE_COMPLETED: u8 = 2;
const STATE_ABANDONED: u8 = 3;

#[inline]
unsafe fn free_transfer_and_user_data(
    transfer: *mut libusb_transfer,
    user_data_ptr: *mut IsoTransferUserData,
) {
    #[cfg(test)]
    (*user_data_ptr).freed_transfer.store(true, Ordering::Release);
    libusb_free_transfer(transfer);
    drop(Box::from_raw(user_data_ptr));
}

/// Lock-free transfer completion state, including per-packet ISO error/short telemetry.
pub struct IsoTransferCompletion {
    pub completed: AtomicBool,
    pub status: AtomicI32,
    pub actual_length: AtomicUsize,
    pub pkt_err_count: AtomicU32,
    pub pkt_short_count: AtomicU32,
    pub last_pkt_err_status: AtomicI32,
    pub first_err_pkt_idx: AtomicI32,
}

impl IsoTransferCompletion {
    pub fn new() -> Self {
        Self {
            completed: AtomicBool::new(false),
            status: AtomicI32::new(0),
            actual_length: AtomicUsize::new(0),
            pkt_err_count: AtomicU32::new(0),
            pkt_short_count: AtomicU32::new(0),
            last_pkt_err_status: AtomicI32::new(0),
            first_err_pkt_idx: AtomicI32::new(-1),
        }
    }

    pub fn reset(&self) {
        self.completed.store(false, Ordering::Release);
        self.status.store(0, Ordering::Release);
        self.actual_length.store(0, Ordering::Release);
        self.pkt_err_count.store(0, Ordering::Release);
        self.pkt_short_count.store(0, Ordering::Release);
        self.last_pkt_err_status.store(0, Ordering::Release);
        self.first_err_pkt_idx.store(-1, Ordering::Release);
    }
}

/// Inspects every ISO packet descriptor in a completed libusb transfer.
/// Returns `(pkt_err_count, pkt_short_count, last_err_status, first_err_pkt_idx)`.
/// Zero allocations, zero locks, panic-free.
#[inline]
pub(crate) fn inspect_iso_packets(descs: &[libusb_iso_packet_descriptor]) -> (u32, u32, i32, i32) {
    let mut err_count: u32 = 0;
    let mut short_count: u32 = 0;
    let mut last_err_status: i32 = 0;
    let mut first_err_idx: i32 = -1;
    for (i, d) in descs.iter().enumerate() {
        if d.status != libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED {
            err_count = err_count.saturating_add(1);
            last_err_status = d.status;
            if first_err_idx < 0 {
                first_err_idx = i as i32;
            }
        } else if d.actual_length != d.length {
            short_count = short_count.saturating_add(1);
            if first_err_idx < 0 {
                first_err_idx = i as i32;
            }
        }
    }
    (err_count, short_count, last_err_status, first_err_idx)
}

/// User data structure passed into libusb callbacks.
pub struct IsoTransferUserData {
    pub completion: Arc<IsoTransferCompletion>,
    pub buffer: Vec<u8>,
    lifecycle: AtomicU8,
    #[cfg(test)]
    freed_transfer: Arc<AtomicBool>,
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
            lifecycle: AtomicU8::new(STATE_IDLE),
            #[cfg(test)]
            freed_transfer: Arc::new(AtomicBool::new(false)),
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
        if self.in_flight || (*self.user_data).lifecycle.load(Ordering::Acquire) == STATE_IN_FLIGHT {
            return Err(-16); // -EBUSY
        }
        if (endpoint & 0x80) != 0 {
            return Err(-22); // -EINVAL: expected OUT endpoint for audio stream
        }
        let user_data = &mut *self.user_data;
        self.completion.reset();
        user_data.lifecycle.store(STATE_IN_FLIGHT, Ordering::Release);

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
            user_data.lifecycle.store(STATE_IDLE, Ordering::Release);
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
        if self.in_flight || (*self.user_data).lifecycle.load(Ordering::Acquire) == STATE_IN_FLIGHT {
            return Err(-16); // -EBUSY
        }
        let user_data = &mut *self.user_data;
        self.completion.reset();
        user_data.lifecycle.store(STATE_IN_FLIGHT, Ordering::Release);

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
            user_data.lifecycle.store(STATE_IDLE, Ordering::Release);
            Err(ret)
        }
    }

    /// Cancel in-flight transfer.
    pub fn cancel(&mut self) -> Result<(), i32> {
        if !self.in_flight {
            return Ok(());
        }
        let transfer_ptr = self.transfer.as_ptr();
        if unsafe { (*transfer_ptr).dev_handle.is_null() } {
            return Ok(());
        }
        let ret = unsafe { libusb_cancel_transfer(transfer_ptr) };
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
        // Only free transfer + user_data if no callback is still pending.
        // If still STATE_IN_FLIGHT, hand ownership to iso_transfer_callback via STATE_ABANDONED
        // so both allocations stay valid until libusb completes cancellation and invokes the callback.
        let handed_off = unsafe {
            (*self.user_data)
                .lifecycle
                .compare_exchange(
                    STATE_IN_FLIGHT,
                    STATE_ABANDONED,
                    Ordering::AcqRel,
                    Ordering::Acquire,
                )
                .is_ok()
        };
        if !handed_off {
            unsafe {
                free_transfer_and_user_data(self.transfer.as_ptr(), self.user_data);
            }
        }
    }
}

/// Real-time callback triggered when a transfer completes or fails.
/// Catches unwinds to prevent panic across FFI boundaries. Zero locks, zero allocations.
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
            let status = (*transfer).status;
            let actual_length = (*transfer).actual_length as usize;
            let num_pkts = (*transfer).num_iso_packets.max(0) as usize;
            let (pkt_errs, pkt_shorts, last_pkt_err, first_err_idx) = if num_pkts > 0 {
                let descs = std::slice::from_raw_parts(
                    (*transfer).iso_packet_desc.as_ptr(),
                    num_pkts,
                );
                inspect_iso_packets(descs)
            } else {
                (0, 0, 0, -1)
            };
            let prev_state = {
                let user_data = &*user_data_ptr;
                user_data.completion.status.store(status, Ordering::Release);
                user_data.completion.actual_length.store(actual_length, Ordering::Release);
                user_data.completion.pkt_err_count.store(pkt_errs, Ordering::Release);
                user_data.completion.pkt_short_count.store(pkt_shorts, Ordering::Release);
                user_data.completion.last_pkt_err_status.store(last_pkt_err, Ordering::Release);
                user_data.completion.first_err_pkt_idx.store(first_err_idx, Ordering::Release);
                user_data.completion.completed.store(true, Ordering::Release);
                user_data.lifecycle.swap(STATE_COMPLETED, Ordering::AcqRel)
            };
            if prev_state == STATE_ABANDONED {
                free_transfer_and_user_data(transfer, user_data_ptr);
            }
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

    #[test]
    fn test_drop_defers_free_until_late_cancellation_callback() {
        let mut slot = IsoTransferSlot::new(4, 128).expect("Slot allocation succeeded");
        let completion = Arc::clone(&slot.completion);
        let freed_transfer = unsafe { Arc::clone(&(*slot.user_data).freed_transfer) };
        let raw_transfer = slot.transfer.as_ptr();

        // Simulate a submitted transfer whose cancellation timed out before Drop.
        unsafe {
            (*raw_transfer).user_data = slot.user_data as *mut c_void;
            (*raw_transfer).status = libusb1_sys::constants::LIBUSB_TRANSFER_CANCELLED;
            (*raw_transfer).actual_length = 0;
            (*slot.user_data).lifecycle.store(STATE_IN_FLIGHT, Ordering::Release);
        }
        slot.in_flight = true;

        assert_eq!(Arc::strong_count(&completion), 3);
        assert!(!freed_transfer.load(Ordering::Acquire));

        // Drop while still in flight: both transfer and user_data must stay allocated.
        drop(slot);

        assert_eq!(
            Arc::strong_count(&completion),
            2,
            "user_data must remain allocated until libusb delivers the cancellation callback"
        );
        assert!(
            !freed_transfer.load(Ordering::Acquire),
            "libusb_transfer must remain allocated until libusb delivers the cancellation callback"
        );

        // Late libusb cancellation callback now runs and frees both allocations.
        iso_transfer_callback(raw_transfer);

        assert!(completion.completed.load(Ordering::Acquire));
        assert_eq!(
            completion.status.load(Ordering::Acquire),
            libusb1_sys::constants::LIBUSB_TRANSFER_CANCELLED
        );
        assert_eq!(
            Arc::strong_count(&completion),
            1,
            "callback must free user_data after abandoned transfer completes"
        );
        assert!(
            freed_transfer.load(Ordering::Acquire),
            "callback must free libusb_transfer after abandoned transfer completes"
        );
    }

    #[test]
    fn test_completed_callback_before_drop_frees_on_drop_only() {
        let mut slot = IsoTransferSlot::new(4, 128).expect("Slot allocation succeeded");
        let completion = Arc::clone(&slot.completion);
        let freed_transfer = unsafe { Arc::clone(&(*slot.user_data).freed_transfer) };
        let raw_transfer = slot.transfer.as_ptr();

        unsafe {
            (*raw_transfer).user_data = slot.user_data as *mut c_void;
            (*raw_transfer).status = libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED;
            (*raw_transfer).actual_length = 64;
            (*slot.user_data).lifecycle.store(STATE_IN_FLIGHT, Ordering::Release);
        }
        slot.in_flight = true;

        // Normal path: callback finishes while slot is still alive.
        iso_transfer_callback(raw_transfer);

        assert!(completion.completed.load(Ordering::Acquire));
        assert_eq!(completion.actual_length.load(Ordering::Acquire), 64);
        assert_eq!(Arc::strong_count(&completion), 3);
        assert!(!freed_transfer.load(Ordering::Acquire));

        // Dropping the slot now frees both allocations immediately.
        drop(slot);

        assert_eq!(Arc::strong_count(&completion), 1);
        assert!(freed_transfer.load(Ordering::Acquire));
    }

    #[test]
    fn test_inspect_iso_packets_detects_per_packet_errors_and_short_packets() {
        let mk_ok = || libusb_iso_packet_descriptor {
            length: 192,
            actual_length: 192,
            status: libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED,
        };
        let short = libusb_iso_packet_descriptor {
            length: 192,
            actual_length: 96,
            status: libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED,
        };
        let err_proto = libusb_iso_packet_descriptor {
            length: 192,
            actual_length: 0,
            status: libusb1_sys::constants::LIBUSB_TRANSFER_ERROR,
        };
        let err_timeout = libusb_iso_packet_descriptor {
            length: 192,
            actual_length: 0,
            status: libusb1_sys::constants::LIBUSB_TRANSFER_TIMED_OUT,
        };

        assert_eq!(inspect_iso_packets(&[mk_ok(), mk_ok(), mk_ok()]), (0, 0, 0, -1));
        assert_eq!(
            inspect_iso_packets(&[mk_ok(), short, err_proto, err_timeout]),
            (2, 1, libusb1_sys::constants::LIBUSB_TRANSFER_TIMED_OUT, 1)
        );
    }

    #[test]
    fn test_callback_records_per_packet_errors_even_when_top_status_completed() {
        let mut slot = IsoTransferSlot::new(4, 128).expect("Slot allocation succeeded");
        let completion = Arc::clone(&slot.completion);
        let raw_transfer = slot.transfer.as_ptr();
        unsafe {
            (*raw_transfer).user_data = slot.user_data as *mut c_void;
            (*raw_transfer).status = libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED;
            (*raw_transfer).actual_length = 256;
            (*raw_transfer).num_iso_packets = 4;
            let descs = (*raw_transfer).iso_packet_desc.as_mut_ptr();
            (*descs.add(0)).length = 64;
            (*descs.add(0)).actual_length = 64;
            (*descs.add(0)).status = libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED;
            (*descs.add(1)).length = 64;
            (*descs.add(1)).actual_length = 32;
            (*descs.add(1)).status = libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED;
            (*descs.add(2)).length = 64;
            (*descs.add(2)).actual_length = 0;
            (*descs.add(2)).status = libusb1_sys::constants::LIBUSB_TRANSFER_ERROR;
            (*descs.add(3)).length = 64;
            (*descs.add(3)).actual_length = 64;
            (*descs.add(3)).status = libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED;
            (*slot.user_data).lifecycle.store(STATE_IN_FLIGHT, Ordering::Release);
        }
        slot.in_flight = true;

        iso_transfer_callback(raw_transfer);

        assert!(completion.completed.load(Ordering::Acquire));
        assert_eq!(completion.status.load(Ordering::Acquire), libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED);
        assert_eq!(completion.pkt_err_count.load(Ordering::Acquire), 1);
        assert_eq!(completion.pkt_short_count.load(Ordering::Acquire), 1);
        assert_eq!(completion.last_pkt_err_status.load(Ordering::Acquire), libusb1_sys::constants::LIBUSB_TRANSFER_ERROR);
        assert_eq!(completion.first_err_pkt_idx.load(Ordering::Acquire), 1);
    }
}
