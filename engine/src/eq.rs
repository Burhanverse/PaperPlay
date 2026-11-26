//! Ten-band graphic equalizer plus a bass stage, then a two-band level and limiter stage.
//!
//! The band gains are fitted so the combined response passes through the requested curve (neighbouring bands overlap and
//! would otherwise add up). To stay clean the signal is split at 180 Hz after the equalizer: each half gets its own level trim
//! and look-ahead limiter, so a bass peak never ducks the mids and highs. A last true-peak limiter catches the sum.
//!
//! Two level modes: Loud (default) keeps the music loud and lowers the rest when bass is lifted, so the bass is heard as
//! somewhat less than drawn; Exact lowers everything by the curve's peak, so what is heard is exactly the drawn curve.
//! Every change glides (filters crossfade, trims ramp, switching on and off fades), so nothing clicks. A dry mode plays the
//! untouched input at the processed level and timing, for a fair A/B.

use crate::extras::{Crossfeed, DeEsser, DynBass};
use std::f64::consts::PI;

/// `set_options` bits.
pub const OPT_DYNAMIC_BASS: u32 = 1;
pub const OPT_DE_ESSER: u32 = 2;
pub const OPT_CROSSFEED: u32 = 4;

pub const BANDS_HZ: [f64; 10] = [32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];
pub(crate) const N: usize = BANDS_HZ.len();
const Q: f64 = 1.41; // one octave wide
pub(crate) const MAX_CH: usize = 8;
const MAX_FIT_DB: f64 = 18.0;
/// Loud mode: net lift the bass half keeps (dB), so the limiter stays a safety net and bass keeps its punch.
const BASS_NET_DB: f64 = 4.0;
/// Loud mode: of the bass boost above that, how much the rest is lowered too, so the bass still stands out.
const SEPARATION: f64 = 0.7;
/// Loud mode: the most the rest is ever lowered for the bass (dB).
const MAX_REST_CUT_DB: f64 = 10.0;
/// Loud mode: share of a boost in the mids and highs taken off their level up front.
const HIGH_SHARE: f64 = 0.35;
/// Ceiling of every limiter: -1 dB. The output limiter measures true (inter-sample) peaks, leaving room for the system
/// resampler, the Bluetooth encoder and the earbuds' own bass boost.
const CEILING: f64 = 0.891;
/// Ceiling of the bass half, lower than the output's so loud bass plus the rest rarely reaches the output limiter, which
/// would duck everything.
const CEILING_LOW: f64 = 0.75;
const CROSSOVER_HZ: f64 = 180.0;
const HIGH_FROM_HZ: f64 = 400.0;
/// Limiter look-ahead in frames (about 1 ms at 48 kHz): the gain is already down when the peak reaches the output.
const LOOK: usize = 48;
/// True-peak estimator: 4x interpolation with a 16-tap windowed sinc, centred 8 frames back.
const TP_TAPS: usize = 16;
const TP_CENTRE: usize = 7;
const TP_LAG: usize = TP_TAPS - 1 - TP_CENTRE;
/// Fade out and back in when the engine switches on or off.
const SWITCH_MS: f64 = 5.0;
/// Crossfade from the old filters to the new ones on any change, and between processed and dry.
const FADE_MS: f64 = 20.0;
/// Most filters an earphone correction may have.
pub const MAX_CORR: usize = 16;
/// Correction filter kinds, as in AutoEq's ParametricEQ.txt: peaking, low shelf, high shelf.
#[allow(dead_code)] // any kind other than the shelves is a peak
pub const CORR_PK: u8 = 0;
pub const CORR_LSC: u8 = 1;
pub const CORR_HSC: u8 = 2;
/// Maximum number of parametric EQ bands (Poweramp 31-band parity).
pub const MAX_PEQ_BANDS: usize = 31;
/// Parametric EQ filter types.
pub const PEQ_PK: u8 = 0;
pub const PEQ_LSC: u8 = 1;
pub const PEQ_HSC: u8 = 2;
pub const PEQ_LP: u8 = 3;
pub const PEQ_HP: u8 = 4;
pub const PEQ_BP: u8 = 5;
/// Parametric EQ per-band channel assignment.
pub const PEQ_CH_STEREO: u8 = 0;
pub const PEQ_CH_LEFT: u8 = 1;
pub const PEQ_CH_RIGHT: u8 = 2;
/// Parametric limiter ceiling: -0.5 dBFS.
const PEQ_LIM_CEILING: f64 = 0.9440608762859234;
/// Filters in the bass stage.
const BASS_F: usize = 4;
/// Time constant of the level trims.
const TRIM_MS: f64 = 10.0;
/// Loudness window of the A/B level match.
const MATCH_S: f64 = 3.0;

#[derive(Clone, Copy, Default)]
pub(crate) struct Coef { pub b0: f64, pub b1: f64, pub b2: f64, pub a1: f64, pub a2: f64 }

#[derive(Clone, Copy, Default)]
pub(crate) struct State { z1: f64, z2: f64 }

pub(crate) fn biquad(k: &Coef, st: &mut State, x: f64) -> f64 {
    let y = k.b0 * x + st.z1;
    st.z1 = k.b1 * x - k.a1 * y + st.z2;
    st.z2 = k.b2 * x - k.a2 * y;
    y
}

/// Approximate K-weighting (high shelf then high-pass), for loudness.
fn kweight(k: &[Coef; 2], st: &mut [State; 2], x: f64) -> f64 {
    let [s0, s1] = st;
    biquad(&k[1], s1, biquad(&k[0], s0, x))
}

/// RBJ peaking filter, one octave wide.
fn peaking(rate: f64, hz: f64, gain_db: f64) -> Coef { peaking_q(rate, hz, gain_db, Q) }

/// RBJ peaking filter with its own Q.
fn peaking_q(rate: f64, hz: f64, gain_db: f64, q: f64) -> Coef {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * PI * hz / rate;
    let alpha = w.sin() / (2.0 * q);
    let cw = w.cos();
    let a0 = 1.0 + alpha / a;
    Coef {
        b0: (1.0 + alpha * a) / a0,
        b1: (-2.0 * cw) / a0,
        b2: (1.0 - alpha * a) / a0,
        a1: (-2.0 * cw) / a0,
        a2: (1.0 - alpha / a) / a0,
    }
}

/// RBJ low shelf (slope 1).
fn low_shelf(rate: f64, hz: f64, gain_db: f64) -> Coef { low_shelf_q(rate, hz, gain_db, std::f64::consts::FRAC_1_SQRT_2) }

/// RBJ low shelf with its own Q (0.707 = slope 1).
fn low_shelf_q(rate: f64, hz: f64, gain_db: f64, q: f64) -> Coef {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * q);
    let t = 2.0 * a.sqrt() * alpha;
    let a0 = (a + 1.0) + (a - 1.0) * cw + t;
    Coef {
        b0: a * ((a + 1.0) - (a - 1.0) * cw + t) / a0,
        b1: 2.0 * a * ((a - 1.0) - (a + 1.0) * cw) / a0,
        b2: a * ((a + 1.0) - (a - 1.0) * cw - t) / a0,
        a1: -2.0 * ((a - 1.0) + (a + 1.0) * cw) / a0,
        a2: ((a + 1.0) + (a - 1.0) * cw - t) / a0,
    }
}

/// RBJ high shelf (slope 1).
fn high_shelf(rate: f64, hz: f64, gain_db: f64) -> Coef { high_shelf_q(rate, hz, gain_db, std::f64::consts::FRAC_1_SQRT_2) }

/// RBJ high shelf with its own Q (0.707 = slope 1).
fn high_shelf_q(rate: f64, hz: f64, gain_db: f64, q: f64) -> Coef {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * q);
    let t = 2.0 * a.sqrt() * alpha;
    let a0 = (a + 1.0) - (a - 1.0) * cw + t;
    Coef {
        b0: a * ((a + 1.0) + (a - 1.0) * cw + t) / a0,
        b1: -2.0 * a * ((a - 1.0) + (a + 1.0) * cw) / a0,
        b2: a * ((a + 1.0) + (a - 1.0) * cw - t) / a0,
        a1: 2.0 * ((a - 1.0) - (a + 1.0) * cw) / a0,
        a2: ((a + 1.0) - (a - 1.0) * cw - t) / a0,
    }
}

/// RBJ high-pass, Q = 0.707 (second order, 12 dB per octave).
fn high_pass(rate: f64, hz: f64) -> Coef {
    let w = 2.0 * PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * std::f64::consts::FRAC_1_SQRT_2);
    let a0 = 1.0 + alpha;
    Coef { b0: (1.0 + cw) / 2.0 / a0, b1: -(1.0 + cw) / a0, b2: (1.0 + cw) / 2.0 / a0, a1: -2.0 * cw / a0, a2: (1.0 - alpha) / a0 }
}

/// RBJ low-pass, Q = 0.707.
pub(crate) fn low_pass(rate: f64, hz: f64) -> Coef { low_pass_q(rate, hz, std::f64::consts::FRAC_1_SQRT_2) }

/// RBJ high-pass with custom Q.
fn high_pass_q(rate: f64, hz: f64, q: f64) -> Coef {
    let w = 2.0 * PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * q);
    let a0 = 1.0 + alpha;
    Coef { b0: (1.0 + cw) / 2.0 / a0, b1: -(1.0 + cw) / a0, b2: (1.0 + cw) / 2.0 / a0, a1: -2.0 * cw / a0, a2: (1.0 - alpha) / a0 }
}

/// RBJ low-pass with custom Q.
fn low_pass_q(rate: f64, hz: f64, q: f64) -> Coef {
    let w = 2.0 * PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * q);
    let a0 = 1.0 + alpha;
    Coef { b0: (1.0 - cw) / 2.0 / a0, b1: (1.0 - cw) / a0, b2: (1.0 - cw) / 2.0 / a0, a1: -2.0 * cw / a0, a2: (1.0 - alpha) / a0 }
}

/// RBJ band-pass (constant 0 dB peak gain) with custom Q.
fn band_pass_q(rate: f64, hz: f64, q: f64) -> Coef {
    let w = 2.0 * PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * q);
    let a0 = 1.0 + alpha;
    Coef { b0: alpha / a0, b1: 0.0, b2: -alpha / a0, a1: -2.0 * cw / a0, a2: (1.0 - alpha) / a0 }
}

/// Specification of one parametric EQ band.
#[derive(Clone, Copy, Debug, Default)]
pub struct PeqBand {
    pub kind: u8,
    pub channel: u8,
    pub enabled: bool,
    pub freq_hz: f64,
    pub gain_db: f64,
    pub q: f64,
}

/// Builds a double-precision RBJ biquad for a parametric band, clamping frequency to `[10, 0.49 * rate]`,
/// Q to `[0.1, 20.0]`, and gain to `[-24, +24] dB`. Returns `None` when disabled or an exact identity filter.
pub(crate) fn peq_coef(rate: f64, band: &PeqBand) -> Option<Coef> {
    if !band.enabled || !band.freq_hz.is_finite() || !band.gain_db.is_finite() || !band.q.is_finite() {
        return None;
    }
    let max_hz = (rate * 0.49).max(10.0);
    let hz = band.freq_hz.clamp(10.0, max_hz);
    let q = band.q.clamp(0.1, 20.0);
    let db = band.gain_db.clamp(-24.0, 24.0);
    match band.kind {
        PEQ_PK => if db.abs() < 1e-4 { None } else { Some(peaking_q(rate, hz, db, q)) },
        PEQ_LSC => if db.abs() < 1e-4 { None } else { Some(low_shelf_q(rate, hz, db, q)) },
        PEQ_HSC => if db.abs() < 1e-4 { None } else { Some(high_shelf_q(rate, hz, db, q)) },
        PEQ_LP => Some(low_pass_q(rate, hz, q)),
        PEQ_HP => Some(high_pass_q(rate, hz, q)),
        PEQ_BP => Some(band_pass_q(rate, hz, q)),
        _ => if db.abs() < 1e-4 { None } else { Some(peaking_q(rate, hz, db, q)) },
    }
}

/// Computes automatic headroom preamp (`-max(0.0, peak_gain_db)`) by scanning 256 log-spaced frequencies
/// across `20 Hz .. 20 kHz` (clamped below Nyquist) on both Left and Right channels.
pub fn compute_auto_preamp_db(rate: f64, bands: &[PeqBand]) -> f64 {
    let rate = rate.max(8000.0);
    let mut coefs = [Coef::default(); MAX_PEQ_BANDS];
    let mut chans = [0u8; MAX_PEQ_BANDS];
    let mut n = 0usize;
    for b in bands.iter().take(MAX_PEQ_BANDS) {
        if let Some(c) = peq_coef(rate, b) {
            coefs[n] = c;
            chans[n] = b.channel;
            n += 1;
        }
    }
    if n == 0 { return 0.0; }
    let f_min = 20.0f64;
    let f_max = 20000.0f64.min(rate * 0.49).max(f_min);
    let steps = 256usize;
    let ratio = (f_max / f_min).ln();
    let mut peak = 0.0f64;
    for i in 0..steps {
        let hz = f_min * (ratio * (i as f64) / ((steps - 1) as f64)).exp();
        let mut dl = 0.0f64;
        let mut dr = 0.0f64;
        for k in 0..n {
            let m = mag_db(&coefs[k], rate, hz);
            if m.is_finite() {
                match chans[k] {
                    PEQ_CH_LEFT => dl += m,
                    PEQ_CH_RIGHT => dr += m,
                    _ => { dl += m; dr += m; }
                }
            }
        }
        peak = peak.max(dl).max(dr);
    }
    -peak.max(0.0)
}

/// Evaluates the exact combined magnitude response (in dB, including manual + optional auto-preamp)
/// of up to `MAX_PEQ_BANDS` parametric bands at every frequency in `freqs_hz`, writing into `out_db`.
/// `channel`: `0` = stereo/average, `1` = Left, `2` = Right.
/// Returns the effective preamp in dB (manual + auto).
pub fn eval_parametric_response(
    rate: f64,
    preamp_db: f64,
    auto_preamp: bool,
    bands: &[PeqBand],
    freqs_hz: &[f32],
    out_db: &mut [f32],
    channel: u8,
) -> f32 {
    let rate = rate.max(8000.0);
    let manual = if preamp_db.is_finite() { preamp_db.clamp(-24.0, 24.0) } else { 0.0 };
    let auto = if auto_preamp { compute_auto_preamp_db(rate, bands) } else { 0.0 };
    let eff_preamp = manual + auto;
    let mut coefs = [Coef::default(); MAX_PEQ_BANDS];
    let mut chans = [0u8; MAX_PEQ_BANDS];
    let mut n = 0usize;
    for b in bands.iter().take(MAX_PEQ_BANDS) {
        if let Some(c) = peq_coef(rate, b) {
            coefs[n] = c;
            chans[n] = b.channel;
            n += 1;
        }
    }
    let count = freqs_hz.len().min(out_db.len());
    let max_hz = (rate * 0.499).max(10.0);
    for i in 0..count {
        let hz = (freqs_hz[i] as f64).clamp(1.0, max_hz);
        let mut dl = eff_preamp;
        let mut dr = eff_preamp;
        for k in 0..n {
            let m = mag_db(&coefs[k], rate, hz);
            if m.is_finite() {
                match chans[k] {
                    PEQ_CH_LEFT => dl += m,
                    PEQ_CH_RIGHT => dr += m,
                    _ => { dl += m; dr += m; }
                }
            }
        }
        let val = match channel {
            PEQ_CH_LEFT => dl,
            PEQ_CH_RIGHT => dr,
            _ => 0.5 * (dl + dr),
        };
        out_db[i] = val.clamp(-96.0, 48.0) as f32;
    }
    eff_preamp as f32
}

/// Magnitude of one biquad at `hz`, in dB.
fn mag_db(k: &Coef, rate: f64, hz: f64) -> f64 {
    let w = 2.0 * PI * hz / rate;
    let (c1, s1, c2, s2) = (w.cos(), w.sin(), (2.0 * w).cos(), (2.0 * w).sin());
    let nr = k.b0 + k.b1 * c1 + k.b2 * c2;
    let ni = -(k.b1 * s1 + k.b2 * s2);
    let dr = 1.0 + k.a1 * c1 + k.a2 * c2;
    let di = -(k.a1 * s1 + k.a2 * s2);
    10.0 * ((nr * nr + ni * ni) / (dr * dr + di * di)).log10()
}

/// Solves `m * x = b` (small dense system, partial pivoting). Returns None when singular.
fn solve(mut m: [[f64; N]; N], mut b: [f64; N]) -> Option<[f64; N]> {
    for c in 0..N {
        let mut p = c;
        for r in c + 1..N { if m[r][c].abs() > m[p][c].abs() { p = r; } }
        if m[p][c].abs() < 1e-9 { return None; }
        m.swap(c, p);
        b.swap(c, p);
        for r in c + 1..N {
            let f = m[r][c] / m[c][c];
            for k in c..N { m[r][k] -= f * m[c][k]; }
            b[r] -= f * b[c];
        }
    }
    let mut x = [0f64; N];
    for r in (0..N).rev() {
        let mut s = b[r];
        for k in r + 1..N { s -= m[r][k] * x[k]; }
        x[r] = s / m[r][r];
    }
    Some(x)
}

/// Estimates inter-sample peaks: the signal is interpolated at quarter-sample steps around the sample `TP_LAG` frames back.
struct TruePeak {
    coefs: [[f64; TP_TAPS]; 3],
    hist: [[f64; TP_TAPS]; MAX_CH],
    last: [f64; MAX_CH],
}

impl TruePeak {
    fn new() -> TruePeak {
        let coefs = std::array::from_fn(|k| std::array::from_fn(|i| {
            let x = TP_CENTRE as f64 + (k + 1) as f64 / 4.0 - i as f64;
            let sinc = if x.abs() < 1e-12 { 1.0 } else { (PI * x).sin() / (PI * x) };
            sinc * (0.5 + 0.5 * (PI * x / 9.0).cos())
        }));
        TruePeak { coefs, hist: [[0.0; TP_TAPS]; MAX_CH], last: [0.0; MAX_CH] }
    }

    fn clear(&mut self) {
        self.hist = [[0.0; TP_TAPS]; MAX_CH];
        self.last = [0.0; MAX_CH];
    }

    /// Pushes one sample of channel `c`; returns the highest level of the reconstructed signal on both sides of the centre sample.
    fn push(&mut self, c: usize, x: f64) -> f64 {
        let h = &mut self.hist[c];
        h.copy_within(1.., 0);
        h[TP_TAPS - 1] = x;
        let mut m = h[TP_CENTRE].abs();
        for k in 0..3 {
            let y: f64 = (0..TP_TAPS).map(|i| self.coefs[k][i] * h[i]).sum();
            m = m.max(y.abs());
        }
        let r = m.max(self.last[c]);
        self.last[c] = m;
        r
    }
}

/// Look-ahead peak limiter: the window minimum of the wanted gain, smoothed over the same window, so the gain is already
/// down when the delayed peak comes out. Fast attack, then a hold and a slow release: a gain that moves within a bass
/// cycle would modulate the bass and add distortion.
struct Limiter {
    limit: f64,
    release: f64,
    hold: usize,
    hold_left: usize,
    tp: Option<Box<TruePeak>>,
    delay: Vec<f64>,
    dlen: usize,
    dpos: usize,
    want: [f64; LOOK],
    mins: [f64; LOOK],
    mins_sum: f64,
    pos: usize,
    gain: f64,
    gr_min: f64,
}

impl Limiter {
    fn new(rate: f64, limit: f64, hold_ms: f64, release_ms: f64, true_peak: bool) -> Limiter {
        let dlen = LOOK + if true_peak { TP_LAG } else { 0 };
        Limiter {
            limit,
            release: 1.0 - (-1.0 / (release_ms * 0.001 * rate)).exp(),
            hold: (hold_ms * 0.001 * rate) as usize,
            hold_left: 0,
            tp: if true_peak { Some(Box::new(TruePeak::new())) } else { None },
            delay: vec![0.0; dlen * MAX_CH],
            dlen,
            dpos: 0,
            want: [1.0; LOOK],
            mins: [1.0; LOOK],
            mins_sum: LOOK as f64,
            pos: 0,
            gain: 1.0,
            gr_min: 1.0,
        }
    }

    /// Frames between a frame going in and coming out.
    fn latency(&self) -> usize { self.dlen - 1 }

    fn clear(&mut self) {
        self.delay.iter_mut().for_each(|v| *v = 0.0);
        if let Some(tp) = self.tp.as_mut() { tp.clear(); }
        self.want = [1.0; LOOK];
        self.mins = [1.0; LOOK];
        self.mins_sum = LOOK as f64;
        self.pos = 0;
        self.dpos = 0;
        self.gain = 1.0;
        self.hold_left = 0;
    }

    /// Replaces the first `ch` values of `frame` with the frame from `latency()` frames ago, limited.
    fn process(&mut self, frame: &mut [f32; MAX_CH], ch: usize) {
        let mut f64_frame = [0f64; MAX_CH];
        for c in 0..ch { f64_frame[c] = frame[c] as f64; }
        self.process_f64(&mut f64_frame, ch);
        for c in 0..ch { frame[c] = f64_frame[c] as f32; }
    }

    /// Full double-precision look-ahead peak limiting (zero f32 quantization for 24/32-bit PCM).
    fn process_f64(&mut self, frame: &mut [f64; MAX_CH], ch: usize) {
        let peak = match self.tp.as_mut() {
            Some(tp) => (0..ch).fold(0f64, |m, c| m.max(tp.push(c, frame[c]))),
            None => frame[..ch].iter().fold(0f64, |m, v| m.max(v.abs())),
        };
        let pos = self.pos;
        self.want[pos] = if peak > self.limit { self.limit / peak } else { 1.0 };
        let mn = self.want.iter().fold(1.0f64, |m, v| m.min(*v));
        self.mins_sum += mn - self.mins[pos];
        self.mins[pos] = mn;
        self.pos = (pos + 1) % LOOK;
        let g1 = self.mins_sum / LOOK as f64;
        if g1 <= self.gain {
            self.gain = g1;
            self.hold_left = self.hold;
        } else if self.hold_left > 0 {
            self.hold_left -= 1;
        } else {
            self.gain += (g1 - self.gain) * self.release;
        }
        self.gr_min = self.gr_min.min(self.gain);
        let g = self.gain;
        let rd = (self.dpos + 1) % self.dlen;
        for c in 0..ch {
            let o = self.delay[rd * MAX_CH + c];
            self.delay[self.dpos * MAX_CH + c] = frame[c];
            frame[c] = o * g;
        }
        self.dpos = rd;
    }

    /// Deepest reduction in dB since the last call (0 or below).
    fn take_gr_db(&mut self) -> f32 {
        let v = 20.0 * self.gr_min.max(1e-6).log10();
        self.gr_min = 1.0;
        v as f32
    }

    /// Transparent soft-clipping brickwall safeguard above `self.limit`, asymptotic to 0.999 (< 0 dBFS).
    #[inline]
    fn soft_clamp_f64(&self, frame: &mut [f64; MAX_CH], ch: usize) {
        let thr = self.limit;
        let span = 0.999 - thr;
        if span <= 0.0 { return; }
        for c in 0..ch {
            let x = frame[c];
            let ax = x.abs();
            if ax > thr {
                let y = thr + span * ((ax - thr) / span).tanh();
                frame[c] = y.copysign(x);
            }
        }
    }
}

/// The equalizer filters and their per-channel state. Cloned to crossfade from the old settings to the new.
#[derive(Clone)]
struct Chain {
    coefs: [Coef; N],
    active: [bool; N],
    state: [[State; N]; MAX_CH],
    bass_on: bool,
    bass: [Coef; BASS_F],
    bass_state: [[State; BASS_F]; MAX_CH],
    corr: [Coef; MAX_CORR],
    corr_n: usize,
    corr_state: [[State; MAX_CORR]; MAX_CH],
    /// Share of the bass stage mixed in (dynamic bass); 1 = all of it.
    bass_mix: f64,
    /// 31-band parametric EQ stage (fixed-size arrays, zero allocations).
    peq_on: bool,
    peq_preamp_lin: f64,
    peq_coefs: [Coef; MAX_PEQ_BANDS],
    peq_ch: [u8; MAX_PEQ_BANDS],
    peq_n: usize,
    peq_state: [[State; MAX_PEQ_BANDS]; MAX_CH],
}

impl Chain {
    fn new() -> Chain {
        Chain {
            coefs: [Coef::default(); N],
            active: [false; N],
            state: [[State::default(); N]; MAX_CH],
            bass_on: false,
            bass: [Coef::default(); BASS_F],
            bass_state: [[State::default(); BASS_F]; MAX_CH],
            corr: [Coef::default(); MAX_CORR],
            corr_n: 0,
            corr_state: [[State::default(); MAX_CORR]; MAX_CH],
            bass_mix: 1.0,
            peq_on: false,
            peq_preamp_lin: 1.0,
            peq_coefs: [Coef::default(); MAX_PEQ_BANDS],
            peq_ch: [PEQ_CH_STEREO; MAX_PEQ_BANDS],
            peq_n: 0,
            peq_state: [[State::default(); MAX_PEQ_BANDS]; MAX_CH],
        }
    }

    fn run(&mut self, c: usize, mut x: f64) -> f64 {
        for b in 0..self.corr_n { x = biquad(&self.corr[b], &mut self.corr_state[c][b], x); }
        for b in 0..N {
            if self.active[b] { x = biquad(&self.coefs[b], &mut self.state[c][b], x); }
        }
        if self.bass_on {
            let dry = x;
            for b in 0..BASS_F { x = biquad(&self.bass[b], &mut self.bass_state[c][b], x); }
            if self.bass_mix < 1.0 { x = dry + (x - dry) * self.bass_mix; }
        }
        if self.peq_on {
            x *= self.peq_preamp_lin;
            for b in 0..self.peq_n {
                let mask = self.peq_ch[b];
                if mask == PEQ_CH_STEREO || (mask == PEQ_CH_LEFT && c == 0) || (mask == PEQ_CH_RIGHT && c == 1) {
                    x = biquad(&self.peq_coefs[b], &mut self.peq_state[c][b], x);
                }
            }
        }
        x
    }

    fn clear(&mut self) {
        self.state = [[State::default(); N]; MAX_CH];
        self.bass_state = [[State::default(); BASS_F]; MAX_CH];
        self.corr_state = [[State::default(); MAX_CORR]; MAX_CH];
        self.peq_state = [[State::default(); MAX_PEQ_BANDS]; MAX_CH];
    }
}

pub struct Eq {
    rate: f64,
    channels: usize,
    chain: Chain,
    old: Chain,
    fade: usize,
    fade_len: usize,
    bands_on: bool,
    gains_db: [f32; N],
    bass_db: f64,
    corr_spec: [(u8, f64, f64, f64); MAX_CORR],
    corr_spec_n: usize,
    peak_db: f64,
    exact: bool,
    opts: u32,
    dyn_bass: DynBass,
    de_esser: DeEsser,
    crossfeed: Crossfeed,
    /// Stereo width above the split (1 = as recorded); `cur_width` glides to it.
    width: f64,
    cur_width: f64,
    // Linkwitz-Riley 4th order split: two cascaded 2nd-order low-pass and two high-pass per channel.
    xo_lp: Coef,
    xo_hp: Coef,
    xo_state: [[State; 4]; MAX_CH],
    gain_low: f64,
    gain_high: f64,
    cur_low: f64,
    cur_high: f64,
    trim_k: f64,
    lim_low: Limiter,
    lim_high: Limiter,
    lim_out: Limiter,
    /// Transparent parametric EQ output limiter (-0.5 dBFS ceiling, 1 ms look-ahead attack, 50 ms release).
    lim_peq: Limiter,
    /// 31-band parametric EQ configuration.
    peq_enabled: bool,
    peq_preamp_db: f64,
    peq_auto_preamp: bool,
    peq_eff_preamp_db: f64,
    peq_limiter_enabled: bool,
    peq_bands: [PeqBand; MAX_PEQ_BANDS],
    peq_band_count: usize,
    /// Whether the processed path is running, and whether it should; `m` fades the output across a switch.
    on: bool,
    want_on: bool,
    m: f64,
    switch_step: f64,
    /// A/B: `dry` plays the input, delayed and level matched; `ab` glides from 0 (processed) to 1 (dry).
    dry: bool,
    ab: f64,
    ab_step: f64,
    dry_delay: Vec<f64>,
    dry_len: usize,
    dry_pos: usize,
    /// Lock-free xorshift64* PRNG state for 1-LSB TPDF dithering when packing integer PCM.
    dither_state: u64,
    // Loudness of input and output (approximate K-weighting), for the A/B level match.
    kw: [Coef; 2],
    kw_in: [[State; 2]; MAX_CH],
    kw_out: [[State; 2]; MAX_CH],
    p_in: f64,
    p_out: f64,
    p_k: f64,
}

impl Eq {
    pub fn new(rate: u32, channels: u32) -> Eq {
        let rate = rate.max(8000) as f64;
        let lim_low = Limiter::new(rate, CEILING_LOW, 35.0, 200.0, false);
        let lim_high = Limiter::new(rate, CEILING, 5.0, 120.0, false);
        let lim_out = Limiter::new(rate, CEILING, 35.0, 200.0, true);
        let lim_peq = Limiter::new(rate, PEQ_LIM_CEILING, 2.0, 50.0, false);
        let dry_len = lim_low.latency() + lim_out.latency() + 1;
        let frames = |ms: f64| (ms * 0.001 * rate).max(1.0);
        Eq {
            rate,
            channels: (channels as usize).clamp(1, MAX_CH),
            chain: Chain::new(),
            old: Chain::new(),
            fade: 0,
            fade_len: frames(FADE_MS) as usize,
            bands_on: false,
            gains_db: [0.0; N],
            bass_db: 0.0,
            corr_spec: [(0, 0.0, 0.0, 0.0); MAX_CORR],
            corr_spec_n: 0,
            peak_db: 0.0,
            exact: false,
            opts: 0,
            dyn_bass: DynBass::new(rate),
            de_esser: DeEsser::new(rate),
            crossfeed: Crossfeed::new(rate),
            width: 1.0,
            cur_width: 1.0,
            xo_lp: low_pass(rate, CROSSOVER_HZ),
            xo_hp: high_pass(rate, CROSSOVER_HZ),
            xo_state: [[State::default(); 4]; MAX_CH],
            gain_low: 1.0,
            gain_high: 1.0,
            cur_low: 1.0,
            cur_high: 1.0,
            trim_k: 1.0 - (-1.0 / frames(TRIM_MS)).exp(),
            lim_low,
            lim_high,
            lim_out,
            lim_peq,
            peq_enabled: false,
            peq_preamp_db: 0.0,
            peq_auto_preamp: false,
            peq_eff_preamp_db: 0.0,
            peq_limiter_enabled: true,
            peq_bands: [PeqBand::default(); MAX_PEQ_BANDS],
            peq_band_count: 0,
            on: false,
            want_on: false,
            m: 1.0,
            switch_step: 1.0 / frames(SWITCH_MS),
            dry: false,
            ab: 0.0,
            ab_step: 1.0 / frames(FADE_MS),
            dry_delay: vec![0.0; dry_len * MAX_CH],
            dry_len,
            dry_pos: 0,
            dither_state: 0x9E37_79B9_7F4A_7C15u64,
            kw: [high_shelf(rate, 1500.0, 4.0), high_pass(rate, 40.0)],
            kw_in: [[State::default(); 2]; MAX_CH],
            kw_out: [[State::default(); 2]; MAX_CH],
            p_in: 0.0,
            p_out: 0.0,
            p_k: 1.0 - (-1.0 / (MATCH_S * rate)).exp(),
        }
    }

    /// Combined response of only the legacy 10-band, Bass, and earphone correction filters at `hz`, in dB.
    /// Excludes the 31-band parametric EQ, which manages its own manual/auto preamp and headroom.
    fn legacy_response_db(&self, hz: f64) -> f64 {
        let ch = &self.chain;
        let bands: f64 = (0..N).filter(|&i| ch.active[i]).map(|i| mag_db(&ch.coefs[i], self.rate, hz)).sum();
        let bass: f64 = if ch.bass_on { ch.bass.iter().map(|k| mag_db(k, self.rate, hz)).sum() } else { 0.0 };
        let corr: f64 = ch.corr[..ch.corr_n].iter().map(|k| mag_db(k, self.rate, hz)).sum();
        bands + bass + corr
    }

    /// Combined response of the current filters at `hz`, in dB (without the level trims).
    pub fn response_db(&self, hz: f64) -> f64 {
        let ch = &self.chain;
        let peq: f64 = if ch.peq_on {
            self.peq_eff_preamp_db + ch.peq_coefs[..ch.peq_n].iter().map(|k| mag_db(k, self.rate, hz)).sum::<f64>()
        } else {
            0.0
        };
        self.legacy_response_db(hz) + peq
    }

    /// Configures the 31-band parametric EQ stage. Parameter updates crossfade smoothly over `FADE_MS`
    /// with zero heap allocations.
    pub fn set_parametric(
        &mut self,
        enabled: bool,
        preamp_db: f32,
        auto_preamp: bool,
        limiter_enabled: bool,
        bands: &[PeqBand],
    ) {
        self.begin_fade();
        self.peq_enabled = enabled;
        self.peq_preamp_db = if preamp_db.is_finite() { (preamp_db as f64).clamp(-24.0, 24.0) } else { 0.0 };
        self.peq_auto_preamp = auto_preamp;
        self.peq_limiter_enabled = limiter_enabled;
        let count = bands.len().min(MAX_PEQ_BANDS);
        self.peq_band_count = count;
        for i in 0..count {
            self.peq_bands[i] = bands[i];
        }
        for i in count..MAX_PEQ_BANDS {
            self.peq_bands[i] = PeqBand::default();
        }
        let mut active_n = 0usize;
        if enabled {
            for b in self.peq_bands[..count].iter() {
                if let Some(c) = peq_coef(self.rate, b) {
                    self.chain.peq_coefs[active_n] = c;
                    self.chain.peq_ch[active_n] = b.channel;
                    active_n += 1;
                }
            }
        }
        if active_n != self.chain.peq_n {
            self.chain.peq_state = [[State::default(); MAX_PEQ_BANDS]; MAX_CH];
        }
        self.chain.peq_n = active_n;
        let auto = if enabled && auto_preamp && active_n > 0 {
            compute_auto_preamp_db(self.rate, &self.peq_bands[..count])
        } else {
            0.0
        };
        let eff_preamp = if enabled { self.peq_preamp_db + auto } else { 0.0 };
        self.peq_eff_preamp_db = eff_preamp;
        let peq_active = enabled && (active_n > 0 || eff_preamp.abs() >= 0.01);
        self.chain.peq_on = peq_active;
        self.chain.peq_preamp_lin = if peq_active { 10f64.powf(eff_preamp / 20.0) } else { 1.0 };
        self.refresh();
    }

    /// Evaluates the current parametric EQ's exact magnitude response at `freqs_hz` into `out_db`.
    pub fn eval_eq_response(&self, freqs_hz: &[f32], out_db: &mut [f32]) -> f32 {
        if !self.peq_enabled {
            for v in out_db.iter_mut().take(freqs_hz.len()) { *v = 0.0; }
            return 0.0;
        }
        eval_parametric_response(
            self.rate,
            self.peq_preamp_db,
            self.peq_auto_preamp,
            &self.peq_bands[..self.peq_band_count],
            freqs_hz,
            out_db,
            PEQ_CH_STEREO,
        )
    }

    /// Returns whether the engine should bypass the legacy 180 Hz split/trim stage and run the
    /// direct high-precision parametric EQ + optional -0.5 dBFS limiter path.
    #[inline]
    fn pure_peq_mode(&self) -> bool {
        self.chain.peq_on
            && !self.bands_on
            && !self.chain.bass_on
            && self.chain.corr_n == 0
            && !(self.channels == 2 && (self.width - 1.0).abs() > 0.005)
    }

    /// Keeps the current filters running as the old set, faded out over `FADE_MS` while the new set fades in.
    fn begin_fade(&mut self) {
        if self.on {
            self.old = self.chain.clone();
            self.fade = self.fade_len;
        }
    }

    fn build_bass_coefs(rate: f64, bass_db: f64) -> [Coef; BASS_F] {
        [
            low_shelf(rate, 100.0, bass_db),
            peaking(rate, 45.0, 0.4 * bass_db),
            high_pass(rate, 22.0),
            peaking_q(rate, 125.0, -0.35 * bass_db, 1.2),
        ]
    }

    fn build_corr_coefs(rate: f64, filters: &[(u8, f64, f64, f64)]) -> ([Coef; MAX_CORR], usize) {
        let mut coefs = [Coef::default(); MAX_CORR];
        let mut n = 0usize;
        for &(kind, hz, q, db) in filters.iter().take(MAX_CORR) {
            if !(hz.is_finite() && q.is_finite() && db.is_finite()) { continue; }
            if !(hz > 10.0 && hz < rate * 0.45 && q > 0.05 && q < 20.0 && db.abs() <= 24.0) { continue; }
            coefs[n] = match kind {
                CORR_LSC => low_shelf_q(rate, hz, db, q),
                CORR_HSC => high_shelf_q(rate, hz, db, q),
                _ => peaking_q(rate, hz, db, q),
            };
            n += 1;
        }
        (coefs, n)
    }

    fn fit_band_coefs(rate: f64, target_db: &[f32; N]) -> ([Coef; N], [bool; N]) {
        let t: [f64; N] = std::array::from_fn(|i| target_db[i] as f64);
        let usable: [bool; N] = std::array::from_fn(|i| BANDS_HZ[i] < rate * 0.45);
        let mut m = [[0f64; N]; N];
        for j in 0..N {
            let k = peaking(rate, BANDS_HZ[j], 6.0);
            for i in 0..N { m[i][j] = if usable[j] { mag_db(&k, rate, BANDS_HZ[i]) / 6.0 } else { 0.0 }; }
            if !usable[j] { m[j][j] = 1.0; }
        }
        let mut g = solve(m, t).unwrap_or(t);
        for v in g.iter_mut() { *v = v.clamp(-MAX_FIT_DB, MAX_FIT_DB); }
        for _ in 0..2 {
            let coefs: [Coef; N] = std::array::from_fn(|i| peaking(rate, BANDS_HZ[i], g[i]));
            let err: [f64; N] = std::array::from_fn(|i| {
                let got: f64 = (0..N).filter(|&j| usable[j] && g[j].abs() >= 0.05).map(|j| mag_db(&coefs[j], rate, BANDS_HZ[i])).sum();
                t[i] - got
            });
            if let Some(d) = solve(m, err) {
                for i in 0..N { g[i] = (g[i] + d[i]).clamp(-MAX_FIT_DB, MAX_FIT_DB); }
            }
        }
        let mut out_coefs = [Coef::default(); N];
        let mut active = [false; N];
        for i in 0..N {
            let on = usable[i] && g[i].abs() >= 0.05;
            if on { out_coefs[i] = peaking(rate, BANDS_HZ[i], g[i]); }
            active[i] = on;
        }
        (out_coefs, active)
    }

    /// Extra low-end boost on top of the bands, 0 to 12 dB: a shelf below 100 Hz plus a lift around 45 Hz, with a high-pass
    /// at 22 Hz so the inaudible range below it is not boosted, and a wide cut around 125 Hz so the rumble and kick
    /// (25 to 60 Hz) keep their full lift while the boom above them, which muddies the mids, is pulled back.
    pub fn set_bass(&mut self, db: f32) {
        self.begin_fade();
        self.bass_db = if db.is_finite() { (db as f64).clamp(0.0, 12.0) } else { 0.0 };
        let on = self.bass_db >= 0.05;
        if on {
            self.chain.bass = Self::build_bass_coefs(self.rate, self.bass_db);
        } else {
            self.chain.bass_state = [[State::default(); BASS_F]; MAX_CH];
        }
        self.chain.bass_on = on;
        self.refresh();
    }

    /// Earphone correction, applied before the curve: `(kind, hz, q, gain dB)` per filter (kinds `CORR_*`), up to
    /// `MAX_CORR`; empty turns it off. Filters above 0.45 x the rate or with a bad Q are skipped. Its boosts count in the headroom.
    pub fn set_correction(&mut self, filters: &[(u8, f64, f64, f64)]) {
        self.begin_fade();
        let spec_n = filters.len().min(MAX_CORR);
        self.corr_spec_n = spec_n;
        for i in 0..spec_n {
            self.corr_spec[i] = filters[i];
        }
        let (coefs, n) = Self::build_corr_coefs(self.rate, &self.corr_spec[..spec_n]);
        self.chain.corr = coefs;
        if n != self.chain.corr_n { self.chain.corr_state = [[State::default(); MAX_CORR]; MAX_CH]; }
        self.chain.corr_n = n;
        self.refresh();
    }

    /// Exact: everything is lowered by the curve's peak, so the heard balance is the drawn curve. Loud: the default trims.
    pub fn set_exact(&mut self, exact: bool) {
        self.exact = exact;
        self.refresh();
    }

    /// Stereo width of everything above 180 Hz, 0.5 to 1.6 (1 = as recorded). Wider spreads instruments apart; the bass
    /// below the split stays as it is, so it keeps its weight and centre. Stereo only.
    pub fn set_width(&mut self, w: f32) {
        self.width = if w.is_finite() { (w as f64).clamp(0.5, 1.6) } else { 1.0 };
        self.refresh();
    }

    /// Plays the input (delayed to the processed timing, at the processed loudness) instead of the processed sound.
    pub fn set_dry(&mut self, dry: bool) { self.dry = dry; }

    /// `OPT_*` bits: dynamic bass (eases the Bass lift on already loud bass), de-esser, crossfeed (stereo).
    pub fn set_options(&mut self, bits: u32) {
        self.opts = bits;
        if bits & OPT_DYNAMIC_BASS == 0 { self.chain.bass_mix = 1.0; self.old.bass_mix = 1.0; }
        self.refresh();
    }

    fn refresh(&mut self) {
        self.want_on = self.bands_on || self.chain.bass_on || self.chain.corr_n > 0 || self.chain.peq_on
            || self.opts & OPT_DE_ESSER != 0
            || (self.channels == 2 && self.opts & OPT_CROSSFEED != 0)
            || (self.channels == 2 && (self.width - 1.0).abs() > 0.005);
        if !self.want_on { return; }
        if self.pure_peq_mode() {
            // Parametric EQ manages its own manual/auto preamp and optional -0.5 dBFS output limiter.
            self.gain_low = 1.0;
            self.gain_high = 1.0;
            self.peak_db = -compute_auto_preamp_db(self.rate, &self.peq_bands[..self.peq_band_count]);
            return;
        }
        // Peak of the response below and above the split, separately: each half is trimmed only for its own boost.
        let top = self.rate * 0.45;
        let (mut pl, mut ph) = (0f64, 0f64);
        let mut f = 20.0;
        while f < top.min(20000.0) {
            let r = self.legacy_response_db(f);
            // The band around the split belongs to the low half's limiter; only boosts well above it trim the highs.
            if f < HIGH_FROM_HZ { pl = pl.max(r); } else { ph = ph.max(r); }
            f *= 1.04;
        }
        self.peak_db = pl.max(ph);
        if self.exact {
            let g = 10f64.powf(-self.peak_db.max(0.0) / 20.0);
            self.gain_low = g;
            self.gain_high = g;
        } else {
            let over = (pl - BASS_NET_DB).max(0.0);
            self.gain_low = 10f64.powf(-over / 20.0);
            self.gain_high = 10f64.powf(-((SEPARATION * over).min(MAX_REST_CUT_DB) + HIGH_SHARE * ph) / 20.0);
        }
    }

    /// One target gain in dB per band; any other length (or all near zero) turns the equalizer off.
    pub fn set_gains(&mut self, target_db: &[f32]) {
        if target_db.len() != N {
            self.begin_fade();
            self.gains_db = [0.0; N];
            self.bands_on = false;
            self.chain.active = [false; N];
            self.chain.state = [[State::default(); N]; MAX_CH];
            self.refresh();
            return;
        }
        let sanitized: [f32; N] = std::array::from_fn(|i| if target_db[i].is_finite() { target_db[i] } else { 0.0 });
        if sanitized.iter().all(|g| g.abs() < 0.05) {
            self.begin_fade();
            self.gains_db = [0.0; N];
            self.bands_on = false;
            self.chain.active = [false; N];
            self.chain.state = [[State::default(); N]; MAX_CH];
            self.refresh();
            return;
        }
        self.gains_db = sanitized;
        let (coefs, active) = Self::fit_band_coefs(self.rate, &sanitized);
        self.begin_fade();
        for i in 0..N {
            if active[i] {
                self.chain.coefs[i] = coefs[i];
            } else {
                // A band switched off forgets its state, so it starts clean when it comes back.
                for c in 0..MAX_CH { self.chain.state[c][i] = State::default(); }
            }
            self.chain.active[i] = active[i];
        }
        self.bands_on = true;
        self.refresh();
    }

    /// Level that makes the dry input as loud as the processed output.
    fn match_gain(&self) -> f64 {
        if self.p_in < 1e-12 || self.p_out < 1e-12 { return 1.0; }
        (self.p_out / self.p_in).sqrt().clamp(0.0316, 4.0)
    }

    /// Starts the processed path from silence (called at the bottom of the switch-on fade, so nothing is heard).
    fn start(&mut self) {
        self.reset();
        self.cur_low = self.gain_low;
        self.cur_high = self.gain_high;
        self.cur_width = self.width;
    }

    /// Dynamically reconfigures sample rate and channel count on track/USB rate switches
    /// (e.g. 44.1 kHz -> 192 kHz / 384 kHz): recomputes all parametric biquad coefficients with
    /// Nyquist clamping (`0.49 * rate`), refreshes rate-dependent time constants, and cleanly resets
    /// filter delay states so no transient burst or DC thump occurs across the rate boundary.
    pub fn reconfigure(&mut self, rate: u32, channels: u32) {
        let new_rate = rate.max(8000) as f64;
        let new_ch = (channels as usize).clamp(1, MAX_CH);
        let frames = |ms: f64| (ms * 0.001 * new_rate).max(1.0);
        self.rate = new_rate;
        self.channels = new_ch;
        self.fade = 0;
        self.fade_len = frames(FADE_MS) as usize;
        self.trim_k = 1.0 - (-1.0 / frames(TRIM_MS)).exp();
        self.switch_step = 1.0 / frames(SWITCH_MS);
        self.ab_step = 1.0 / frames(FADE_MS);
        self.p_k = 1.0 - (-1.0 / (MATCH_S * new_rate)).exp();
        self.xo_lp = low_pass(new_rate, CROSSOVER_HZ);
        self.xo_hp = high_pass(new_rate, CROSSOVER_HZ);
        self.kw = [high_shelf(new_rate, 1500.0, 4.0), high_pass(new_rate, 40.0)];
        self.dyn_bass = DynBass::new(new_rate);
        self.de_esser = DeEsser::new(new_rate);
        self.crossfeed = Crossfeed::new(new_rate);
        self.lim_low = Limiter::new(new_rate, CEILING_LOW, 35.0, 200.0, false);
        self.lim_high = Limiter::new(new_rate, CEILING, 5.0, 120.0, false);
        self.lim_out = Limiter::new(new_rate, CEILING, 35.0, 200.0, true);
        self.lim_peq = Limiter::new(new_rate, PEQ_LIM_CEILING, 2.0, 50.0, false);
        let dry_len = self.lim_low.latency() + self.lim_out.latency() + 1;
        self.dry_delay.resize(dry_len * MAX_CH, 0.0);
        self.dry_len = dry_len;
        self.dry_pos = 0;

        // Recompute legacy 10-band, bass, and earphone correction filters for the new sample rate.
        if self.bands_on {
            let (coefs, active) = Self::fit_band_coefs(self.rate, &self.gains_db);
            self.chain.coefs = coefs;
            self.chain.active = active;
        }
        if self.chain.bass_on {
            self.chain.bass = Self::build_bass_coefs(self.rate, self.bass_db);
        }
        if self.corr_spec_n > 0 {
            let (coefs, n) = Self::build_corr_coefs(self.rate, &self.corr_spec[..self.corr_spec_n]);
            self.chain.corr = coefs;
            self.chain.corr_n = n;
        }

        // Recompute parametric EQ coefficients & auto-preamp for the new sample rate.
        let count = self.peq_band_count;
        let mut active_n = 0usize;
        if self.peq_enabled {
            for b in self.peq_bands[..count].iter() {
                if let Some(c) = peq_coef(self.rate, b) {
                    self.chain.peq_coefs[active_n] = c;
                    self.chain.peq_ch[active_n] = b.channel;
                    active_n += 1;
                }
            }
        }
        self.chain.peq_n = active_n;
        let auto = if self.peq_enabled && self.peq_auto_preamp && active_n > 0 {
            compute_auto_preamp_db(self.rate, &self.peq_bands[..count])
        } else {
            0.0
        };
        let eff_preamp = if self.peq_enabled { self.peq_preamp_db + auto } else { 0.0 };
        self.peq_eff_preamp_db = eff_preamp;
        let peq_active = self.peq_enabled && (active_n > 0 || eff_preamp.abs() >= 0.01);
        self.chain.peq_on = peq_active;
        self.chain.peq_preamp_lin = if peq_active { 10f64.powf(eff_preamp / 20.0) } else { 1.0 };

        self.refresh();
        self.reset();
        self.on = self.want_on;
        self.m = 1.0;
        self.cur_low = self.gain_low;
        self.cur_high = self.gain_high;
        self.cur_width = self.width;
    }

    /// Returns true when the engine is actively shaping audio (not in bit-exact bypass).
    #[inline]
    pub fn is_active(&self) -> bool {
        self.on || self.want_on || self.m < 1.0
    }

    /// Generates 1-LSB peak-to-peak TPDF dither in `(-1.0, 1.0)` via lock-free `xorshift64*`.
    #[inline]
    fn tpdf_lsb(&mut self) -> f64 {
        let mut x = self.dither_state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.dither_state = x;
        let r = x.wrapping_mul(0x2545_F491_4F6C_DD1Du64);
        let u1 = (r & 0xFFFF_FFFF) as f64 * (1.0 / 4294967296.0);
        let u2 = (r >> 32) as f64 * (1.0 / 4294967296.0);
        u1 - u2
    }

    /// Processes a single interleaved frame in full `f64` precision (zero heap allocations).
    #[inline]
    fn process_frame_f64(&mut self, frame: &mut [f64; MAX_CH]) {
        let ch = self.channels;
        if self.on != self.want_on {
            self.m -= self.switch_step;
            if self.m <= 0.0 {
                self.m = 0.0;
                self.on = self.want_on;
                if self.on { self.start(); }
            }
        } else if self.m < 1.0 {
            self.m = (self.m + self.switch_step).min(1.0);
        }
        if !self.on {
            if self.m < 1.0 {
                for c in 0..ch { frame[c] *= self.m; }
            }
            return;
        }

        self.cur_low += (self.gain_low - self.cur_low) * self.trim_k;
        self.cur_high += (self.gain_high - self.cur_high) * self.trim_k;
        let a = if self.fade > 0 { self.fade -= 1; 1.0 - self.fade as f64 / self.fade_len as f64 } else { 1.0 };
        let mut p_in = 0.0;
        if self.opts & OPT_DYNAMIC_BASS != 0 && self.chain.bass_on {
            let mono = frame[..ch].iter().sum::<f64>() / ch as f64;
            let mix = self.dyn_bass.mix(mono, self.bass_db);
            self.chain.bass_mix = mix;
            self.old.bass_mix = mix;
        }
        let mut vs = [0f64; MAX_CH];
        for c in 0..ch {
            let x = frame[c];
            let w = kweight(&self.kw, &mut self.kw_in[c], x);
            p_in += w * w;
            let mut v = self.chain.run(c, x);
            if a < 1.0 { v = a * v + (1.0 - a) * self.old.run(c, x); }
            vs[c] = v;
        }
        if self.opts & OPT_DE_ESSER != 0 { self.de_esser.process(&mut vs, ch); }
        if ch == 2 && self.opts & OPT_CROSSFEED != 0 { self.crossfeed.process(&mut vs); }

        let mut sum = [0f64; MAX_CH];
        let dry_lat = if self.pure_peq_mode() {
            for c in 0..ch { sum[c] = vs[c]; }
            if self.peq_limiter_enabled {
                self.lim_peq.process_f64(&mut sum, ch);
                self.lim_peq.soft_clamp_f64(&mut sum, ch);
                self.lim_peq.latency()
            } else {
                0
            }
        } else {
            let mut low = [0f32; MAX_CH];
            let mut high = [0f32; MAX_CH];
            for c in 0..ch {
                let v = vs[c];
                let l1 = biquad(&self.xo_lp, &mut self.xo_state[c][0], v);
                let lo = biquad(&self.xo_lp, &mut self.xo_state[c][1], l1);
                let h1 = biquad(&self.xo_hp, &mut self.xo_state[c][2], v);
                let hi = biquad(&self.xo_hp, &mut self.xo_state[c][3], h1);
                low[c] = (lo * self.cur_low) as f32;
                high[c] = (hi * self.cur_high) as f32;
            }
            if ch == 2 {
                self.cur_width += (self.width - self.cur_width) * self.trim_k;
                if (self.cur_width - 1.0).abs() > 1e-6 {
                    let (l, r) = (high[0] as f64, high[1] as f64);
                    let (mid, side) = ((l + r) * 0.5, (l - r) * 0.5 * self.cur_width);
                    high[0] = (mid + side) as f32;
                    high[1] = (mid - side) as f32;
                }
            }
            self.lim_low.process(&mut low, ch);
            self.lim_high.process(&mut high, ch);
            let mut sum32 = [0f32; MAX_CH];
            for c in 0..ch { sum32[c] = low[c] + high[c]; }
            self.lim_out.process(&mut sum32, ch);
            for c in 0..ch { sum[c] = sum32[c] as f64; }
            self.dry_len - 1
        };

        let mut p_out = 0.0;
        for c in 0..ch {
            let w = kweight(&self.kw, &mut self.kw_out[c], sum[c]);
            p_out += w * w;
        }
        self.p_in += (p_in - self.p_in) * self.p_k;
        self.p_out += (p_out - self.p_out) * self.p_k;

        let next_pos = (self.dry_pos + 1) % self.dry_len;
        let rd = if dry_lat == 0 { self.dry_pos } else { (self.dry_pos + self.dry_len - dry_lat.min(self.dry_len - 1)) % self.dry_len };
        let target = if self.dry { 1.0 } else { 0.0 };
        if self.ab < target { self.ab = (self.ab + self.ab_step).min(1.0); } else if self.ab > target { self.ab = (self.ab - self.ab_step).max(0.0); }
        let g = if self.ab > 0.0 { self.match_gain() } else { 1.0 };
        for c in 0..ch {
            let d = if dry_lat == 0 { frame[c] } else { self.dry_delay[rd * MAX_CH + c] };
            self.dry_delay[self.dry_pos * MAX_CH + c] = frame[c];
            let mut y = sum[c];
            if self.ab > 0.0 { y = y * (1.0 - self.ab) + d * g * self.ab; }
            if self.m < 1.0 { y *= self.m; }
            frame[c] = y;
        }
        self.dry_pos = next_pos;
    }

    /// In place, interleaved `f32`. While the equalizer is on the output is delayed by the limiters' look-ahead (about 1-2 ms).
    pub fn process(&mut self, buf: &mut [f32]) {
        if !self.on && !self.want_on && self.m >= 1.0 { return; }
        let ch = self.channels;
        for frame in buf.chunks_exact_mut(ch) {
            let mut f = [0f64; MAX_CH];
            for c in 0..ch { f[c] = frame[c] as f64; }
            self.process_frame_f64(&mut f);
            for c in 0..ch { frame[c] = f[c] as f32; }
        }
    }

    /// In-place multi-format PCM processing for USB Exclusive & API 34 Direct Mixer paths.
    /// `kind`: `0` = PCM_16BIT (LE i16), `1` = PCM_24BIT (3-byte packed LE signed), `2` = PCM_32BIT (LE i32), `3` = PCM_FLOAT (native f32).
    /// Applies full `f64` processing + 1-LSB TPDF dither when packing 16-bit or 24-bit integer PCM.
    /// When bypassed (`!is_active()`), leaves `buf` byte-for-byte untouched (zero copies, zero dithering).
    /// Returns the number of samples processed.
    pub fn process_pcm_in_place(&mut self, buf: &mut [u8], kind: i32) -> usize {
        let step = crate::pcm_bytes(kind);
        if step == 0 { return 0; }
        let ch = self.channels;
        let frame_bytes = step * ch;
        let n_frames = buf.len() / frame_bytes;
        if n_frames == 0 { return 0; }
        if !self.is_active() {
            return n_frames * ch;
        }
        for frame_slice in buf[..n_frames * frame_bytes].chunks_exact_mut(frame_bytes) {
            let mut f = [0f64; MAX_CH];
            match kind {
                crate::PCM_16 => {
                    for c in 0..ch {
                        let o = c * 2;
                        f[c] = i16::from_le_bytes([frame_slice[o], frame_slice[o + 1]]) as f64 * (1.0 / 32768.0);
                    }
                }
                crate::PCM_24 => {
                    for c in 0..ch {
                        let o = c * 3;
                        let s24 = i32::from_le_bytes([0, frame_slice[o], frame_slice[o + 1], frame_slice[o + 2]]) >> 8;
                        f[c] = s24 as f64 * (1.0 / 8388608.0);
                    }
                }
                crate::PCM_32 => {
                    for c in 0..ch {
                        let o = c * 4;
                        let s32 = i32::from_le_bytes([frame_slice[o], frame_slice[o + 1], frame_slice[o + 2], frame_slice[o + 3]]);
                        f[c] = s32 as f64 * (1.0 / 2147483648.0);
                    }
                }
                _ => {
                    for c in 0..ch {
                        let o = c * 4;
                        f[c] = f32::from_ne_bytes([frame_slice[o], frame_slice[o + 1], frame_slice[o + 2], frame_slice[o + 3]]) as f64;
                    }
                }
            }

            self.process_frame_f64(&mut f);

            match kind {
                crate::PCM_16 => {
                    for c in 0..ch {
                        let d = self.tpdf_lsb();
                        let s = (f[c] * 32768.0 + d).round().clamp(-32768.0, 32767.0) as i16;
                        let b = s.to_le_bytes();
                        let o = c * 2;
                        frame_slice[o] = b[0];
                        frame_slice[o + 1] = b[1];
                    }
                }
                crate::PCM_24 => {
                    for c in 0..ch {
                        let d = self.tpdf_lsb();
                        let s = (f[c] * 8388608.0 + d).round().clamp(-8388608.0, 8388607.0) as i32;
                        let b = s.to_le_bytes();
                        let o = c * 3;
                        frame_slice[o] = b[0];
                        frame_slice[o + 1] = b[1];
                        frame_slice[o + 2] = b[2];
                    }
                }
                crate::PCM_32 => {
                    for c in 0..ch {
                        let s = (f[c] * 2147483648.0).round().clamp(-2147483648.0, 2147483647.0) as i32;
                        let b = s.to_le_bytes();
                        let o = c * 4;
                        frame_slice[o] = b[0];
                        frame_slice[o + 1] = b[1];
                        frame_slice[o + 2] = b[2];
                        frame_slice[o + 3] = b[3];
                    }
                }
                _ => {
                    for c in 0..ch {
                        let b = (f[c] as f32).to_ne_bytes();
                        let o = c * 4;
                        frame_slice[o] = b[0];
                        frame_slice[o + 1] = b[1];
                        frame_slice[o + 2] = b[2];
                        frame_slice[o + 3] = b[3];
                    }
                }
            }
        }
        n_frames * ch
    }

    /// `[low-half level dB, high-half level dB, bass dB, bass-half limiter reduction dB, other limiter reduction dB,
    /// curve peak dB, A/B level match dB]`. The reductions are the deepest since the last call (0 or below).
    pub fn take_stats(&mut self) -> [f32; 7] {
        let gl = self.lim_low.take_gr_db();
        let gh = self.lim_high.take_gr_db().min(self.lim_out.take_gr_db()).min(self.lim_peq.take_gr_db());
        if !self.on { return [0.0, 0.0, 0.0, gl, gh, 0.0, 0.0]; }
        let db = |v: f64| (20.0 * v.log10()) as f32;
        if self.pure_peq_mode() {
            return [self.peq_eff_preamp_db as f32, self.peq_eff_preamp_db as f32, 0.0, 0.0, gh, self.peak_db as f32, db(self.match_gain())];
        }
        [db(self.gain_low), db(self.gain_high), self.bass_db as f32, gl, gh, self.peak_db as f32, db(self.match_gain())]
    }

    pub fn reset(&mut self) {
        self.chain.clear();
        self.old.clear();
        self.fade = 0;
        self.xo_state = [[State::default(); 4]; MAX_CH];
        self.lim_low.clear();
        self.lim_high.clear();
        self.lim_out.clear();
        self.lim_peq.clear();
        self.dry_delay.iter_mut().for_each(|v| *v = 0.0);
        self.dry_pos = 0;
        self.kw_in = [[State::default(); 2]; MAX_CH];
        self.kw_out = [[State::default(); 2]; MAX_CH];
        self.dyn_bass.clear();
        self.de_esser.clear();
        self.crossfeed.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(rate: f64, hz: f64, amp: f64, n: usize) -> Vec<f32> {
        (0..n).map(|i| (amp * (2.0 * PI * hz * i as f64 / rate).sin()) as f32).collect()
    }
    fn rms(x: &[f32]) -> f64 { (x.iter().map(|v| (*v as f64).powi(2)).sum::<f64>() / x.len() as f64).sqrt() }
    /// Amplitude of the component at `hz` (single-bin correlation).
    fn amp_at(x: &[f32], hz: f64, rate: f64) -> f64 {
        let (mut re, mut im) = (0.0, 0.0);
        for (i, v) in x.iter().enumerate() {
            let p = 2.0 * PI * hz * i as f64 / rate;
            re += *v as f64 * p.cos();
            im += *v as f64 * p.sin();
        }
        2.0 * (re * re + im * im).sqrt() / x.len() as f64
    }
    fn db(v: f64) -> f64 { 20.0 * v.log10() }
    /// True peak by 8x interpolation with a long windowed sinc (independent of the engine's estimator).
    fn true_peak(x: &[f32]) -> f64 {
        let half = 32i64;
        let mut m = 0f64;
        for n in half as usize..x.len() - half as usize {
            for k in 0..8 {
                let t = n as f64 + k as f64 / 8.0;
                let mut y = 0.0;
                for i in (n as i64 - half)..(n as i64 + half) {
                    let d = t - i as f64;
                    let s = if d.abs() < 1e-12 { 1.0 } else { (PI * d).sin() / (PI * d) };
                    y += x[i as usize] as f64 * s * (0.5 + 0.5 * (PI * d / (half as f64 + 1.0)).cos());
                }
                m = m.max(y.abs());
            }
        }
        m
    }
    /// Largest deviation from a pure sine at `hz` (second-order predictor residual): a click shows as a spike.
    fn click(x: &[f32], hz: f64, rate: f64) -> f64 {
        let c = 2.0 * (2.0 * PI * hz / rate).cos();
        x.windows(3).map(|w| (w[2] as f64 - c * w[1] as f64 + w[0] as f64).abs()).fold(0.0, f64::max)
    }

    #[test]
    fn flat_is_identity() {
        let mut e = Eq::new(48000, 2);
        e.set_gains(&[0.0; 10]);
        let mut a = sine(48000.0, 1000.0, 0.5, 4800);
        let b = a.clone();
        e.process(&mut a);
        assert_eq!(a, b);
    }

    #[test]
    fn flat_again_is_identity_after_the_fade() {
        let mut e = Eq::new(48000, 2);
        e.set_gains(&[6.0, 4.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3.0, 0.0]);
        let mut a = sine(48000.0, 440.0, 0.5, 9600);
        e.process(&mut a);
        e.set_gains(&[0.0; 10]);
        let mut fade = sine(48000.0, 440.0, 0.5, 4800);
        e.process(&mut fade);
        let mut a = sine(48000.0, 440.0, 0.5, 4800);
        let b = a.clone();
        e.process(&mut a);
        assert_eq!(a, b);
    }

    #[test]
    fn response_matches_the_drawn_curve() {
        let mut e = Eq::new(48000, 2);
        let t = [8.0f32, 7.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0];
        e.set_gains(&t);
        for i in 0..10 {
            let got = e.response_db(BANDS_HZ[i]);
            assert!((got - t[i] as f64).abs() < 0.5, "band {} wanted {} got {}", i, t[i], got);
        }
    }

    #[test]
    fn exact_mode_plays_the_drawn_curve() {
        let rate = 48000.0;
        let t = [8.0f32, 7.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0];
        for (hz, want) in [(64.0, 7.0), (1000.0, 0.0), (250.0, 3.0)] {
            let mut e = Eq::new(48000, 1);
            e.set_exact(true);
            e.set_gains(&t);
            let peak = e.peak_db;
            let mut a = sine(rate, hz, 0.05, 96000);
            e.process(&mut a);
            let got = db(amp_at(&a[48000..], hz, rate) / 0.05);
            assert!((got - (want - peak)).abs() < 0.6, "{hz} Hz: got {got} dB, wanted {}", want - peak);
        }
    }

    #[test]
    fn a_mid_boost_comes_out_boosted_by_its_trim() {
        let mut e = Eq::new(48000, 1);
        let mut g = [0.0f32; 10];
        g[5] = 6.0; // 1 kHz
        e.set_gains(&g);
        let mut a = sine(48000.0, 1000.0, 0.1, 48000);
        e.process(&mut a);
        let gain_db = 20.0 * (rms(&a[24000..]) / (0.1 / 2f64.sqrt())).log10();
        let expect = 6.0 - HIGH_SHARE * 6.0;
        assert!((gain_db - expect).abs() < 0.8, "got {gain_db} expected about {expect}");
    }

    #[test]
    fn bass_alone_lifts_the_low_end_and_leaves_the_highs() {
        let mut e = Eq::new(48000, 2);
        e.set_bass(9.0);
        assert!(e.response_db(40.0) > 7.0, "{}", e.response_db(40.0));
        assert!(e.response_db(10.0) < 0.0, "10 Hz must be cut, got {}", e.response_db(10.0));
        assert!(e.response_db(60.0) > 6.0);
        assert!(e.response_db(1000.0).abs() < 0.5);
        assert!(e.response_db(8000.0).abs() < 0.2);
        e.set_bass(0.0);
        let mut b = sine(48000.0, 1000.0, 0.5, 4800);
        let c = b.clone();
        e.process(&mut b);
        assert_eq!(b, c);
    }

    #[test]
    fn bass_keeps_the_rumble_and_clears_the_boom() {
        let mut e = Eq::new(48000, 2);
        e.set_bass(12.0);
        for hz in [30.0, 40.0, 50.0] { assert!(e.response_db(hz) > 12.0, "{hz} Hz only {}", e.response_db(hz)); }
        assert!(e.response_db(100.0) < 4.0, "100 Hz {}", e.response_db(100.0));
        for hz in [160.0, 200.0, 250.0, 315.0] { assert!(e.response_db(hz) < 0.0, "{hz} Hz {}", e.response_db(hz)); }
        assert!(e.response_db(1000.0).abs() < 0.2);
    }

    #[test]
    fn width_spreads_the_sides_and_leaves_bass_and_centre() {
        let rate = 48000.0;
        let n = 96000;
        // Left only at 1 kHz (a hard-panned instrument), both channels at 60 Hz (bass) and 2 kHz (centre vocal).
        let mut a = vec![0f32; n * 2];
        for i in 0..n {
            let t = i as f64 / rate;
            let (b, v, s) = (0.2 * (2.0 * PI * 60.0 * t).sin(), 0.1 * (2.0 * PI * 2000.0 * t).sin(), 0.1 * (2.0 * PI * 1000.0 * t).sin());
            a[2 * i] = (b + v + s) as f32;
            a[2 * i + 1] = (b + v) as f32;
        }
        let input = a.clone();
        let mut e = Eq::new(48000, 2);
        e.set_exact(true);
        e.set_width(1.5);
        e.process(&mut a);
        let ch = |x: &[f32], c: usize| x.iter().skip(c).step_by(2).skip(n / 2).copied().collect::<Vec<f32>>();
        let (l, r) = (ch(&a, 0), ch(&a, 1));
        let (il, ir) = (ch(&input, 0), ch(&input, 1));
        // Panned part: wider means louder on its side and out of phase on the other (side = 1.5 x).
        assert!((amp_at(&l, 1000.0, rate) / amp_at(&il, 1000.0, rate) - 1.25).abs() < 0.03);
        assert!((amp_at(&r, 1000.0, rate) - 0.025).abs() < 0.004, "right {}", amp_at(&r, 1000.0, rate));
        // Centre and bass unchanged.
        for hz in [60.0, 2000.0] {
            assert!(db(amp_at(&l, hz, rate) / amp_at(&il, hz, rate)).abs() < 0.2, "{hz} left");
            assert!(db(amp_at(&r, hz, rate) / amp_at(&ir, hz, rate)).abs() < 0.2, "{hz} right");
        }
        let _ = ir;
    }

    #[test]
    fn correction_follows_its_filters_and_turns_off_cleanly() {
        // A peak, a low shelf and a high shelf: the response at their centres matches the RBJ shapes.
        let mut e = Eq::new(48000, 2);
        e.set_correction(&[(CORR_PK, 244.0, 1.21, 4.9), (CORR_LSC, 105.0, 0.7, 4.2), (CORR_HSC, 10000.0, 0.7, -3.8)]);
        let pk = peaking_q(48000.0, 244.0, 4.9, 1.21);
        let ls = low_shelf_q(48000.0, 105.0, 4.2, 0.7);
        let hs = high_shelf_q(48000.0, 10000.0, -3.8, 0.7);
        for hz in [30.0, 105.0, 244.0, 1000.0, 10000.0, 16000.0] {
            let want = mag_db(&pk, 48000.0, hz) + mag_db(&ls, 48000.0, hz) + mag_db(&hs, 48000.0, hz);
            assert!((e.response_db(hz) - want).abs() < 1e-9, "{hz}");
        }
        assert!(e.response_db(20.0) > 3.9 && e.response_db(16000.0) < -3.5);
        // Bad filters are skipped, empty switches it off and the output is untouched again.
        e.set_correction(&[(CORR_PK, 30000.0, 1.0, 3.0), (CORR_PK, 1000.0, 0.0, 3.0)]);
        assert!(e.response_db(1000.0).abs() < 1e-9);
        let mut a = sine(48000.0, 440.0, 0.3, 9600);
        e.set_correction(&[(CORR_PK, 1000.0, 1.0, 3.0)]);
        e.process(&mut a);
        e.set_correction(&[]);
        let mut fade = sine(48000.0, 440.0, 0.3, 4800);
        e.process(&mut fade);
        let mut b = sine(48000.0, 440.0, 0.3, 4800);
        let c = b.clone();
        e.process(&mut b);
        assert_eq!(b, c);
    }

    #[test]
    fn options_switch_the_engine_and_stay_clean() {
        // Crossfeed alone turns the engine on; with no option and a flat curve it is untouched again.
        let mut e = Eq::new(48000, 2);
        e.set_options(OPT_CROSSFEED | OPT_DE_ESSER);
        let mut a = sine(48000.0, 440.0, 0.3, 9600);
        let b = a.clone();
        e.process(&mut a);
        assert_ne!(a, b);
        e.set_options(0);
        let mut fade = sine(48000.0, 440.0, 0.3, 4800);
        e.process(&mut fade);
        let mut c = sine(48000.0, 440.0, 0.3, 4800);
        let d = c.clone();
        e.process(&mut c);
        assert_eq!(c, d);
        // Dynamic bass on loud bass: lower lift, no added distortion.
        let rate = 48000.0;
        let lift = |dynamic: bool| {
            let mut e = Eq::new(48000, 1);
            e.set_exact(true);
            e.set_bass(12.0);
            if dynamic { e.set_options(OPT_DYNAMIC_BASS); }
            let mut a = sine(rate, 50.0, 0.5, 96000 * 2);
            e.process(&mut a);
            let t = &a[96000..];
            let f = amp_at(t, 50.0, rate);
            let worst = [100.0, 150.0, 200.0].iter().map(|h| db(amp_at(t, *h, rate) / f)).fold(f64::MIN, f64::max);
            (db(f / 0.5), worst)
        };
        let (on, harm) = lift(true);
        let (off, _) = lift(false);
        assert!(off - on > 2.0, "dynamic bass eased only {} dB", off - on);
        assert!(harm < -50.0, "dynamic bass harmonics {harm} dB");
    }

    #[test]
    fn loud_bass_does_not_duck_the_highs() {
        // Same curve, with and without a loud 60 Hz bass under a quiet 3 kHz tone: the 3 kHz level must not change.
        let rate = 48000.0;
        let n = 96000;
        let bass = sine(rate, 60.0, 0.8, n);
        let treble = sine(rate, 3000.0, 0.1, n);
        let mix: Vec<f32> = bass.iter().zip(&treble).map(|(a, b)| a + b).collect();
        let curve = [12.0f32, 10.0, 5.0, 0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0];

        let mut with_bass = Eq::new(48000, 1);
        with_bass.set_gains(&curve);
        with_bass.set_bass(12.0);
        let mut a = mix.clone();
        with_bass.process(&mut a);

        let mut alone = Eq::new(48000, 1);
        alone.set_gains(&curve);
        alone.set_bass(12.0);
        let mut b = treble.clone();
        alone.process(&mut b);

        let d = 20.0 * (amp_at(&a[48000..], 3000.0, rate) / amp_at(&b[48000..], 3000.0, rate)).log10();
        assert!(d.abs() < 0.7, "bass moved the 3 kHz tone by {d} dB");
        assert!(a.iter().all(|v| v.abs() <= 1.0 && v.is_finite()));
    }

    #[test]
    fn bass_stands_out_without_being_crushed() {
        // Typical bass peak 0.5 with Bass +9: the bass must come out clearly stronger relative to a 1 kHz tone, and the
        // bass limiter must stay nearly idle.
        let rate = 48000.0;
        let n = 96000;
        let bass = sine(rate, 55.0, 0.5, n);
        let mid = sine(rate, 1000.0, 0.2, n);
        let mix: Vec<f32> = bass.iter().zip(&mid).map(|(a, b)| a + b).collect();
        let mut e = Eq::new(48000, 1);
        e.set_gains(&[0.0; 10]);
        e.set_bass(9.0);
        let mut a = mix.clone();
        e.process(&mut a);
        let ratio_before = amp_at(&mix[48000..], 55.0, rate) / amp_at(&mix[48000..], 1000.0, rate);
        let ratio_after = amp_at(&a[48000..], 55.0, rate) / amp_at(&a[48000..], 1000.0, rate);
        let gain_db = 20.0 * (ratio_after / ratio_before).log10();
        assert!(gain_db > 7.5, "bass only gained {gain_db} dB over the mids");
        let st = e.take_stats();
        assert!(st[3] > -1.0, "bass limiter reduced {} dB", st[3]);
    }

    #[test]
    fn a_hard_working_bass_limiter_stays_clean() {
        // Loud 50 Hz with a big boost: the bass limiter works hard, but its gain must not follow the waveform.
        let rate = 48000.0;
        let mut e = Eq::new(48000, 1);
        e.set_gains(&[12.0, 10.0, 5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
        e.set_bass(12.0);
        let mut a = sine(rate, 50.0, 0.9, 96000 * 2);
        e.process(&mut a);
        assert!(e.take_stats()[3] < -3.0, "limiter should be working");
        let tail = &a[96000..];
        let f = amp_at(tail, 50.0, rate);
        let worst = [100.0, 150.0, 200.0, 250.0].iter().map(|h| db(amp_at(tail, *h, rate) / f)).fold(f64::MIN, f64::max);
        assert!(worst < -60.0, "harmonics at {worst} dB");
    }

    #[test]
    fn inter_sample_peaks_stay_under_the_ceiling() {
        // A quarter-rate sine at 45 degrees: samples read 0.707 while the real wave reaches 1.0.
        let rate = 44100.0;
        let mut e = Eq::new(44100, 1);
        e.set_exact(true);
        e.set_gains(&[0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, -0.5, 0.0]);
        let mut a: Vec<f32> = (0..44100).map(|i| (2.0 * PI * i as f64 / 4.0 + PI / 4.0).sin() as f32).collect();
        e.process(&mut a);
        let tp = true_peak(&a[22050..23050]);
        assert!(db(tp) < -0.7, "true peak {} dB", db(tp));
        let _ = rate;
    }

    #[test]
    fn peaks_stay_under_full_scale_with_extreme_bass() {
        let mut e = Eq::new(44100, 2);
        e.set_gains(&[12.0, 12.0, 10.0, 6.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
        e.set_bass(12.0);
        let mut a = sine(44100.0, 60.0, 0.95, 44100 * 2);
        e.process(&mut a);
        let m = a.iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!(m <= CEILING as f32 + 0.01, "peak {m}");
        assert!(a.iter().all(|v| v.is_finite()));
    }

    #[test]
    fn changes_do_not_click() {
        let rate = 48000.0;
        let mut e = Eq::new(48000, 1);
        let mut a = sine(rate, 1000.0, 0.2, 48000 * 3);
        let (p1, rest) = a.split_at_mut(48000);
        let (p2, p3) = rest.split_at_mut(48000);
        // Switching on, a preset change and the mode change all inside the run.
        e.set_gains(&[8.0, 7.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
        e.process(p1);
        e.set_gains(&[0.0, 0.0, 0.0, 0.0, 0.0, 9.0, 0.0, -6.0, 0.0, 0.0]);
        e.process(p2);
        e.set_exact(true);
        e.process(p3);
        let k = click(&a[24000..], 1000.0, rate);
        assert!(k < 0.01, "click of {k}");
    }

    #[test]
    fn dry_is_level_matched_and_in_time() {
        let rate = 48000.0;
        let n = 48000 * 8;
        let input: Vec<f32> = (0..n).map(|i| {
            let t = i as f64 / rate;
            (0.3 * (2.0 * PI * 60.0 * t).sin() + 0.1 * (2.0 * PI * 1000.0 * t).sin()) as f32
        }).collect();
        let mut e = Eq::new(48000, 1);
        e.set_exact(true);
        e.set_gains(&[8.0, 7.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
        let mut a = input.clone();
        let (wet, dry) = a.split_at_mut(n / 2);
        e.process(wet);
        e.set_dry(true);
        e.process(dry);
        // Loudness of the processed sound and the dry sound (same weighting as the engine).
        let loud = |x: &[f32]| {
            let k = [high_shelf(rate, 1500.0, 4.0), high_pass(rate, 40.0)];
            let mut s = [State::default(); 2];
            let p: f64 = x.iter().map(|v| { let w = kweight(&k, &mut s, *v as f64); w * w }).sum();
            (p / x.len() as f64).sqrt()
        };
        let d = db(loud(&a[n - 96000..]) / loud(&a[n / 2 - 96000..n / 2]));
        assert!(d.abs() < 0.5, "dry is {d} dB off");
        // Dry is the input delayed by the processed latency, times one gain.
        let lat = e.dry_len - 1;
        let g = a[n - 1000] as f64 / input[n - 1000 - lat] as f64;
        for i in n - 900..n {
            if input[i - lat].abs() > 0.05 {
                assert!((a[i] as f64 / input[i - lat] as f64 - g).abs() < 1e-3, "dry not aligned at {i}");
            }
        }
    }

    #[test]
    fn no_nan_on_extreme_curve() {
        let mut e = Eq::new(44100, 2);
        e.set_gains(&[12.0, -12.0, 12.0, -12.0, 12.0, -12.0, 12.0, -12.0, 12.0, -12.0]);
        let mut a = sine(44100.0, 440.0, 0.5, 20000);
        e.process(&mut a);
        assert!(a.iter().all(|v| v.is_finite()));
    }

    // ---- Gate 1: 31-Band Parametric EQ Verification Tests ----

    #[test]
    fn peq_gate1_flat_response_is_identity() {
        let mut e = Eq::new(48000, 2);
        let flat_bands = [PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 0.0, q: 1.41 }; 31];
        e.set_parametric(true, 0.0, true, true, &flat_bands);
        let mut a = sine(48000.0, 1000.0, 0.5, 9600);
        let b = a.clone();
        e.process(&mut a);
        let max_err = a.iter().zip(&b).map(|(x, y)| (*x as f64 - *y as f64).abs()).fold(0.0f64, f64::max);
        assert!(max_err < 1e-6, "flat PEQ error {max_err} exceeds -120 dBFS");
        assert_eq!(a, b);
    }

    #[test]
    fn peq_gate1_biquad_accuracy() {
        let rate = 48000.0;
        let band = [PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 6.0, q: 1.41 }];
        for (hz, tol) in [(1000.0, 0.1), (50.0, 0.2), (15000.0, 0.2)] {
            let mut e = Eq::new(48000, 1);
            e.set_parametric(true, 0.0, false, false, &band);
            let mut buf = sine(rate, hz, 0.1, 96000);
            e.process(&mut buf);
            let got = db(amp_at(&buf[48000..], hz, rate) / 0.1);
            let want = if (hz - 1000.0).abs() < 1.0 { 6.0 } else { 0.0 };
            assert!((got - want).abs() < tol, "{hz} Hz: got {got:.4} dB, want {want:.4} ± {tol} dB");
        }
    }

    #[test]
    fn peq_gate1_stability_at_extreme_q_and_rates() {
        for &rate in &[44100u32, 192000u32] {
            let mut e = Eq::new(rate, 2);
            let extreme = [PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 20.0, gain_db: 24.0, q: 20.0 }];
            e.set_parametric(true, 0.0, true, true, &extreme);
            let mut buf = vec![0f32; 100_000 * 2];
            for i in 0..100_000 {
                let s = (0.3 * (2.0 * PI * 20.0 * i as f64 / rate as f64).sin()) as f32;
                buf[2 * i] = s;
                buf[2 * i + 1] = s;
            }
            e.process(&mut buf);
            assert!(buf.iter().all(|v| v.is_finite()), "non-finite sample at {rate} Hz");
        }
    }

    #[test]
    fn peq_gate1_limiter_prevents_clipping_at_plus18db() {
        let rate = 48000.0;
        let mut e = Eq::new(48000, 2);
        let boost = [PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 18.0, q: 1.0 }];
        e.set_parametric(true, 0.0, false, true, &boost);
        let mut buf = vec![0f32; 48000 * 2];
        for i in 0..48000 {
            let s = (2.0 * PI * 1000.0 * i as f64 / rate).sin() as f32; // 0 dBFS sine
            buf[2 * i] = s;
            buf[2 * i + 1] = s;
        }
        e.process(&mut buf);
        let peak = buf.iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!(peak <= 1.0 && buf.iter().all(|v| v.is_finite()), "output clipped at peak={peak}");
        assert!(peak <= 0.9995, "peak {peak} too close to 0 dBFS");
    }

    #[test]
    fn peq_gate1_eval_response_matches_sine_sweep() {
        let rate = 48000.0;
        let bands = [
            PeqBand { kind: PEQ_LSC, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 105.0, gain_db: 4.5, q: 0.71 },
            PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 500.0, gain_db: -3.0, q: 1.8 },
            PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 3200.0, gain_db: 5.0, q: 2.2 },
            PeqBand { kind: PEQ_HSC, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 9000.0, gain_db: -2.5, q: 0.71 },
        ];
        let test_freqs: [f32; 7] = [40.0, 105.0, 250.0, 500.0, 1000.0, 3200.0, 10000.0];
        let mut predicted = [0f32; 7];
        let mut eq_ref = Eq::new(48000, 1);
        eq_ref.set_parametric(true, -1.5, true, false, &bands);
        let eff_preamp = eq_ref.eval_eq_response(&test_freqs, &mut predicted);
        assert!(eff_preamp < -1.5, "auto-preamp should reduce headroom, got {eff_preamp}");

        for (idx, &hz) in test_freqs.iter().enumerate() {
            let mut e = Eq::new(48000, 1);
            e.set_parametric(true, -1.5, true, false, &bands);
            let mut buf = sine(rate, hz as f64, 0.1, 96000);
            e.process(&mut buf);
            let measured = db(amp_at(&buf[48000..], hz as f64, rate) / 0.1);
            assert!(
                (measured - predicted[idx] as f64).abs() < 0.15,
                "{hz} Hz: eval={:.3} dB vs sine-sweep={:.3} dB",
                predicted[idx], measured
            );
        }
    }

    #[test]
    fn peq_per_channel_routing_isolates_left_and_right() {
        let rate = 48000.0;
        let bands = [
            PeqBand { kind: PEQ_PK, channel: PEQ_CH_LEFT, enabled: true, freq_hz: 1000.0, gain_db: 6.0, q: 1.41 },
            PeqBand { kind: PEQ_PK, channel: PEQ_CH_RIGHT, enabled: true, freq_hz: 1000.0, gain_db: -6.0, q: 1.41 },
        ];
        let mut e = Eq::new(48000, 2);
        e.set_parametric(true, 0.0, false, false, &bands);
        let n = 96000;
        let mut buf = vec![0f32; n * 2];
        for i in 0..n {
            let s = (0.1 * (2.0 * PI * 1000.0 * i as f64 / rate).sin()) as f32;
            buf[2 * i] = s;
            buf[2 * i + 1] = s;
        }
        e.process(&mut buf);
        let left: Vec<f32> = buf.iter().skip(0).step_by(2).skip(n / 2).copied().collect();
        let right: Vec<f32> = buf.iter().skip(1).step_by(2).skip(n / 2).copied().collect();
        let l_db = db(amp_at(&left, 1000.0, rate) / 0.1);
        let r_db = db(amp_at(&right, 1000.0, rate) / 0.1);
        assert!((l_db - 6.0).abs() < 0.15, "left got {l_db} dB");
        assert!((r_db + 6.0).abs() < 0.15, "right got {r_db} dB");
    }

    #[test]
    fn peq_all_31_bands_and_lp_hp_bp_work() {
        let rate = 48000.0;
        let mut bands = [PeqBand::default(); 31];
        for i in 0..28 {
            bands[i] = PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 30.0 * 1.25f64.powi(i as i32), gain_db: 0.5, q: 4.0 };
        }
        bands[28] = PeqBand { kind: PEQ_HP, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 30.0, gain_db: 0.0, q: 0.707 };
        bands[29] = PeqBand { kind: PEQ_LP, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 18000.0, gain_db: 0.0, q: 0.707 };
        bands[30] = PeqBand { kind: PEQ_BP, channel: PEQ_CH_STEREO, enabled: false, freq_hz: 1000.0, gain_db: 0.0, q: 1.0 };
        let mut e = Eq::new(48000, 2);
        e.set_parametric(true, 0.0, true, true, &bands);
        let mut buf = sine(rate, 1000.0, 0.3, 48000 * 2);
        e.process(&mut buf);
        assert!(buf.iter().all(|v| v.is_finite()));
        // Also verify BP filter attenuates out-of-band frequencies
        let bp = [PeqBand { kind: PEQ_BP, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 0.0, q: 2.0 }];
        let mut out = [0f32; 3];
        eval_parametric_response(rate, 0.0, false, &bp, &[100.0, 1000.0, 10000.0], &mut out, PEQ_CH_STEREO);
        assert!(out[1].abs() < 0.1, "BP center should be ~0 dB, got {}", out[1]);
        assert!(out[0] < -15.0 && out[2] < -15.0, "BP skirts should roll off: {:?}", out);
    }

    // ---- USB Exclusive High-Res Multi-Format PCM + TPDF Dither + Dynamic Rate Reconfigure Tests ----

    fn encode_pcm(samples: &[f64], kind: i32) -> Vec<u8> {
        let mut out = Vec::with_capacity(samples.len() * crate::pcm_bytes(kind));
        for &s in samples {
            match kind {
                crate::PCM_16 => out.extend_from_slice(&((s * 32768.0).round().clamp(-32768.0, 32767.0) as i16).to_le_bytes()),
                crate::PCM_24 => {
                    let b = ((s * 8388608.0).round().clamp(-8388608.0, 8388607.0) as i32).to_le_bytes();
                    out.extend_from_slice(&[b[0], b[1], b[2]]);
                }
                crate::PCM_32 => out.extend_from_slice(&((s * 2147483648.0).round().clamp(-2147483648.0, 2147483647.0) as i32).to_le_bytes()),
                _ => out.extend_from_slice(&(s as f32).to_ne_bytes()),
            }
        }
        out
    }

    fn decode_pcm_ch0(bytes: &[u8], kind: i32, channels: usize) -> Vec<f32> {
        let step = crate::pcm_bytes(kind);
        bytes.chunks_exact(step * channels).map(|frame| {
            match kind {
                crate::PCM_16 => i16::from_le_bytes([frame[0], frame[1]]) as f32 / 32768.0,
                crate::PCM_24 => (i32::from_le_bytes([0, frame[0], frame[1], frame[2]]) >> 8) as f32 / 8388608.0,
                crate::PCM_32 => i32::from_le_bytes([frame[0], frame[1], frame[2], frame[3]]) as f32 / 2147483648.0,
                _ => f32::from_ne_bytes([frame[0], frame[1], frame[2], frame[3]]),
            }
        }).collect()
    }

    #[test]
    fn usb_gate1_all_four_pcm_encodings_apply_plus6db_accurately() {
        let rate = 96000.0;
        let frames = 96000usize;
        let band = [PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 6.0, q: 1.41 }];
        let mut stereo = Vec::with_capacity(frames * 2);
        for i in 0..frames {
            let s = 0.1 * (2.0 * PI * 1000.0 * i as f64 / rate).sin();
            stereo.push(s);
            stereo.push(s);
        }
        for &kind in &[crate::PCM_16, crate::PCM_24, crate::PCM_32, crate::PCM_FLOAT] {
            let mut e = Eq::new(96000, 2);
            e.set_parametric(true, 0.0, false, false, &band);
            let mut bytes = encode_pcm(&stereo, kind);
            let processed = e.process_pcm_in_place(&mut bytes, kind);
            assert_eq!(processed, frames * 2, "sample count mismatch for kind={kind}");
            let ch0 = decode_pcm_ch0(&bytes, kind, 2);
            let got = db(amp_at(&ch0[frames / 2..], 1000.0, rate) / 0.1);
            assert!((got - 6.0).abs() < 0.15, "kind={kind}: got {got:.4} dB, expected 6.0 ± 0.15 dB");
        }
    }

    #[test]
    fn usb_gate1_high_sample_rates_44k_to_384k_match_gain() {
        let bands = [
            PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 6.0, q: 1.41 },
            PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 10000.0, gain_db: -4.0, q: 2.0 },
        ];
        for &rate in &[44100u32, 96000u32, 192000u32, 384000u32] {
            let frames = (rate as usize) / 2; // 0.5 s at each rate
            for &(hz, want) in &[(1000.0, 6.0), (10000.0, -4.0)] {
                let mut e = Eq::new(rate, 2);
                e.set_parametric(true, 0.0, false, false, &bands);
                let mut stereo = Vec::with_capacity(frames * 2);
                for i in 0..frames {
                    let s = 0.1 * (2.0 * PI * hz * i as f64 / rate as f64).sin();
                    stereo.push(s);
                    stereo.push(s);
                }
                let mut bytes = encode_pcm(&stereo, crate::PCM_24);
                e.process_pcm_in_place(&mut bytes, crate::PCM_24);
                let ch0 = decode_pcm_ch0(&bytes, crate::PCM_24, 2);
                assert!(ch0.iter().all(|v| v.is_finite()), "non-finite at {rate} Hz");
                let got = db(amp_at(&ch0[frames / 2..], hz, rate as f64) / 0.1);
                assert!((got - want).abs() < 0.15, "{rate} Hz @ {hz} Hz: got {got:.4} dB, want {want:.4} ± 0.15 dB");
            }
        }
    }

    #[test]
    fn usb_gate1_sample_rate_switch_44k_to_192k_resets_cleanly_without_spike() {
        let band = [PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 6.0, q: 1.41 }];
        let mut e = Eq::new(44100, 2);
        e.set_parametric(true, 0.0, false, true, &band);

        // Drive heavily at 44.1 kHz to charge all biquad delay lines.
        let mut warm = Vec::with_capacity(22050 * 2);
        for i in 0..22050 {
            let s = 0.6 * (2.0 * PI * 1000.0 * i as f64 / 44100.0).sin();
            warm.push(s); warm.push(s);
        }
        let mut warm_bytes = encode_pcm(&warm, crate::PCM_24);
        e.process_pcm_in_place(&mut warm_bytes, crate::PCM_24);

        // Mid-session switch to 192 kHz: first frames start from 0.0 silence -> must NOT spike!
        e.reconfigure(192000, 2);
        let frames = 96000usize;
        let mut hi = Vec::with_capacity(frames * 2);
        for i in 0..frames {
            let s = if i < 64 { 0.0 } else { 0.1 * (2.0 * PI * 1000.0 * (i - 64) as f64 / 192000.0).sin() };
            hi.push(s); hi.push(s);
        }
        let mut hi_bytes = encode_pcm(&hi, crate::PCM_24);
        e.process_pcm_in_place(&mut hi_bytes, crate::PCM_24);
        let ch0 = decode_pcm_ch0(&hi_bytes, crate::PCM_24, 2);
        let first_peak = ch0[..64].iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!(first_peak < 1e-4, "stale state spike after 44.1->192 kHz switch: {first_peak}");
        let got = db(amp_at(&ch0[frames / 2..], 1000.0, 192000.0) / 0.1);
        assert!((got - 6.0).abs() < 0.15, "post-reconfigure 192 kHz gain got {got:.4} dB");
    }

    #[test]
    fn usb_gate1_bypass_is_byte_exact_across_all_four_encodings() {
        let frames = 4096usize;
        let mut stereo = Vec::with_capacity(frames * 2);
        for i in 0..frames {
            let s = 0.45 * (2.0 * PI * 733.0 * i as f64 / 96000.0).sin();
            stereo.push(s);
            stereo.push(-s * 0.7);
        }
        for &kind in &[crate::PCM_16, crate::PCM_24, crate::PCM_32, crate::PCM_FLOAT] {
            let mut e = Eq::new(96000, 2);
            e.set_parametric(false, -3.0, true, true, &[
                PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 9.0, q: 1.41 },
            ]);
            let mut bytes = encode_pcm(&stereo, kind);
            let orig = bytes.clone();
            e.process_pcm_in_place(&mut bytes, kind);
            assert_eq!(bytes, orig, "bypass modified bytes for kind={kind}");
        }
    }

    #[test]
    fn peq_boost_not_cancelled_by_legacy_bass_trim() {
        let rate = 48000.0;
        let frames = 48000usize;
        let mut e = Eq::new(48000, 2);
        // Simulate saved eq_bass = 3.0 together with Parametric EQ (+4 dB preamp +6 dB peak @ 1 kHz).
        e.set_bass(3.0);
        let band = [PeqBand { kind: PEQ_PK, channel: PEQ_CH_STEREO, enabled: true, freq_hz: 1000.0, gain_db: 6.0, q: 1.41 }];
        e.set_parametric(true, 4.0, false, false, &band);

        let amp = 0.05f64;
        let mut buf = Vec::with_capacity(frames * 2);
        for i in 0..frames {
            let s = (amp * (2.0 * PI * 1000.0 * i as f64 / rate).sin()) as f32;
            buf.push(s);
            buf.push(s);
        }
        let in_rms = rms(&buf[frames..]);
        e.process(&mut buf);
        let out_rms = rms(&buf[frames..]);
        let delta_db = db(out_rms / in_rms);
        assert!(delta_db >= 8.5, "expected >= +8.5 dB net PEQ gain with bass=3.0, got {delta_db:.3} dB");
    }

    #[test]
    fn nan_inputs_are_sanitized_and_reconfigure_updates_legacy_filters() {
        let mut e = Eq::new(44100, 2);
        e.set_bass(f32::NAN);
        e.set_width(f32::NAN);
        e.set_gains(&[f32::NAN; N]);
        assert!(!e.is_active(), "NaN inputs must not poison or activate Eq");

        let mut g = [0.0f32; N];
        g[9] = 6.0; // 16 kHz band
        e.set_gains(&g);
        e.set_bass(6.0);
        let old_coef = e.chain.coefs[9].b0;
        let old_bass = e.chain.bass[0].b0;
        e.reconfigure(192000, 2);
        assert_ne!(e.chain.coefs[9].b0, old_coef, "10-band coefs must update on reconfigure");
        assert_ne!(e.chain.bass[0].b0, old_bass, "bass coefs must update on reconfigure");
    }
}
