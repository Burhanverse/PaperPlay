//! Standalone debug generators: 440 Hz sine tone and 24-bit ramp pattern.
//! Usable without Media3 or external framework for standalone DAC verification.

pub const DEBUG_MODE_SINE: i32 = 1;
pub const DEBUG_MODE_RAMP_24_96: i32 = 2;

pub fn generate_debug_buffer(
    mode: i32,
    sample_rate: u32,
    bit_depth: u32,
    subslot_size: u32,
    channels: u32,
    duration_ms: u32,
) -> Vec<u8> {
    let total_frames = ((sample_rate as u64 * duration_ms as u64) / 1000) as usize;
    let bytes_per_sample = subslot_size.max(1) as usize;
    let bytes_per_frame = bytes_per_sample * channels as usize;
    let mut buffer = vec![0u8; total_frames * bytes_per_frame];

    match mode {
        DEBUG_MODE_RAMP_24_96 => {
            // 24-bit ramp pattern: increments linearly across full 24-bit scale
            let mut val: i32 = -8388608;
            let step: i32 = 256;
            let mut offset = 0;

            for _ in 0..total_frames {
                let sample_bytes: [u8; 4] = match bytes_per_sample {
                    3 => [
                        (val & 0xFF) as u8,
                        ((val >> 8) & 0xFF) as u8,
                        ((val >> 16) & 0xFF) as u8,
                        0,
                    ],
                    4 => {
                        let val32 = val << 8;
                        [
                            (val32 & 0xFF) as u8,
                            ((val32 >> 8) & 0xFF) as u8,
                            ((val32 >> 16) & 0xFF) as u8,
                            ((val32 >> 24) & 0xFF) as u8,
                        ]
                    }
                    _ => [0, 0, 0, 0],
                };

                for _ in 0..channels {
                    for b in 0..bytes_per_sample {
                        buffer[offset + b] = sample_bytes[b];
                    }
                    offset += bytes_per_sample;
                }

                val = val.wrapping_add(step);
                if val > 8388607 {
                    val = -8388608;
                }
            }
        }
        _ => {
            // 440 Hz Sine Tone at -6 dBFS (amplitude 0.5)
            let freq = 440.0f64;
            let two_pi = 2.0 * std::f64::consts::PI;
            let mut offset = 0;

            for f in 0..total_frames {
                let t = f as f64 / sample_rate as f64;
                let sample_val = 0.5 * (two_pi * freq * t).sin();

                match bit_depth {
                    16 => {
                        let s16 = (sample_val * 32767.0) as i16;
                        let b0 = (s16 & 0xFF) as u8;
                        let b1 = ((s16 >> 8) & 0xFF) as u8;
                        for _ in 0..channels {
                            buffer[offset] = b0;
                            buffer[offset + 1] = b1;
                            offset += 2;
                        }
                    }
                    24 => {
                        let s24 = (sample_val * 8388607.0) as i32;
                        if bytes_per_sample == 4 {
                            let s32 = s24 << 8;
                            let b0 = (s32 & 0xFF) as u8;
                            let b1 = ((s32 >> 8) & 0xFF) as u8;
                            let b2 = ((s32 >> 16) & 0xFF) as u8;
                            let b3 = ((s32 >> 24) & 0xFF) as u8;
                            for _ in 0..channels {
                                buffer[offset] = b0;
                                buffer[offset + 1] = b1;
                                buffer[offset + 2] = b2;
                                buffer[offset + 3] = b3;
                                offset += 4;
                            }
                        } else {
                            let b0 = (s24 & 0xFF) as u8;
                            let b1 = ((s24 >> 8) & 0xFF) as u8;
                            let b2 = ((s24 >> 16) & 0xFF) as u8;
                            for _ in 0..channels {
                                buffer[offset] = b0;
                                buffer[offset + 1] = b1;
                                buffer[offset + 2] = b2;
                                offset += 3;
                            }
                        }
                    }
                    32 => {
                        let s32 = (sample_val * 2147483647.0) as i32;
                        let b0 = (s32 & 0xFF) as u8;
                        let b1 = ((s32 >> 8) & 0xFF) as u8;
                        let b2 = ((s32 >> 16) & 0xFF) as u8;
                        let b3 = ((s32 >> 24) & 0xFF) as u8;
                        for _ in 0..channels {
                            buffer[offset] = b0;
                            buffer[offset + 1] = b1;
                            buffer[offset + 2] = b2;
                            buffer[offset + 3] = b3;
                            offset += 4;
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    buffer
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sine_buffer_generation() {
        let buf = generate_debug_buffer(DEBUG_MODE_SINE, 48000, 16, 2, 2, 100);
        assert_eq!(buf.len(), 4800 * 2 * 2);
    }

    #[test]
    fn test_ramp_buffer_generation() {
        let buf = generate_debug_buffer(DEBUG_MODE_RAMP_24_96, 96000, 24, 3, 2, 10);
        assert_eq!(buf.len(), 960 * 3 * 2);
    }
}
