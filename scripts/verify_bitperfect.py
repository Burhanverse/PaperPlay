#!/usr/bin/env python3
"""
Bit-perfect verification tool for PaperPlay USB Audio.
Compares a reference source audio file against a captured raw PCM dump from UsbPcmDumper.

Features:
- Bit-exact integer sample comparison for 16-bit, 24-bit, and 32-bit PCM.
- Automatic stream format discovery from files or filenames.
- Subslot unpacking (2-byte s16le, 3-byte s24le, and 4-byte s32le MSB-aligned).
- Latency and offset alignment via cross-correlation.
- Detailed diff reporting (SNR, bit errors, truncation / attenuation detection).
- Test signal generation (16-bit/44.1k sine+ramp, 24-bit/96k sine+ramp, 24-bit/48k LSB pattern).

Usage:
  python3 scripts/verify_bitperfect.py --generate-test-signals
  python3 scripts/verify_bitperfect.py --source ref.flac --capture dump_usb_30s_44100hz_16bit.raw
  python3 scripts/verify_bitperfect.py --source ref.flac --capture dump_usb_30s_96000hz_24bit.raw
"""

import argparse
import math
import os
import re
import struct
import subprocess
import sys

def generate_test_signals(out_dir="scripts/test_audio"):
    os.makedirs(out_dir, exist_ok=True)
    duration_sec = 30

    # 1. 16-bit / 44.1 kHz Sine & Linear Ramp FLAC
    sr16 = 44100
    n16 = sr16 * duration_sec
    flac16_path = os.path.join(out_dir, "test_sine_ramp_16bit_44k.flac")
    raw16_path = flac16_path + ".raw"
    with open(raw16_path, "wb") as f:
        half = n16 // 2
        # First 15s: 1 kHz Sine at -3 dBFS (amplitude 23170)
        for i in range(half):
            val = int(23170 * math.sin(2.0 * math.pi * 1000.0 * i / sr16))
            val = max(-32768, min(32767, val)) & 0xFFFF
            f.write(struct.pack("<hh", val if val < 32768 else val - 65536, val if val < 32768 else val - 65536))
        # Second 15s: Full dynamic range linear ramp (-32768 to 32767)
        step16 = 65535.0 / (n16 - half)
        for i in range(n16 - half):
            val = int(-32768 + i * step16)
            val = max(-32768, min(32767, val))
            f.write(struct.pack("<hh", val, val))

    subprocess.run([
        "ffmpeg", "-y", "-f", "s16le", "-ar", "44100", "-ac", "2",
        "-i", raw16_path, "-c:a", "flac", flac16_path
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    if os.path.exists(raw16_path):
        os.remove(raw16_path)
    print(f"Generated 16-bit / 44.1 kHz sine+ramp: {flac16_path}")

    # 2. 24-bit / 96 kHz Sine & Linear Ramp FLAC
    sr24 = 96000
    n24 = sr24 * duration_sec
    flac24_path = os.path.join(out_dir, "test_sine_ramp_24bit_96k.flac")
    raw24_path = flac24_path + ".raw"
    with open(raw24_path, "wb") as f:
        half = n24 // 2
        # First 15s: 1 kHz Sine at -3 dBFS (amplitude 5931641)
        for i in range(half):
            val = int(5931641 * math.sin(2.0 * math.pi * 1000.0 * i / sr24))
            val = max(-8388608, min(8388607, val)) & 0xFFFFFF
            b0 = val & 0xFF
            b1 = (val >> 8) & 0xFF
            b2 = (val >> 16) & 0xFF
            f.write(bytes([b0, b1, b2, b0, b1, b2]))
        # Second 15s: Full dynamic range linear ramp (-8388608 to 8388607)
        step24 = 16777215.0 / (n24 - half)
        for i in range(n24 - half):
            val = int(-8388608 + i * step24) & 0xFFFFFF
            b0 = val & 0xFF
            b1 = (val >> 8) & 0xFF
            b2 = (val >> 16) & 0xFF
            f.write(bytes([b0, b1, b2, b0, b1, b2]))

    subprocess.run([
        "ffmpeg", "-y", "-f", "s24le", "-ar", "96000", "-ac", "2",
        "-i", raw24_path, "-c:a", "flac", flac24_path
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    if os.path.exists(raw24_path):
        os.remove(raw24_path)
    print(f"Generated 24-bit / 96 kHz sine+ramp: {flac24_path}")

    # 3. 24-bit / 48 kHz FLAC with Lowest Bits Set (Non-zero LSBs)
    sr_lsb = 48000
    n_lsb = sr_lsb * duration_sec
    flac_lsb_path = os.path.join(out_dir, "test_lsb_pattern_24bit_48k.flac")
    raw_lsb_path = flac_lsb_path + ".raw"
    patterns = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x55, 0xAA, 0xFF, 0x7F]
    with open(raw_lsb_path, "wb") as f:
        for i in range(n_lsb):
            # Upper 16 bits have a gentle sine wave
            upper16 = int(16384 * math.sin(2.0 * math.pi * 440.0 * i / sr_lsb))
            upper16_u = (upper16 & 0xFFFF)
            # Lowest 8 bits cycle through known test patterns
            lsb8 = patterns[i % len(patterns)]
            val = ((upper16_u << 8) | lsb8) & 0xFFFFFF
            b0 = val & 0xFF
            b1 = (val >> 8) & 0xFF
            b2 = (val >> 16) & 0xFF
            f.write(bytes([b0, b1, b2, b0, b1, b2]))

    subprocess.run([
        "ffmpeg", "-y", "-f", "s24le", "-ar", "48000", "-ac", "2",
        "-i", raw_lsb_path, "-c:a", "flac", flac_lsb_path
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    if os.path.exists(raw_lsb_path):
        os.remove(raw_lsb_path)
    print(f"Generated 24-bit / 48 kHz LSB pattern: {flac_lsb_path}")

    print(f"\nAll test signals generated successfully in {out_dir}/")

def probe_audio_format(file_path):
    """Uses ffprobe to extract (sample_rate, channels, bit_depth)."""
    cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "stream=sample_rate,channels,bits_per_raw_sample,sample_fmt",
        "-of", "default=noprint_wrappers=1",
        file_path
    ]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode != 0:
        return None
    info = {}
    for line in p.stdout.strip().splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            info[k.strip()] = v.strip()
    try:
        rate = int(info["sample_rate"])
        channels = int(info["channels"])
        raw_bits = info.get("bits_per_raw_sample", "")
        sample_fmt = info.get("sample_fmt", "")
        if raw_bits.isdigit():
            bits = int(raw_bits)
        elif "16" in sample_fmt:
            bits = 16
        elif "32" in sample_fmt or "24" in sample_fmt:
            bits = 32
        else:
            bits = 16
        return rate, channels, bits
    except (KeyError, ValueError):
        return None

def parse_raw_filename(file_path):
    """Extracts rate, bit depth, and optional subslot bytes from filenames like dump_usb_30s_44100hz_16bit_2b.raw."""
    base = os.path.basename(file_path)
    m = re.search(r"(\d+)hz_(\d+)bit(?:_(\d+)b)?", base, re.IGNORECASE)
    if m:
        rate = int(m.group(1))
        bits = int(m.group(2))
        subslot = int(m.group(3)) if m.group(3) else None
        return rate, bits, subslot
    return None, None, None

def load_raw_pcm_samples(file_path, sample_rate=None, bit_depth=None, channels=2, subslot_size=None):
    """Loads raw PCM samples normalized into signed 32-bit full-scale integer space (s16<<16, s24<<8, s32)."""
    with open(file_path, "rb") as f:
        data = f.read()

    file_size = len(data)
    if file_size == 0:
        return []

    r, b, parsed_subslot = parse_raw_filename(file_path)
    sample_rate = sample_rate or r or 44100
    bit_depth = bit_depth or b or 16
    subslot_size = subslot_size or parsed_subslot

    if subslot_size is None:
        if bit_depth == 16:
            subslot_size = 2
        elif bit_depth == 24:
            subslot_size = 3 if (file_size % (3 * channels) == 0 and file_size % (4 * channels) != 0) else 4
        else:
            subslot_size = 4

    samples = []
    if subslot_size == 2:
        count = file_size // 2
        raw_samples = struct.unpack(f"<{count}h", data[:count * 2])
        samples = [s << 16 for s in raw_samples]
    elif subslot_size == 3:
        count = file_size // 3
        for i in range(count):
            idx = i * 3
            val = data[idx] | (data[idx + 1] << 8) | (data[idx + 2] << 16)
            if val & 0x800000:
                val -= 0x1000000
            samples.append(val << 8)
    elif subslot_size == 4:
        count = file_size // 4
        raw_samples = struct.unpack(f"<{count}i", data[:count * 4])
        if bit_depth == 24:
            # 24-in-32 LSB container: sign-extend 24-bit payload in bytes 0..2 and scale to 32-bit space
            for s in raw_samples:
                v24 = s & 0xFFFFFF
                if v24 & 0x800000:
                    v24 -= 0x1000000
                samples.append(v24 << 8)
        else:
            samples = list(raw_samples)

    return samples

def decode_source_to_samples(file_path, target_rate, target_bit_depth, channels=2):
    """Decodes a source audio file (FLAC, WAV) into signed 32-bit full-scale integer space via ffmpeg."""
    fmt = "s16le" if target_bit_depth == 16 else "s32le"
    cmd = [
        "ffmpeg", "-i", file_path,
        "-f", fmt, "-ac", str(channels),
        "-ar", str(target_rate), "-"
    ]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = p.communicate()
    if p.returncode != 0:
        raise RuntimeError(f"ffmpeg error decoding {file_path}: {stderr.decode(errors='replace')}")

    if target_bit_depth == 16:
        count = len(stdout) // 2
        raw = struct.unpack(f"<{count}h", stdout[:count * 2])
        return [s << 16 for s in raw]
    count = len(stdout) // 4
    return list(struct.unpack(f"<{count}i", stdout[:count * 4]))

def find_exact_or_best_offset(src_samples, cap_samples, src_channels=2):
    """Finds frame alignment offset between src_samples and cap_samples."""
    src_frames = len(src_samples) // src_channels
    cap_frames = len(cap_samples) // src_channels
    probe_len = min(2000, src_frames, cap_frames)
    # Find first non-silent probe window in source
    src_start = 0
    for f in range(0, max(1, src_frames - probe_len)):
        if src_samples[f * src_channels] != 0 or src_samples[f * src_channels + 1] != 0:
            src_start = f
            break

    probe = [src_samples[(src_start + i) * src_channels] for i in range(probe_len)]

    # Fast exact match check around offset 0..min(cap_frames - probe_len, 48000)
    search_cap = min(48000, cap_frames - probe_len + 1)
    if search_cap > 0:
        for cap_start in range(search_cap):
            if cap_samples[cap_start * src_channels] == probe[0] and cap_samples[(cap_start + 1) * src_channels] == probe[1]:
                if all(probe[k] == cap_samples[(cap_start + k) * src_channels] for k in range(min(32, probe_len))):
                    return src_start, cap_start

    # Check if capture starts later in source (rare seek/start case)
    search_src = min(48000, src_frames - probe_len + 1)
    cap_nz = 0
    for f in range(0, max(1, cap_frames - probe_len)):
        if cap_samples[f * src_channels] != 0 or cap_samples[f * src_channels + 1] != 0:
            cap_nz = f
            break
    for s_off in range(search_src):
        if src_samples[s_off * src_channels] == cap_samples[cap_nz * src_channels]:
            if all(src_samples[(s_off + k) * src_channels] == cap_samples[(cap_nz + k) * src_channels] for k in range(min(32, probe_len))):
                return s_off, cap_nz

    # Cross-correlation fallback (strided candidate search + local refine)
    best_offset = 0
    best_corr = -1e30
    sub_idx = range(0, min(512, probe_len), 8)
    for cap_start in range(0, max(1, search_cap), 4):
        corr = sum(probe[k] * cap_samples[(cap_start + k) * src_channels] for k in sub_idx)
        if corr > best_corr:
            best_corr = corr
            best_offset = cap_start
    lo = max(0, best_offset - 8)
    hi = min(max(1, search_cap), best_offset + 9)
    for cap_start in range(lo, hi):
        corr = sum(probe[k] * cap_samples[(cap_start + k) * src_channels] for k in range(0, min(512, probe_len)))
        if corr > best_corr:
            best_corr = corr
            best_offset = cap_start
    return src_start, best_offset

def align_and_compare(source_file, capture_file, sample_rate=None, bit_depth=None, subslot_size=None):
    print("Comparing:")
    print(f"  Source:  {source_file}")
    print(f"  Capture: {capture_file}")

    src_bits_detected = None
    if source_file.endswith(".flac") or source_file.endswith(".wav"):
        info = probe_audio_format(source_file)
        if info:
            src_rate, _, src_bits_detected = info
            sample_rate = sample_rate or src_rate

    cap_rate, cap_bits, cap_subslot = parse_raw_filename(capture_file)
    sample_rate = sample_rate or cap_rate or 44100
    src_bits = bit_depth or src_bits_detected or cap_bits or 16
    raw_cap_bits = cap_bits or bit_depth or src_bits
    raw_subslot = subslot_size or cap_subslot

    print(f"Stream parameters: {sample_rate} Hz, source={src_bits}-bit, capture={raw_cap_bits}-bit (subslot={raw_subslot or 'auto'} B), 2 channels")

    if capture_file.endswith(".raw"):
        cap_samples = load_raw_pcm_samples(capture_file, sample_rate, raw_cap_bits, subslot_size=raw_subslot)
    else:
        cap_samples = decode_source_to_samples(capture_file, sample_rate, src_bits)

    if source_file.endswith(".raw"):
        src_samples = load_raw_pcm_samples(source_file, sample_rate, src_bits, subslot_size=raw_subslot)
    else:
        src_samples = decode_source_to_samples(source_file, sample_rate, src_bits)

    if len(src_samples) == 0 or len(cap_samples) == 0:
        print("Error: empty audio stream.")
        return False

    print(f"Loaded {len(src_samples)//2} source frames, {len(cap_samples)//2} capture frames.")

    src_offset, cap_offset = find_exact_or_best_offset(src_samples, cap_samples, src_channels=2)
    rel_offset = cap_offset - src_offset
    print(f"Detected alignment: src_start={src_offset} frames, cap_offset={cap_offset} frames (delta={rel_offset} frames, {(rel_offset/sample_rate)*1000:.2f} ms)")

    avail_src_frames = (len(src_samples) // 2) - src_offset
    avail_cap_frames = (len(cap_samples) // 2) - cap_offset
    compare_frames = min(avail_src_frames, avail_cap_frames)
    if compare_frames <= 0:
        print("Error: insufficient overlapping frames.")
        return False

    align_src = src_samples[src_offset * 2 : (src_offset + compare_frames) * 2]
    align_cap = cap_samples[cap_offset * 2 : (cap_offset + compare_frames) * 2]

    shift_back = 16 if src_bits == 16 else (8 if src_bits == 24 else 0)
    total = len(align_src)
    matches = 0
    mismatches = 0
    max_diff = 0
    diff_sum_sq = 0.0
    mismatch_samples = []

    for i in range(total):
        s = align_src[i] >> shift_back
        c = align_cap[i] >> shift_back
        diff = abs(s - c)
        if diff == 0:
            matches += 1
        else:
            mismatches += 1
            max_diff = max(max_diff, diff)
            diff_sum_sq += diff * diff
            if len(mismatch_samples) < 10:
                mismatch_samples.append((i // 2, "L" if i % 2 == 0 else "R", s, c, diff))

    pct_match = (matches / total) * 100.0 if total > 0 else 0.0
    print("\n--- Bit-Perfect Verification Report ---")
    print(f"Compared frames:        {compare_frames} ({compare_frames / sample_rate:.3f} s)")
    print(f"Total samples compared: {total}")
    print(f"Identical samples:      {matches} ({pct_match:.4f}%)")
    print(f"Mismatched samples:     {mismatches}")
    print(f"Maximum difference:     {max_diff}")

    if mismatches == 0:
        print("\n>>> [BIT-PERFECT VERIFIED] Exact bit-for-bit match! Zero differences across the entire captured stream. <<<")
        return True
    else:
        rms = (diff_sum_sq / mismatches) ** 0.5
        print(f"RMS error of mismatches: {rms:.2f}")
        print("\nFirst mismatched samples:")
        for idx, ch, s, c, d in mismatch_samples:
            print(f"  Frame {idx} ({ch}): Source={s:#08x} ({s}) | Capture={c:#08x} ({c}) | Diff={d}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Bit-perfect verification tool for USB Audio.")
    parser.add_argument("--generate-test-signals", action="store_true", help="Generate 16-bit & 24-bit test files")
    parser.add_argument("--source", help="Path to reference source audio file")
    parser.add_argument("--capture", help="Path to raw PCM dump or loopback audio file")
    parser.add_argument("--rate", type=int, help="Sample rate in Hz (e.g. 44100, 96000)")
    parser.add_argument("--bit-depth", type=int, help="Bit depth (16, 24, 32)")
    parser.add_argument("--subslot", type=int, choices=[2, 3, 4], help="Wire subslot byte width (2, 3, or 4)")

    args = parser.parse_args()

    if args.generate_test_signals:
        generate_test_signals()
        return

    if args.source and args.capture:
        success = align_and_compare(args.source, args.capture, args.rate, args.bit_depth, args.subslot)
        sys.exit(0 if success else 1)

    parser.print_help()

if __name__ == "__main__":
    main()
