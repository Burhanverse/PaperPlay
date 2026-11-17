// Clean-room USB audio decoders (FLAC via claxon, WAV via hound, DSF/DFF DoP and native DSD).
// Obeying AGENTS.md: pure clean-room, no GPL, public specs only.

use std::io::{Read, Seek, SeekFrom};
use crate::usb::ring_buffer::SpscRingBuffer;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioCodec {
    PcmWav,
    Flac,
    Dsf,
    Dff,
    RawPcm,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutputMode {
    Pcm { bit_depth: u8, subslot_size: u8 },
    DoP { subslot_size: u8 },
    NativeDsd { subslot_size: u8, bit_reverse: bool },
}

#[derive(Debug, Clone)]
pub struct AudioStreamInfo {
    pub codec: AudioCodec,
    pub sample_rate: u32,
    pub channels: u16,
    pub bit_depth: u8,
    pub total_samples: Option<u64>,
}

/// Formats a 24-bit integer sample into 3-byte or 4-byte subslots.
#[inline(always)]
pub fn pack_24bit_sample(sample: i32, subslot_size: u8, out: &mut [u8]) -> usize {
    let s = sample.clamp(-8_388_608, 8_388_607);
    if subslot_size == 3 {
        out[0] = (s & 0xFF) as u8;
        out[1] = ((s >> 8) & 0xFF) as u8;
        out[2] = ((s >> 16) & 0xFF) as u8;
        3
    } else {
        // 24-bit in 32-bit subslot: MSB aligned per UAC 2.0 spec
        out[0] = 0;
        out[1] = (s & 0xFF) as u8;
        out[2] = ((s >> 8) & 0xFF) as u8;
        out[3] = ((s >> 16) & 0xFF) as u8;
        4
    }
}

/// Packs 16-bit DSD bytes (d0, d1) and marker into a DoP slot.
/// DoP spec: 24-bit word = [marker, d0, d1].
#[inline(always)]
pub fn pack_dop_slot(d0: u8, d1: u8, marker: u8, subslot_size: u8, out: &mut [u8]) -> usize {
    if subslot_size == 3 {
        out[0] = d1;
        out[1] = d0;
        out[2] = marker;
        3
    } else {
        // MSB aligned in 32-bit container
        out[0] = 0;
        out[1] = d1;
        out[2] = d0;
        out[3] = marker;
        4
    }
}

/// Decodes FLAC stream into the given ring buffer using claxon.
#[cfg(feature = "usb")]
pub fn decode_flac<R: Read>(
    reader: R,
    ring: &SpscRingBuffer,
    subslot_size: u8,
    should_stop: &std::sync::atomic::AtomicBool,
) -> Result<AudioStreamInfo, String> {
    let mut flac = claxon::FlacReader::new(reader).map_err(|e| format!("FLAC init error: {e}"))?;
    let streaminfo = flac.streaminfo();
    let channels = streaminfo.channels as u16;
    let sample_rate = streaminfo.sample_rate;
    let bit_depth = streaminfo.bits_per_sample as u8;
    let total_samples = streaminfo.samples;

    let info = AudioStreamInfo {
        codec: AudioCodec::Flac,
        sample_rate,
        channels,
        bit_depth,
        total_samples,
    };

    let mut sample_buf = Vec::with_capacity(4096 * channels as usize);
    let mut out_buf = vec![0u8; 4096 * channels as usize * subslot_size as usize];

    let mut blocks = flac.blocks();
    while let Some(block) = blocks.read_next_or_eof(sample_buf).map_err(|e| format!("FLAC read error: {e}"))? {
        if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
            break;
        }

        let duration = block.duration() as usize;
        let mut out_idx = 0;

        for t in 0..duration {
            for c in 0..channels {
                let s = block.channel(c as u32)[t];
                match (bit_depth, subslot_size) {
                    (16, 2) => {
                        let s16 = s as i16;
                        let b = s16.to_le_bytes();
                        out_buf[out_idx..out_idx + 2].copy_from_slice(&b);
                        out_idx += 2;
                    }
                    (16, 4) => {
                        // 16-bit in 32-bit slot (MSB aligned)
                        let s16 = s as i16;
                        out_buf[out_idx] = 0;
                        out_buf[out_idx + 1] = 0;
                        let b = s16.to_le_bytes();
                        out_buf[out_idx + 2..out_idx + 4].copy_from_slice(&b);
                        out_idx += 4;
                    }
                    (24, 3) | (24, 4) => {
                        out_idx += pack_24bit_sample(s, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
                    }
                    (32, 4) => {
                        let b = s.to_le_bytes();
                        out_buf[out_idx..out_idx + 4].copy_from_slice(&b);
                        out_idx += 4;
                    }
                    _ => {
                        out_idx += pack_24bit_sample(s, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
                    }
                }
            }
        }

        let mut written = 0;
        while written < out_idx {
            if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
                return Ok(info);
            }
            let n = ring.write(&out_buf[written..out_idx]);
            if n > 0 {
                written += n;
            } else {
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }

        sample_buf = block.into_buffer();
    }

    Ok(info)
}

/// Decodes WAV stream into the given ring buffer using hound.
#[cfg(feature = "usb")]
pub fn decode_wav<R: Read + Seek>(
    reader: R,
    ring: &SpscRingBuffer,
    subslot_size: u8,
    should_stop: &std::sync::atomic::AtomicBool,
) -> Result<AudioStreamInfo, String> {
    let mut wav = hound::WavReader::new(reader).map_err(|e| format!("WAV init error: {e}"))?;
    let spec = wav.spec();
    let channels = spec.channels;
    let sample_rate = spec.sample_rate;
    let bit_depth = spec.bits_per_sample as u8;

    let info = AudioStreamInfo {
        codec: AudioCodec::PcmWav,
        sample_rate,
        channels,
        bit_depth,
        total_samples: Some(wav.duration() as u64),
    };

    let mut out_buf = vec![0u8; 4096 * channels as usize * subslot_size as usize];
    let mut out_idx = 0;

    for sample_res in wav.samples::<i32>() {
        if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
            break;
        }

        let s = sample_res.map_err(|e| format!("WAV sample read error: {e}"))?;
        match (bit_depth, subslot_size) {
            (16, 2) => {
                let b = (s as i16).to_le_bytes();
                out_buf[out_idx..out_idx + 2].copy_from_slice(&b);
                out_idx += 2;
            }
            (16, 4) => {
                out_buf[out_idx] = 0;
                out_buf[out_idx + 1] = 0;
                let b = (s as i16).to_le_bytes();
                out_buf[out_idx + 2..out_idx + 4].copy_from_slice(&b);
                out_idx += 4;
            }
            (24, 3) | (24, 4) => {
                out_idx += pack_24bit_sample(s, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
            }
            (32, 4) => {
                let b = s.to_le_bytes();
                out_buf[out_idx..out_idx + 4].copy_from_slice(&b);
                out_idx += 4;
            }
            _ => {
                out_idx += pack_24bit_sample(s, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
            }
        }

        if out_idx >= out_buf.len() - (channels as usize * subslot_size as usize) {
            let mut written = 0;
            while written < out_idx {
                if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
                    return Ok(info);
                }
                let n = ring.write(&out_buf[written..out_idx]);
                if n > 0 {
                    written += n;
                } else {
                    std::thread::sleep(std::time::Duration::from_millis(1));
                }
            }
            out_idx = 0;
        }
    }

    if out_idx > 0 {
        let mut written = 0;
        while written < out_idx {
            if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
                break;
            }
            let n = ring.write(&out_buf[written..out_idx]);
            if n > 0 {
                written += n;
            } else {
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }
    }

    Ok(info)
}

/// Sony DSF format parser & DoP / native DSD packer.
/// Block size is typically 4096 bytes per channel, LSB-first bit order.
pub fn decode_dsf<R: Read + Seek>(
    mut reader: R,
    ring: &SpscRingBuffer,
    subslot_size: u8,
    is_dop: bool,
    should_stop: &std::sync::atomic::AtomicBool,
) -> Result<AudioStreamInfo, String> {
    // Read DSD header chunk
    let mut header = [0u8; 28];
    reader.read_exact(&mut header).map_err(|e| format!("DSF header error: {e}"))?;
    if &header[0..4] != b"DSD " {
        return Err("Not a valid DSF file (missing DSD header)".to_string());
    }

    // Read fmt chunk
    let mut fmt = [0u8; 52];
    reader.read_exact(&mut fmt).map_err(|e| format!("DSF fmt chunk error: {e}"))?;
    if &fmt[0..4] != b"fmt " {
        return Err("Not a valid DSF file (missing fmt chunk)".to_string());
    }

    let channel_count = u32::from_le_bytes([fmt[24], fmt[25], fmt[26], fmt[27]]) as u16;
    let sample_rate = u32::from_le_bytes([fmt[28], fmt[29], fmt[30], fmt[31]]);
    let bits_per_sample = u32::from_le_bytes([fmt[32], fmt[33], fmt[34], fmt[35]]) as u8;
    let sample_count = u64::from_le_bytes([fmt[36], fmt[37], fmt[38], fmt[39], fmt[40], fmt[41], fmt[42], fmt[43]]);
    let block_size = u32::from_le_bytes([fmt[44], fmt[45], fmt[46], fmt[47]]) as usize;

    if channel_count != 2 {
        return Err(format!("Only stereo DSF is supported for USB output (found {channel_count} channels)"));
    }
    if block_size == 0 || block_size > 65536 {
        return Err(format!("Unsupported DSF block size: {block_size}"));
    }

    // Read data chunk header
    let mut data_hdr = [0u8; 12];
    reader.read_exact(&mut data_hdr).map_err(|e| format!("DSF data chunk header error: {e}"))?;
    if &data_hdr[0..4] != b"data" {
        return Err("Not a valid DSF file (missing data chunk)".to_string());
    }
    let data_size = u64::from_le_bytes([
        data_hdr[4], data_hdr[5], data_hdr[6], data_hdr[7],
        data_hdr[8], data_hdr[9], data_hdr[10], data_hdr[11],
    ]).saturating_sub(12);

    // Carrier sample rate for DoP is sample_rate / 16 (e.g. 2,822,400 / 16 = 176,400 Hz for DSD64)
    let carrier_rate = if is_dop { sample_rate / 16 } else { sample_rate };

    let info = AudioStreamInfo {
        codec: AudioCodec::Dsf,
        sample_rate: carrier_rate,
        channels: 2,
        bit_depth: if is_dop { 24 } else { bits_per_sample },
        total_samples: Some(sample_count),
    };

    let mut ch0_block = vec![0u8; block_size];
    let mut ch1_block = vec![0u8; block_size];
    let mut out_buf = vec![0u8; block_size * 4 * subslot_size as usize];

    let mut marker_toggle = true;
    let mut bytes_left = data_size;

    while bytes_left >= (block_size * 2) as u64 {
        if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
            break;
        }

        reader.read_exact(&mut ch0_block).map_err(|e| format!("DSF data read error (ch0): {e}"))?;
        reader.read_exact(&mut ch1_block).map_err(|e| format!("DSF data read error (ch1): {e}"))?;
        bytes_left -= (block_size * 2) as u64;

        let mut out_idx = 0;
        if is_dop {
            // DoP packing: 2 DSD bytes per channel per DoP frame
            // DSF bytes are LSB-first; standard DSD bitstream expects reversed bits
            for i in (0..block_size).step_by(2) {
                let marker = if marker_toggle { 0x05u8 } else { 0xFAu8 };
                marker_toggle = !marker_toggle;

                let l0 = ch0_block[i].reverse_bits();
                let l1 = ch0_block[i + 1].reverse_bits();
                let r0 = ch1_block[i].reverse_bits();
                let r1 = ch1_block[i + 1].reverse_bits();

                out_idx += pack_dop_slot(l0, l1, marker, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
                out_idx += pack_dop_slot(r0, r1, marker, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
            }
        } else {
            // Native DSD: interleave raw DSD bytes (reversed from DSF LSB-first)
            for i in 0..block_size {
                let l = ch0_block[i].reverse_bits();
                let r = ch1_block[i].reverse_bits();
                if subslot_size == 1 {
                    out_buf[out_idx] = l;
                    out_buf[out_idx + 1] = r;
                    out_idx += 2;
                } else {
                    out_buf[out_idx..out_idx + subslot_size as usize].fill(0);
                    out_buf[out_idx] = l;
                    out_idx += subslot_size as usize;
                    out_buf[out_idx..out_idx + subslot_size as usize].fill(0);
                    out_buf[out_idx] = r;
                    out_idx += subslot_size as usize;
                }
            }
        }

        let mut written = 0;
        while written < out_idx {
            if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
                return Ok(info);
            }
            let n = ring.write(&out_buf[written..out_idx]);
            if n > 0 {
                written += n;
            } else {
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }
    }

    Ok(info)
}

/// Philips DFF (DSDIFF) parser & DoP / native DSD packer.
/// Interleaved 1 byte per channel, MSB-first bit order.
pub fn decode_dff<R: Read + Seek>(
    mut reader: R,
    ring: &SpscRingBuffer,
    subslot_size: u8,
    is_dop: bool,
    should_stop: &std::sync::atomic::AtomicBool,
) -> Result<AudioStreamInfo, String> {
    let mut frm8 = [0u8; 16];
    reader.read_exact(&mut frm8).map_err(|e| format!("DFF header error: {e}"))?;
    if &frm8[0..4] != b"FRM8" || &frm8[12..16] != b"DSD " {
        return Err("Not a valid DFF file (missing FRM8 DSD)".to_string());
    }

    let mut channels: u16 = 2;
    let mut sample_rate: u32 = 2_822_400;
    let mut dsd_data_offset: Option<u64> = None;
    let mut dsd_data_len: u64 = 0;

    // Scan chunks until DSD audio data is found
    let mut chunk_hdr = [0u8; 12];
    while reader.read_exact(&mut chunk_hdr).is_ok() {
        let chunk_id = &chunk_hdr[0..4];
        let chunk_len = u64::from_be_bytes([
            chunk_hdr[4], chunk_hdr[5], chunk_hdr[6], chunk_hdr[7],
            chunk_hdr[8], chunk_hdr[9], chunk_hdr[10], chunk_hdr[11],
        ]);

        match chunk_id {
            b"PROP" => {
                let mut prop_id = [0u8; 4];
                reader.read_exact(&mut prop_id).map_err(|e| format!("DFF PROP read error: {e}"))?;
                // Inside PROP SND subchunks:
                let mut bytes_left = chunk_len.saturating_sub(4);
                while bytes_left >= 12 {
                    let mut sub_hdr = [0u8; 12];
                    reader.read_exact(&mut sub_hdr).map_err(|e| format!("DFF subchunk read error: {e}"))?;
                    let sub_id = &sub_hdr[0..4];
                    let sub_len = u64::from_be_bytes([
                        sub_hdr[4], sub_hdr[5], sub_hdr[6], sub_hdr[7],
                        sub_hdr[8], sub_hdr[9], sub_hdr[10], sub_hdr[11],
                    ]);
                    bytes_left = bytes_left.saturating_sub(12 + sub_len);

                    match sub_id {
                        b"FS  " => {
                            let mut fs_bytes = [0u8; 4];
                            reader.read_exact(&mut fs_bytes).map_err(|e| format!("DFF FS read error: {e}"))?;
                            sample_rate = u32::from_be_bytes(fs_bytes);
                            if sub_len > 4 {
                                reader.seek(SeekFrom::Current((sub_len - 4) as i64)).map_err(|e| format!("DFF seek error: {e}"))?;
                            }
                        }
                        b"CHNL" => {
                            let mut ch_bytes = [0u8; 2];
                            reader.read_exact(&mut ch_bytes).map_err(|e| format!("DFF CHNL read error: {e}"))?;
                            channels = u16::from_be_bytes(ch_bytes);
                            if sub_len > 2 {
                                reader.seek(SeekFrom::Current((sub_len - 2) as i64)).map_err(|e| format!("DFF seek error: {e}"))?;
                            }
                        }
                        _ => {
                            reader.seek(SeekFrom::Current(sub_len as i64)).map_err(|e| format!("DFF seek error: {e}"))?;
                        }
                    }
                }
            }
            b"DSD " => {
                let cur = reader.stream_position().map_err(|e| format!("DFF stream pos error: {e}"))?;
                dsd_data_offset = Some(cur);
                dsd_data_len = chunk_len;
                break;
            }
            _ => {
                reader.seek(SeekFrom::Current(chunk_len as i64)).map_err(|e| format!("DFF chunk seek error: {e}"))?;
            }
        }
    }

    let data_offset = dsd_data_offset.ok_or_else(|| "DFF DSD data chunk not found".to_string())?;
    reader.seek(SeekFrom::Start(data_offset)).map_err(|e| format!("DFF data seek error: {e}"))?;

    if channels != 2 {
        return Err(format!("Only stereo DFF is supported for USB output (found {channels} channels)"));
    }

    let carrier_rate = if is_dop { sample_rate / 16 } else { sample_rate };
    let info = AudioStreamInfo {
        codec: AudioCodec::Dff,
        sample_rate: carrier_rate,
        channels: 2,
        bit_depth: if is_dop { 24 } else { 1 },
        total_samples: Some(dsd_data_len * 8 / 2),
    };

    let mut in_buf = vec![0u8; 4096];
    let mut out_buf = vec![0u8; 4096 * 4 * subslot_size as usize];
    let mut marker_toggle = true;
    let mut bytes_left = dsd_data_len;

    while bytes_left >= 4 {
        if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
            break;
        }

        let chunk = (bytes_left as usize).min(in_buf.len()) & !3; // align to 4 bytes (2 stereo frames)
        if chunk == 0 {
            break;
        }

        reader.read_exact(&mut in_buf[0..chunk]).map_err(|e| format!("DFF data read error: {e}"))?;
        bytes_left -= chunk as u64;

        let mut out_idx = 0;
        if is_dop {
            // in_buf contains interleaved: [L0, R0, L1, R1, L2, R2, L3, R3, ...]
            for i in (0..chunk).step_by(4) {
                let marker = if marker_toggle { 0x05u8 } else { 0xFAu8 };
                marker_toggle = !marker_toggle;

                let l0 = in_buf[i];
                let r0 = in_buf[i + 1];
                let l1 = in_buf[i + 2];
                let r1 = in_buf[i + 3];

                out_idx += pack_dop_slot(l0, l1, marker, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
                out_idx += pack_dop_slot(r0, r1, marker, subslot_size, &mut out_buf[out_idx..out_idx + subslot_size as usize]);
            }
        } else {
            // Native DSD: copy interleaved
            for i in (0..chunk).step_by(2) {
                let l = in_buf[i];
                let r = in_buf[i + 1];
                if subslot_size == 1 {
                    out_buf[out_idx] = l;
                    out_buf[out_idx + 1] = r;
                    out_idx += 2;
                } else {
                    out_buf[out_idx..out_idx + subslot_size as usize].fill(0);
                    out_buf[out_idx] = l;
                    out_idx += subslot_size as usize;
                    out_buf[out_idx..out_idx + subslot_size as usize].fill(0);
                    out_buf[out_idx] = r;
                    out_idx += subslot_size as usize;
                }
            }
        }

        let mut written = 0;
        while written < out_idx {
            if should_stop.load(std::sync::atomic::Ordering::Relaxed) {
                return Ok(info);
            }
            let n = ring.write(&out_buf[written..out_idx]);
            if n > 0 {
                written += n;
            } else {
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
        }
    }

    Ok(info)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    use std::sync::atomic::AtomicBool;

    #[test]
    fn test_pack_24bit_sample() {
        let mut out3 = [0u8; 3];
        pack_24bit_sample(0x123456, 3, &mut out3);
        assert_eq!(out3, [0x56, 0x34, 0x12]);

        let mut out4 = [0u8; 4];
        pack_24bit_sample(0x123456, 4, &mut out4);
        assert_eq!(out4, [0x00, 0x56, 0x34, 0x12]);
    }

    #[test]
    fn test_pack_dop_slot() {
        let mut out3 = [0u8; 3];
        pack_dop_slot(0xAA, 0xBB, 0x05, 3, &mut out3);
        assert_eq!(out3, [0xBB, 0xAA, 0x05]);

        let mut out4 = [0u8; 4];
        pack_dop_slot(0xAA, 0xBB, 0xFA, 4, &mut out4);
        assert_eq!(out4, [0x00, 0xBB, 0xAA, 0xFA]);
    }

    #[test]
    fn test_wav_decoder() {
        // Generate a 16-bit stereo WAV in memory with hound
        let mut wav_bytes = Vec::new();
        {
            let spec = hound::WavSpec {
                channels: 2,
                sample_rate: 44100,
                bits_per_sample: 16,
                sample_format: hound::SampleFormat::Int,
            };
            let mut writer = hound::WavWriter::new(Cursor::new(&mut wav_bytes), spec).unwrap();
            writer.write_sample(1000i16).unwrap();
            writer.write_sample(-1000i16).unwrap();
            writer.finalize().unwrap();
        }

        let ring = SpscRingBuffer::new(4096);
        let stop = AtomicBool::new(false);
        let info = decode_wav(Cursor::new(&wav_bytes), &ring, 2, &stop).unwrap();

        assert_eq!(info.sample_rate, 44100);
        assert_eq!(info.channels, 2);
        assert_eq!(info.bit_depth, 16);
        assert_eq!(ring.available_read(), 4);

        let mut out = [0u8; 4];
        assert_eq!(ring.read(&mut out), 4);
        assert_eq!(&out[0..2], &1000i16.to_le_bytes());
        assert_eq!(&out[2..4], &(-1000i16).to_le_bytes());
    }

    #[test]
    fn test_dsf_decoder() {
        // Build minimal synthetic DSF file (block_size = 4, 1 block = 8 bytes)
        let mut dsf = Vec::new();
        // DSD chunk (28 bytes)
        dsf.extend_from_slice(b"DSD ");
        dsf.extend_from_slice(&28u64.to_le_bytes());
        dsf.extend_from_slice(&100u64.to_le_bytes()); // total size
        dsf.extend_from_slice(&0u64.to_le_bytes());   // metadata ptr

        // fmt chunk (52 bytes)
        dsf.extend_from_slice(b"fmt ");
        dsf.extend_from_slice(&52u64.to_le_bytes());
        dsf.extend_from_slice(&1u32.to_le_bytes()); // version
        dsf.extend_from_slice(&0u32.to_le_bytes()); // format id
        dsf.extend_from_slice(&2u32.to_le_bytes()); // channel type stereo
        dsf.extend_from_slice(&2u32.to_le_bytes()); // channel count
        dsf.extend_from_slice(&2822400u32.to_le_bytes()); // sample rate DSD64
        dsf.extend_from_slice(&1u32.to_le_bytes()); // bits per sample
        dsf.extend_from_slice(&32u64.to_le_bytes()); // sample count
        dsf.extend_from_slice(&4u32.to_le_bytes()); // block size per channel = 4
        dsf.extend_from_slice(&0u32.to_le_bytes()); // reserved

        // data chunk (12 bytes header + 8 bytes data)
        dsf.extend_from_slice(b"data");
        dsf.extend_from_slice(&20u64.to_le_bytes()); // 12 + 8
        // Ch 0 data (4 bytes)
        dsf.extend_from_slice(&[0x12, 0x34, 0x56, 0x78]);
        // Ch 1 data (4 bytes)
        dsf.extend_from_slice(&[0x9A, 0xBC, 0xDE, 0xF0]);

        let ring = SpscRingBuffer::new(4096);
        let stop = AtomicBool::new(false);
        let info = decode_dsf(Cursor::new(&dsf), &ring, 3, true, &stop).unwrap();

        assert_eq!(info.sample_rate, 176400); // 2822400 / 16
        assert_eq!(info.channels, 2);
        assert_eq!(info.bit_depth, 24); // DoP 24-bit container
        // Block size 4 means 2 DoP frames = 2 * 2 channels * 3 bytes = 12 bytes
        assert_eq!(ring.available_read(), 12);
    }

    #[test]
    fn test_dff_decoder() {
        // Build minimal synthetic DFF file (stereo, 8 bytes data = 2 stereo frames = 4 bytes per frame)
        let mut dff = Vec::new();
        dff.extend_from_slice(b"FRM8");
        dff.extend_from_slice(&100u64.to_be_bytes()); // total size
        dff.extend_from_slice(b"DSD ");

        // PROP chunk (34 bytes: 4 for "SND " + (12+4) for FS + (12+2) for CHNL)
        dff.extend_from_slice(b"PROP");
        let prop_len = 4u64 + 16 + 14;
        dff.extend_from_slice(&prop_len.to_be_bytes());
        dff.extend_from_slice(b"SND ");

        // FS chunk
        dff.extend_from_slice(b"FS  ");
        dff.extend_from_slice(&4u64.to_be_bytes());
        dff.extend_from_slice(&2822400u32.to_be_bytes()); // DSD64

        // CHNL chunk
        dff.extend_from_slice(b"CHNL");
        dff.extend_from_slice(&2u64.to_be_bytes());
        dff.extend_from_slice(&2u16.to_be_bytes()); // Stereo

        // DSD data chunk (8 bytes = 2 frames * 2 ch * 2 bytes/frame)
        dff.extend_from_slice(b"DSD ");
        dff.extend_from_slice(&8u64.to_be_bytes());
        dff.extend_from_slice(&[0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88]);

        let ring = SpscRingBuffer::new(4096);
        let stop = AtomicBool::new(false);
        let info = decode_dff(Cursor::new(&dff), &ring, 3, true, &stop).unwrap();

        assert_eq!(info.sample_rate, 176400); // 2822400 / 16
        assert_eq!(info.channels, 2);
        assert_eq!(info.bit_depth, 24);
        // 8 bytes in_buf -> 2 DoP frames = 2 * 2 ch * 3 bytes = 12 bytes
        assert_eq!(ring.available_read(), 12);
    }
}
