//! JNI interface for UsbEngineNative (io.max.paperplay.player.usb.UsbEngineNative).
//! No panics across FFI line; catches all unwinds and returns error codes.

use std::panic::catch_unwind;
use std::sync::atomic::Ordering;

use jni::objects::{JByteBuffer, JClass, JLongArray};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;

use super::debug::generate_debug_buffer;
use super::streamer::{StreamConfig, UsbStreamer};

pub fn ensure_no_device_discovery() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        unsafe {
            libusb1_sys::libusb_set_option(
                std::ptr::null_mut(),
                libusb1_sys::constants::LIBUSB_OPTION_NO_DEVICE_DISCOVERY,
            );
        }
        let _ = rusb::disable_device_discovery();
    });
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: *mut std::ffi::c_void, _reserved: *mut std::ffi::c_void) -> jint {
    ensure_no_device_discovery();
    jni::sys::JNI_VERSION_1_6
}

pub struct UsbNativeInstance {
    pub streamer: UsbStreamer,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channels: u32,
    pub subslot_size: u32,
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_create(
    _env: JNIEnv,
    _c: JClass,
    fd: jint,
    endpoint: jint,
    streaming_iface: jint,
    target_alt: jint,
    is_uac2: jboolean,
    clock_source_id: jint,
    control_iface: jint,
    sample_rate: jint,
    bit_depth: jint,
    channels: jint,
    subslot_size: jint,
    is_high_speed: jboolean,
    is_dop: jboolean,
    feedback_ep: jint,
) -> jlong {
    let result = catch_unwind(|| {
        ensure_no_device_discovery();
        if fd < 0 || sample_rate <= 0 || channels <= 0 {
            return 0;
        }

        let session = match super::device::UsbDeviceSession::open_and_configure(
            fd,
            streaming_iface as u8,
            target_alt as u8,
            is_uac2 != 0,
            clock_source_id as u8,
            control_iface as u8,
            sample_rate as u32,
            (endpoint & 0xFF) as u8,
        ) {
            Ok(s) => std::sync::Arc::new(s),
            Err(_) => return 0,
        };

        let config = StreamConfig {
            endpoint: (endpoint & 0xFF) as u8,
            sample_rate: sample_rate as u32,
            bit_depth: bit_depth as u32,
            channels: channels as u32,
            subslot_size: subslot_size as u32,
            is_high_speed: is_high_speed != 0,
            is_dop: is_dop != 0,
            feedback_ep: (feedback_ep & 0xFF) as u8,
        };

        super::device::set_active_session(Some(std::sync::Arc::clone(&session)));

        let instance = Box::new(UsbNativeInstance {
            streamer: UsbStreamer::new(session, config),
            sample_rate: sample_rate as u32,
            bit_depth: bit_depth as u32,
            channels: channels as u32,
            subslot_size: subslot_size as u32,
        });

        Box::into_raw(instance) as jlong
    });

    result.unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_reconfigure(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    endpoint: jint,
    streaming_iface: jint,
    target_alt: jint,
    sample_rate: jint,
    bit_depth: jint,
    channels: jint,
    subslot_size: jint,
    is_high_speed: jboolean,
    is_dop: jboolean,
    feedback_ep: jint,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 || sample_rate <= 0 || channels <= 0 || subslot_size <= 0 {
            return -22;
        }
        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        let new_config = StreamConfig {
            endpoint: (endpoint & 0xFF) as u8,
            sample_rate: sample_rate as u32,
            bit_depth: bit_depth as u32,
            channels: channels as u32,
            subslot_size: subslot_size as u32,
            is_high_speed: is_high_speed != 0,
            is_dop: is_dop != 0,
            feedback_ep: (feedback_ep & 0xFF) as u8,
        };
        match instance.streamer.reconfigure(
            streaming_iface as u8,
            target_alt as u8,
            new_config,
        ) {
            Ok(_) => {
                instance.sample_rate = sample_rate as u32;
                instance.bit_depth = bit_depth as u32;
                instance.channels = channels as u32;
                instance.subslot_size = subslot_size as u32;
                0
            }
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_destroy(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(|| {
        super::device::set_active_session(None);
        if handle != 0 {
            let mut instance = unsafe { Box::from_raw(handle as *mut UsbNativeInstance) };
            let _ = instance.streamer.stop();
            drop(instance);
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_start(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        match instance.streamer.start() {
            Ok(_) => 0,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_stop(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        match instance.streamer.stop() {
            Ok(_) => 0,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_pause(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        instance.streamer.pause();
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_resume(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        instance.streamer.resume();
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_flush(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        instance.streamer.clear();
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_write(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    buffer: JByteBuffer,
    offset: jint,
    length: jint,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 || length <= 0 || offset < 0 {
            return 0;
        }

        let (Ok(ptr), Ok(cap)) = (
            env.get_direct_buffer_address(&buffer),
            env.get_direct_buffer_capacity(&buffer),
        ) else {
            return -1;
        };

        if ptr.is_null() || (offset as usize + length as usize) > cap {
            return -1;
        }

        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        let ring = instance.streamer.ring_buffer();
        let src = unsafe { ptr.add(offset as usize) };
        let written = unsafe { ring.write_raw(src, length as usize) };
        if written > 0 {
            instance.streamer.resume_after_flush();
        }
        written as jint
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_setHardwareVolume(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    fu_id: jint,
    control_iface: jint,
    channel: jint,
    volume_raw: jint,
) -> jint {
    let result = catch_unwind(|| {
        let session = if handle != 0 {
            let instance = unsafe { &*(handle as *mut UsbNativeInstance) };
            Some(std::sync::Arc::clone(&instance.streamer.session))
        } else {
            super::device::get_active_session()
        };

        if let Some(session) = session {
            match session.set_hardware_volume(
                fu_id as u8,
                control_iface as u8,
                channel as u8,
                volume_raw as i16,
            ) {
                Ok(read_val) => (read_val as u16) as jint,
                Err(err) => err,
            }
        } else {
            -1
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_setHardwareMute(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    fu_id: jint,
    control_iface: jint,
    channel: jint,
    mute: jboolean,
) -> jint {
    let result = catch_unwind(|| {
        let session = if handle != 0 {
            let instance = unsafe { &*(handle as *mut UsbNativeInstance) };
            Some(std::sync::Arc::clone(&instance.streamer.session))
        } else {
            super::device::get_active_session()
        };

        if let Some(session) = session {
            match session.set_hardware_mute(
                fu_id as u8,
                control_iface as u8,
                channel as u8,
                mute != 0,
            ) {
                Ok(read_mute) => if read_mute { 1 } else { 0 },
                Err(err) => err,
            }
        } else {
            -1
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_getStats(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    out_stats: JLongArray,
) {
    let _ = catch_unwind(|| {
        if handle == 0 {
            return;
        }
        let instance = unsafe { &*(handle as *mut UsbNativeInstance) };
        let ring = instance.streamer.ring_buffer();

        let consumed = ring.frames_consumed.load(Ordering::Relaxed) as i64;
        let underruns = ring.underruns.load(Ordering::Relaxed) as i64;
        let urb_errors = ring.urb_errors.load(Ordering::Relaxed) as i64;
        let fill = ring.available_read() as i64;
        let cap = ring.capacity() as i64;
        let is_running = if instance.streamer.is_running() { 1i64 } else { 0i64 };
        let rate = instance.sample_rate as i64;
        let is_paused = if instance.streamer.is_paused() { 1i64 } else { 0i64 };
        let is_flushing = if instance.streamer.is_flushing() { 1i64 } else { 0i64 };

        let stats = [consumed, underruns, urb_errors, fill, cap, is_running, rate, is_paused, is_flushing];
        let len = match env.get_array_length(&out_stats) {
            Ok(l) => (l as usize).min(stats.len()),
            Err(_) => stats.len(),
        };
        let _ = env.set_long_array_region(&out_stats, 0, &stats[..len]);
    });
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_playDebugTest(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    test_type: jint,
    duration_ms: jint,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &mut *(handle as *mut UsbNativeInstance) };
        let dur = duration_ms.max(10) as u32;

        let debug_bytes = generate_debug_buffer(
            test_type,
            instance.sample_rate,
            instance.bit_depth,
            instance.subslot_size,
            instance.channels,
            dur,
        );

        let ring = instance.streamer.ring_buffer();
        let written = ring.write(&debug_bytes);
        written as jint
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_reattachKernelDriver(
    _env: JNIEnv,
    _c: JClass,
    fd: jint,
    interface_number: jint,
) -> jint {
    let result = catch_unwind(|| {
        if fd < 0 || interface_number < 0 {
            return -1;
        }
        match super::usbfs::reattach_kernel_driver(fd, interface_number as u32) {
            Ok(_) => 0,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_controlTransfer(
    env: JNIEnv,
    _c: JClass,
    fd: jint,
    request_type: jint,
    request: jint,
    value: jint,
    index: jint,
    buffer: jni::objects::JByteArray,
    length: jint,
    timeout_ms: jint,
) -> jint {
    let result = catch_unwind(|| {
        if fd < 0 || length < 0 {
            return -22; // -EINVAL
        }

        let mut data = vec![0u8; length as usize];
        if (request_type as u8 & 0x80) == 0 && length > 0 {
            // OUT transfer: copy input bytes from Java array
            let mut i8_data = vec![0i8; length as usize];
            if env.get_byte_array_region(&buffer, 0, &mut i8_data).is_err() {
                return -14; // -EFAULT
            }
            for (dst, src) in data.iter_mut().zip(i8_data.iter()) {
                *dst = *src as u8;
            }
        }

        crate::usb::log::GLOBAL_NATIVE_LOG.record(
            crate::usb::log::TAG_CONTROL_REQ,
            ((request as i32) << 8) | (request_type as i32 & 0xFF),
            ((index as i64) << 16) | (value as i64 & 0xFFFF),
        );

        match super::usbfs::control_transfer(
            fd,
            request_type as u8,
            request as u8,
            value as u16,
            index as u16,
            &mut data,
            timeout_ms as u32,
        ) {
            Ok(transferred) => {
                if (request_type as u8 & 0x80) != 0 && transferred > 0 {
                    // IN transfer: copy received bytes back to Java array
                    let to_copy = transferred.min(length as usize);
                    let mut i8_data = vec![0i8; to_copy];
                    for (dst, src) in i8_data.iter_mut().zip(data[..to_copy].iter()) {
                        *dst = *src as i8;
                    }
                    let _ = env.set_byte_array_region(&buffer, 0, &i8_data);
                }
                transferred as jint
            }
            Err(errno) => {
                crate::usb::log::GLOBAL_NATIVE_LOG.record(
                    crate::usb::log::TAG_CONTROL_ERR,
                    errno,
                    request_type as i64,
                );
                -errno
            }
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_dumpNativeLog(
    env: JNIEnv,
    _c: JClass,
) -> jstring {
    let result = catch_unwind(|| {
        let lines = crate::usb::log::GLOBAL_NATIVE_LOG.dump_lines();
        let joined = lines.join("\n");
        match env.new_string(joined) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    });
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_getCurrentRate(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &*(handle as *const UsbNativeInstance) };
        match instance.streamer.session.read_clock_rate() {
            Ok(rate) => rate as jint,
            Err(_) => instance.sample_rate as jint,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_getCurrentAlt(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &*(handle as *const UsbNativeInstance) };
        match instance.streamer.session.read_active_alt() {
            Ok(alt) => alt as jint,
            Err(_) => instance.streamer.session.target_alt.load(Ordering::Acquire) as jint,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_restoreDefaultVolume(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        if handle == 0 {
            return -1;
        }
        let instance = unsafe { &*(handle as *const UsbNativeInstance) };
        instance.streamer.session.restore_default_volume_and_unmute();
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_probeDac(
    env: JNIEnv,
    _c: JClass,
    fd: jint,
) -> jstring {
    let result = catch_unwind(|| {
        ensure_no_device_discovery();
        match super::probe::probe_dac(fd) {
            Ok(caps) => {
                let json = format!(
                    r#"{{"rates":{:?},"depths":{:?},"channels":{:?},"clockId":{},"uac":{},"volMin":{},"volMax":{},"volRes":{},"hasVol":{},"hasMute":{}}}"#,
                    caps.sample_rates,
                    caps.bit_depths,
                    caps.channel_counts,
                    caps.clock_source_id,
                    caps.uac_version,
                    caps.volume_min_db,
                    caps.volume_max_db,
                    caps.volume_res_db,
                    caps.has_volume,
                    caps.has_mute,
                );
                match env.new_string(json) {
                    Ok(js) => js.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                }
            }
            Err(_) => std::ptr::null_mut(),
        }
    });
    result.unwrap_or(std::ptr::null_mut())
}
