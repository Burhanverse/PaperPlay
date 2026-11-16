//! PaperPlay audio engine: float DSP for the Android player. JNI entry points at the bottom.
mod eq;
pub use eq::{Eq, BANDS_HZ};

use jni::objects::{JByteBuffer, JClass, JFloatArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_create(_env: JNIEnv, _c: JClass, rate: jint, channels: jint) -> jlong {
    Box::into_raw(Box::new(Eq::new(rate.max(0) as u32, channels.max(1) as u32))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_destroy(_env: JNIEnv, _c: JClass, handle: jlong) {
    if handle != 0 { drop(unsafe { Box::from_raw(handle as *mut Eq) }); }
}

/// An empty or wrong-sized array turns the equalizer off.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setEq(env: JNIEnv, _c: JClass, handle: jlong, gains: JFloatArray) {
    if handle == 0 { return; }
    let eq = unsafe { &mut *(handle as *mut Eq) };
    let n = env.get_array_length(&gains).unwrap_or(0).max(0) as usize;
    let mut v = vec![0f32; n];
    if env.get_float_array_region(&gains, 0, &mut v).is_err() { v.clear(); }
    eq.set_gains(&v);
}

/// `buf` is a direct buffer of interleaved native-endian f32; processed in place.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_process(env: JNIEnv, _c: JClass, handle: jlong, buf: JByteBuffer, samples: jint) {
    if handle == 0 || samples <= 0 { return; }
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else { return };
    let n = (samples as usize).min(cap / 4);
    if ptr.is_null() || (ptr as usize) % 4 != 0 { return; }
    let eq = unsafe { &mut *(handle as *mut Eq) };
    eq.process(unsafe { std::slice::from_raw_parts_mut(ptr as *mut f32, n) });
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_reset(_env: JNIEnv, _c: JClass, handle: jlong) {
    if handle != 0 { unsafe { &mut *(handle as *mut Eq) }.reset(); }
}

/// Extra bass in dB, 0 to 12.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setBass(_env: JNIEnv, _c: JClass, handle: jlong, db: jni::sys::jfloat) {
    if handle != 0 { unsafe { &mut *(handle as *mut Eq) }.set_bass(db); }
}

/// Writes `[preamp dB, bass dB, limiter reduction dB, peak boost dB]` into `out` (4 floats) and restarts the limiter meter.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_stats(env: JNIEnv, _c: JClass, handle: jlong, out: JFloatArray) {
    if handle == 0 { return; }
    let st = unsafe { &mut *(handle as *mut Eq) }.take_stats();
    let _ = env.set_float_array_region(&out, 0, &st);
}
