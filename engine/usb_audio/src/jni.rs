//! JNI interface for UsbEngineNative (io.max.paperplay.player.usb.UsbEngineNative).
//! No panics across FFI line; catches all unwinds and returns error codes.

use std::panic::catch_unwind;
use std::sync::atomic::Ordering;
use std::sync::OnceLock;

use jni::objects::{GlobalRef, JByteBuffer, JClass, JLongArray, JValue};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};

use super::session::{EndpointFormatConfig, RealUsbControlTransport, UsbSessionInstance};
use super::streamer::{ControlCommand, ControlReply, StreamConfig, UsbStreamer};

static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();
static THREAD_HELPER_CLASS: OnceLock<GlobalRef> = OnceLock::new();

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
pub extern "system" fn JNI_OnLoad(vm: *mut std::ffi::c_void, _reserved: *mut std::ffi::c_void) -> jint {
    ensure_no_device_discovery();
    if !vm.is_null() {
        let _ = catch_unwind(|| {
            if let Ok(jvm) = unsafe { JavaVM::from_raw(vm as *mut jni::sys::JavaVM) } {
                if let Ok(mut env) = jvm.get_env() {
                    if let Ok(cls) = env.find_class("io/max/paperplay/player/usb/UsbThreadHelper") {
                        if let Ok(gref) = env.new_global_ref(cls) {
                            let _ = THREAD_HELPER_CLASS.set(gref);
                        }
                    }
                }
                let _ = JAVA_VM.set(jvm);
            }
        });
    }
    jni::sys::JNI_VERSION_1_6
}

/// Invokes Android's `UsbThreadHelper.onNativeAudioThreadStarted(tid)` via JNI so the native
/// `UsbIsoAudio` thread receives `Process.setThreadPriority(tid, THREAD_PRIORITY_URGENT_AUDIO)`
/// and optional ADPF `PerformanceHintManager` session. Returns `(jni_rc, adpf_active)`.
pub(crate) fn notify_audio_thread_started(tid: i32) -> (i32, bool) {
    let Some(vm) = JAVA_VM.get() else {
        return (0, false);
    };
    let Some(cls_ref) = THREAD_HELPER_CLASS.get() else {
        return (0, false);
    };
    let res = catch_unwind(|| {
        let Ok(mut env) = vm.attach_current_thread() else {
            return (-1, false);
        };
        let cls: &JClass = cls_ref.as_obj().into();
        match env.call_static_method(
            cls,
            "onNativeAudioThreadStarted",
            "(I)I",
            &[JValue::Int(tid)],
        ) {
            Ok(v) => {
                let bits = v.i().unwrap_or(-1);
                if bits < 0 {
                    (bits, false)
                } else {
                    let prio_ok = (bits & 1) != 0;
                    let adpf_on = (bits & 2) != 0;
                    (if prio_ok { 1 } else { 0 }, adpf_on)
                }
            }
            Err(_) => {
                let _ = env.exception_clear();
                (-2, false)
            }
        }
    });
    res.unwrap_or((-3, false))
}

/// Closes any ADPF session associated with `tid` on thread teardown.
pub(crate) fn notify_audio_thread_stopped(tid: i32) {
    let Some(vm) = JAVA_VM.get() else {
        return;
    };
    let Some(cls_ref) = THREAD_HELPER_CLASS.get() else {
        return;
    };
    let _ = catch_unwind(|| {
        if let Ok(mut env) = vm.attach_current_thread() {
            let cls: &JClass = cls_ref.as_obj().into();
            let _ = env.call_static_method(
                cls,
                "onNativeAudioThreadStopped",
                "(I)V",
                &[JValue::Int(tid)],
            );
            let _ = env.exception_clear();
        }
    });
}

/// Reports actual wakeup work duration to an active ADPF session (only called when ADPF is enabled).
pub(crate) fn report_adpf_work_duration(tid: i32, duration_ns: i64) {
    let Some(vm) = JAVA_VM.get() else {
        return;
    };
    let Some(cls_ref) = THREAD_HELPER_CLASS.get() else {
        return;
    };
    let _ = catch_unwind(|| {
        if let Ok(mut env) = vm.attach_current_thread() {
            let cls: &JClass = cls_ref.as_obj().into();
            let _ = env.call_static_method(
                cls,
                "reportNativeWorkDuration",
                "(IJ)V",
                &[JValue::Int(tid), JValue::Long(duration_ns)],
            );
            let _ = env.exception_clear();
        }
    });
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
        if handle != 0 {
            let instance = unsafe { &*(handle as *mut UsbNativeInstance) };
            if instance.streamer.is_running() {
                return match instance.streamer.ctrl_queue().submit_and_wait(
                    ControlCommand::SetVolume {
                        fu_id: fu_id as u8,
                        control_iface: control_iface as u8,
                        channel: channel as u8,
                        vol_raw: volume_raw as i16,
                    },
                ) {
                    Ok(ControlReply::Volume(Ok(read_val))) => (read_val as u16) as jint,
                    Ok(ControlReply::Volume(Err(err))) => err,
                    Err(err) => err,
                    _ => -1,
                };
            }
            return match instance.streamer.session.set_hardware_volume(
                fu_id as u8,
                control_iface as u8,
                channel as u8,
                volume_raw as i16,
            ) {
                Ok(read_val) => (read_val as u16) as jint,
                Err(err) => err,
            };
        }
        if let Some(session) = super::device::get_active_session() {
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
        if handle != 0 {
            let instance = unsafe { &*(handle as *mut UsbNativeInstance) };
            if instance.streamer.is_running() {
                return match instance.streamer.ctrl_queue().submit_and_wait(
                    ControlCommand::SetMute {
                        fu_id: fu_id as u8,
                        control_iface: control_iface as u8,
                        channel: channel as u8,
                        mute: mute != 0,
                    },
                ) {
                    Ok(ControlReply::Mute(Ok(read_mute))) => if read_mute { 1 } else { 0 },
                    Ok(ControlReply::Mute(Err(err))) => err,
                    Err(err) => err,
                    _ => -1,
                };
            }
            return match instance.streamer.session.set_hardware_mute(
                fu_id as u8,
                control_iface as u8,
                channel as u8,
                mute != 0,
            ) {
                Ok(read_mute) => if read_mute { 1 } else { 0 },
                Err(err) => err,
            };
        }
        if let Some(session) = super::device::get_active_session() {
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

        crate::log::GLOBAL_NATIVE_LOG.record(
            crate::log::TAG_CONTROL_REQ,
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
                crate::log::GLOBAL_NATIVE_LOG.record(
                    crate::log::TAG_CONTROL_ERR,
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
        let lines = crate::log::GLOBAL_NATIVE_LOG.dump_lines();
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
    ac_iface: jint,
    streaming_iface: jint,
    is_uac2: jboolean,
    clock_source_id: jint,
    feature_unit_id: jint,
    fu_has_volume: jboolean,
    fu_has_master_volume: jboolean,
    fu_has_mute: jboolean,
) -> jstring {
    let result = catch_unwind(|| {
        ensure_no_device_discovery();
        match super::probe::probe_dac(
            fd,
            ac_iface.max(0) as u8,
            streaming_iface.max(0) as u8,
            is_uac2 != 0,
            clock_source_id.max(0) as u8,
            feature_unit_id.max(0) as u8,
            fu_has_volume != 0,
            fu_has_master_volume != 0,
            fu_has_mute != 0,
        ) {
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

// ---- Handle registry (Stage 5+ lifecycle hardening) ---------------------------------------
// Fixed-capacity handle registry prevents double-free (SIGABRT) and use-after-free when
// teardown runs concurrently across ExoPlayer's sink thread and Android main/USB threads.

mod handle_registry {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::{Arc, RwLock};

    pub const MAX_ACTIVE_SESSIONS: usize = 8;

    pub struct SessionRegistry<T> {
        next_id: AtomicU64,
        slots: RwLock<[(u64, Option<Arc<T>>); MAX_ACTIVE_SESSIONS]>,
    }

    impl<T> SessionRegistry<T> {
        pub const fn new() -> Self {
            Self {
                next_id: AtomicU64::new(1),
                slots: RwLock::new([
                    (0, None),
                    (0, None),
                    (0, None),
                    (0, None),
                    (0, None),
                    (0, None),
                    (0, None),
                    (0, None),
                ]),
            }
        }

        pub fn register(&self, item: T) -> Result<u64, T> {
            let Ok(mut guard) = self.slots.write() else {
                return Err(item);
            };
            let Some(slot) = guard.iter_mut().find(|slot| slot.1.is_none()) else {
                return Err(item);
            };
            let arc = Arc::new(item);
            // Keep non-zero positive handle fitting signed jlong (bit 63 clear).
            let id = loop {
                let raw = self.next_id.fetch_add(1, Ordering::Relaxed);
                let masked = raw & 0x7FFF_FFFF_FFFF_FFFF;
                if masked != 0 {
                    break masked;
                }
            };
            *slot = (id, Some(arc));
            Ok(id)
        }

        pub fn lookup(&self, id: u64) -> Option<Arc<T>> {
            if id == 0 {
                return None;
            }
            let guard = self.slots.read().ok()?;
            for (slot_id, item) in guard.iter() {
                if *slot_id == id {
                    return item.as_ref().map(Arc::clone);
                }
            }
            None
        }

        pub fn take(&self, id: u64) -> Option<Arc<T>> {
            if id == 0 {
                return None;
            }
            let mut guard = self.slots.write().ok()?;
            for slot in guard.iter_mut() {
                if slot.0 == id {
                    slot.0 = 0;
                    return slot.1.take();
                }
            }
            None
        }
    }
}

static SESSION_REGISTRY: handle_registry::SessionRegistry<UsbSessionInstance> =
    handle_registry::SessionRegistry::new();

fn lookup_session(handle: jlong) -> Option<std::sync::Arc<UsbSessionInstance>> {
    if handle <= 0 {
        return None;
    }
    SESSION_REGISTRY.lookup(handle as u64)
}

fn take_session(handle: jlong) -> Option<std::sync::Arc<UsbSessionInstance>> {
    if handle <= 0 {
        return None;
    }
    SESSION_REGISTRY.take(handle as u64)
}

// ---- Coarse UsbSession JNI entry points (Stage 2) ------------------------------------------

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionOpen(
    _env: JNIEnv,
    _c: JClass,
    fd: jint,
    control_iface: jint,
    streaming_iface: jint,
    is_uac2: jboolean,
    clock_source_id: jint,
    has_hardware_volume: jboolean,
    feature_unit_id: jint,
) -> jlong {
    let result = catch_unwind(|| {
        ensure_no_device_discovery();
        if fd < 0 {
            return 0;
        }
        match RealUsbControlTransport::open_device(
            fd,
            control_iface.max(0) as u8,
            streaming_iface.max(0) as u8,
            is_uac2 != 0,
            clock_source_id.max(0) as u8,
            has_hardware_volume != 0,
            feature_unit_id.max(0) as u8,
        ) {
            Ok(inst) => match SESSION_REGISTRY.register(inst) {
                Ok(id) => id as jlong,
                Err(leftover) => {
                    if let Ok(mut core) = leftover.core.lock() {
                        core.close();
                    }
                    0
                }
            },
            Err(_) => 0,
        }
    });
    result.unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionReconfigure(
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
        if sample_rate <= 0 || channels <= 0 || subslot_size <= 0 {
            return -22;
        }
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let Ok(mut core) = instance.core.lock() else {
            return -1;
        };
        let cfg = EndpointFormatConfig {
            streaming_iface: streaming_iface.max(0) as u8,
            target_alt: target_alt.max(0) as u8,
            endpoint: (endpoint & 0xFF) as u8,
            sample_rate: sample_rate as u32,
            bit_depth: bit_depth.max(0) as u32,
            channels: channels as u32,
            subslot_size: subslot_size as u32,
            is_high_speed: is_high_speed != 0,
            is_dop: is_dop != 0,
            feedback_ep: (feedback_ep & 0xFF) as u8,
        };
        match core.reconfigure(cfg) {
            Ok(()) => 0,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionApplyVolume(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    fu_id: jint,
    control_iface: jint,
    channel: jint,
    volume_raw: jint,
    mute: jboolean,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let Ok(mut core) = instance.core.lock() else {
            return -1;
        };
        match core.apply_volume(
            fu_id.max(0) as u8,
            control_iface.max(0) as u8,
            channel.max(0) as u8,
            volume_raw as i16,
            mute != 0,
        ) {
            Ok((read_vol, _mute)) => (read_vol as u16) as jint,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionReadVolume(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    fu_id: jint,
    control_iface: jint,
    channel: jint,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let Ok(mut core) = instance.core.lock() else {
            return -1;
        };
        match core.read_volume(
            fu_id.max(0) as u8,
            control_iface.max(0) as u8,
            channel.max(0) as u8,
        ) {
            Ok(read_vol) => (read_vol as u16) as jint,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionReadMute(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    fu_id: jint,
    control_iface: jint,
    channel: jint,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let Ok(mut core) = instance.core.lock() else {
            return -1;
        };
        match core.read_mute(
            fu_id.max(0) as u8,
            control_iface.max(0) as u8,
            channel.max(0) as u8,
        ) {
            Ok(muted) => if muted { 1 } else { 0 },
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionStart(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let Ok(mut core) = instance.core.lock() else {
            return -1;
        };
        match core.start() {
            Ok(()) => 0,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionPauseGrace(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let Ok(mut core) = instance.core.lock() else {
            return -1;
        };
        match core.pause_grace() {
            Ok(()) => 0,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionStop(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let Ok(mut core) = instance.core.lock() else {
            return -1;
        };
        match core.stop() {
            Ok(()) => 0,
            Err(code) => code,
        }
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionWritePcm(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    buffer: JByteBuffer,
    offset: jint,
    length: jint,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        if length <= 0 || offset < 0 {
            return 0;
        }
        let (Ok(ptr), Ok(cap)) = (
            env.get_direct_buffer_address(&buffer),
            env.get_direct_buffer_capacity(&buffer),
        ) else {
            return -1;
        };
        let Some(end) = (offset as usize).checked_add(length as usize) else {
            return -22;
        };
        if ptr.is_null() || end > cap {
            return -22;
        }
        // Lock-free write on SpscRingBuffer + AtomicBool resume (zero core mutex on audio path).
        let src = unsafe { ptr.add(offset as usize) };
        let written = unsafe { instance.ring_buffer.write_raw(src, length as usize) };
        if written > 0 {
            instance.flushing.store(false, Ordering::Release);
        }
        written as jint
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionSetMuteRamp(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    muted: jboolean,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        instance.mute_ramp.store(muted != 0, Ordering::Release);
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionFlush(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        instance.flushing.store(true, Ordering::Release);
        instance.ring_buffer.clear();
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionEscalateAndResync(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        instance.escalate_and_resync();
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionGetStats(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    out_stats: JLongArray,
) -> jint {
    let result = catch_unwind(|| {
        let Some(instance) = lookup_session(handle) else {
            return -22;
        };
        let consumed = instance.ring_buffer.frames_consumed.load(Ordering::Relaxed) as i64;
        let underruns = instance.ring_buffer.underruns.load(Ordering::Relaxed) as i64;
        let urb_errors = instance.ring_buffer.urb_errors.load(Ordering::Relaxed) as i64;
        let fill = instance.ring_buffer.available_read() as i64;
        let cap = instance.ring_buffer.capacity() as i64;
        let is_running = if instance.streaming_running.load(Ordering::Acquire) { 1i64 } else { 0i64 };
        let rate = instance.sample_rate.load(Ordering::Acquire) as i64;
        let is_paused = if instance.paused.load(Ordering::Acquire) { 1i64 } else { 0i64 };
        let is_flushing = if instance.flushing.load(Ordering::Acquire) { 1i64 } else { 0i64 };
        let state_ord = instance
            .core
            .try_lock()
            .map(|c| c.state() as i64)
            .unwrap_or(-1);
        let pkt_errors = instance.ring_buffer.pkt_errors.load(Ordering::Relaxed) as i64;
        let pkt_shorts = instance.ring_buffer.pkt_shorts.load(Ordering::Relaxed) as i64;
        let last_pkt_err = instance.ring_buffer.last_pkt_err_status.load(Ordering::Relaxed) as i64;
        let max_cb_gap_us = instance.ring_buffer.max_cb_gap_us.load(Ordering::Relaxed) as i64;
        let late_callbacks = instance.ring_buffer.late_callbacks.load(Ordering::Relaxed) as i64;
        let active_slots = instance.ring_buffer.active_slots.load(Ordering::Relaxed) as i64;
        let cushion_escalations = instance.ring_buffer.cushion_escalations.load(Ordering::Relaxed) as i64;

        let stats = [
            consumed,
            underruns,
            urb_errors,
            fill,
            cap,
            is_running,
            rate,
            is_paused,
            is_flushing,
            state_ord,
            pkt_errors,
            pkt_shorts,
            last_pkt_err,
            max_cb_gap_us,
            late_callbacks,
            active_slots,
            cushion_escalations,
        ];
        let len = match env.get_array_length(&out_stats) {
            Ok(l) => (l.max(0) as usize).min(stats.len()),
            Err(_) => return -22,
        };
        let _ = env.set_long_array_region(&out_stats, 0, &stats[..len]);
        0
    });
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_usb_UsbEngineNative_sessionClose(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
) {
    let _ = catch_unwind(|| {
        if let Some(instance) = take_session(handle) {
            // Take removes the handle first so concurrent/duplicate closes become no-ops.
            if let Ok(mut core) = instance.core.lock() {
                core.close();
            }
            drop(instance);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::handle_registry::{SessionRegistry, MAX_ACTIVE_SESSIONS};
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, Barrier};

    #[derive(Debug)]
    struct DropCounter(Arc<AtomicUsize>);
    impl Drop for DropCounter {
        fn drop(&mut self) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    #[test]
    fn handle_registry_prevents_double_close_and_concurrent_use_after_free() {
        let registry: Arc<SessionRegistry<DropCounter>> = Arc::new(SessionRegistry::new());
        let drops = Arc::new(AtomicUsize::new(0));

        for round in 0..20 {
            let before = drops.load(Ordering::SeqCst);
            let id = registry.register(DropCounter(Arc::clone(&drops))).expect("register");
            assert!(id > 0);

            let barrier = Arc::new(Barrier::new(6));
            let take_wins = Arc::new(AtomicUsize::new(0));
            let mut handles = Vec::with_capacity(6);
            for i in 0..6 {
                let reg = Arc::clone(&registry);
                let bar = Arc::clone(&barrier);
                let wins = Arc::clone(&take_wins);
                handles.push(std::thread::spawn(move || {
                    bar.wait();
                    if i < 2 {
                        if round % 2 == 0 {
                            std::thread::yield_now();
                        }
                        if let Some(arc) = reg.take(id) {
                            wins.fetch_add(1, Ordering::SeqCst);
                            drop(arc);
                        }
                    } else {
                        for _ in 0..200 {
                            if let Some(arc) = reg.lookup(id) {
                                let _ = arc.0.load(Ordering::Relaxed);
                                drop(arc);
                            }
                        }
                    }
                }));
            }
            for h in handles {
                h.join().unwrap();
            }
            assert_eq!(take_wins.load(Ordering::SeqCst), 1);
            assert!(registry.take(id).is_none());
            assert!(registry.lookup(id).is_none());
            assert_eq!(drops.load(Ordering::SeqCst), before + 1);
        }

        // Capacity bound: MAX_ACTIVE_SESSIONS registrations succeed, next returns Err.
        let before_cap = drops.load(Ordering::SeqCst);
        let mut ids = Vec::new();
        for _ in 0..MAX_ACTIVE_SESSIONS {
            ids.push(registry.register(DropCounter(Arc::clone(&drops))).expect("slot"));
        }
        assert!(registry.register(DropCounter(Arc::clone(&drops))).is_err());
        assert_eq!(drops.load(Ordering::SeqCst), before_cap + 1);
        for rem in ids {
            assert!(registry.take(rem).is_some());
        }
        assert_eq!(drops.load(Ordering::SeqCst), before_cap + 1 + MAX_ACTIVE_SESSIONS);
    }
}
