//! Linux usbfs ioctl definitions and raw structures for USB isochronous transfers.
//! Conforms to <linux/usbdevice_fs.h>. Zero external dependencies.

use std::alloc::{alloc_zeroed, dealloc, Layout};
use std::ffi::{c_int, c_ulong, c_void};
use std::io::Error as IoError;

pub const USBDEVFS_URB_SHORT_NOT_OK: u32 = 0x01;
pub const USBDEVFS_URB_ISO_ASAP: u32 = 0x02;
pub const USBDEVFS_URB_TYPE_ISO: u8 = 0;
pub const USBDEVFS_URB_TYPE_INTERRUPT: u8 = 1;

pub const USBDEVFS_SUBMITURB: c_ulong = 0x8038550a;
pub const USBDEVFS_DISCARDURB: c_ulong = 0x0000550b;
pub const USBDEVFS_REAPURB: c_ulong = 0x4008550c;
pub const USBDEVFS_REAPURBNDELAY: c_ulong = 0x4008550d;
pub const USBDEVFS_CLAIMINTERFACE: c_ulong = 0x8004550f;
pub const USBDEVFS_RELEASEINTERFACE: c_ulong = 0x80045510;
pub const USBDEVFS_CONNECT: c_ulong = 0x00005517;
pub const USBDEVFS_DISCONNECT: c_ulong = 0x00005516;
pub const USBDEVFS_SETINTERFACE: c_ulong = 0x80085504;
pub const USBDEVFS_CONTROL: c_ulong = 0xc0185500;
pub const USBDEVFS_GET_CAPABILITIES: c_ulong = 0x8004551a;

pub const EAGAIN: i32 = 11;
pub const ENODEV: i32 = 19;
pub const EPIPE: i32 = 32;
pub const ECONNRESET: i32 = 104;
pub const ESHUTDOWN: i32 = 108;

extern "C" {
    fn ioctl(fd: c_int, request: c_ulong, ...) -> c_int;
}

#[repr(C)]
#[derive(Debug, Default, Copy, Clone, PartialEq, Eq)]
pub struct UsbdevfsIsoPacketDesc {
    pub length: u32,
    pub actual_length: u32,
    pub status: u32,
}

#[repr(C)]
#[derive(Debug)]
pub struct UsbdevfsUrb {
    pub urb_type: u8,
    pub endpoint: u8,
    pub status: i32,
    pub flags: u32,
    pub buffer: *mut u8,
    pub buffer_length: i32,
    pub actual_length: i32,
    pub start_frame: i32,
    pub number_of_packets: i32,
    pub error_count: i32,
    pub signr: u32,
    pub usercontext: *mut c_void,
}

#[repr(C)]
pub struct UsbdevfsSetInterface {
    pub interface: u32,
    pub altsetting: u32,
}

/// A heap-allocated Isochronous URB with a trailing array of `UsbdevfsIsoPacketDesc`
/// and a pre-allocated contiguous payload buffer.
pub struct IsoUrb {
    raw_ptr: *mut u8,
    layout: Layout,
    pub num_packets: usize,
    pub payload_buffer: Vec<u8>,
}

unsafe impl Send for IsoUrb {}
unsafe impl Sync for IsoUrb {}

impl IsoUrb {
    pub fn new(endpoint: u8, num_packets: usize, max_packet_size: usize) -> Self {
        assert!(num_packets > 0, "num_packets must be > 0");
        let header_size = std::mem::size_of::<UsbdevfsUrb>();
        let desc_size = std::mem::size_of::<UsbdevfsIsoPacketDesc>() * num_packets;
        let total_size = header_size + desc_size;
        let align = std::mem::align_of::<UsbdevfsUrb>().max(8);

        let layout = Layout::from_size_align(total_size, align)
            .expect("Valid URB memory layout");
        let raw_ptr = unsafe { alloc_zeroed(layout) };
        assert!(!raw_ptr.is_null(), "Failed to allocate URB memory");

        let urb = unsafe { &mut *(raw_ptr as *mut UsbdevfsUrb) };
        urb.urb_type = USBDEVFS_URB_TYPE_ISO;
        urb.endpoint = endpoint;
        urb.flags = USBDEVFS_URB_ISO_ASAP;
        urb.number_of_packets = num_packets as i32;
        urb.usercontext = raw_ptr as *mut c_void;

        let total_payload = num_packets * max_packet_size;
        let mut payload_buffer = vec![0u8; total_payload];
        urb.buffer = payload_buffer.as_mut_ptr();
        urb.buffer_length = total_payload as i32;

        Self {
            raw_ptr,
            layout,
            num_packets,
            payload_buffer,
        }
    }

    #[inline]
    pub fn urb_ptr(&self) -> *mut UsbdevfsUrb {
        self.raw_ptr as *mut UsbdevfsUrb
    }

    #[inline]
    pub fn urb_header(&mut self) -> &mut UsbdevfsUrb {
        unsafe { &mut *(self.raw_ptr as *mut UsbdevfsUrb) }
    }

    #[inline]
    pub fn packet_descs(&mut self) -> &mut [UsbdevfsIsoPacketDesc] {
        let desc_ptr = unsafe {
            self.raw_ptr.add(std::mem::size_of::<UsbdevfsUrb>())
                as *mut UsbdevfsIsoPacketDesc
        };
        unsafe { std::slice::from_raw_parts_mut(desc_ptr, self.num_packets) }
    }

    #[inline]
    pub fn set_packet_lengths(&mut self, lengths: &[usize]) {
        let n = self.num_packets.min(lengths.len());
        let buf_ptr = self.payload_buffer.as_mut_ptr();
        let descs = self.packet_descs();
        let mut offset = 0;
        for i in 0..n {
            descs[i].length = lengths[i] as u32;
            descs[i].actual_length = 0;
            descs[i].status = 0;
            offset += lengths[i];
        }
        let urb = self.urb_header();
        urb.buffer = buf_ptr;
        urb.buffer_length = offset as i32;
    }
}

impl Drop for IsoUrb {
    fn drop(&mut self) {
        if !self.raw_ptr.is_null() {
            unsafe { dealloc(self.raw_ptr, self.layout) };
            self.raw_ptr = std::ptr::null_mut();
        }
    }
}

pub fn submit_urb(fd: i32, urb: &mut IsoUrb) -> Result<(), i32> {
    let ret = unsafe { ioctl(fd, USBDEVFS_SUBMITURB, urb.urb_ptr()) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(())
    }
}

pub fn discard_urb(fd: i32, urb_ptr: *mut UsbdevfsUrb) -> Result<(), i32> {
    let ret = unsafe { ioctl(fd, USBDEVFS_DISCARDURB, urb_ptr) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(())
    }
}

pub fn reap_urb_ndelay(fd: i32) -> Result<Option<*mut c_void>, i32> {
    let mut ptr: *mut c_void = std::ptr::null_mut();
    let ret = unsafe { ioctl(fd, USBDEVFS_REAPURBNDELAY, &mut ptr) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        if err == EAGAIN {
            Ok(None)
        } else {
            Err(err)
        }
    } else {
        Ok(Some(ptr))
    }
}

pub fn get_capabilities(fd: i32) -> Result<u32, i32> {
    let mut caps: u32 = 0;
    let ret = unsafe { ioctl(fd, USBDEVFS_GET_CAPABILITIES, &mut caps as *mut u32) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(caps)
    }
}

pub fn claim_interface(fd: i32, intf: u32) -> Result<(), i32> {
    let ret = unsafe { ioctl(fd, USBDEVFS_CLAIMINTERFACE, &intf as *const u32) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(())
    }
}

pub fn release_interface(fd: i32, intf: u32) -> Result<(), i32> {
    let ret = unsafe { ioctl(fd, USBDEVFS_RELEASEINTERFACE, &intf as *const u32) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(())
    }
}

pub fn reattach_kernel_driver(fd: i32, intf: u32) -> Result<(), i32> {
    let mut cmd = UsbdevfsIoctl {
        ifno: intf as c_int,
        ioctl_code: USBDEVFS_CONNECT_CMD,
        data: std::ptr::null_mut(),
    };
    let ret = unsafe { ioctl(fd, USBDEVFS_IOCTL, &mut cmd as *mut UsbdevfsIoctl) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(())
    }
}

#[repr(C)]
pub struct UsbdevfsIoctl {
    pub ifno: c_int,
    pub ioctl_code: c_int,
    pub data: *mut c_void,
}

pub const USBDEVFS_IOCTL: c_ulong = 0xc0105512;
pub const USBDEVFS_DISCONNECT_CMD: c_int = 0x5516;
pub const USBDEVFS_CONNECT_CMD: c_int = 0x5517;

pub fn detach_kernel_driver(fd: i32, intf: u32) -> Result<(), i32> {
    let mut cmd = UsbdevfsIoctl {
        ifno: intf as c_int,
        ioctl_code: USBDEVFS_DISCONNECT_CMD,
        data: std::ptr::null_mut(),
    };
    let ret = unsafe { ioctl(fd, USBDEVFS_IOCTL, &mut cmd as *mut UsbdevfsIoctl) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(())
    }
}

#[repr(C)]
#[derive(Debug)]
pub struct UsbdevfsCtrlTransfer {
    pub request_type: u8,
    pub request: u8,
    pub value: u16,
    pub index: u16,
    pub length: u16,
    pub timeout: u32,
    pub data: *mut c_void,
}

pub fn control_transfer(
    fd: i32,
    request_type: u8,
    request: u8,
    value: u16,
    index: u16,
    data: &mut [u8],
    timeout_ms: u32,
) -> Result<usize, i32> {
    let mut ctrl = UsbdevfsCtrlTransfer {
        request_type,
        request,
        value,
        index,
        length: data.len() as u16,
        timeout: timeout_ms,
        data: if data.is_empty() {
            std::ptr::null_mut()
        } else {
            data.as_mut_ptr() as *mut c_void
        },
    };
    let ret = unsafe { ioctl(fd, USBDEVFS_CONTROL, &mut ctrl as *mut UsbdevfsCtrlTransfer) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(ret as usize)
    }
}

pub fn set_interface(fd: i32, intf: u32, alt: u32) -> Result<(), i32> {
    let s = UsbdevfsSetInterface {
        interface: intf,
        altsetting: alt,
    };
    let ret = unsafe { ioctl(fd, USBDEVFS_SETINTERFACE, &s as *const UsbdevfsSetInterface) };
    if ret < 0 {
        let err = IoError::last_os_error().raw_os_error().unwrap_or(-1);
        Err(err)
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urb_memory_layout_and_offsets() {
        assert_eq!(std::mem::size_of::<UsbdevfsIsoPacketDesc>(), 12);
        assert_eq!(std::mem::size_of::<UsbdevfsUrb>(), 56);
        assert_eq!(std::mem::size_of::<UsbdevfsCtrlTransfer>(), 24);
        assert_eq!(std::mem::size_of::<UsbdevfsIoctl>(), 16);
        assert_eq!(USBDEVFS_IOCTL, 0xc0105512);
        assert_eq!(USBDEVFS_DISCONNECT_CMD, 0x5516);
        assert_eq!(USBDEVFS_CONNECT_CMD, 0x5517);
        let urb = IsoUrb::new(0x01, 8, 256);
        assert_eq!(urb.num_packets, 8);
        assert_eq!(urb.payload_buffer.len(), 8 * 256);
    }
}
