//! USB device session management using rusb and libusb-1.0.
//! Unifies interface claiming, kernel driver detachment, and sample rate negotiation.

use std::os::fd::RawFd;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use rusb::{Context, DeviceHandle, UsbContext};

use crate::log::{
    GLOBAL_NATIVE_LOG, TAG_CLAIM_INTERFACE, TAG_CLOCK_GET_CUR, TAG_CLOCK_GET_RANGE,
    TAG_CLOCK_SET_CUR, TAG_DETACH_DRIVER, TAG_OPEN_DEVICE, TAG_REATTACH_DRIVER,
    TAG_RELEASE_INTERFACE, TAG_SET_ALT_SETTING,
};

pub const FU_MUTE_SELECTOR: u16 = 0x01;
pub const FU_VOLUME_SELECTOR: u16 = 0x02;

#[inline]
pub fn fu_mute_w_value(channel: u8) -> u16 {
    (FU_MUTE_SELECTOR << 8) | (channel as u16)
}

#[inline]
pub fn fu_volume_w_value(channel: u8) -> u16 {
    (FU_VOLUME_SELECTOR << 8) | (channel as u16)
}

#[inline]
pub fn fu_w_index(fu_id: u8, control_iface: u8) -> u16 {
    ((fu_id as u16) << 8) | (control_iface as u16)
}

#[inline]
pub fn fu_get_cur_request(is_uac2: bool) -> u8 {
    if is_uac2 { 0x01 } else { 0x81 }
}

/// Computes the safe release and kernel-driver reattach order for claimed interfaces:
/// 1. Release ALL claimed interfaces first (reverse order: streaming before control).
/// 2. Reattach kernel drivers AFTER every interface is released (ascending order: control iface 0 first).
pub fn interface_release_and_reattach_plan(claimed: &[u8]) -> (Vec<u8>, Vec<u8>) {
    let mut release_order = Vec::with_capacity(claimed.len());
    for &iface in claimed.iter().rev() {
        if !release_order.contains(&iface) {
            release_order.push(iface);
        }
    }
    let mut reattach_order = release_order.clone();
    reattach_order.sort_unstable();
    (release_order, reattach_order)
}

fn release_and_reattach_interfaces(handle: &DeviceHandle<Context>, claimed: &[u8]) {
    let (release_order, reattach_order) = interface_release_and_reattach_plan(claimed);
    for iface in release_order {
        let rel_res = match handle.release_interface(iface) {
            Ok(_) => 0,
            Err(_) => -1,
        };
        GLOBAL_NATIVE_LOG.record(TAG_RELEASE_INTERFACE, iface as i32, rel_res);
    }
    for iface in reattach_order {
        let att_res = match handle.attach_kernel_driver(iface) {
            Ok(_) => 0,
            Err(_) => -1,
        };
        GLOBAL_NATIVE_LOG.record(TAG_REATTACH_DRIVER, iface as i32, att_res);
    }
}

pub(crate) fn program_sample_rate(
    handle: &DeviceHandle<Context>,
    is_uac2: bool,
    clock_source_id: u8,
    control_iface: u8,
    streaming_iface: u8,
    endpoint: u8,
    sample_rate: u32,
) -> Result<Option<u32>, i32> {
    if is_uac2 {
        let w_value = 0x0100u16; // CS_SAM_FREQ_CONTROL in high byte
        let w_index = ((clock_source_id as u16) << 8) | (control_iface as u16);

        // 1. Query GET_RANGE on Clock Source
        let mut range_buf = [0u8; 256];
        match handle.read_control(
            0xA1, // Class | Interface | In
            0x02, // GET_RANGE
            w_value,
            w_index,
            &mut range_buf,
            Duration::from_millis(1000),
        ) {
            Ok(read_len) => {
                let num_ranges = if read_len >= 2 {
                    u16::from_le_bytes([range_buf[0], range_buf[1]]) as i32
                } else {
                    0
                };
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_RANGE, clock_source_id as i32, num_ranges as i64);
            }
            Err(_) => {
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_RANGE, clock_source_id as i32, -1);
            }
        }

        // 2. SET_CUR
        let rate_data = sample_rate.to_le_bytes();
        match handle.write_control(
            0x21, // Class | Interface | Out
            0x01, // SET_CUR
            w_value,
            w_index,
            &rate_data,
            Duration::from_millis(1000),
        ) {
            Ok(_) => {
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_SET_CUR, clock_source_id as i32, sample_rate as i64);
            }
            Err(e) => {
                let err_code = match e {
                    rusb::Error::Pipe => -32,
                    rusb::Error::Timeout => -110,
                    _ => -1,
                };
                GLOBAL_NATIVE_LOG.record(
                    TAG_CLOCK_SET_CUR,
                    clock_source_id as i32,
                    ((err_code as i64) << 32) | (sample_rate as i64),
                );
                return Err(err_code);
            }
        }

        // Settle delay for DAC PLL
        std::thread::sleep(Duration::from_millis(20));

        // 3. GET_CUR readback verification
        let mut readback = [0u8; 4];
        match handle.read_control(
            0xA1,
            0x01,
            w_value,
            w_index,
            &mut readback,
            Duration::from_millis(1000),
        ) {
            Ok(read_len) if read_len >= 4 => {
                let read_rate = u32::from_le_bytes(readback);
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_CUR, clock_source_id as i32, read_rate as i64);
                if read_rate != sample_rate {
                    GLOBAL_NATIVE_LOG.record(
                        TAG_CLOCK_GET_CUR,
                        clock_source_id as i32,
                        (-22i64 << 32) | (read_rate as i64),
                    );
                }
                Ok(Some(read_rate))
            }
            Ok(_) => {
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_CUR, clock_source_id as i32, -1);
                Ok(None)
            }
            Err(e) => {
                let err_code = match e {
                    rusb::Error::Pipe => -32,
                    rusb::Error::Timeout => -110,
                    _ => -1,
                };
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_CUR, clock_source_id as i32, err_code as i64);
                Ok(None)
            }
        }
    } else {
        // UAC 1.0 endpoint sampling frequency control
        let rate_data = [
            (sample_rate & 0xFF) as u8,
            ((sample_rate >> 8) & 0xFF) as u8,
            ((sample_rate >> 16) & 0xFF) as u8,
        ];
        let w_value = 0x0100u16;
        let candidates = [
            endpoint as u16,
            (endpoint as u16) | ((streaming_iface as u16) << 8),
            streaming_iface as u16,
        ];
        let mut success_index = None;
        for &idx in &candidates {
            let res = handle.write_control(
                0x22,
                0x01,
                w_value,
                idx,
                &rate_data,
                Duration::from_millis(1000),
            );
            if res.is_ok() {
                success_index = Some(idx);
                break;
            }
        }
        let idx = match success_index {
            Some(i) => i,
            None => {
                GLOBAL_NATIVE_LOG.record(
                    TAG_CLOCK_SET_CUR,
                    endpoint as i32,
                    (-1i64 << 32) | (sample_rate as i64),
                );
                return Err(-1);
            }
        };
        GLOBAL_NATIVE_LOG.record(TAG_CLOCK_SET_CUR, endpoint as i32, sample_rate as i64);

        std::thread::sleep(Duration::from_millis(20));

        let mut readback = [0u8; 3];
        if let Ok(read_len) = handle.read_control(
            0xA2,
            0x81,
            w_value,
            idx,
            &mut readback,
            Duration::from_millis(1000),
        ) {
            if read_len >= 3 {
                let read_rate = (readback[0] as u32) | ((readback[1] as u32) << 8) | ((readback[2] as u32) << 16);
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_CUR, endpoint as i32, read_rate as i64);
                return Ok(Some(read_rate));
            }
        }
        Ok(None)
    }
}

struct SavedDacState {
    volumes: Vec<(u8, u8, u8, i16)>,
    mutes: Vec<(u8, u8, u8, bool)>,
}

/// Preserves the true OS/pre-PaperPlay DAC volume and mute state across intra-session
/// rate/alt reconfigurations until explicitly restored on handoff back to the OS.
static INITIAL_DAC_STATE: Mutex<Option<SavedDacState>> = Mutex::new(None);

pub struct UsbDeviceSession {
    pub context: Arc<Context>,
    pub handle: Arc<DeviceHandle<Context>>,
    pub dup_fd: RawFd,
    pub claimed_interfaces: Vec<u8>,
    pub streaming_iface: AtomicU8,
    pub target_alt: AtomicU8,
    pub is_uac2: bool,
    pub clock_source_id: u8,
    pub control_iface: u8,
    pub endpoint: AtomicU8,
    pub feature_units: Vec<(u8, u8)>, // (feature_unit_id, ac_interface_number)
    pub saved_volumes: Vec<(u8, u8, u8, i16)>, // (fu_id, ac_iface, channel, volume_raw)
    pub saved_mutes: Vec<(u8, u8, u8, bool)>, // (fu_id, ac_iface, channel, is_muted)
    volume_restored: AtomicBool,
}

unsafe impl Send for UsbDeviceSession {}
unsafe impl Sync for UsbDeviceSession {}

static ACTIVE_SESSION: Mutex<Option<Arc<UsbDeviceSession>>> = Mutex::new(None);

pub fn set_active_session(session: Option<Arc<UsbDeviceSession>>) {
    if let Ok(mut lock) = ACTIVE_SESSION.lock() {
        *lock = session;
    }
}

pub fn get_active_session() -> Option<Arc<UsbDeviceSession>> {
    if let Ok(lock) = ACTIVE_SESSION.lock() {
        lock.clone()
    } else {
        None
    }
}

impl UsbDeviceSession {
    /// Open the USB device from the Android file descriptor, detach kernel drivers,
    /// claim audio interfaces (0, 1, and 2 if present), capture pre-session FU mute/vol,
    /// and leave the streaming interface in Alt 0 ready for [reconfigure_format].
    pub fn open_claimed(
        fd: i32,
        streaming_iface: u8,
        is_uac2: bool,
        clock_source_id: u8,
        control_iface: u8,
        explicit_fu_id: u8,
    ) -> Result<Self, i32> {
        crate::jni::ensure_no_device_discovery();
        #[cfg(unix)]
        let _ = rusb::disable_device_discovery();

        let dup_fd = unsafe { libc::dup(fd) };
        if dup_fd < 0 {
            let err = std::io::Error::last_os_error().raw_os_error().unwrap_or(1);
            GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, -err, fd as i64);
            return Err(-err);
        }

        #[repr(C)]
        struct LibusbInitOption {
            option: std::os::raw::c_int,
            padding: std::os::raw::c_int,
            value: i64,
        }
        const LIBUSB_OPTION_NO_DEVICE_DISCOVERY: std::os::raw::c_int = 2;
        extern "C" {
            fn libusb_init_context(
                ctx: *mut *mut libusb1_sys::libusb_context,
                options: *const LibusbInitOption,
                num_options: std::os::raw::c_int,
            ) -> std::os::raw::c_int;
        }

        let mut raw_ctx: *mut libusb1_sys::libusb_context = std::ptr::null_mut();
        let init_options = [LibusbInitOption {
            option: LIBUSB_OPTION_NO_DEVICE_DISCOVERY,
            padding: 0,
            value: 0,
        }];
        let init_res = unsafe {
            libusb_init_context(&mut raw_ctx, init_options.as_ptr(), 1)
        };

        let context = if init_res == 0 && !raw_ctx.is_null() {
            Arc::new(unsafe { Context::from_raw(raw_ctx) })
        } else {
            match Context::new() {
                Ok(c) => Arc::new(c),
                Err(_) => {
                    GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, -1, fd as i64);
                    unsafe { libc::close(dup_fd); }
                    return Err(-1);
                }
            }
        };

        let handle = match unsafe { context.open_device_with_fd(dup_fd) } {
            Ok(h) => {
                let raw_ptr = h.as_raw();
                GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, dup_fd, raw_ptr as i64);
                Arc::new(h)
            }
            Err(e) => {
                let err_code = match e {
                    rusb::Error::Access => -13,
                    rusb::Error::NoDevice => -19,
                    _ => -1,
                };
                GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, err_code, dup_fd as i64);
                unsafe { libc::close(dup_fd); }
                return Err(err_code);
            }
        };

        let mut audio_interfaces = Vec::new();
        let active_config = handle.device().active_config_descriptor().ok();
        if let Some(ref config) = active_config {
            for iface in config.interfaces() {
                if iface.descriptors().any(|desc| desc.class_code() == 1) {
                    audio_interfaces.push(iface.number());
                }
            }
        }
        if audio_interfaces.is_empty() {
            audio_interfaces = vec![control_iface, streaming_iface];
        }
        if !audio_interfaces.contains(&control_iface) {
            audio_interfaces.push(control_iface);
        }
        if !audio_interfaces.contains(&streaming_iface) {
            audio_interfaces.push(streaming_iface);
        }
        audio_interfaces.sort();
        audio_interfaces.dedup();

        let max_attempts = 10;
        let delay_ms = 30;
        let mut claimed_interfaces = Vec::new();

        for &iface in &audio_interfaces {
            let mut claimed = false;
            for attempt in 1..=max_attempts {
                let detach_code = match handle.detach_kernel_driver(iface) {
                    Ok(_) => 0,
                    Err(e) => match e {
                        rusb::Error::NotFound => 0,
                        _ => -1,
                    },
                };
                GLOBAL_NATIVE_LOG.record(TAG_DETACH_DRIVER, iface as i32, detach_code as i64);

                match handle.claim_interface(iface) {
                    Ok(_) => {
                        claimed = true;
                        claimed_interfaces.push(iface);
                        GLOBAL_NATIVE_LOG.record(
                            TAG_CLAIM_INTERFACE,
                            iface as i32,
                            (attempt as i64) << 16,
                        );
                        break;
                    }
                    Err(e) => {
                        let err_code = match e {
                            rusb::Error::Busy => -16,
                            rusb::Error::NoDevice => -19,
                            rusb::Error::Access => -13,
                            rusb::Error::NotFound => -2,
                            _ => -1,
                        };
                        GLOBAL_NATIVE_LOG.record(
                            TAG_CLAIM_INTERFACE,
                            iface as i32,
                            ((attempt as i64) << 16) | (err_code as i64 & 0xFFFF),
                        );
                        if attempt < max_attempts {
                            std::thread::sleep(Duration::from_millis(delay_ms));
                        }
                    }
                }
            }

            if !claimed && (iface == control_iface || iface == streaming_iface) {
                release_and_reattach_interfaces(&handle, &claimed_interfaces);
                return Err(-16);
            }
        }

        let mut feature_units_list = Vec::new();
        if explicit_fu_id > 0 {
            feature_units_list.push((explicit_fu_id, control_iface));
        }

        let mut saved_volumes_list = Vec::new();
        let mut saved_mutes_list = Vec::new();
        let get_cur_req = fu_get_cur_request(is_uac2);
        for &(fu_id, ac_iface) in &feature_units_list {
            let w_idx = fu_w_index(fu_id, ac_iface);
            for ch in 0..=2 {
                let w_val_mute = fu_mute_w_value(ch);
                let mut mute_buf = [0u8; 1];
                if let Ok(1) = handle.read_control(
                    0xA1,
                    get_cur_req,
                    w_val_mute,
                    w_idx,
                    &mut mute_buf,
                    Duration::from_millis(100),
                ) {
                    let is_muted = mute_buf[0] != 0;
                    saved_mutes_list.push((fu_id, ac_iface, ch, is_muted));
                    GLOBAL_NATIVE_LOG.record(
                        crate::log::TAG_GET_CUR_MUTE,
                        (0xA1 << 8) | (get_cur_req as i32),
                        ((w_idx as i64) << 32)
                            | ((w_val_mute as i64) << 16)
                            | (if is_muted { 1 } else { 0 }),
                    );
                }

                let w_val_vol = fu_volume_w_value(ch);
                let mut vol_buf = [0u8; 2];
                if let Ok(2) = handle.read_control(
                    0xA1,
                    get_cur_req,
                    w_val_vol,
                    w_idx,
                    &mut vol_buf,
                    Duration::from_millis(100),
                ) {
                    let vol_raw = i16::from_le_bytes(vol_buf);
                    saved_volumes_list.push((fu_id, ac_iface, ch, vol_raw));
                    GLOBAL_NATIVE_LOG.record(
                        crate::log::TAG_GET_CUR_VOL,
                        (0xA1 << 8) | (get_cur_req as i32),
                        ((w_idx as i64) << 32)
                            | ((w_val_vol as i64) << 16)
                            | (vol_raw as u16 as i64),
                    );
                }
            }
        }

        if let Ok(mut initial_lock) = INITIAL_DAC_STATE.lock() {
            if initial_lock.is_none() && (!saved_volumes_list.is_empty() || !saved_mutes_list.is_empty()) {
                *initial_lock = Some(SavedDacState {
                    volumes: saved_volumes_list.clone(),
                    mutes: saved_mutes_list.clone(),
                });
            }
        }

        match handle.set_alternate_setting(streaming_iface, 0) {
            Ok(_) => {
                GLOBAL_NATIVE_LOG.record(TAG_SET_ALT_SETTING, streaming_iface as i32, 0);
            }
            Err(e) => {
                let err_code = match e {
                    rusb::Error::Busy => -16,
                    rusb::Error::NoDevice => -19,
                    _ => -1,
                };
                GLOBAL_NATIVE_LOG.record(
                    TAG_SET_ALT_SETTING,
                    streaming_iface as i32,
                    (err_code as i64) << 8,
                );
            }
        }

        Ok(Self {
            context,
            handle,
            dup_fd,
            claimed_interfaces,
            streaming_iface: AtomicU8::new(streaming_iface),
            target_alt: AtomicU8::new(0),
            is_uac2,
            clock_source_id,
            control_iface,
            endpoint: AtomicU8::new(0),
            feature_units: feature_units_list,
            saved_volumes: saved_volumes_list,
            saved_mutes: saved_mutes_list,
            volume_restored: AtomicBool::new(false),
        })
    }

    pub fn open_and_configure(
        fd: i32,
        streaming_iface: u8,
        target_alt: u8,
        is_uac2: bool,
        clock_source_id: u8,
        control_iface: u8,
        sample_rate: u32,
        endpoint: u8,
    ) -> Result<Self, i32> {
        let session = Self::open_claimed(fd, streaming_iface, is_uac2, clock_source_id, control_iface, 0)?;
        session.reconfigure_format(streaming_iface, target_alt, sample_rate, endpoint)?;
        Ok(session)
    }

    /// Reconfigure sample rate, streaming interface, and alternate setting in place
    /// without releasing interfaces or reopening the USB device handle.
    pub fn reconfigure_format(
        &self,
        streaming_iface: u8,
        target_alt: u8,
        sample_rate: u32,
        endpoint: u8,
    ) -> Result<(), i32> {
        let prev_iface = self.streaming_iface.load(Ordering::Acquire);
        let _ = self.handle.set_alternate_setting(prev_iface, 0);
        GLOBAL_NATIVE_LOG.record(TAG_SET_ALT_SETTING, prev_iface as i32, 0);
        if streaming_iface != prev_iface {
            let _ = self.handle.set_alternate_setting(streaming_iface, 0);
            GLOBAL_NATIVE_LOG.record(TAG_SET_ALT_SETTING, streaming_iface as i32, 0);
        }

        let read_rate = program_sample_rate(
            &self.handle,
            self.is_uac2,
            self.clock_source_id,
            self.control_iface,
            streaming_iface,
            endpoint,
            sample_rate,
        )?;
        if let Some(actual_rate) = read_rate {
            if actual_rate != sample_rate {
                return Err(-22); // EINVAL: clock rate mismatch -> caller recreates session
            }
        }

        match self.handle.set_alternate_setting(streaming_iface, target_alt) {
            Ok(_) => {
                GLOBAL_NATIVE_LOG.record(
                    TAG_SET_ALT_SETTING,
                    streaming_iface as i32,
                    (target_alt as i64) & 0xFF,
                );
            }
            Err(e) => {
                let err_code = match e {
                    rusb::Error::Busy => -16,
                    rusb::Error::NoDevice => -19,
                    _ => -1,
                };
                GLOBAL_NATIVE_LOG.record(
                    TAG_SET_ALT_SETTING,
                    streaming_iface as i32,
                    ((err_code as i64) << 8) | ((target_alt as i64) & 0xFF),
                );
                return Err(err_code);
            }
        }

        self.streaming_iface.store(streaming_iface, Ordering::Release);
        self.target_alt.store(target_alt, Ordering::Release);
        self.endpoint.store(endpoint, Ordering::Release);

        if let Ok(active_alt) = self.read_active_alt() {
            if active_alt != target_alt {
                return Err(-22); // Alt mismatch -> caller recreates session
            }
        }
        Ok(())
    }

    /// Execute a generic USB control transfer on this device handle.
    pub fn control_transfer(
        &self,
        request_type: u8,
        request: u8,
        value: u16,
        index: u16,
        data: &mut [u8],
        timeout_ms: u32,
    ) -> Result<usize, i32> {
        let timeout = Duration::from_millis(timeout_ms as u64);
        if (request_type & 0x80) != 0 {
            self.handle.read_control(request_type, request, value, index, data, timeout)
                .map_err(|e| match e {
                    rusb::Error::Pipe => -32,
                    rusb::Error::Timeout => -110,
                    rusb::Error::NoDevice => -19,
                    rusb::Error::Busy => -16,
                    _ => -1,
                })
        } else {
            self.handle.write_control(request_type, request, value, index, data, timeout)
                .map_err(|e| match e {
                    rusb::Error::Pipe => -32,
                    rusb::Error::Timeout => -110,
                    rusb::Error::NoDevice => -19,
                    rusb::Error::Busy => -16,
                    _ => -1,
                })
        }
    }

    /// Reattach kernel driver to an interface.
    pub fn reattach_kernel_driver(&self, iface: u8) -> Result<(), i32> {
        let _ = self.handle.attach_kernel_driver(iface);
        Ok(())
    }

    /// Sets hardware volume via UAC Feature Unit on claimed control interface.
    /// Captures pre-session FU mute/volume baseline once if not already recorded.
    /// Returns the verified readback volume raw value (i16) on success, or negative errno on error.
    pub fn set_hardware_volume(
        &self,
        fu_id: u8,
        control_iface: u8,
        channel: u8,
        volume_raw: i16,
    ) -> Result<i16, i32> {
        self.capture_fu_baseline_once(fu_id, control_iface);
        let w_val = fu_volume_w_value(channel);
        let w_idx = fu_w_index(fu_id, control_iface);
        let data = volume_raw.to_le_bytes();

        GLOBAL_NATIVE_LOG.record(
            crate::log::TAG_SET_CUR_VOL,
            (0x21 << 8) | 0x01,
            ((w_idx as i64) << 32) | ((w_val as i64) << 16) | (volume_raw as u16 as i64),
        );

        // 1. SET_CUR
        self.handle.write_control(
            0x21,
            0x01,
            w_val,
            w_idx,
            &data,
            Duration::from_millis(500),
        ).map_err(|e| match e {
            rusb::Error::Pipe => -32,
            rusb::Error::Timeout => -110,
            _ => -1,
        })?;

        // 2. GET_CUR readback verification
        let get_cur_req = fu_get_cur_request(self.is_uac2);
        let mut readback = [0u8; 2];
        match self.handle.read_control(
            0xA1,
            get_cur_req,
            w_val,
            w_idx,
            &mut readback,
            Duration::from_millis(500),
        ) {
            Ok(2) => {
                let read_val = i16::from_le_bytes(readback);
                GLOBAL_NATIVE_LOG.record(
                    crate::log::TAG_GET_CUR_VOL,
                    (0xA1 << 8) | (get_cur_req as i32),
                    ((w_idx as i64) << 32) | ((w_val as i64) << 16) | (read_val as u16 as i64),
                );
                Ok(read_val)
            }
            Ok(_) => Err(-1),
            Err(e) => Err(match e {
                rusb::Error::Pipe => -32,
                rusb::Error::Timeout => -110,
                _ => -1,
            }),
        }
    }

    /// Sets hardware mute via UAC Feature Unit on claimed control interface.
    /// Captures pre-session FU mute/volume baseline once if not already recorded.
    /// Returns the verified readback mute state (bool) on success, or negative errno on error.
    pub fn set_hardware_mute(
        &self,
        fu_id: u8,
        control_iface: u8,
        channel: u8,
        mute: bool,
    ) -> Result<bool, i32> {
        self.capture_fu_baseline_once(fu_id, control_iface);
        let w_val = fu_mute_w_value(channel);
        let w_idx = fu_w_index(fu_id, control_iface);
        let data = [if mute { 1u8 } else { 0u8 }];

        GLOBAL_NATIVE_LOG.record(
            crate::log::TAG_SET_CUR_MUTE,
            (0x21 << 8) | 0x01,
            ((w_idx as i64) << 32) | ((w_val as i64) << 16) | (if mute { 1 } else { 0 }),
        );

        // 1. SET_CUR
        self.handle.write_control(
            0x21,
            0x01,
            w_val,
            w_idx,
            &data,
            Duration::from_millis(500),
        ).map_err(|e| match e {
            rusb::Error::Pipe => -32,
            rusb::Error::Timeout => -110,
            _ => -1,
        })?;

        // 2. GET_CUR readback verification
        let get_cur_req = fu_get_cur_request(self.is_uac2);
        let mut readback = [0u8; 1];
        match self.handle.read_control(
            0xA1,
            get_cur_req,
            w_val,
            w_idx,
            &mut readback,
            Duration::from_millis(500),
        ) {
            Ok(1) => {
                let is_muted = readback[0] != 0;
                GLOBAL_NATIVE_LOG.record(
                    crate::log::TAG_GET_CUR_MUTE,
                    (0xA1 << 8) | (get_cur_req as i32),
                    ((w_idx as i64) << 32) | ((w_val as i64) << 16) | (if is_muted { 1 } else { 0 }),
                );
                Ok(is_muted)
            }
            _ => Err(-1),
        }
    }

    /// Captures pre-session mute/volume for a Feature Unit once per claim cycle before any mutation.
    fn capture_fu_baseline_once(&self, fu_id: u8, ac_iface: u8) {
        if fu_id == 0 {
            return;
        }
        let Ok(mut guard) = INITIAL_DAC_STATE.lock() else {
            return;
        };
        let get_cur_req = fu_get_cur_request(self.is_uac2);
        let state = guard.get_or_insert_with(|| SavedDacState {
            volumes: Vec::new(),
            mutes: Vec::new(),
        });
        for ch in 0..=2 {
            if !state.mutes.iter().any(|&(f, a, c, _)| f == fu_id && a == ac_iface && c == ch) {
                if let Ok(m) = self.read_hardware_mute(fu_id, ac_iface, ch) {
                    state.mutes.push((fu_id, ac_iface, ch, m));
                }
            }
            if !state.volumes.iter().any(|&(f, a, c, _)| f == fu_id && a == ac_iface && c == ch) {
                if let Ok(v) = self.read_hardware_volume(fu_id, ac_iface, ch) {
                    state.volumes.push((fu_id, ac_iface, ch, v));
                }
            }
        }
        let _ = get_cur_req;
    }

    pub fn set_alt_setting(&self, iface: u8, alt: u8) -> Result<(), i32> {
        self.handle.set_alternate_setting(iface, alt).map_err(|e| match e {
            rusb::Error::Busy => -16,
            rusb::Error::NoDevice => -19,
            _ => -1,
        })
    }

    pub fn read_hardware_volume(&self, fu_id: u8, control_iface: u8, channel: u8) -> Result<i16, i32> {
        let w_val = fu_volume_w_value(channel);
        let w_idx = fu_w_index(fu_id, control_iface);
        let get_cur_req = fu_get_cur_request(self.is_uac2);
        let mut readback = [0u8; 2];
        match self.handle.read_control(0xA1, get_cur_req, w_val, w_idx, &mut readback, Duration::from_millis(200)) {
            Ok(2) => Ok(i16::from_le_bytes(readback)),
            Ok(_) => Err(-1),
            Err(e) => Err(match e {
                rusb::Error::Pipe => -32,
                rusb::Error::Timeout => -110,
                _ => -1,
            }),
        }
    }

    pub fn read_hardware_mute(&self, fu_id: u8, control_iface: u8, channel: u8) -> Result<bool, i32> {
        let w_val = fu_mute_w_value(channel);
        let w_idx = fu_w_index(fu_id, control_iface);
        let get_cur_req = fu_get_cur_request(self.is_uac2);
        let mut readback = [0u8; 1];
        match self.handle.read_control(0xA1, get_cur_req, w_val, w_idx, &mut readback, Duration::from_millis(200)) {
            Ok(1) => Ok(readback[0] != 0),
            Ok(_) => Err(-1),
            Err(e) => Err(match e {
                rusb::Error::Pipe => -32,
                rusb::Error::Timeout => -110,
                _ => -1,
            }),
        }
    }

    /// Read back the current active sample rate from the DAC clock source.
    pub fn read_clock_rate(&self) -> Result<u32, i32> {
        if self.is_uac2 {
            let w_value = 0x0100u16; // CS_SAM_FREQ_CONTROL
            let w_index = ((self.clock_source_id as u16) << 8) | (self.control_iface as u16);
            let mut readback = [0u8; 4];
            match self.handle.read_control(
                0xA1, // Class | Interface | In
                0x01, // GET_CUR
                w_value,
                w_index,
                &mut readback,
                Duration::from_millis(500),
            ) {
                Ok(read_len) if read_len >= 4 => Ok(u32::from_le_bytes(readback)),
                _ => Err(-1),
            }
        } else {
            let mut readback = [0u8; 3];
            match self.handle.read_control(
                0xA2, // Endpoint | Class | In
                0x81, // GET_CUR (UAC 1.0)
                0x0100, // SAMPLING_FREQ_CONTROL
                self.endpoint.load(Ordering::Acquire) as u16,
                &mut readback,
                Duration::from_millis(500),
            ) {
                Ok(read_len) if read_len >= 3 => {
                    let rate = (readback[0] as u32) | ((readback[1] as u32) << 8) | ((readback[2] as u32) << 16);
                    Ok(rate)
                }
                _ => Err(-1),
            }
        }
    }

    /// Read back the currently active alternate setting via standard GET_INTERFACE request.
    pub fn read_active_alt(&self) -> Result<u8, i32> {
        let mut alt_buf = [0u8; 1];
        let iface = self.streaming_iface.load(Ordering::Acquire);
        match self.handle.read_control(
            0x81, // Standard | Interface | In
            0x0A, // GET_INTERFACE
            0x0000,
            iface as u16,
            &mut alt_buf,
            Duration::from_millis(500),
        ) {
            Ok(1) => Ok(alt_buf[0]),
            _ => Ok(self.target_alt.load(Ordering::Acquire)), // Fall back to stored target_alt if device doesn't support GET_INTERFACE
        }
    }

    /// Writes one Feature Unit mute setting and verifies it via GET_CUR while claiming is active.
    fn restore_fu_mute_and_verify(&self, fu_id: u8, ac_iface: u8, ch: u8, mute: bool, get_cur_req: u8) {
        let w_idx = fu_w_index(fu_id, ac_iface);
        let w_val_mute = fu_mute_w_value(ch);
        let mute_data = [if mute { 1u8 } else { 0u8 }];
        let _ = self.handle.write_control(
            0x21,
            0x01, // SET_CUR
            w_val_mute,
            w_idx,
            &mute_data,
            Duration::from_millis(100),
        );
        GLOBAL_NATIVE_LOG.record(
            crate::log::TAG_SET_CUR_MUTE,
            (0x21 << 8) | 0x01,
            ((w_idx as i64) << 32) | ((w_val_mute as i64) << 16) | (if mute { 1 } else { 0 }),
        );

        let mut mute_readback = [0u8; 1];
        if let Ok(1) = self.handle.read_control(
            0xA1,
            get_cur_req,
            w_val_mute,
            w_idx,
            &mut mute_readback,
            Duration::from_millis(100),
        ) {
            let read_mute = mute_readback[0] != 0;
            GLOBAL_NATIVE_LOG.record(
                crate::log::TAG_GET_CUR_MUTE,
                (0xA1 << 8) | (get_cur_req as i32),
                ((w_idx as i64) << 32) | ((w_val_mute as i64) << 16) | (if read_mute { 1 } else { 0 }),
            );
        }
    }

    /// Writes one Feature Unit volume setting and verifies it via GET_CUR while claiming is active.
    fn restore_fu_vol_and_verify(&self, fu_id: u8, ac_iface: u8, ch: u8, vol_raw: i16, get_cur_req: u8) {
        let w_idx = fu_w_index(fu_id, ac_iface);
        let w_val_vol = fu_volume_w_value(ch);
        let vol_data = vol_raw.to_le_bytes();
        let _ = self.handle.write_control(
            0x21,
            0x01, // SET_CUR
            w_val_vol,
            w_idx,
            &vol_data,
            Duration::from_millis(100),
        );
        GLOBAL_NATIVE_LOG.record(
            crate::log::TAG_SET_CUR_VOL,
            (0x21 << 8) | 0x01,
            ((w_idx as i64) << 32) | ((w_val_vol as i64) << 16) | (vol_raw as u16 as i64),
        );

        let mut readback = [0u8; 2];
        if let Ok(2) = self.handle.read_control(
            0xA1,
            get_cur_req,
            w_val_vol,
            w_idx,
            &mut readback,
            Duration::from_millis(100),
        ) {
            let raw_vol = i16::from_le_bytes(readback);
            GLOBAL_NATIVE_LOG.record(
                crate::log::TAG_GET_CUR_VOL,
                (0xA1 << 8) | (get_cur_req as i32),
                ((w_idx as i64) << 32) | ((w_val_vol as i64) << 16) | (raw_vol as u16 as i64),
            );
        }
    }

    /// Restores DAC Feature Units to their true pre-session saved state (or 0 dB / unmuted if none saved)
    /// while interface 0 is still claimed, verifying both mute and volume with GET_CUR readback.
    pub fn restore_default_volume_and_unmute(&self) {
        if self.volume_restored.swap(true, Ordering::AcqRel) {
            return;
        }
        let initial_state = INITIAL_DAC_STATE
            .lock()
            .ok()
            .and_then(|mut guard| guard.take());
        let saved_mutes = initial_state
            .as_ref()
            .map(|s| s.mutes.as_slice())
            .unwrap_or(&self.saved_mutes);
        let saved_volumes = initial_state
            .as_ref()
            .map(|s| s.volumes.as_slice())
            .unwrap_or(&self.saved_volumes);

        let get_cur_req = fu_get_cur_request(self.is_uac2);
        let resolve_mute = |fu_id: u8, ac_iface: u8, ch: u8| -> bool {
            saved_mutes.iter()
                .find(|&&(f, a, c, _)| f == fu_id && a == ac_iface && c == ch)
                .map(|&(_, _, _, m)| m)
                .unwrap_or(false)
        };

        // Pass 1: apply mute=true channels first so volume changes occur silently.
        for &(fu_id, ac_iface) in &self.feature_units {
            for ch in 0..=2 {
                if resolve_mute(fu_id, ac_iface, ch) {
                    self.restore_fu_mute_and_verify(fu_id, ac_iface, ch, true, get_cur_req);
                }
            }
        }
        // Pass 2: restore saved volumes while muted / before unmuting.
        for &(fu_id, ac_iface) in &self.feature_units {
            for ch in 0..=2 {
                let saved_vol = saved_volumes.iter()
                    .find(|&&(f, a, c, _)| f == fu_id && a == ac_iface && c == ch)
                    .map(|&(_, _, _, v)| v)
                    .unwrap_or(0);
                self.restore_fu_vol_and_verify(fu_id, ac_iface, ch, saved_vol, get_cur_req);
            }
        }
        // Pass 3: unmute channels after volumes are in place.
        for &(fu_id, ac_iface) in &self.feature_units {
            for ch in 0..=2 {
                if !resolve_mute(fu_id, ac_iface, ch) {
                    self.restore_fu_mute_and_verify(fu_id, ac_iface, ch, false, get_cur_req);
                }
            }
        }
    }
}

impl Drop for UsbDeviceSession {
    fn drop(&mut self) {
        // Ensure DAC volume/mute is restored while interface 0 is still claimed
        if !self.volume_restored.load(Ordering::Acquire) {
            self.restore_default_volume_and_unmute();
        }
        // Return streaming interface to alt 0 before releasing
        let streaming_iface = self.streaming_iface.load(Ordering::Acquire);
        let _ = self.handle.set_alternate_setting(streaming_iface, 0);
        // Release ALL claimed interfaces first, THEN reattach kernel drivers (iface 0 first)
        // so snd-usb-audio never encounters EBUSY on iface 1 while probing iface 0.
        release_and_reattach_interfaces(&self.handle, &self.claimed_interfaces);
        // Note: dup_fd is closed automatically when self.handle is dropped via libusb_close.
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn feature_unit_selectors_and_channels_match_uac_spec() {
        // Mute selector is 0x01 in high byte, channel in low byte
        assert_eq!(fu_mute_w_value(0), 0x0100);
        assert_eq!(fu_mute_w_value(1), 0x0101);
        assert_eq!(fu_mute_w_value(2), 0x0102);

        // Volume selector is 0x02 in high byte, channel in low byte
        assert_eq!(fu_volume_w_value(0), 0x0200);
        assert_eq!(fu_volume_w_value(1), 0x0201);
        assert_eq!(fu_volume_w_value(2), 0x0202);

        // wIndex packs Feature Unit ID in high byte and AC interface in low byte
        assert_eq!(fu_w_index(2, 0), 0x0200);
        assert_eq!(fu_w_index(5, 1), 0x0501);

        // GET_CUR bRequest differs between UAC 2.0 (0x01) and UAC 1.0 (0x81)
        assert_eq!(fu_get_cur_request(true), 0x01);
        assert_eq!(fu_get_cur_request(false), 0x81);
    }

    #[test]
    fn release_all_interfaces_before_reattaching_kernel_drivers() {
        let (release_order, reattach_order) = interface_release_and_reattach_plan(&[0, 1, 2]);
        // Streaming interfaces released before control interface 0
        assert_eq!(release_order, vec![2, 1, 0]);
        // Kernel drivers reattached ascending (control iface 0 first) only after all releases
        assert_eq!(reattach_order, vec![0, 1, 2]);

        // Handles duplicates gracefully
        let (rel_dup, att_dup) = interface_release_and_reattach_plan(&[0, 1, 1]);
        assert_eq!(rel_dup, vec![1, 0]);
        assert_eq!(att_dup, vec![0, 1]);
    }
}
