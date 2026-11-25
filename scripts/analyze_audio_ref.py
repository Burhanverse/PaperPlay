#!/usr/bin/env python3
"""
Dev-only offline reference analyzer and synthetic fixture generator for AudioTap.
Uses pure Python + numpy (no copyrighted audio, not shipped).

Usage:
  python3 scripts/analyze_audio_ref.py --generate-fixtures <dir>
  python3 scripts/analyze_audio_ref.py --analyze <wav>
  python3 scripts/analyze_audio_ref.py --compare <kotlin_json>
"""

import argparse
import json
import math
import os
import struct
import sys
import wave
import numpy as np

BANDS = 32
N_LONG = 4096
N_SHORT = 1024
HOP = 512
MAX_RATE_BEFORE_DECIM = 56_000
MIN_HZ = 42.0
MAX_HZ = 14_500.0
LOW_SPLIT_HZ = 550.0
BASS_LPF_HZ = 140.0
SILENCE_FLOOR = 0.00045
REF_MIN = 0.0018
DB_WINDOW = 48.0


def band_centers():
    return np.array(
        [MIN_HZ * ((MAX_HZ / MIN_HZ) ** (b / (BANDS - 1))) for b in range(BANDS)],
        dtype=np.float64,
    )


def band_tilt(centers):
    tilt = np.ones(BANDS, dtype=np.float64)
    for b, f in enumerate(centers):
        if f < 80.0:
            u = min(max((f - 42.0) / 38.0, 0.0), 1.0)
            tilt[b] = 0.85 + 0.15 * u
        elif f > 1000.0:
            u = min(max(math.log10(f / 1000.0) / math.log10(14.5), 0.0), 1.0)
            tilt[b] = 1.0 + 0.65 * u
    return tilt


def read_wav_mono(path):
    with wave.open(path, "rb") as wf:
        ch = wf.getnchannels()
        sw = wf.getsampwidth()
        sr = wf.getframerate()
        nframes = wf.getnframes()
        raw = wf.readframes(nframes)
    if sw == 2:
        data = np.frombuffer(raw, dtype=np.int16).astype(np.float64) / 32768.0
    elif sw == 3:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
        vals = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
        vals = np.where(vals >= 0x800000, vals - 0x1000000, vals)
        data = vals.astype(np.float64) / 8388608.0
    elif sw == 4:
        data = np.frombuffer(raw, dtype=np.int32).astype(np.float64) / 2147483648.0
    else:
        raise ValueError(f"Unsupported sample width {sw}")
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    return sr, data


def write_wav_pcm16(path, sr, samples):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    clipped = np.clip(samples, -1.0, 1.0)
    pcm = (clipped * 32767.0).astype("<i2")
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm.tobytes())


def generate_fixtures(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    rng = np.random.default_rng(42)
    files = {}

    # 1. Click tracks at 120 BPM across multiple sample rates
    for sr in (44100, 48000, 96000, 192000):
        dur = 3.0
        n = int(sr * dur)
        t = np.arange(n) / sr
        sig = np.zeros(n, dtype=np.float64)
        for beat in range(6):
            idx = int((0.25 + beat * 0.5) * sr)
            click_len = int(0.025 * sr)
            if idx + click_len < n:
                tt = np.arange(click_len) / sr
                env = np.exp(-tt * 180.0)
                sig[idx:idx + click_len] += 0.9 * env * (
                    0.65 * np.sin(2 * np.pi * 85.0 * tt) + 0.35 * np.sin(2 * np.pi * 1600.0 * tt)
                )
        p = os.path.join(out_dir, f"click_120bpm_{sr}.wav")
        write_wav_pcm16(p, sr, sig)
        files[f"click_120bpm_{sr}"] = p

    # 2. Pure sines (100 Hz, 1 kHz)
    sr = 44100
    t1 = np.arange(int(sr * 1.5)) / sr
    write_wav_pcm16(os.path.join(out_dir, "sine_100hz.wav"), sr, 0.5 * np.sin(2 * np.pi * 100.0 * t1))
    write_wav_pcm16(os.path.join(out_dir, "sine_1khz.wav"), sr, 0.5 * np.sin(2 * np.pi * 1000.0 * t1))
    files["sine_100hz"] = os.path.join(out_dir, "sine_100hz.wav")
    files["sine_1khz"] = os.path.join(out_dir, "sine_1khz.wav")

    # 3. Log sweep 42 Hz -> 14 kHz
    dur_sw = 4.0
    n_sw = int(sr * dur_sw)
    t_sw = np.arange(n_sw) / sr
    f0, f1 = 42.0, 14000.0
    k = math.log(f1 / f0) / dur_sw
    phase = 2 * np.pi * f0 * (np.exp(k * t_sw) - 1.0) / k
    write_wav_pcm16(os.path.join(out_dir, "log_sweep.wav"), sr, 0.5 * np.sin(phase))
    files["log_sweep"] = os.path.join(out_dir, "log_sweep.wav")

    # 4. Silence & Pink-like noise
    write_wav_pcm16(os.path.join(out_dir, "silence.wav"), sr, np.zeros(int(sr * 1.5)))
    files["silence"] = os.path.join(out_dir, "silence.wav")

    white = rng.normal(0.0, 1.0, int(sr * 2.0))
    freqs = np.fft.rfftfreq(len(white), 1.0 / sr)
    spec = np.fft.rfft(white)
    spec[1:] /= np.sqrt(freqs[1:])
    pink = np.fft.irfft(spec, n=len(white))
    pink = 0.35 * pink / (np.max(np.abs(pink)) + 1e-9)
    write_wav_pcm16(os.path.join(out_dir, "pink_noise.wav"), sr, pink)
    files["pink_noise"] = os.path.join(out_dir, "pink_noise.wav")

    # 5. Three synthetic music-like loops (kick, bassline, snare, hi-hat, chords)
    # Loop A: Club 124 BPM (4.0 s)
    dur_a = 4.0
    na = int(sr * dur_a)
    ta = np.arange(na) / sr
    beat_period = 60.0 / 124.0
    sig_a = np.zeros(na, dtype=np.float64)
    # Chord pad (220, 277.18, 329.63 Hz)
    sig_a += 0.12 * (np.sin(2 * np.pi * 220.0 * ta) + np.sin(2 * np.pi * 277.18 * ta) + np.sin(2 * np.pi * 329.63 * ta)) / 3.0
    # Kick on every beat + bassline off-beat
    for i in range(int(dur_a / beat_period)):
        s0 = int(i * beat_period * sr)
        klen = min(int(0.18 * sr), na - s0)
        if klen > 0:
            tt = np.arange(klen) / sr
            freq_k = 55.0 + 95.0 * np.exp(-tt * 35.0)
            ph = 2 * np.pi * np.cumsum(freq_k) / sr
            sig_a[s0:s0 + klen] += 0.65 * np.exp(-tt * 14.0) * np.sin(ph)
        # Off-beat bass
        b0 = s0 + int(0.5 * beat_period * sr)
        blen = min(int(0.16 * sr), na - b0)
        if blen > 0:
            tt = np.arange(blen) / sr
            sig_a[b0:b0 + blen] += 0.30 * np.exp(-tt * 10.0) * np.sin(2 * np.pi * 110.0 * tt)
        # Snare on 2 & 4
        if i % 2 == 1:
            slen = min(int(0.12 * sr), na - s0)
            if slen > 0:
                tt = np.arange(slen) / sr
                sig_a[s0:s0 + slen] += 0.32 * np.exp(-tt * 28.0) * (
                    0.5 * rng.normal(0.0, 1.0, slen) + 0.5 * np.sin(2 * np.pi * 210.0 * tt)
                )
        # Hi-hat 8th notes
        for sub in (0.0, 0.5):
            h0 = s0 + int(sub * beat_period * sr)
            hlen = min(int(0.04 * sr), na - h0)
            if hlen > 0:
                tt = np.arange(hlen) / sr
                sig_a[h0:h0 + hlen] += 0.16 * np.exp(-tt * 80.0) * np.sin(2 * np.pi * 8200.0 * tt)
    write_wav_pcm16(os.path.join(out_dir, "loop_club_124bpm.wav"), sr, np.clip(sig_a, -0.98, 0.98))
    files["loop_club_124bpm"] = os.path.join(out_dir, "loop_club_124bpm.wav")

    # Loop B: Pop 105 BPM (4.0 s)
    dur_b = 4.0
    nb = int(sr * dur_b)
    tb = np.arange(nb) / sr
    bp_b = 60.0 / 105.0
    sig_b = 0.14 * np.sin(2 * np.pi * 440.0 * tb) * (0.6 + 0.4 * np.sin(2 * np.pi * 1.5 * tb))
    for i in range(int(dur_b / bp_b)):
        s0 = int(i * bp_b * sr)
        klen = min(int(0.15 * sr), nb - s0)
        if klen > 0:
            tt = np.arange(klen) / sr
            sig_b[s0:s0 + klen] += 0.55 * np.exp(-tt * 16.0) * np.sin(2 * np.pi * 68.0 * tt)
        if i % 2 == 1:
            slen = min(int(0.10 * sr), nb - s0)
            if slen > 0:
                tt = np.arange(slen) / sr
                sig_b[s0:s0 + slen] += 0.28 * np.exp(-tt * 24.0) * np.sin(2 * np.pi * 1850.0 * tt)
    write_wav_pcm16(os.path.join(out_dir, "loop_pop_105bpm.wav"), sr, np.clip(sig_b, -0.98, 0.98))
    files["loop_pop_105bpm"] = os.path.join(out_dir, "loop_pop_105bpm.wav")

    # Loop C: Dynamics (0.5 s loud kick -> 2.5 s quiet 1 kHz melody)
    dur_c = 3.0
    nc = int(sr * dur_c)
    tc = np.arange(nc) / sr
    sig_c = np.where(tc < 0.5, 0.9 * np.sin(2 * np.pi * 65.0 * tc), 0.08 * np.sin(2 * np.pi * 1000.0 * tc))
    write_wav_pcm16(os.path.join(out_dir, "loop_dynamics.wav"), sr, sig_c)
    files["loop_dynamics"] = os.path.join(out_dir, "loop_dynamics.wav")

    return files


def analyze_samples(sr, samples):
    d = 1
    while sr // d > MAX_RATE_BEFORE_DECIM:
        d *= 2
    eff_rate = sr // d
    if d > 1:
        trim = (len(samples) // d) * d
        samples = samples[:trim].reshape(-1, d).mean(axis=1)

    centers = band_centers()
    tilt = band_tilt(centers)

    # Hann (4096) & Hann (1024)
    idx_l = np.arange(N_LONG, dtype=np.float64)
    win_l = 0.5 - 0.5 * np.cos(2.0 * math.pi * idx_l / (N_LONG - 1))
    norm_l = 4.0 / (np.sum(win_l) ** 2)

    idx_s = np.arange(N_SHORT, dtype=np.float64)
    win_s = 0.5 - 0.5 * np.cos(2.0 * math.pi * idx_s / (N_SHORT - 1))
    norm_s = 4.0 / (np.sum(win_s) ** 2)

    # Triangular filterbank
    ratio = (MAX_HZ / MIN_HZ) ** (1.0 / (BANDS - 1))
    nyq = eff_rate * 0.495
    filters = []
    for b in range(BANDS):
        f_c = centers[b]
        f_l = MIN_HZ / ratio if b == 0 else centers[b - 1]
        f_r = min(MAX_HZ * ratio if b == BANDS - 1 else centers[b + 1], nyq)
        use_long = f_c <= LOW_SPLIT_HZ
        fft_n = N_LONG if use_long else N_SHORT
        max_bin = fft_n // 2
        df = eff_rate / fft_n
        b_start = max(1, min(max_bin - 1, int(f_l / df)))
        b_end = max(b_start + 1, min(max_bin + 1, int(f_r / df) + 1))
        c_bin = max(1, min(max_bin, int(f_c / df)))
        b_start = min(b_start, c_bin)
        b_end = max(b_end, min(max_bin + 1, c_bin + 1))
        left_span = max(f_c - f_l, df * 0.5)
        right_span = max(f_r - f_c, df * 0.5)
        weights = np.zeros(b_end - b_start, dtype=np.float64)
        for idx, bin_i in enumerate(range(b_start, b_end)):
            freq = bin_i * df
            if f_l <= freq <= f_r:
                w = (freq - f_l) / left_span if freq <= f_c else (f_r - freq) / right_span
                weights[idx] = max(0.0, min(1.0, w))
        if np.sum(weights) <= 1e-4:
            weights[max(0, min(len(weights) - 1, c_bin - b_start))] = 1.0
        filters.append((use_long, b_start, b_end, weights))

    # 2nd-order Butterworth 140 Hz LPF
    k = math.tan(math.pi * BASS_LPF_HZ / eff_rate)
    norm = 1.0 / (1.0 + math.sqrt(2.0) * k + k * k)
    b0 = k * k * norm
    b1 = 2.0 * b0
    b2 = b0
    a1 = 2.0 * (k * k - 1.0) * norm
    a2 = (1.0 - math.sqrt(2.0) * k + k * k) * norm
    bass_atk = 1.0 - math.exp(-1.0 / (0.008 * eff_rate))
    bass_rel = 1.0 - math.exp(-1.0 / (0.075 * eff_rate))

    hop_dt = HOP / eff_rate
    agc_atk = 1.0 - math.exp(-hop_dt / 0.025)
    agc_rel = 1.0 - math.exp(-hop_dt / 1.6)

    ring = np.zeros(N_LONG, dtype=np.float64)
    ring_pos = 0
    since_hop = 0
    total_eff = 0
    x1 = x2 = y1 = y2 = 0.0
    bass_env_raw = 0.0
    bass_ref = REF_MIN
    reg_ref = [REF_MIN, REF_MIN, REF_MIN]
    global_ref = REF_MIN

    prev_bands = np.zeros(BANDS, dtype=np.float64)
    prev_bass = 0.0
    flux_mean = 0.0
    flux_dev = 0.0
    cooldown = 0.0
    onset_val = 0.0

    band_frames = []
    bass_frames = []
    onset_frames = []
    beat_times_sec = []

    for s in samples:
        ring[ring_pos] = s
        ring_pos = (ring_pos + 1) & (N_LONG - 1)
        total_eff += 1
        since_hop += 1

        lp = b0 * s + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, s
        y2, y1 = y1, lp
        abs_lp = abs(lp)
        bass_env_raw += (abs_lp - bass_env_raw) * (bass_atk if abs_lp > bass_env_raw else bass_rel)

        if total_eff < N_SHORT or since_hop < HOP:
            continue
        since_hop = 0

        # Extract circular buffers in chronological order
        buf_l = np.concatenate((ring[ring_pos:], ring[:ring_pos]))
        buf_s = buf_l[-N_SHORT:]

        spec_l = np.fft.rfft(buf_l * win_l)
        pow_l = (spec_l.real ** 2 + spec_l.imag ** 2) * norm_l
        spec_s = np.fft.rfft(buf_s * win_s)
        pow_s = (spec_s.real ** 2 + spec_s.imag ** 2) * norm_s

        raw_b = np.zeros(BANDS, dtype=np.float64)
        for b, (use_long, b_start, b_end, weights) in enumerate(filters):
            p_arr = pow_l if use_long else pow_s
            raw_b[b] = math.sqrt(max(0.0, float(np.dot(weights, p_arr[b_start:b_end])))) * tilt[b]

        g_peak = float(np.max(raw_b))
        l_peak = float(np.max(raw_b[:8]))
        m_peak = float(np.max(raw_b[8:20]))
        h_peak = float(np.max(raw_b[20:]))

        global_ref = max(global_ref + (g_peak - global_ref) * (agc_atk if g_peak > global_ref else agc_rel), REF_MIN)
        for g_idx, pk in enumerate((l_peak, m_peak, h_peak)):
            reg_ref[g_idx] = max(reg_ref[g_idx] + (pk - reg_ref[g_idx]) * (agc_atk if pk > reg_ref[g_idx] else agc_rel), REF_MIN)

        out_b = np.zeros(BANDS, dtype=np.float64)
        for b in range(BANDS):
            if raw_b[b] >= SILENCE_FLOOR:
                reg = reg_ref[0 if b < 8 else (1 if b < 20 else 2)]
                c = max(0.65 * global_ref + 0.35 * reg, REF_MIN)
                db = 20.0 * math.log10(raw_b[b] / c)
                x = min(max((db + DB_WINDOW) / DB_WINDOW, 0.0), 1.0)
                out_b[b] = x ** 1.25

        bass_ref = max(bass_ref + (bass_env_raw - bass_ref) * (agc_atk if bass_env_raw > bass_ref else agc_rel), 0.002)
        if bass_env_raw < 0.0004:
            bass_norm = 0.0
        else:
            db = 20.0 * math.log10(bass_env_raw / max(bass_ref, 0.002))
            x = min(max((db + DB_WINDOW) / DB_WINDOW, 0.0), 1.0)
            bass_norm = x ** 1.15

        # Hop onset detector
        if cooldown > 0.0:
            cooldown = max(0.0, cooldown - hop_dt)
        flux = 0.0
        wsum = 0.0
        for b in range(BANDS):
            diff = out_b[b] - prev_bands[b]
            prev_bands[b] = out_b[b]
            w = 2.4 if b < 8 else (1.4 if b < 16 else (1.0 if b < 24 else 0.5))
            wsum += w
            if diff > 0.0:
                flux += diff * w
        flux /= wsum
        bass_diff = max(0.0, bass_norm - prev_bass)
        prev_bass = bass_norm
        comb = flux + bass_diff * 0.45

        alpha_f = 1.0 - math.exp(-hop_dt / 0.40)
        flux_mean += (comb - flux_mean) * alpha_f
        flux_dev += (abs(comb - flux_mean) - flux_dev) * alpha_f
        thresh = flux_mean + 1.65 * flux_dev
        if comb > thresh and comb > 0.025 and cooldown <= 0.0:
            onset_val = min(1.0, max(0.45, (comb - thresh) * 4.5 + 0.55))
            cooldown = 0.13
            beat_times_sec.append(total_eff / eff_rate)
        else:
            onset_val *= math.exp(-hop_dt / 0.11)
            if onset_val < 0.001:
                onset_val = 0.0

        band_frames.append(out_b.tolist())
        bass_frames.append(float(bass_norm))
        onset_frames.append(float(onset_val))

    return {
        "sample_rate": sr,
        "eff_rate": eff_rate,
        "hop_count": len(band_frames),
        "bands": band_frames,
        "bass": bass_frames,
        "onset": onset_frames,
        "beats_sec": beat_times_sec,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--generate-fixtures", metavar="DIR")
    parser.add_argument("--analyze", metavar="WAV")
    parser.add_argument("--out", metavar="JSON")
    args = parser.parse_args()

    if args.generate_fixtures:
        files = generate_fixtures(args.generate_fixtures)
        summary = {}
        for name, path in files.items():
            sr, samples = read_wav_mono(path)
            summary[name] = analyze_samples(sr, samples)
            summary[name]["wav"] = path
        payload = json.dumps(summary)
        if args.out:
            with open(args.out, "w") as f:
                f.write(payload)
        else:
            print(payload)
    elif args.analyze:
        sr, samples = read_wav_mono(args.analyze)
        res = analyze_samples(sr, samples)
        payload = json.dumps(res)
        if args.out:
            with open(args.out, "w") as f:
                f.write(payload)
        else:
            print(payload)
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
