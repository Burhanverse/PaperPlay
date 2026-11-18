//! Optional effects around the equalizer: dynamic bass, de-esser and crossfeed. Each is off unless switched on.

use crate::eq::{biquad, low_pass, Coef, State, MAX_CH};
use std::f64::consts::PI;

/// One-pole smoothing coefficient for a time constant in ms.
fn k_ms(rate: f64, ms: f64) -> f64 { 1.0 - (-1.0 / (ms * 0.001 * rate)).exp() }

/// RBJ band-pass with 0 dB at the centre: `x - a * bp(x)` is then a peaking cut of `20 log10(1 - a)` dB.
fn band_pass(rate: f64, hz: f64, q: f64) -> Coef {
    let w = 2.0 * PI * hz / rate;
    let alpha = w.sin() / (2.0 * q);
    let a0 = 1.0 + alpha;
    Coef { b0: alpha / a0, b1: 0.0, b2: -alpha / a0, a1: -2.0 * w.cos() / a0, a2: (1.0 - alpha) / a0 }
}

/// Dynamic bass: the Bass control's full lift while the music's own low end is quiet, less while it is already loud, so
/// heavy bass lines stay clean at high levels. Returns how much of the bass stage to mix in (0 to 1).
pub struct DynBass {
    lp: Coef,
    st: State,
    mean: f64,
    avg: f64,
    env: f64,
    att: f64,
    rel: f64,
}

/// Low-end level (dBFS, a sine read at its peak) above which the lift starts to come down.
const DYN_THRESHOLD_DB: f64 = -12.0;
/// dB of lift taken back per dB above the threshold.
const DYN_SLOPE: f64 = 0.5;
/// Share of the lift that always stays.
const DYN_KEEP: f64 = 0.25;

impl DynBass {
    pub fn new(rate: f64) -> DynBass {
        DynBass { lp: low_pass(rate, 120.0), st: State::default(), mean: 0.0, avg: k_ms(rate, 20.0), env: 0.0, att: k_ms(rate, 50.0), rel: k_ms(rate, 500.0) }
    }

    pub fn clear(&mut self) { self.st = State::default(); self.mean = 0.0; self.env = 0.0; }

    /// `mono` is the input (channels averaged); `bass_db` the Bass control.
    pub fn mix(&mut self, mono: f64, bass_db: f64) -> f64 {
        let l = biquad(&self.lp, &mut self.st, mono);
        let p = l * l * 2.0; // a sine's mean square is half its peak squared, so x2 reads a sine at its peak level
        // Average out the ripple of the bass wave first (20 ms), then follow the level: quick up, slow down.
        self.mean += (p - self.mean) * self.avg;
        let p = self.mean;
        self.env += (p - self.env) * if p > self.env { self.att } else { self.rel };
        if bass_db < 0.05 { return 1.0; }
        let level = 10.0 * self.env.max(1e-12).log10();
        let cut = ((level - DYN_THRESHOLD_DB) * DYN_SLOPE).clamp(0.0, bass_db * (1.0 - DYN_KEEP));
        let full = 10f64.powf(bass_db / 20.0) - 1.0;
        (10f64.powf((bass_db - cut) / 20.0) - 1.0) / full
    }
}

/// De-esser: a dynamic cut around 7 kHz that only acts while that band dominates the sound (s, t, ch), so ordinary treble
/// and cymbals under music are left alone.
pub struct DeEsser {
    bp: Coef,
    st: [State; MAX_CH],
    env_s: f64,
    env_f: f64,
    att: f64,
    rel: f64,
}

/// Sibilance counts once the band holds more than this share of the power (dB relative to the whole).
const DS_RATIO_DB: f64 = -8.0;
/// dB of cut per dB above that, and the most it cuts.
const DS_SLOPE: f64 = 0.75;
const DS_MAX_DB: f64 = 8.0;
/// Below this band level (dBFS) nothing is cut.
const DS_FLOOR_DB: f64 = -45.0;

impl DeEsser {
    pub fn new(rate: f64) -> DeEsser {
        let hz = 7000f64.min(rate * 0.3);
        DeEsser { bp: band_pass(rate, hz, 0.8), st: [State::default(); MAX_CH], env_s: 0.0, env_f: 0.0, att: k_ms(rate, 1.0), rel: k_ms(rate, 60.0) }
    }

    pub fn clear(&mut self) { self.st = [State::default(); MAX_CH]; self.env_s = 0.0; self.env_f = 0.0; }

    pub fn process(&mut self, v: &mut [f64; MAX_CH], ch: usize) {
        let mut s = [0f64; MAX_CH];
        let (mut ps, mut pf) = (0.0, 0.0);
        for c in 0..ch {
            s[c] = biquad(&self.bp, &mut self.st[c], v[c]);
            ps += s[c] * s[c];
            pf += v[c] * v[c];
        }
        self.env_s += (ps - self.env_s) * if ps > self.env_s { self.att } else { self.rel };
        self.env_f += (pf - self.env_f) * if pf > self.env_f { self.att } else { self.rel };
        let band = 10.0 * (self.env_s / ch as f64).max(1e-12).log10();
        let ratio = 10.0 * (self.env_s.max(1e-12) / self.env_f.max(1e-12)).log10();
        if band < DS_FLOOR_DB { return; }
        let cut = ((ratio - DS_RATIO_DB) * DS_SLOPE).clamp(0.0, DS_MAX_DB);
        if cut <= 0.0 { return; }
        let a = 1.0 - 10f64.powf(-cut / 20.0);
        for c in 0..ch { v[c] -= a * s[c]; }
    }
}

/// Crossfeed for stereo: each side gets a little of the other's low and mid range, slightly delayed, as from speakers in a
/// room. Eases hard-panned recordings. The same amount is taken off each side's own low range, so centred sound (vocals,
/// bass) keeps its level.
pub struct Crossfeed {
    c: f64,
    lp: [f64; 2],
    delay: Vec<[f64; 2]>,
    pos: usize,
}

/// Level of the fed-over signal (-9 dB) and its low-pass corner.
const XF_LEVEL: f64 = 0.35;
const XF_HZ: f64 = 700.0;
const XF_DELAY_MS: f64 = 0.3;

impl Crossfeed {
    pub fn new(rate: f64) -> Crossfeed {
        let n = ((XF_DELAY_MS * 0.001 * rate).round() as usize).max(1);
        Crossfeed { c: 1.0 - (-2.0 * PI * XF_HZ / rate).exp(), lp: [0.0; 2], delay: vec![[0.0; 2]; n + 1], pos: 0 }
    }

    pub fn clear(&mut self) { self.lp = [0.0; 2]; self.delay.iter_mut().for_each(|d| *d = [0.0; 2]); self.pos = 0; }

    pub fn process(&mut self, v: &mut [f64; MAX_CH]) {
        for c in 0..2 { self.lp[c] += (v[c] - self.lp[c]) * self.c; }
        let n = self.delay.len();
        let rd = (self.pos + 1) % n;
        let old = self.delay[rd];
        self.delay[self.pos] = self.lp;
        self.pos = rd;
        // Both the part taken off a side and the part fed to the other are delayed alike, so centred sound is unchanged.
        let (l, r) = (v[0], v[1]);
        v[0] = l - XF_LEVEL * old[0] + XF_LEVEL * old[1];
        v[1] = r - XF_LEVEL * old[1] + XF_LEVEL * old[0];
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(rate: f64, hz: f64, amp: f64, i: usize) -> f64 { amp * (2.0 * PI * hz * i as f64 / rate).sin() }
    fn amp_at(x: &[f64], hz: f64, rate: f64) -> f64 {
        let (mut re, mut im) = (0.0, 0.0);
        for (i, v) in x.iter().enumerate() {
            let p = 2.0 * PI * hz * i as f64 / rate;
            re += v * p.cos();
            im += v * p.sin();
        }
        2.0 * (re * re + im * im).sqrt() / x.len() as f64
    }
    fn db(v: f64) -> f64 { 20.0 * v.log10() }

    #[test]
    fn dynamic_bass_keeps_quiet_bass_and_eases_loud_bass() {
        let rate = 48000.0;
        let settle = |amp: f64| {
            let mut d = DynBass::new(rate);
            let mut m = 0.0;
            for i in 0..96000 { m = d.mix(tone(rate, 50.0, amp, i), 12.0); }
            m
        };
        assert!(settle(0.02) > 0.99, "quiet bass must keep the full lift");
        let loud = settle(0.7); // -3.1 dBFS: 8.9 dB over the threshold, so 4.45 dB of the 12 dB lift taken back
        let lift = db(1.0 + loud * (10f64.powf(12.0 / 20.0) - 1.0));
        assert!((lift - 7.55).abs() < 0.6, "loud bass lift {lift} dB");
        assert!(lift >= 12.0 * DYN_KEEP - 0.01);
    }

    #[test]
    fn de_esser_cuts_dominant_sibilance_only() {
        let rate = 48000.0;
        let run = |s_amp: f64, v_amp: f64| {
            let mut d = DeEsser::new(rate);
            let mut out = Vec::new();
            let mut inp = Vec::new();
            for i in 0..48000 {
                let x = tone(rate, 7000.0, s_amp, i) + tone(rate, 300.0, v_amp, i);
                let mut v = [0f64; MAX_CH];
                v[0] = x;
                d.process(&mut v, 1);
                if i >= 24000 { out.push(v[0]); inp.push(x); }
            }
            db(amp_at(&out, 7000.0, rate) / amp_at(&inp, 7000.0, rate))
        };
        assert!(run(0.3, 0.05) < -4.0, "a dominant s must be cut, got {}", run(0.3, 0.05));
        assert!(run(0.03, 0.5).abs() < 0.3, "treble under a loud voice must stay, got {}", run(0.03, 0.5));
        assert!(run(0.002, 0.0).abs() < 0.3, "very quiet treble must stay");
    }

    #[test]
    fn crossfeed_moves_panned_lows_and_keeps_the_centre() {
        let rate = 48000.0;
        let mut x = Crossfeed::new(rate);
        let (mut l, mut r, mut cl) = (Vec::new(), Vec::new(), Vec::new());
        let mut xc = Crossfeed::new(rate);
        for i in 0..48000 {
            let s = tone(rate, 200.0, 0.5, i);
            let mut v = [0f64; MAX_CH];
            v[0] = s; // hard left
            x.process(&mut v);
            let mut m = [0f64; MAX_CH];
            m[0] = s; m[1] = s; // centre
            xc.process(&mut m);
            if i >= 24000 { l.push(v[0]); r.push(v[1]); cl.push(m[0]); }
        }
        let right = db(amp_at(&r, 200.0, rate) / 0.5);
        assert!(right > -11.0 && right < -8.0, "fed over {right} dB");
        assert!(db(amp_at(&cl, 200.0, rate) / 0.5).abs() < 0.3, "centre must keep its level");
        assert!(amp_at(&l, 200.0, rate) < 0.5, "the panned side gives some of its lows away");
    }
}
