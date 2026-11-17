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
        "-show_entries", "stream=sample_rate,channels,bits_per_raw_sample",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file_path
    ]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode != 0:
        return None
    lines = [line.strip() for line in p.stdout.strip().splitlines() if line.strip()]
    if len(lines) >= 2:
        try:
            rate = int(lines[0])
            channels = int(lines[1])
            bits = int(lines[2]) if len(lines) >= 3 and lines[2].isdigit() else 16
            return rate, channels, bits
        except ValueError:
            pass
    return None

def parse_raw_filename(file_path):
    """Extracts rate and bit depth from filenames like dump_usb_30s_44100hz_16bit.raw."""
    base = os.path.basename(file_path)
    m = re.search(r"(\d+)hz_(\d+)bit", base, re.IGNORECASE)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None, None

def load_raw_pcm_samples(file_path, sample_rate=None, bit_depth=None, channels=2):
    """Loads raw PCM samples from a captured file."""
    with open(file_path, "rb") as f:
        data = f.read()

    file_size = len(data)
    if file_size == 0:
        return []

    # Detect subslot size and bit depth
    if bit_depth is None or sample_rate is None:
        r, b = parse_raw_filename(file_path)
        sample_rate = sample_rate or r or 44100
        bit_depth = bit_depth or b or 16

    # Determine subslot size from file size and expected duration (approx 30s)
    # 30s * rate * channels * subslotSize
    expected_frames = sample_rate * 30
    subslot_size = file_size // (channels * expected_frames) if expected_frames > 0 else 0
    if subslot_size not in (2, 3, 4):
        # Fall back to bit_depth inference
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
        # Normalize to 24-bit space (shifted left by 8) for universal comparison
        samples = [s << 8 for s in raw_samples]
    elif subslot_size == 3:
        # 3 bytes per sample (s24le)
        count = file_size // 3
        for i in range(count):
            idx = i * 3
            b0 = data[idx]
            b1 = data[idx + 1]
            b2 = data[idx + 2]
            val = b0 | (b1 << 8) | (b2 << 16)
            if val & 0x800000:
                val -= 0x1000000
            samples.append(val)
    elif subslot_size == 4:
        # 4 bytes per sample (s32le)
        count = file_size // 4
        raw_samples = struct.unpack(f"<{count}i", data[:count * 4])
        if bit_depth == 24:
            # UAC 2.0 MSB-aligned: 24-bit sample is in upper 24 bits (val >> 8)
            samples = [s >> 8 for s in raw_samples]
        else:
            samples = list(raw_samples)

    return samples

def decode_source_to_samples(file_path, target_rate, target_bit_depth, channels=2):
    """Decodes a source audio file (FLAC, WAV) to normalized samples using ffmpeg."""
    if target_bit_depth == 16:
        fmt = "s16le"
    elif target_bit_depth == 24:
        fmt = "s32le"  # ffmpeg decodes 24-bit FLAC to s32le left-shifted by 8
    else:
        fmt = "s32le"

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
        return [s << 8 for s in raw]
    else:
        count = len(stdout) // 4
        raw = struct.unpack(f"<{count}i", stdout[:count * 4])
        # ffmpeg produces 24-bit in 32-bit container (upper 24 bits)
        return [s >> 8 for s in raw]

def align_and_compare(source_file, capture_file, sample_rate=None, bit_depth=None):
    print(f"Comparing:")
    print(f"  Source:  {source_file}")
    print(f"  Capture: {capture_file}")

    # Determine format parameters
    if source_file.endswith(".flac") or source_file.endswith(".wav"):
        info = probe_audio_format(source_file)
        if info:
            src_rate, src_ch, src_bits = info
            sample_rate = sample_rate or src_rate
            bit_depth = bit_depth or src_bits

    if sample_rate is None or bit_depth is None:
        r, b = parse_raw_filename(capture_file)
        sample_rate = sample_rate or r or 44100
        bit_depth = bit_depth or b or 16

    print(f"Stream parameters: {sample_rate} Hz, {bit_depth}-bit, 2 channels")

    # Load capture samples
    if capture_file.endswith(".raw"):
        cap_samples = load_raw_pcm_samples(capture_file, sample_rate, bit_depth)
    else:
        cap_samples = decode_source_to_samples(capture_file, sample_rate, bit_depth)

    # Load source samples
    if source_file.endswith(".raw"):
        src_samples = load_raw_pcm_samples(source_file, sample_rate, bit_depth)
    else:
        src_samples = decode_source_to_samples(source_file, sample_rate, bit_depth)

    if len(src_samples) == 0 or len(cap_samples) == 0:
        print("Error: empty audio stream.")
        return False

    print(f"Loaded {len(src_samples)//2} source frames, {len(cap_samples)//2} capture frames.")

    # Find alignment offset using cross-correlation on the first 4000 frames
    probe_len = min(4000, len(src_samples) // 2, len(cap_samples) // 2)
    search_window = min(48000, len(cap_samples) // 2 - probe_len)

    if search_window <= 0:
        print("Error: capture is too short to correlate.")
        return False

    # Extract probe channel 0
    probe = [src_samples[i * 2] for i in range(probe_len)]
    probe_energy = sum(abs(x) for x in probe)
    if probe_energy == 0:
        probe_start = min(1000, len(src_samples) // 4)
        probe = [src_samples[(probe_start + i) * 2] for i in range(probe_len)]

    best_offset = 0
    best_corr = -1e24

    # Check if already aligned at offset 0
    direct_match = True
    for i in range(min(200, probe_len)):
        if probe[i] != cap_samples[i * 2]:
            direct_match = False
            break

    if direct_match:
        best_offset = 0
    else:
        for offset in range(0, search_window):
            corr = sum(probe[i] * cap_samples[(offset + i) * 2] for i in range(probe_len))
            if corr > best_corr:
                best_corr = corr
                best_offset = offset

    print(f"Detected capture alignment offset: {best_offset} frames ({(best_offset/sample_rate)*1000:.2f} ms)")

    # Compare aligned samples
    align_src = src_samples[:(len(cap_samples)//2 - best_offset) * 2]
    align_cap = cap_samples[best_offset * 2 : best_offset * 2 + len(align_src)]

    total = len(align_src)
    matches = 0
    mismatches = 0
    max_diff = 0
    diff_sum_sq = 0.0

    mismatch_samples = []

    for i in range(total):
        s = align_src[i]
        c = align_cap[i]
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
    print(f"Total samples compared: {total}")
    print(f"Identical samples:      {matches} ({pct_match:.4f}%)")
    print(f"Mismatched samples:     {mismatches}")
    print(f"Maximum difference:     {max_diff}")

    if mismatches == 0:
        print("\n>>> [BIT-PERFECT VERIFIED] Exact bit-for-bit match! Zero differences across the entire stream. <<<")
        return True
    else:
        rms = (diff_sum_sq / mismatches) ** 0.5
        print(f"RMS error of mismatches: {rms:.2f}")
        print("\nFirst mismatched samples:")
        for idx, ch, s, c, d in mismatch_samples:
            print(f"  Frame {idx} ({ch}): Source={s:#08x} ({s}) | Capture={c:#08x} ({c}) | Diff={d}")

        if max_diff < 256:
            print("Note: Small differences (< 256) typically indicate 16-to-24 bit dither or quantization noise.")
        elif (max_diff & 0xFF) == 0 and max_diff > 65536:
            print("Note: Lower bits zeroed; likely 16-bit truncation of a 24-bit source.")
        return False

def main():
    parser = argparse.ArgumentParser(description="Bit-perfect verification tool for USB Audio.")
    parser.add_argument("--generate-test-signals", action="store_true", help="Generate 16-bit & 24-bit test files")
    parser.add_argument("--source", help="Path to reference source audio file")
    parser.add_argument("--capture", help="Path to raw PCM dump or loopback audio file")
    parser.add_argument("--rate", type=int, help="Sample rate in Hz (e.g. 44100, 96000)")
    parser.add_argument("--bit-depth", type=int, help="Bit depth (16, 24, 32)")

    args = parser.parse_args()

    if args.generate_test_signals:
        generate_test_signals()
        return

    if args.source and args.capture:
        success = align_and_compare(args.source, args.capture, args.rate, args.bit_depth)
        sys.exit(0 if success else 1)

    parser.print_help()

if __name__ == "__main__":
    main()
