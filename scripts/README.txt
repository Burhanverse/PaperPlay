================================================================================
PaperPlay USB Audio: Bit-Perfect Capture & Offline Verification Guide
================================================================================

This document describes how to capture bit-exact PCM audio handed to the native
USB ring buffer on Android, pull it to a computer, and perform mathematical and
sample-exact bit-perfect verification against reference source files.

--------------------------------------------------------------------------------
1. Capturing USB PCM from PaperPlay on Android
--------------------------------------------------------------------------------

1. Connect your USB DAC to the Android phone and allow USB host permission.
2. In PaperPlay, navigate to:
   Settings > Audio > USB exclusive mode (or Settings > Audio > USB probe)
3. Ensure "USB exclusive mode" is switched ON.
4. Tap the "Dump USB PCM" button.
   The status will change to "Armed: waiting for USB playback".
5. Play a track (e.g. one of the reference test files below).
   PaperPlay captures the exact integer bytes written to the USB ring buffer
   for the first 30 seconds of playback and saves them to:
   /sdcard/Android/data/io.max.paperplay/cache/dump_usb_30s_<rate>hz_<bits>bit.raw
6. Once completed, the status shows "Finished: 30s captured successfully".
   You can tap "Share PCM dump" to export or pull it via adb.

--------------------------------------------------------------------------------
2. Pulling the Capture via ADB
--------------------------------------------------------------------------------

Run from your computer:

  adb pull /sdcard/Android/data/io.max.paperplay/cache/dump_usb_30s_44100hz_16bit.raw .
  # Or for 24-bit / 96 kHz:
  adb pull /sdcard/Android/data/io.max.paperplay/cache/dump_usb_30s_96000hz_24bit.raw .

--------------------------------------------------------------------------------
3. Automated Verification Tool
--------------------------------------------------------------------------------

Use `scripts/verify_bitperfect.py` for automated decoding, alignment, and sample comparison:

  # Generate test files (if not already generated):
  python3 scripts/verify_bitperfect.py --generate-test-signals

  # Compare reference FLAC against captured raw dump:
  python3 scripts/verify_bitperfect.py \
    --source scripts/test_audio/test_sine_ramp_16bit_44k.flac \
    --capture dump_usb_30s_44100hz_16bit.raw

  # 24-bit / 96 kHz test:
  python3 scripts/verify_bitperfect.py \
    --source scripts/test_audio/test_sine_ramp_24bit_96k.flac \
    --capture dump_usb_30s_96000hz_24bit.raw

  # 24-bit / 48 kHz LSB pattern test:
  python3 scripts/verify_bitperfect.py \
    --source scripts/test_audio/test_lsb_pattern_24bit_48k.flac \
    --capture dump_usb_30s_48000hz_24bit.raw

--------------------------------------------------------------------------------
4. Manual Verification Using Standard Laptop Commands (FFmpeg)
--------------------------------------------------------------------------------

You can also decode and inspect the files manually without Python:

1. Decode reference FLAC to 32-bit signed integer PCM (s32le):
   ffmpeg -i reference.flac -f s32le -ac 2 -ar 44100 ref_s32le.raw

2. If your USB DAC selected Alt 1 (16-bit in 2-byte subslot), convert the
   captured 16-bit raw dump to 32-bit for direct sample comparison:
   ffmpeg -f s16le -ar 44100 -ac 2 -i dump_usb_30s_44100hz_16bit.raw -f s32le cap_s32le.raw

3. Compare byte hashes or diff:
   cmp -l ref_s32le.raw cap_s32le.raw | head -n 20

--------------------------------------------------------------------------------
5. Mathematical Proof of Exact Float -> 32-bit Integer Conversion
--------------------------------------------------------------------------------

When MediaCodec or ExoPlayer outputs 32-bit floating point PCM (`ENCODING_PCM_FLOAT`):
- Samples are IEEE 754 single-precision floats in the range [-1.0, 1.0].
- An IEEE 754 32-bit float consists of:
  * 1 sign bit
  * 8 exponent bits
  * 23 explicit mantissa bits (+ 1 implicit leading bit = 24 bits of precision).

1. 16-bit Integer Source:
   Original integer sample: s16 in [-32768, 32767].
   Decoder normalization: fl = s16 / 32768.0.
   Because 32768 = 2^15 is an exact power of two, floating point division by
   32768 only decrements the exponent by 15; the mantissa remains identical.
   Reconversion in UsbSinkStateMachine:
     s16_recovered = round(fl * 32768.0).coerceIn(-32768, 32767).toShort()
   Since s16 has only 16 bits of significand (well within the 24 bits of float
   precision), no truncation or rounding error occurs.
   Max deviation = 0.

2. 24-bit Integer Source:
   Original integer sample: s24 in [-8388608, 8388607].
   Decoder normalization: fl = s24 / 8388608.0.
   Because 8388608 = 2^23 is an exact power of two, exponent is adjusted by 23.
   The 24 bits of s24 fit exactly into the 24-bit significand of IEEE 754 float.
   Reconversion in UsbSinkStateMachine:
     s24_recovered = round(fl * 8388608.0).coerceIn(-8388608, 8388607).toInt()
   Every integer in [-8388608, 8388607] maps 1:1 to an IEEE 754 float and back.
   Max deviation = 0.

3. Raw Integer Passthrough:
   When MediaCodec outputs raw 16-bit or 24-bit PCM directly (or hound/claxon
   decoders in the Rust engine), samples bypass floating point entirely and are
   repacked directly into the USB transfer buffer:
   * 16-bit: directly copied as 2-byte little endian shorts (subslotSize = 2).
   * 24-bit: copied as 3 bytes (subslotSize = 3) or shifted left by 8 for
     MSB-alignment in 4-byte subslots (subslotSize = 4).

--------------------------------------------------------------------------------
6. Alternate Setting Selection
--------------------------------------------------------------------------------

The UAC 1.0 / 2.0 negotiator evaluates the DAC's descriptors and selects:

- 16-bit / 44.1 kHz - 192 kHz sources:
  -> Alt Setting 1: subslotSize = 2, bitResolution = 16, formatTag = PCM.
  Result: 16-bit PCM transmitted in 2-byte subslots. Zero padding.

- 24-bit / 44.1 kHz - 384 kHz sources:
  -> Alt Setting 2: subslotSize = 3, bitResolution = 24, formatTag = PCM.
  Result: 24-bit PCM transmitted in 3-byte subslots. Zero padding.

- 32-bit or DACs without 3-byte subslots:
  -> Alt Setting 3: subslotSize = 4, bitResolution = 32 (or 24 in 32-bit slot).
  Result: 32-bit PCM or 24-bit MSB-aligned in 4-byte subslots.

--------------------------------------------------------------------------------
7. Boundary of Proof: What Cannot Be Proven in Software
--------------------------------------------------------------------------------

Software bit-perfect verification guarantees:
- Android system audio mixer (AudioFlinger) is 100% bypassed.
- No system volume scaling, limiter, or Android resampling is applied.
- Exact integer PCM bytes from the media decoder are placed on the USB bus.

What software CANNOT prove (DAC internal hardware limits):
- DAC internal hardware digital filters (e.g. sharp roll-off, slow roll-off,
  apodizing, or minimum phase FIR interpolation filters).
- Hardware delta-sigma modulators (converting PCM to 1-bit or multi-bit DSM).
- DAC chip internal clocks, jitter, analog reconstruction filters, and op-amps.
Proving those requires an external digital loopback analyzer (e.g. Audio Precision
APx555 or an FPGA capturing the I2S/DSD lines on the DAC board).
================================================================================
