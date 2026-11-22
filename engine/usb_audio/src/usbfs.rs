//! Linux usbfs ioctl wrappers for USB interface claim/release, kernel driver
//! detach/reattach, and control transfers. Conforms to <linux/usbdevice_fs.h>.

use std::ffi::{c_int, c_ulong, c_void};
use std::io::Error as IoError;

pub const USBDEVFS_CLAIMINTERFACE: c_ulong = 0x8004550f;
pub const USBDEVFS_RELEASEINTERFACE: c_ulong = 0x80045510;
pub const USBDEVFS_CONTROL: c_ulong = 0xc0185500;
pub const USBDEVFS_IOCTL: c_ulong = 0xc0105512;
pub const USBDEVFS_DISCONNECT_CMD: c_int = 0x5516;
pub const USBDEVFS_CONNECT_CMD: c_int = 0x5517;

extern "C" {
    fn ioctl(fd: c_int, request: c_ulong, ...) -> c_int;
}

#[repr(C)]
pub struct UsbdevfsIoctl {
    pub ifno: c_int,
    pub ioctl_code: c_int,
    pub data: *mut c_void,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usbfs_ioctl_memory_layout_and_constants() {
        assert_eq!(std::mem::size_of::<UsbdevfsCtrlTransfer>(), 24);
        assert_eq!(std::mem::size_of::<UsbdevfsIoctl>(), 16);
        assert_eq!(USBDEVFS_IOCTL, 0xc0105512);
        assert_eq!(USBDEVFS_DISCONNECT_CMD, 0x5516);
        assert_eq!(USBDEVFS_CONNECT_CMD, 0x5517);
    }
}
