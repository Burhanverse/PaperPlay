//! PaperPlay audio engine: float DSP for the Android player. JNI entry points at the bottom.
mod eq;
pub mod features;
mod extras;
pub use eq::{Eq, BANDS_HZ};

#[cfg(feature = "usb")]
pub mod usb;

use jni::objects::{JByteBuffer, JClass, JFloatArray};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

/// Sample formats `convert_pcm` reads: 16-bit, 24-bit packed, 32-bit integer, 32-bit float (native endian).
pub const PCM_16: i32 = 0;
pub const PCM_24: i32 = 1;
pub const PCM_32: i32 = 2;
pub const PCM_FLOAT: i32 = 3;

/// Bytes per sample of a `PCM_*` kind, or 0 for an unknown one.
pub fn pcm_bytes(kind: i32) -> usize {
    match kind { PCM_16 => 2, PCM_24 => 3, PCM_32 | PCM_FLOAT => 4, _ => 0 }
}

/// Converts interleaved PCM bytes to float in -1..1. Writes `min(src samples, out.len())` samples and returns that count.
pub fn convert_pcm(src: &[u8], kind: i32, out: &mut [f32]) -> usize {
    let step = pcm_bytes(kind);
    if step == 0 { return 0; }
    let n = (src.len() / step).min(out.len());
    for (i, s) in src.chunks_exact(step).take(n).enumerate() {
        out[i] = match kind {
            PCM_16 => i16::from_ne_bytes([s[0], s[1]]) as f32 / 32768.0,
            PCM_24 => {
                let b = if cfg!(target_endian = "little") { [0, s[0], s[1], s[2]] } else { [s[0], s[1], s[2], 0] };
                (i32::from_ne_bytes(b) >> 8) as f32 / 8388608.0
            }
            PCM_32 => i32::from_ne_bytes([s[0], s[1], s[2], s[3]]) as f32 / 2147483648.0,
            _ => f32::from_ne_bytes([s[0], s[1], s[2], s[3]]),
        };
    }
    n
}

fn eq<'a>(handle: jlong) -> Option<&'a mut Eq> {
    if handle == 0 { None } else { Some(unsafe { &mut *(handle as *mut Eq) }) }
}

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
    let Some(eq) = eq(handle) else { return };
    let n = env.get_array_length(&gains).unwrap_or(0).max(0) as usize;
    let mut v = vec![0f32; n];
    if env.get_float_array_region(&gains, 0, &mut v).is_err() { v.clear(); }
    eq.set_gains(&v);
}

/// `buf` is a direct buffer of interleaved native-endian f32; processed in place.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_process(env: JNIEnv, _c: JClass, handle: jlong, buf: JByteBuffer, samples: jint) {
    if samples <= 0 { return; }
    let Some(eq) = eq(handle) else { return };
    let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else { return };
    let n = (samples as usize).min(cap / 4);
    if ptr.is_null() || (ptr as usize) % 4 != 0 { return; }
    eq.process(unsafe { std::slice::from_raw_parts_mut(ptr as *mut f32, n) });
}

/// Reads `samples` PCM samples of `kind` (a `PCM_*` value) from direct buffer `src` at byte `offset`, writes them as float
/// to direct buffer `out` and processes them there. Returns the number of samples written, 0 on any problem.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_processPcm(
    env: JNIEnv, _c: JClass, handle: jlong, src: JByteBuffer, offset: jint, kind: jint, samples: jint, out: JByteBuffer,
) -> jint {
    let step = pcm_bytes(kind);
    if samples <= 0 || offset < 0 || step == 0 { return 0; }
    let Some(eq) = eq(handle) else { return 0 };
    let (Ok(sp), Ok(scap)) = (env.get_direct_buffer_address(&src), env.get_direct_buffer_capacity(&src)) else { return 0 };
    let (Ok(op), Ok(ocap)) = (env.get_direct_buffer_address(&out), env.get_direct_buffer_capacity(&out)) else { return 0 };
    if sp.is_null() || op.is_null() || (op as usize) % 4 != 0 { return 0; }
    let offset = offset as usize;
    let n = (samples as usize).min(scap.saturating_sub(offset) / step).min(ocap / 4);
    if n == 0 { return 0; }
    let src = unsafe { std::slice::from_raw_parts(sp.add(offset), n * step) };
    let out = unsafe { std::slice::from_raw_parts_mut(op as *mut f32, n) };
    let n = convert_pcm(src, kind, out);
    eq.process(&mut out[..n]);
    n as jint
}

#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_reset(_env: JNIEnv, _c: JClass, handle: jlong) {
    if let Some(eq) = eq(handle) { eq.reset(); }
}

/// Extra bass in dB, 0 to 12.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setBass(_env: JNIEnv, _c: JClass, handle: jlong, db: jni::sys::jfloat) {
    if let Some(eq) = eq(handle) { eq.set_bass(db); }
}

/// Earphone correction: `filters` holds 4 floats per filter (kind 0 peak / 1 low shelf / 2 high shelf, Hz, Q, gain dB);
/// an empty array turns it off.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setCorrection(env: JNIEnv, _c: JClass, handle: jlong, filters: JFloatArray) {
    let Some(eq) = eq(handle) else { return };
    let n = env.get_array_length(&filters).unwrap_or(0).max(0) as usize;
    let mut v = vec![0f32; n];
    if env.get_float_array_region(&filters, 0, &mut v).is_err() { v.clear(); }
    let f: Vec<(u8, f64, f64, f64)> = v.chunks_exact(4).map(|c| (c[0] as u8, c[1] as f64, c[2] as f64, c[3] as f64)).collect();
    eq.set_correction(&f);
}

/// Stereo width above 180 Hz, 0.5 to 1.6 (1 = as recorded).
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setWidth(_env: JNIEnv, _c: JClass, handle: jlong, w: jni::sys::jfloat) {
    if let Some(eq) = eq(handle) { eq.set_width(w); }
}

/// Option bits: 1 dynamic bass, 2 de-esser, 4 crossfeed.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setOptions(_env: JNIEnv, _c: JClass, handle: jlong, bits: jint) {
    if let Some(eq) = eq(handle) { eq.set_options(bits.max(0) as u32); }
}

/// Exact level mode (the heard balance is the drawn curve) instead of Loud.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setExact(_env: JNIEnv, _c: JClass, handle: jlong, on: jboolean) {
    if let Some(eq) = eq(handle) { eq.set_exact(on != 0); }
}

/// A/B: plays the untouched input at the processed level and timing.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setDry(_env: JNIEnv, _c: JClass, handle: jlong, on: jboolean) {
    if let Some(eq) = eq(handle) { eq.set_dry(on != 0); }
}

/// Writes the 7 values of [`Eq::take_stats`] into `out` and restarts the limiter meters.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_stats(env: JNIEnv, _c: JClass, handle: jlong, out: JFloatArray) {
    let Some(eq) = eq(handle) else { return };
    let st = eq.take_stats();
    let n = env.get_array_length(&out).unwrap_or(0).clamp(0, st.len() as i32) as usize;
    let _ = env.set_float_array_region(&out, 0, &st[..n]);
}

/// Measures the first `n` samples of mono `pcm` at `rate` Hz into `out` (`features::N_FEATURES` values); false when too short or silent.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_analyze(
    env: JNIEnv, _c: JClass, pcm: JFloatArray, n: jint, rate: jint, out: JFloatArray,
) -> jboolean {
    let len = env.get_array_length(&pcm).unwrap_or(0).clamp(0, n.max(0)) as usize;
    let mut v = vec![0f32; len];
    if len == 0 || rate <= 0 || env.get_float_array_region(&pcm, 0, &mut v).is_err() { return 0; }
    let Some(f) = features::analyze(&v, rate as u32) else { return 0 };
    if env.get_array_length(&out).unwrap_or(0) < f.len() as i32 { return 0; }
    if env.set_float_array_region(&out, 0, &f).is_err() { return 0; }
    1
}

/// Version of the feature layout; stored with each vector so a new layout measures songs again.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_featureVersion(_env: JNIEnv, _c: JClass) -> jint {
    features::FEATURE_VERSION
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pcm_converts_every_format() {
        let mut out = [0f32; 3];
        let s16: Vec<u8> = [-32768i16, 0, 16384].iter().flat_map(|v| v.to_ne_bytes()).collect();
        assert_eq!(convert_pcm(&s16, PCM_16, &mut out), 3);
        assert_eq!(out, [-1.0, 0.0, 0.5]);

        let s24: Vec<u8> = [-8388608i32, 0, 4194304].iter().flat_map(|v| {
            let b = v.to_le_bytes();
            [b[0], b[1], b[2]]
        }).collect();
        assert_eq!(convert_pcm(&s24, PCM_24, &mut out), 3);
        assert_eq!(out, [-1.0, 0.0, 0.5]);

        let s32: Vec<u8> = [i32::MIN, 0, 1 << 30].iter().flat_map(|v| v.to_ne_bytes()).collect();
        assert_eq!(convert_pcm(&s32, PCM_32, &mut out), 3);
        assert_eq!(out, [-1.0, 0.0, 0.5]);

        let f: Vec<u8> = [-1.0f32, 0.0, 0.5].iter().flat_map(|v| v.to_ne_bytes()).collect();
        assert_eq!(convert_pcm(&f, PCM_FLOAT, &mut out), 3);
        assert_eq!(out, [-1.0, 0.0, 0.5]);

        assert_eq!(convert_pcm(&f, 9, &mut out), 0);
    }
}
