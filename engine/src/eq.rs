//! Ten-band graphic equalizer plus a bass stage, then a two-band level and limiter stage.
//!
//! The band gains are fitted so the combined response passes through the requested curve (neighbouring bands overlap and
//! would otherwise add up). To stay clean the signal is split at 180 Hz after the equalizer: each half gets its own level trim
//! and look-ahead limiter, so a bass peak never ducks the mids and highs. A last limiter catches the rare sum of both.

pub const BANDS_HZ: [f64; 10] = [32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];
const N: usize = BANDS_HZ.len();
const Q: f64 = 1.41; // one octave wide
const MAX_CH: usize = 8;
const MAX_FIT_DB: f64 = 18.0;
/// Net lift the bass half keeps (dB): clean on ordinary masters, so the limiter stays a safety net and bass keeps its punch.
const BASS_NET_DB: f64 = 4.0;
/// Of the bass boost above that, how much the rest is lowered too, so the bass still stands out (0 = not at all, 1 = fully).
const SEPARATION: f64 = 0.7;
/// The most the rest is ever lowered for the bass (dB), so an extreme curve cannot make the music unusably quiet.
const MAX_REST_CUT_DB: f64 = 10.0;
/// Share of a boost in the mids and highs taken off their level up front.
const HIGH_SHARE: f64 = 0.35;
const LIMIT_OUT: f64 = 0.98;
const LIMIT_LOW: f64 = 0.8;
const LIMIT_HIGH: f64 = 0.7;
const CROSSOVER_HZ: f64 = 180.0;
const HIGH_FROM_HZ: f64 = 400.0;
const RELEASE_MS: f64 = 120.0;
/// Limiter look-ahead in frames (about 1 ms at 48 kHz): the gain is already down when the peak reaches the output.
const LOOK: usize = 48;

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

/// RBJ peaking filter.
fn peaking(rate: f64, hz: f64, gain_db: f64) -> Coef {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * std::f64::consts::PI * hz / rate;
    let alpha = w.sin() / (2.0 * Q);
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
fn low_shelf(rate: f64, hz: f64, gain_db: f64) -> Coef {
    let a = 10f64.powf(gain_db / 40.0);
    let w = 2.0 * std::f64::consts::PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / 2.0 * 2f64.sqrt(); // S = 1
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

/// RBJ high-pass, Q = 0.707 (second order, 12 dB per octave).
fn high_pass(rate: f64, hz: f64) -> Coef {
    let w = 2.0 * std::f64::consts::PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * std::f64::consts::FRAC_1_SQRT_2);
    let a0 = 1.0 + alpha;
    Coef { b0: (1.0 + cw) / 2.0 / a0, b1: -(1.0 + cw) / a0, b2: (1.0 + cw) / 2.0 / a0, a1: -2.0 * cw / a0, a2: (1.0 - alpha) / a0 }
}

/// RBJ low-pass, Q = 0.707.
fn low_pass(rate: f64, hz: f64) -> Coef {
    let w = 2.0 * std::f64::consts::PI * hz / rate;
    let (sw, cw) = (w.sin(), w.cos());
    let alpha = sw / (2.0 * std::f64::consts::FRAC_1_SQRT_2);
    let a0 = 1.0 + alpha;
    Coef { b0: (1.0 - cw) / 2.0 / a0, b1: (1.0 - cw) / a0, b2: (1.0 - cw) / 2.0 / a0, a1: -2.0 * cw / a0, a2: (1.0 - alpha) / a0 }
}

/// Magnitude of one biquad at `hz`, in dB.
fn mag_db(k: &Coef, rate: f64, hz: f64) -> f64 {
    let w = 2.0 * std::f64::consts::PI * hz / rate;
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

/// Look-ahead peak limiter: the window minimum of the wanted gain, smoothed over the same window, so the gain is already
/// down when the delayed peak comes out. Fast attack, slow release (bass is slow, a quick release distorts it).
struct Limiter {
    limit: f64,
    release: f64,
    delay: Vec<f32>,
    want: [f64; LOOK],
    mins: [f64; LOOK],
    mins_sum: f64,
    pos: usize,
    gain: f64,
    gr_min: f64,
}

impl Limiter {
    fn new(limit: f64, release: f64) -> Limiter {
        Limiter { limit, release, delay: vec![0.0; LOOK * MAX_CH], want: [1.0; LOOK], mins: [1.0; LOOK], mins_sum: LOOK as f64, pos: 0, gain: 1.0, gr_min: 1.0 }
    }

    fn clear(&mut self) {
        self.delay.iter_mut().for_each(|v| *v = 0.0);
        self.want = [1.0; LOOK];
        self.mins = [1.0; LOOK];
        self.mins_sum = LOOK as f64;
        self.pos = 0;
        self.gain = 1.0;
    }

    /// Replaces the first `ch` values of `frame` with the frame from `LOOK - 1` frames ago, limited.
    fn process(&mut self, frame: &mut [f32; MAX_CH], ch: usize) {
        let peak = frame[..ch].iter().fold(0f64, |m, v| m.max((*v as f64).abs()));
        let pos = self.pos;
        self.want[pos] = if peak > self.limit { self.limit / peak } else { 1.0 };
        let mn = self.want.iter().fold(1.0f64, |m, v| m.min(*v));
        self.mins_sum += mn - self.mins[pos];
        self.mins[pos] = mn;
        let g1 = self.mins_sum / LOOK as f64;
        self.gain = if g1 < self.gain { g1 } else { self.gain + (g1 - self.gain) * self.release };
        self.gr_min = self.gr_min.min(self.gain);
        let g = self.gain as f32;
        let oldest = (pos + 1) % LOOK;
        for c in 0..ch {
            let o = self.delay[oldest * ch + c];
            self.delay[pos * ch + c] = frame[c];
            frame[c] = o * g;
        }
        self.pos = oldest;
    }

    /// Deepest reduction in dB since the last call (0 or below).
    fn take_gr_db(&mut self) -> f32 {
        let v = 20.0 * self.gr_min.max(1e-6).log10();
        self.gr_min = 1.0;
        v as f32
    }
}

pub struct Eq {
    rate: f64,
    channels: usize,
    coefs: [Coef; N],
    active: [bool; N],
    state: [[State; N]; MAX_CH],
    bass_db: f64,
    bass: [Coef; 3],
    bass_state: [[State; 3]; MAX_CH],
    bands_on: bool,
    peak_db: f64,
    // Linkwitz-Riley 4th order split: two cascaded 2nd-order low-pass and two high-pass per channel.
    xo_lp: Coef,
    xo_hp: Coef,
    xo_state: [[State; 4]; MAX_CH],
    gain_low: f64,
    gain_high: f64,
    lim_low: Limiter,
    lim_high: Limiter,
    lim_out: Limiter,
    bypass: bool,
}

impl Eq {
    pub fn new(rate: u32, channels: u32) -> Eq {
        let rate = rate.max(8000) as f64;
        let release = 1.0 - (-1.0 / (RELEASE_MS * 0.001 * rate)).exp();
        Eq {
            rate,
            channels: (channels as usize).clamp(1, MAX_CH),
            coefs: [Coef::default(); N],
            active: [false; N],
            state: [[State::default(); N]; MAX_CH],
            bass_db: 0.0,
            bass: [Coef::default(); 3],
            bass_state: [[State::default(); 3]; MAX_CH],
            bands_on: false,
            peak_db: 0.0,
            xo_lp: low_pass(rate, CROSSOVER_HZ),
            xo_hp: high_pass(rate, CROSSOVER_HZ),
            xo_state: [[State::default(); 4]; MAX_CH],
            gain_low: 1.0,
            gain_high: 1.0,
            lim_low: Limiter::new(LIMIT_LOW, release),
            lim_high: Limiter::new(LIMIT_HIGH, release),
            lim_out: Limiter::new(LIMIT_OUT, release),
            bypass: true,
        }
    }

    /// Combined response of the current filters at `hz`, in dB (without the level trims).
    pub fn response_db(&self, hz: f64) -> f64 {
        let bands: f64 = (0..N).filter(|&i| self.active[i]).map(|i| mag_db(&self.coefs[i], self.rate, hz)).sum();
        let bass: f64 = if self.bass_db.abs() >= 0.05 { self.bass.iter().map(|k| mag_db(k, self.rate, hz)).sum() } else { 0.0 };
        bands + bass
    }

    /// Extra low-end boost on top of the bands, 0 to 12 dB: a shelf below 100 Hz plus a lift around 45 Hz,
    /// with a high-pass at 22 Hz so the inaudible range below it is not boosted.
    pub fn set_bass(&mut self, db: f32) {
        self.bass_db = (db as f64).clamp(0.0, 12.0);
        if self.bass_db >= 0.05 {
            self.bass = [low_shelf(self.rate, 100.0, self.bass_db), peaking(self.rate, 45.0, 0.4 * self.bass_db), high_pass(self.rate, 22.0)];
        }
        self.refresh();
    }

    fn refresh(&mut self) {
        let was_bypass = self.bypass;
        self.bypass = !self.bands_on && self.bass_db < 0.05;
        if self.bypass { return; }
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
        let over = (pl - BASS_NET_DB).max(0.0);
        self.gain_low = 10f64.powf(-over / 20.0);
        self.gain_high = 10f64.powf(-((SEPARATION * over).min(MAX_REST_CUT_DB) + HIGH_SHARE * ph) / 20.0);
        if was_bypass { self.reset(); }
    }

    /// One target gain in dB per band; any other length (or all near zero) turns the equalizer off.
    pub fn set_gains(&mut self, target_db: &[f32]) {
        if target_db.len() != N || target_db.iter().all(|g| g.abs() < 0.05) {
            self.bands_on = false;
            self.active = [false; N];
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
        for i in 0..N {
            self.active[i] = usable[i] && g[i].abs() >= 0.05;
            if self.active[i] { self.coefs[i] = peaking(self.rate, BANDS_HZ[i], g[i]); }
        }
        self.bands_on = true;
        self.refresh();
    }

    /// In place, interleaved. While the equalizer is on the output is delayed by about two limiter look-aheads (2 ms).
    pub fn process(&mut self, buf: &mut [f32]) {
        if self.bypass { return; }
        let ch = self.channels;
        for frame in buf.chunks_exact_mut(ch) {
            let mut low = [0f32; MAX_CH];
            let mut high = [0f32; MAX_CH];
            for c in 0..ch {
                let mut x = frame[c] as f64;
                for b in 0..N {
                    if self.active[b] { x = biquad(&self.coefs[b], &mut self.state[c][b], x); }
                }
                if self.bass_db >= 0.05 {
                    for b in 0..3 { x = biquad(&self.bass[b], &mut self.bass_state[c][b], x); }
                }
                let l1 = biquad(&self.xo_lp, &mut self.xo_state[c][0], x);
                let lo = biquad(&self.xo_lp, &mut self.xo_state[c][1], l1);
                let h1 = biquad(&self.xo_hp, &mut self.xo_state[c][2], x);
                let hi = biquad(&self.xo_hp, &mut self.xo_state[c][3], h1);
                low[c] = (lo * self.gain_low) as f32;
                high[c] = (hi * self.gain_high) as f32;
            }
            self.lim_low.process(&mut low, ch);
            self.lim_high.process(&mut high, ch);
            let mut sum = [0f32; MAX_CH];
            for c in 0..ch { sum[c] = low[c] + high[c]; }
            self.lim_out.process(&mut sum, ch);
            frame.copy_from_slice(&sum[..ch]);
        }
    }

    /// `[low-half level cut dB, high-half level cut dB, bass dB, bass-half limiter reduction dB, other limiter reduction dB, curve peak dB]`.
    /// The reductions are the deepest since the last call (0 or below).
    pub fn take_stats(&mut self) -> [f32; 6] {
        let gl = self.lim_low.take_gr_db();
        let gh = self.lim_high.take_gr_db().min(self.lim_out.take_gr_db());
        if self.bypass { return [0.0, 0.0, 0.0, gl, gh, 0.0]; }
        [(20.0 * self.gain_low.log10()) as f32, (20.0 * self.gain_high.log10()) as f32, self.bass_db as f32, gl, gh, self.peak_db as f32]
    }

    pub fn reset(&mut self) {
        self.state = [[State::default(); N]; MAX_CH];
        self.bass_state = [[State::default(); 3]; MAX_CH];
        self.xo_state = [[State::default(); 4]; MAX_CH];
        self.lim_low.clear();
        self.lim_high.clear();
        self.lim_out.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(rate: f64, hz: f64, amp: f64, n: usize) -> Vec<f32> {
        (0..n).map(|i| (amp * (2.0 * std::f64::consts::PI * hz * i as f64 / rate).sin()) as f32).collect()
    }
    fn rms(x: &[f32]) -> f64 { (x.iter().map(|v| (*v as f64).powi(2)).sum::<f64>() / x.len() as f64).sqrt() }
    /// Amplitude of the component at `hz` (single-bin correlation).
    fn amp_at(x: &[f32], hz: f64, rate: f64) -> f64 {
        let (mut re, mut im) = (0.0, 0.0);
        for (i, v) in x.iter().enumerate() {
            let p = 2.0 * std::f64::consts::PI * hz * i as f64 / rate;
            re += *v as f64 * p.cos();
            im += *v as f64 * p.sin();
        }
        2.0 * (re * re + im * im).sqrt() / x.len() as f64
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
        assert!(gain_db > 8.0, "bass only gained {gain_db} dB over the mids");
        let st = e.take_stats();
        assert!(st[3] > -1.0, "bass limiter reduced {} dB", st[3]);
    }

    #[test]
    fn peaks_stay_under_full_scale_with_extreme_bass() {
        let mut e = Eq::new(44100, 2);
        e.set_gains(&[12.0, 12.0, 10.0, 6.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
        e.set_bass(12.0);
        let mut a = sine(44100.0, 60.0, 0.95, 44100 * 2);
        e.process(&mut a);
        let m = a.iter().fold(0f32, |m, v| m.max(v.abs()));
        assert!(m <= 1.0, "peak {m}");
        assert!(a.iter().all(|v| v.is_finite()));
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
