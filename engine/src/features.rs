//! Audio features for recommendations: a fixed-length description of how a song sounds (loudness, dynamics,
//! brightness, timbre, tempo, key), measured from mono PCM. Runs off the audio thread, on a few decoded windows of a file.
//! Own code; the FFT, mel filters, DCT and key profiles are textbook methods (Krumhansl-Kessler key profiles).

/// Bump when the layout or the maths change, so stored vectors are measured again.
pub const FEATURE_VERSION: i32 = 1;
pub const N_FEATURES: usize = 45;

// Layout of the output vector.
pub const LOUDNESS_DB: usize = 0;
pub const LOUDNESS_STD: usize = 1;
pub const CREST_DB: usize = 2;
pub const CENTROID_HZ: usize = 3;
pub const CENTROID_STD: usize = 4;
pub const ROLLOFF_HZ: usize = 5;
pub const FLATNESS: usize = 6;
pub const FLUX: usize = 7;
pub const ZCR: usize = 8;
/// Five shares of spectral energy: <150 Hz, 150-500, 500-2k, 2k-6k, >6k. They sum to 1.
pub const BANDS: usize = 9;
pub const TEMPO_BPM: usize = 14;
pub const TEMPO_CONF: usize = 15;
pub const ONSET_RATE: usize = 16;
/// Pitch class of the key, 0 = C.
pub const KEY: usize = 17;
/// 1 major, 0 minor.
pub const MODE: usize = 18;
pub const KEY_STRENGTH: usize = 19;
/// Twelve pitch-class shares from C, summing to 1.
pub const CHROMA: usize = 20;
/// Thirteen mel cepstral coefficients (timbre), averaged.
pub const MFCC: usize = 32;

const TARGET_RATE: u32 = 22_050;
const N: usize = 2048;
const HOP: usize = 512;
const MELS: usize = 26;
const N_MFCC: usize = 13;
const SILENT_DB: f32 = -60.0;

/// Measures [mono] at [rate] Hz. None when it is too short (under about two seconds) or silent.
pub fn analyze(mono: &[f32], rate: u32) -> Option<[f32; N_FEATURES]> {
    if rate < 8_000 {
        return None;
    }
    // Integer decimation to about 22 kHz with a box average: crude, but features above 11 kHz do not matter here.
    let factor = (rate / TARGET_RATE).max(1) as usize;
    let sr = rate as f32 / factor as f32;
    let x: Vec<f32> = mono.chunks_exact(factor).map(|c| c.iter().sum::<f32>() / factor as f32).collect();
    if x.len() < N + HOP * 40 {
        return None;
    }

    let fft = Fft::new(N);
    let window: Vec<f32> = (0..N).map(|i| 0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / N as f32).cos()).collect();
    let bins = N / 2 + 1;
    let hz = |k: usize| k as f32 * sr / N as f32;
    let mel_fb = mel_filters(MELS, bins, sr);
    let chroma_of: Vec<i32> = (0..bins)
        .map(|k| {
            let f = hz(k);
            if !(60.0..=5000.0).contains(&f) {
                -1
            } else {
                let midi = 69.0 + 12.0 * (f / 440.0).log2();
                (midi.round() as i32).rem_euclid(12)
            }
        })
        .collect();
    let band_edges = [150.0f32, 500.0, 2000.0, 6000.0];

    let mut re = vec![0f32; N];
    let mut im = vec![0f32; N];
    let mut prev_mag = vec![0f32; bins];
    let mut mag = vec![0f32; bins];

    let mut loud = Vec::new();
    let mut cent = Vec::new();
    let mut roll = 0f64;
    let mut flat = 0f64;
    let mut zcr = 0f64;
    let mut bands = [0f64; 5];
    let mut chroma = [0f64; 12];
    let mut mfcc = [0f64; N_MFCC];
    let mut onset_env = Vec::new();
    let mut voiced = 0usize;
    let mut peak = 0f32;
    let mut sum_sq = 0f64;

    for s in &x {
        peak = peak.max(s.abs());
        sum_sq += (*s as f64) * (*s as f64);
    }

    let mut start = 0;
    while start + N <= x.len() {
        let frame = &x[start..start + N];
        start += HOP;
        let ms: f32 = frame.iter().map(|v| v * v).sum::<f32>() / N as f32;
        let db = 10.0 * (ms + 1e-12).log10();
        for i in 0..N {
            re[i] = frame[i] * window[i];
            im[i] = 0.0;
        }
        fft.run(&mut re, &mut im);
        let mut total = 0f64;
        for k in 0..bins {
            mag[k] = (re[k] * re[k] + im[k] * im[k]).sqrt();
            total += (mag[k] * mag[k]) as f64;
        }
        // Onset strength: rise of log magnitude since the last frame, kept for every frame so tempo sees a steady clock.
        let mut flux = 0f32;
        for k in 1..bins {
            let a = (1.0 + 100.0 * mag[k]).ln();
            let b = (1.0 + 100.0 * prev_mag[k]).ln();
            if a > b {
                flux += a - b;
            }
        }
        onset_env.push(flux / bins as f32);
        std::mem::swap(&mut prev_mag, &mut mag);
        if db < SILENT_DB || total <= 0.0 {
            continue;
        }
        let p = &prev_mag; // this frame's magnitudes, after the swap
        voiced += 1;
        loud.push(db);
        let mut c = 0f64;
        let mut log_sum = 0f64;
        for k in 0..bins {
            let pw = (p[k] * p[k]) as f64;
            c += hz(k) as f64 * pw;
            log_sum += (pw + 1e-12).ln();
            let band = band_edges.iter().position(|&e| hz(k) < e).unwrap_or(4);
            bands[band] += pw / total;
            if chroma_of[k] >= 0 {
                chroma[chroma_of[k] as usize] += pw;
            }
        }
        cent.push((c / total) as f32);
        flat += (log_sum / bins as f64).exp() / (total / bins as f64);
        let mut acc = 0f64;
        let mut r = bins - 1;
        for k in 0..bins {
            acc += (p[k] * p[k]) as f64;
            if acc >= 0.85 * total {
                r = k;
                break;
            }
        }
        roll += hz(r) as f64;
        let mut crossings = 0;
        for i in 1..N {
            if (frame[i] >= 0.0) != (frame[i - 1] >= 0.0) {
                crossings += 1;
            }
        }
        zcr += crossings as f64 / N as f64;
        let mut logmel = [0f64; MELS];
        for (m, filt) in mel_fb.iter().enumerate() {
            let e: f64 = filt.iter().map(|&(k, w)| (p[k] * p[k]) as f64 * w as f64).sum();
            logmel[m] = (e + 1e-10).ln();
        }
        for (j, out) in mfcc.iter_mut().enumerate() {
            let mut v = 0f64;
            for (m, lm) in logmel.iter().enumerate() {
                v += lm * (std::f64::consts::PI * j as f64 * (m as f64 + 0.5) / MELS as f64).cos();
            }
            *out += v;
        }
    }
    if voiced < 20 {
        return None;
    }
    let vn = voiced as f64;
    let mut out = [0f32; N_FEATURES];
    let (lm, ls) = mean_std(&loud);
    out[LOUDNESS_DB] = lm;
    out[LOUDNESS_STD] = ls;
    let rms = (sum_sq / x.len() as f64).sqrt() as f32;
    out[CREST_DB] = 20.0 * (peak / rms.max(1e-9)).log10();
    let (cm, cs) = mean_std(&cent);
    out[CENTROID_HZ] = cm;
    out[CENTROID_STD] = cs;
    out[ROLLOFF_HZ] = (roll / vn) as f32;
    out[FLATNESS] = (flat / vn) as f32;
    out[FLUX] = onset_env.iter().sum::<f32>() / onset_env.len() as f32;
    out[ZCR] = (zcr / vn) as f32;
    for i in 0..5 {
        out[BANDS + i] = (bands[i] / vn) as f32;
    }
    let frame_rate = sr / HOP as f32;
    let (bpm, conf, rate_on) = tempo(&onset_env, frame_rate);
    out[TEMPO_BPM] = bpm;
    out[TEMPO_CONF] = conf;
    out[ONSET_RATE] = rate_on;
    let csum: f64 = chroma.iter().sum();
    let mut ch = [0f32; 12];
    for i in 0..12 {
        ch[i] = if csum > 0.0 { (chroma[i] / csum) as f32 } else { 0.0 };
        out[CHROMA + i] = ch[i];
    }
    let (key, major, strength) = key_of(&ch);
    out[KEY] = key as f32;
    out[MODE] = if major { 1.0 } else { 0.0 };
    out[KEY_STRENGTH] = strength;
    for j in 0..N_MFCC {
        out[MFCC + j] = (mfcc[j] / vn) as f32;
    }
    if out.iter().any(|v| !v.is_finite()) {
        return None;
    }
    Some(out)
}

fn mean_std(v: &[f32]) -> (f32, f32) {
    if v.is_empty() {
        return (0.0, 0.0);
    }
    let m = v.iter().sum::<f32>() / v.len() as f32;
    let var = v.iter().map(|x| (x - m) * (x - m)).sum::<f32>() / v.len() as f32;
    (m, var.sqrt())
}

/// Beats per minute (folded into 70-180), how sure (0-1), and onsets per second, from the onset envelope.
fn tempo(env: &[f32], frame_rate: f32) -> (f32, f32, f32) {
    let n = env.len();
    if n < 64 {
        return (0.0, 0.0, 0.0);
    }
    // Remove the slow trend (one second moving mean) and keep the rises.
    let w = (frame_rate as usize).max(1);
    let mut prefix = vec![0f32; n + 1];
    for i in 0..n {
        prefix[i + 1] = prefix[i] + env[i];
    }
    let d: Vec<f32> = (0..n)
        .map(|i| {
            let a = i.saturating_sub(w / 2);
            let b = (i + w / 2 + 1).min(n);
            (env[i] - (prefix[b] - prefix[a]) / (b - a) as f32).max(0.0)
        })
        .collect();
    let mean = d.iter().sum::<f32>() / n as f32;
    let sd = (d.iter().map(|v| (v - mean) * (v - mean)).sum::<f32>() / n as f32).sqrt();
    let mut onsets = 0;
    for i in 1..n - 1 {
        if d[i] > mean + sd && d[i] >= d[i - 1] && d[i] > d[i + 1] {
            onsets += 1;
        }
    }
    let rate_on = onsets as f32 * frame_rate / n as f32;
    let ac0: f32 = d.iter().map(|v| v * v).sum();
    if ac0 <= 0.0 {
        return (0.0, 0.0, rate_on);
    }
    let lag_of = |bpm: f32| 60.0 * frame_rate / bpm;
    let lo = lag_of(200.0).floor().max(1.0) as usize;
    let hi = (lag_of(50.0).ceil() as usize).min(n / 2);
    let ac = |lag: usize| -> f32 { (0..n - lag).map(|i| d[i] * d[i + lag]).sum::<f32>() / (n - lag) as f32 * n as f32 };
    let mut best = (0usize, 0f32, 0f32);
    for lag in lo..=hi {
        let raw = ac(lag);
        let bpm = 60.0 * frame_rate / lag as f32;
        // Listeners hear tempos near 120; a mild preference breaks octave ties.
        let prior = (-0.5 * ((bpm / 120.0).log2() / 0.9).powi(2)).exp();
        let score = raw * prior;
        if score > best.1 {
            best = (lag, score, raw);
        }
    }
    if best.0 == 0 {
        return (0.0, 0.0, rate_on);
    }
    // Parabolic refinement of the peak lag.
    let l = best.0;
    let mut lag = l as f32;
    if l > lo && l < hi {
        let (a, b, c) = (ac(l - 1), ac(l), ac(l + 1));
        let den = a - 2.0 * b + c;
        if den.abs() > 1e-12 {
            lag += (0.5 * (a - c) / den).clamp(-0.5, 0.5);
        }
    }
    let mut bpm = 60.0 * frame_rate / lag;
    while bpm < 70.0 {
        bpm *= 2.0;
    }
    while bpm > 180.0 {
        bpm /= 2.0;
    }
    (bpm, (best.2 / ac0).clamp(0.0, 1.0), rate_on)
}

/// Key from a chroma vector: the best of 24 rotated Krumhansl-Kessler profiles by correlation.
fn key_of(ch: &[f32; 12]) -> (usize, bool, f32) {
    const MAJOR: [f32; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
    const MINOR: [f32; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];
    let mut best = (0usize, true, f32::MIN);
    for tonic in 0..12 {
        for (major, prof) in [(true, &MAJOR), (false, &MINOR)] {
            let r = corr(ch, |i| prof[(i + 12 - tonic) % 12]);
            if r > best.2 {
                best = (tonic, major, r);
            }
        }
    }
    (best.0, best.1, best.2.max(0.0))
}

fn corr(a: &[f32; 12], b: impl Fn(usize) -> f32) -> f32 {
    let ma = a.iter().sum::<f32>() / 12.0;
    let mb = (0..12).map(&b).sum::<f32>() / 12.0;
    let (mut num, mut da, mut db) = (0f32, 0f32, 0f32);
    for i in 0..12 {
        let x = a[i] - ma;
        let y = b(i) - mb;
        num += x * y;
        da += x * x;
        db += y * y;
    }
    if da <= 0.0 || db <= 0.0 {
        0.0
    } else {
        num / (da * db).sqrt()
    }
}

/// Triangular mel filters as sparse (bin, weight) lists.
fn mel_filters(m: usize, bins: usize, sr: f32) -> Vec<Vec<(usize, f32)>> {
    let mel = |f: f32| 2595.0 * (1.0 + f / 700.0).log10();
    let inv = |m: f32| 700.0 * (10f32.powf(m / 2595.0) - 1.0);
    let top = mel((sr / 2.0).min(8000.0));
    let pts: Vec<f32> = (0..m + 2).map(|i| inv(top * i as f32 / (m + 1) as f32)).collect();
    let bin_hz = sr / ((bins - 1) * 2) as f32;
    (0..m)
        .map(|j| {
            let (a, b, c) = (pts[j], pts[j + 1], pts[j + 2]);
            (0..bins)
                .filter_map(|k| {
                    let f = k as f32 * bin_hz;
                    let w = if f > a && f <= b { (f - a) / (b - a) } else if f > b && f < c { (c - f) / (c - b) } else { 0.0 };
                    if w > 0.0 { Some((k, w)) } else { None }
                })
                .collect()
        })
        .collect()
}

/// In-place radix-2 complex FFT of a fixed power-of-two size.
struct Fft {
    n: usize,
    cos: Vec<f32>,
    sin: Vec<f32>,
}

impl Fft {
    fn new(n: usize) -> Self {
        let cos = (0..n / 2).map(|i| (2.0 * std::f64::consts::PI * i as f64 / n as f64).cos() as f32).collect();
        let sin = (0..n / 2).map(|i| (2.0 * std::f64::consts::PI * i as f64 / n as f64).sin() as f32).collect();
        Fft { n, cos, sin }
    }

    fn run(&self, re: &mut [f32], im: &mut [f32]) {
        let n = self.n;
        let mut j = 0;
        for i in 1..n {
            let mut bit = n >> 1;
            while j & bit != 0 {
                j ^= bit;
                bit >>= 1;
            }
            j |= bit;
            if i < j {
                re.swap(i, j);
                im.swap(i, j);
            }
        }
        let mut len = 2;
        while len <= n {
            let step = n / len;
            for s in (0..n).step_by(len) {
                for k in 0..len / 2 {
                    let (c, si) = (self.cos[k * step], -self.sin[k * step]);
                    let (a, b) = (s + k, s + k + len / 2);
                    let tr = re[b] * c - im[b] * si;
                    let ti = re[b] * si + im[b] * c;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
            len <<= 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::f32::consts::PI;

    const SR: u32 = 44_100;

    fn tone(freqs: &[f32], secs: f32) -> Vec<f32> {
        let n = (SR as f32 * secs) as usize;
        (0..n).map(|i| freqs.iter().map(|f| (2.0 * PI * f * i as f32 / SR as f32).sin()).sum::<f32>() * 0.2).collect()
    }

    fn noise(secs: f32) -> Vec<f32> {
        let mut s = 0x1234_5678u32;
        (0..(SR as f32 * secs) as usize)
            .map(|_| {
                s ^= s << 13;
                s ^= s >> 17;
                s ^= s << 5;
                (s as f32 / u32::MAX as f32 - 0.5) * 0.4
            })
            .collect()
    }

    #[test]
    fn fft_finds_a_bin() {
        let fft = Fft::new(64);
        let mut re: Vec<f32> = (0..64).map(|i| (2.0 * PI * 5.0 * i as f32 / 64.0).cos()).collect();
        let mut im = vec![0f32; 64];
        fft.run(&mut re, &mut im);
        let mags: Vec<f32> = (0..33).map(|k| (re[k] * re[k] + im[k] * im[k]).sqrt()).collect();
        let peak = (0..33).max_by(|a, b| mags[*a].total_cmp(&mags[*b])).unwrap();
        assert_eq!(peak, 5);
        assert!((mags[5] - 32.0).abs() < 0.01);
    }

    #[test]
    fn silence_and_short_give_nothing() {
        assert!(analyze(&vec![0.0; SR as usize * 10], SR).is_none());
        assert!(analyze(&tone(&[440.0], 0.5), SR).is_none());
    }

    #[test]
    fn a_tone_lands_on_a() {
        let f = analyze(&tone(&[440.0], 8.0), SR).unwrap();
        let peak = (0..12).max_by(|a, b| f[CHROMA + a].total_cmp(&f[CHROMA + b])).unwrap();
        assert_eq!(peak, 9, "A is pitch class 9");
        assert!((f[CENTROID_HZ] - 440.0).abs() < 40.0, "centroid {}", f[CENTROID_HZ]);
        assert!(f[BANDS + 1] > 0.9, "440 Hz sits in the 150-500 band");
    }

    #[test]
    fn c_major_chord_reads_as_c_major() {
        let f = analyze(&tone(&[261.63, 329.63, 392.0, 523.25], 8.0), SR).unwrap();
        assert_eq!(f[KEY] as usize, 0);
        assert_eq!(f[MODE], 1.0);
    }

    #[test]
    fn a_minor_chord_reads_as_a_minor() {
        let f = analyze(&tone(&[220.0, 261.63, 329.63, 440.0], 8.0), SR).unwrap();
        assert_eq!(f[KEY] as usize, 9);
        assert_eq!(f[MODE], 0.0);
    }

    #[test]
    fn noise_is_flatter_and_brighter_than_a_tone() {
        let n = analyze(&noise(8.0), SR).unwrap();
        let t = analyze(&tone(&[440.0], 8.0), SR).unwrap();
        assert!(n[FLATNESS] > 10.0 * t[FLATNESS], "{} vs {}", n[FLATNESS], t[FLATNESS]);
        assert!(n[CENTROID_HZ] > 2000.0);
    }

    fn clicks(bpm: f32, secs: f32) -> Vec<f32> {
        let mut x = vec![0f32; (SR as f32 * secs) as usize];
        let period = 60.0 / bpm * SR as f32;
        let mut t = 0f32;
        let mut s = 99u32;
        while (t as usize) < x.len() {
            for i in 0..400 {
                if let Some(v) = x.get_mut(t as usize + i) {
                    s ^= s << 13;
                    s ^= s >> 17;
                    s ^= s << 5;
                    *v = (s as f32 / u32::MAX as f32 - 0.5) * (1.0 - i as f32 / 400.0);
                }
            }
            t += period;
        }
        // A quiet bed so frames between clicks are not silent.
        for (i, v) in x.iter_mut().enumerate() {
            *v += 0.02 * (2.0 * PI * 110.0 * i as f32 / SR as f32).sin();
        }
        x
    }

    #[test]
    fn click_tracks_give_their_tempo() {
        for bpm in [90.0f32, 120.0, 140.0] {
            let f = analyze(&clicks(bpm, 20.0), SR).unwrap();
            assert!((f[TEMPO_BPM] - bpm).abs() < 3.0, "wanted {bpm}, got {}", f[TEMPO_BPM]);
            assert!((f[ONSET_RATE] - bpm / 60.0).abs() < 0.5, "onsets {}", f[ONSET_RATE]);
        }
    }

    #[test]
    fn louder_is_louder_and_rate_does_not_matter_much() {
        let quiet: Vec<f32> = tone(&[440.0], 8.0).iter().map(|v| v * 0.1).collect();
        let q = analyze(&quiet, SR).unwrap();
        let l = analyze(&tone(&[440.0], 8.0), SR).unwrap();
        assert!((l[LOUDNESS_DB] - q[LOUDNESS_DB] - 20.0).abs() < 0.5);
        let at48: Vec<f32> = (0..48_000 * 8).map(|i| (2.0 * PI * 440.0 * i as f32 / 48_000.0).sin() * 0.2).collect();
        let f48 = analyze(&at48, 48_000).unwrap();
        assert!((f48[CENTROID_HZ] - l[CENTROID_HZ]).abs() < 30.0);
    }
}
