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

use std::f64::consts::PI;

pub const BANDS_HZ: [f64; 10] = [32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];
const N: usize = BANDS_HZ.len();
const Q: f64 = 1.41; // one octave wide
const MAX_CH: usize = 8;
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
pub const CORR_PK: u8 = 0;
pub const CORR_LSC: u8 = 1;
pub const CORR_HSC: u8 = 2;
/// Filters in the bass stage.
const BASS_F: usize = 4;
/// Time constant of the level trims.
const TRIM_MS: f64 = 10.0;
/// Loudness window of the A/B level match.
const MATCH_S: f64 = 3.0;

#[derive(Clone, Copy, Default)]
struct Coef { b0: f64, b1: f64, b2: f64, a1: f64, a2: f64 }

#[derive(Clone, Copy, Default)]
struct State { z1: f64, z2: f64 }

fn biquad(k: &Coef, st: &mut State, x: f64) -> f64 {
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
fn low_pass(rate: f64, hz: f64) -> Coef {
    let w = 2.0 * PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * std::f64::consts::FRAC_1_SQRT_2);
    let a0 = 1.0 + alpha;
    Coef { b0: (1.0 - cw) / 2.0 / a0, b1: (1.0 - cw) / a0, b2: (1.0 - cw) / 2.0 / a0, a1: -2.0 * cw / a0, a2: (1.0 - alpha) / a0 }
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
    delay: Vec<f32>,
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
        let peak = match self.tp.as_mut() {
            Some(tp) => (0..ch).fold(0f64, |m, c| m.max(tp.push(c, frame[c] as f64))),
            None => frame[..ch].iter().fold(0f64, |m, v| m.max((*v as f64).abs())),
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
        let g = self.gain as f32;
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
        }
    }

    fn run(&mut self, c: usize, mut x: f64) -> f64 {
        for b in 0..self.corr_n { x = biquad(&self.corr[b], &mut self.corr_state[c][b], x); }
        for b in 0..N {
            if self.active[b] { x = biquad(&self.coefs[b], &mut self.state[c][b], x); }
        }
        if self.bass_on {
            for b in 0..BASS_F { x = biquad(&self.bass[b], &mut self.bass_state[c][b], x); }
        }
        x
    }

    fn clear(&mut self) {
        self.state = [[State::default(); N]; MAX_CH];
        self.bass_state = [[State::default(); BASS_F]; MAX_CH];
        self.corr_state = [[State::default(); MAX_CORR]; MAX_CH];
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
    bass_db: f64,
    peak_db: f64,
    exact: bool,
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
    /// Whether the processed path is running, and whether it should; `m` fades the output across a switch.
    on: bool,
    want_on: bool,
    m: f64,
    switch_step: f64,
    /// A/B: `dry` plays the input, delayed and level matched; `ab` glides from 0 (processed) to 1 (dry).
    dry: bool,
    ab: f64,
    ab_step: f64,
    dry_delay: Vec<f32>,
    dry_len: usize,
    dry_pos: usize,
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
            bass_db: 0.0,
            peak_db: 0.0,
            exact: false,
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
            kw: [high_shelf(rate, 1500.0, 4.0), high_pass(rate, 40.0)],
            kw_in: [[State::default(); 2]; MAX_CH],
            kw_out: [[State::default(); 2]; MAX_CH],
            p_in: 0.0,
            p_out: 0.0,
            p_k: 1.0 - (-1.0 / (MATCH_S * rate)).exp(),
        }
    }

    /// Combined response of the current filters at `hz`, in dB (without the level trims).
    pub fn response_db(&self, hz: f64) -> f64 {
        let ch = &self.chain;
        let bands: f64 = (0..N).filter(|&i| ch.active[i]).map(|i| mag_db(&ch.coefs[i], self.rate, hz)).sum();
        let bass: f64 = if ch.bass_on { ch.bass.iter().map(|k| mag_db(k, self.rate, hz)).sum() } else { 0.0 };
        let corr: f64 = ch.corr[..ch.corr_n].iter().map(|k| mag_db(k, self.rate, hz)).sum();
        bands + bass + corr
    }

    /// Keeps the current filters running as the old set, faded out over `FADE_MS` while the new set fades in.
    fn begin_fade(&mut self) {
        if self.on {
            self.old = self.chain.clone();
            self.fade = self.fade_len;
        }
    }

    /// Extra low-end boost on top of the bands, 0 to 12 dB: a shelf below 100 Hz plus a lift around 45 Hz, with a high-pass
    /// at 22 Hz so the inaudible range below it is not boosted, and a wide cut around 125 Hz so the rumble and kick
    /// (25 to 60 Hz) keep their full lift while the boom above them, which muddies the mids, is pulled back.
    pub fn set_bass(&mut self, db: f32) {
        self.begin_fade();
        self.bass_db = (db as f64).clamp(0.0, 12.0);
        let on = self.bass_db >= 0.05;
        if on {
            self.chain.bass = [
                low_shelf(self.rate, 100.0, self.bass_db),
                peaking(self.rate, 45.0, 0.4 * self.bass_db),
                high_pass(self.rate, 22.0),
                peaking_q(self.rate, 125.0, -0.35 * self.bass_db, 1.2),
            ];
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
        let mut n = 0;
        for &(kind, hz, q, db) in filters.iter().take(MAX_CORR) {
            if !(hz > 10.0 && hz < self.rate * 0.45 && q > 0.05 && q < 20.0 && db.abs() <= 24.0) { continue; }
            self.chain.corr[n] = match kind {
                CORR_LSC => low_shelf_q(self.rate, hz, db, q),
                CORR_HSC => high_shelf_q(self.rate, hz, db, q),
                _ => peaking_q(self.rate, hz, db, q),
            };
            n += 1;
        }
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
        self.width = (w as f64).clamp(0.5, 1.6);
        self.refresh();
    }

    /// Plays the input (delayed to the processed timing, at the processed loudness) instead of the processed sound.
    pub fn set_dry(&mut self, dry: bool) { self.dry = dry; }

    fn refresh(&mut self) {
        self.want_on = self.bands_on || self.chain.bass_on || self.chain.corr_n > 0 || (self.channels == 2 && (self.width - 1.0).abs() > 0.005);
        if !self.want_on { return; }
        // Peak of the response below and above the split, separately: each half is trimmed only for its own boost.
        let top = self.rate * 0.45;
        let (mut pl, mut ph) = (0f64, 0f64);
        let mut f = 20.0;
        while f < top.min(20000.0) {
            let r = self.response_db(f);
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
        if target_db.len() != N || target_db.iter().all(|g| g.abs() < 0.05) {
            self.begin_fade();
            self.bands_on = false;
            self.chain.active = [false; N];
            self.chain.state = [[State::default(); N]; MAX_CH];
            self.refresh();
            return;
        }
        let t: [f64; N] = std::array::from_fn(|i| target_db[i] as f64);
        let usable: [bool; N] = std::array::from_fn(|i| BANDS_HZ[i] < self.rate * 0.45);

        // Influence of each filter (at +6 dB) on every band centre, per dB.
        let mut m = [[0f64; N]; N];
        for j in 0..N {
            let k = peaking(self.rate, BANDS_HZ[j], 6.0);
            for i in 0..N { m[i][j] = if usable[j] { mag_db(&k, self.rate, BANDS_HZ[i]) / 6.0 } else { 0.0 }; }
            if !usable[j] { m[j][j] = 1.0; }
        }
        // Fit: solve for filter gains, then correct twice for the error left by the nonlinearity.
        let mut g = solve(m, t).unwrap_or(t);
        for v in g.iter_mut() { *v = v.clamp(-MAX_FIT_DB, MAX_FIT_DB); }
        for _ in 0..2 {
            let coefs: [Coef; N] = std::array::from_fn(|i| peaking(self.rate, BANDS_HZ[i], g[i]));
            let err: [f64; N] = std::array::from_fn(|i| {
                let got: f64 = (0..N).filter(|&j| usable[j] && g[j].abs() >= 0.05).map(|j| mag_db(&coefs[j], self.rate, BANDS_HZ[i])).sum();
                t[i] - got
            });
            if let Some(d) = solve(m, err) {
                for i in 0..N { g[i] = (g[i] + d[i]).clamp(-MAX_FIT_DB, MAX_FIT_DB); }
            }
        }
        self.begin_fade();
        for i in 0..N {
            let on = usable[i] && g[i].abs() >= 0.05;
            if on {
                self.chain.coefs[i] = peaking(self.rate, BANDS_HZ[i], g[i]);
            } else {
                // A band switched off forgets its state, so it starts clean when it comes back.
                for c in 0..MAX_CH { self.chain.state[c][i] = State::default(); }
            }
            self.chain.active[i] = on;
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

    /// In place, interleaved. While the equalizer is on the output is delayed by the limiters' look-ahead (about 2 ms).
    pub fn process(&mut self, buf: &mut [f32]) {
        if !self.on && !self.want_on && self.m >= 1.0 { return; }
        let ch = self.channels;
        for frame in buf.chunks_exact_mut(ch) {
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
                if self.m < 1.0 { for v in frame.iter_mut() { *v = (*v as f64 * self.m) as f32; } }
                continue;
            }

            self.cur_low += (self.gain_low - self.cur_low) * self.trim_k;
            self.cur_high += (self.gain_high - self.cur_high) * self.trim_k;
            let a = if self.fade > 0 { self.fade -= 1; 1.0 - self.fade as f64 / self.fade_len as f64 } else { 1.0 };
            let mut low = [0f32; MAX_CH];
            let mut high = [0f32; MAX_CH];
            let mut p_in = 0.0;
            for c in 0..ch {
                let x = frame[c] as f64;
                let w = kweight(&self.kw, &mut self.kw_in[c], x);
                p_in += w * w;
                let mut v = self.chain.run(c, x);
                if a < 1.0 { v = a * v + (1.0 - a) * self.old.run(c, x); }
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
            let mut sum = [0f32; MAX_CH];
            for c in 0..ch { sum[c] = low[c] + high[c]; }
            self.lim_out.process(&mut sum, ch);

            let mut p_out = 0.0;
            for c in 0..ch {
                let w = kweight(&self.kw, &mut self.kw_out[c], sum[c] as f64);
                p_out += w * w;
            }
            self.p_in += (p_in - self.p_in) * self.p_k;
            self.p_out += (p_out - self.p_out) * self.p_k;

            let rd = (self.dry_pos + 1) % self.dry_len;
            let target = if self.dry { 1.0 } else { 0.0 };
            if self.ab < target { self.ab = (self.ab + self.ab_step).min(1.0); } else if self.ab > target { self.ab = (self.ab - self.ab_step).max(0.0); }
            let g = if self.ab > 0.0 { self.match_gain() } else { 1.0 };
            for c in 0..ch {
                let d = self.dry_delay[rd * MAX_CH + c] as f64;
                self.dry_delay[self.dry_pos * MAX_CH + c] = frame[c];
                let mut y = sum[c] as f64;
                if self.ab > 0.0 { y = y * (1.0 - self.ab) + d * g * self.ab; }
                if self.m < 1.0 { y *= self.m; }
                frame[c] = y as f32;
            }
            self.dry_pos = rd;
        }
    }

    /// `[low-half level dB, high-half level dB, bass dB, bass-half limiter reduction dB, other limiter reduction dB,
    /// curve peak dB, A/B level match dB]`. The reductions are the deepest since the last call (0 or below).
    pub fn take_stats(&mut self) -> [f32; 7] {
        let gl = self.lim_low.take_gr_db();
        let gh = self.lim_high.take_gr_db().min(self.lim_out.take_gr_db());
        if !self.on { return [0.0, 0.0, 0.0, gl, gh, 0.0, 0.0]; }
        let db = |v: f64| (20.0 * v.log10()) as f32;
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
        self.dry_delay.iter_mut().for_each(|v| *v = 0.0);
        self.dry_pos = 0;
        self.kw_in = [[State::default(); 2]; MAX_CH];
        self.kw_out = [[State::default(); 2]; MAX_CH];
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
}
