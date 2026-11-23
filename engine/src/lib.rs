//! PaperPlay audio engine: float DSP for the Android player. JNI entry points at the bottom.
mod eq;
pub mod features;
mod extras;
pub use eq::{
    compute_auto_preamp_db, eval_parametric_response, Eq, PeqBand, BANDS_HZ, MAX_PEQ_BANDS,
    PEQ_BP, PEQ_CH_LEFT, PEQ_CH_RIGHT, PEQ_CH_STEREO, PEQ_HP, PEQ_LP, PEQ_LSC, PEQ_HSC, PEQ_PK,
};

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

/// Reconfigures sample rate and channel count dynamically (clamps band frequencies to `0.49 * rate`
/// and resets filter states cleanly across track/USB rate switches).
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_reconfigure(
    _env: JNIEnv,
    _c: JClass,
    handle: jlong,
    rate: jint,
    channels: jint,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if let Some(eq) = eq(handle) {
            eq.reconfigure(rate.max(8000) as u32, channels.max(1) as u32);
        }
    }));
}

/// Processes `[offset .. offset + length]` bytes of direct `ByteBuffer` `buf` in-place in full `f64`
/// precision with TPDF dither for integer encodings (`PCM_16`, `PCM_24`, `PCM_32`, `PCM_FLOAT`).
/// Zero heap allocations and zero intermediate copies; byte-exact no-op when bypassed.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_processPcmInPlace(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    buf: JByteBuffer,
    offset: jint,
    length: jint,
    kind: jint,
) -> jint {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if offset < 0 || length <= 0 || pcm_bytes(kind) == 0 { return 0; }
        let Some(eq) = eq(handle) else { return 0 };
        let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) else { return 0 };
        if ptr.is_null() { return 0; }
        let off = offset as usize;
        let len = (length as usize).min(cap.saturating_sub(off));
        if len == 0 { return 0; }
        let slice = unsafe { std::slice::from_raw_parts_mut(ptr.add(off), len) };
        eq.process_pcm_in_place(slice, kind) as jint
    })).unwrap_or(0)
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

/// Decodes a flat float slice of `6 * N` floats (`[kind, channel, enabled, freqHz, gainDb, q]` per band)
/// into a stack array of up to `MAX_PEQ_BANDS` without heap allocations.
fn decode_peq_bands(flat: &[f32], out: &mut [PeqBand; MAX_PEQ_BANDS]) -> usize {
    let mut n = 0usize;
    for chunk in flat.chunks_exact(6).take(MAX_PEQ_BANDS) {
        out[n] = PeqBand {
            kind: chunk[0].round().clamp(0.0, 5.0) as u8,
            channel: chunk[1].round().clamp(0.0, 2.0) as u8,
            enabled: chunk[2] >= 0.5,
            freq_hz: chunk[3] as f64,
            gain_db: chunk[4] as f64,
            q: chunk[5] as f64,
        };
        n += 1;
    }
    n
}

/// Configures the 31-band parametric EQ on `handle`. `bands` holds 6 floats per band:
/// `[kind, channel, enabled(0/1), freqHz, gainDb, q]`.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_setParametricEq(
    env: JNIEnv,
    _c: JClass,
    handle: jlong,
    enabled: jboolean,
    preamp_db: jni::sys::jfloat,
    auto_preamp: jboolean,
    limiter_enabled: jboolean,
    bands: JFloatArray,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let Some(eq) = eq(handle) else { return };
        let len = env.get_array_length(&bands).unwrap_or(0).clamp(0, (MAX_PEQ_BANDS * 6) as i32) as usize;
        let mut flat = [0f32; MAX_PEQ_BANDS * 6];
        let count = if len > 0 && env.get_float_array_region(&bands, 0, &mut flat[..len]).is_ok() {
            let mut decoded = [PeqBand::default(); MAX_PEQ_BANDS];
            let n = decode_peq_bands(&flat[..len], &mut decoded);
            eq.set_parametric(enabled != 0, preamp_db, auto_preamp != 0, limiter_enabled != 0, &decoded[..n]);
            n
        } else {
            eq.set_parametric(enabled != 0, preamp_db, auto_preamp != 0, limiter_enabled != 0, &[]);
            0
        };
        let _ = count;
    }));
}

/// Evaluates the exact Rust DSP magnitude response of a parametric EQ configuration at `freqs_hz`,
/// writing dB values into `out_db`. Returns the effective preamp in dB (manual + auto).
/// Stateless and safe to call at 60 FPS from the UI thread without locking the audio thread.
#[no_mangle]
pub extern "system" fn Java_io_max_paperplay_player_EngineNative_evalParametricResponse(
    env: JNIEnv,
    _c: JClass,
    rate: jint,
    preamp_db: jni::sys::jfloat,
    auto_preamp: jboolean,
    bands: JFloatArray,
    freqs_hz: JFloatArray,
    out_db: JFloatArray,
    channel: jint,
) -> jni::sys::jfloat {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let blen = env.get_array_length(&bands).unwrap_or(0).clamp(0, (MAX_PEQ_BANDS * 6) as i32) as usize;
        let mut flat = [0f32; MAX_PEQ_BANDS * 6];
        let mut decoded = [PeqBand::default(); MAX_PEQ_BANDS];
        let nb = if blen > 0 && env.get_float_array_region(&bands, 0, &mut flat[..blen]).is_ok() {
            decode_peq_bands(&flat[..blen], &mut decoded)
        } else {
            0
        };
        let flen = env.get_array_length(&freqs_hz).unwrap_or(0).max(0) as usize;
        let olen = env.get_array_length(&out_db).unwrap_or(0).max(0) as usize;
        let n = flen.min(olen);
        if n == 0 { return 0.0; }
        let mut freqs = vec![0f32; n];
        let mut out = vec![0f32; n];
        if env.get_float_array_region(&freqs_hz, 0, &mut freqs).is_err() { return 0.0; }
        let eff = eval_parametric_response(
            rate.max(8000) as f64,
            preamp_db as f64,
            auto_preamp != 0,
            &decoded[..nb],
            &freqs,
            &mut out,
            channel.clamp(0, 2) as u8,
        );
        let _ = env.set_float_array_region(&out_db, 0, &out);
        eff
    })).unwrap_or(0.0)
}

// ---- C-ABI entry points for Desktop JVM (JNA) ----

#[no_mangle]
pub extern "C" fn paperplay_eq_create(rate: u32, channels: u32) -> *mut Eq {
    std::panic::catch_unwind(|| Box::into_raw(Box::new(Eq::new(rate, channels))))
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn paperplay_eq_destroy(handle: *mut Eq) {
    let _ = std::panic::catch_unwind(|| {
        if !handle.is_null() { drop(unsafe { Box::from_raw(handle) }); }
    });
}

#[no_mangle]
pub extern "C" fn paperplay_eq_set_parametric(
    handle: *mut Eq,
    enabled: i32,
    preamp_db: f32,
    auto_preamp: i32,
    limiter_enabled: i32,
    bands_flat: *const f32,
    bands_float_len: i32,
) {
    let _ = std::panic::catch_unwind(|| {
        if handle.is_null() { return; }
        let eq = unsafe { &mut *handle };
        let mut decoded = [PeqBand::default(); MAX_PEQ_BANDS];
        let nb = if !bands_flat.is_null() && bands_float_len > 0 {
            let len = (bands_float_len as usize).min(MAX_PEQ_BANDS * 6);
            let slice = unsafe { std::slice::from_raw_parts(bands_flat, len) };
            decode_peq_bands(slice, &mut decoded)
        } else {
            0
        };
        eq.set_parametric(enabled != 0, preamp_db, auto_preamp != 0, limiter_enabled != 0, &decoded[..nb]);
    });
}

#[no_mangle]
pub extern "C" fn paperplay_eq_process(handle: *mut Eq, buf: *mut f32, samples: i32) {
    let _ = std::panic::catch_unwind(|| {
        if handle.is_null() || buf.is_null() || samples <= 0 { return; }
        let eq = unsafe { &mut *handle };
        let slice = unsafe { std::slice::from_raw_parts_mut(buf, samples as usize) };
        eq.process(slice);
    });
}

#[no_mangle]
pub extern "C" fn paperplay_eq_reconfigure(handle: *mut Eq, rate: u32, channels: u32) {
    let _ = std::panic::catch_unwind(|| {
        if handle.is_null() { return; }
        let eq = unsafe { &mut *handle };
        eq.reconfigure(rate, channels);
    });
}

#[no_mangle]
pub extern "C" fn paperplay_eq_process_pcm_in_place(handle: *mut Eq, buf: *mut u8, byte_len: i32, kind: i32) -> i32 {
    std::panic::catch_unwind(|| {
        if handle.is_null() || buf.is_null() || byte_len <= 0 { return 0; }
        let eq = unsafe { &mut *handle };
        let slice = unsafe { std::slice::from_raw_parts_mut(buf, byte_len as usize) };
        eq.process_pcm_in_place(slice, kind) as i32
    }).unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn paperplay_eq_eval_response(
    rate: i32,
    preamp_db: f32,
    auto_preamp: i32,
    bands_flat: *const f32,
    bands_float_len: i32,
    freqs_hz: *const f32,
    out_db: *mut f32,
    n_freqs: i32,
    channel: i32,
) -> f32 {
    std::panic::catch_unwind(|| {
        if freqs_hz.is_null() || out_db.is_null() || n_freqs <= 0 { return 0.0; }
        let mut decoded = [PeqBand::default(); MAX_PEQ_BANDS];
        let nb = if !bands_flat.is_null() && bands_float_len > 0 {
            let len = (bands_float_len as usize).min(MAX_PEQ_BANDS * 6);
            let slice = unsafe { std::slice::from_raw_parts(bands_flat, len) };
            decode_peq_bands(slice, &mut decoded)
        } else {
            0
        };
        let n = n_freqs as usize;
        let freqs = unsafe { std::slice::from_raw_parts(freqs_hz, n) };
        let out = unsafe { std::slice::from_raw_parts_mut(out_db, n) };
        eval_parametric_response(
            rate.max(8000) as f64,
            preamp_db as f64,
            auto_preamp != 0,
            &decoded[..nb],
            freqs,
            out,
            channel.clamp(0, 2) as u8,
        )
    }).unwrap_or(0.0)
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
