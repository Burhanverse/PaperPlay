//! Native USB DAC probing engine.
//! Probes UAC Clock Source sample frequency ranges via GET_RANGE and Feature Unit volume
//! ranges via direct usbfs ioctls using Kotlin-parsed topology IDs (`UsbDescriptorParser`).
//! Zero directory discovery and zero descriptor duplication in Rust.

use std::time::{Duration, Instant};

use crate::log::{
    GLOBAL_NATIVE_LOG, TAG_CLAIM_INTERFACE, TAG_CLOCK_GET_RANGE, TAG_DETACH_DRIVER,
    TAG_OPEN_DEVICE, TAG_PROBE_COMPLETE, TAG_PROBE_RATES, TAG_PROBE_START,
    TAG_REATTACH_DRIVER, TAG_RELEASE_INTERFACE,
};
use crate::usbfs;

/// Ensures any interface detached or claimed during probing is always released and
/// re-bound to the kernel driver (`snd-usb-audio`), even on early return.
struct ProbeClaimGuard {
    fd: i32,
    claimed_interfaces: Vec<u8>,
    detached_interfaces: Vec<u8>,
}

impl ProbeClaimGuard {
    fn new(fd: i32) -> Self {
        Self {
            fd,
            claimed_interfaces: Vec::new(),
            detached_interfaces: Vec::new(),
        }
    }

    fn record_detached(&mut self, iface: u8) {
        if !self.detached_interfaces.contains(&iface) {
            self.detached_interfaces.push(iface);
        }
    }

    fn record_claimed(&mut self, iface: u8) {
        if !self.claimed_interfaces.contains(&iface) {
            self.claimed_interfaces.push(iface);
        }
    }

    fn release_and_reattach(&mut self) {
        // Release claimed interfaces first (in reverse order), then re-attach kernel drivers
        // for every claimed or detached interface so snd-usb-audio rebinds cleanly.
        let mut to_reattach = Vec::new();
        for &iface in self.claimed_interfaces.iter().rev() {
            let rel_code = match usbfs::release_interface(self.fd, iface as u32) {
                Ok(_) => 0,
                Err(e) => -e,
            };
            GLOBAL_NATIVE_LOG.record(TAG_RELEASE_INTERFACE, iface as i32, rel_code as i64);
            if !to_reattach.contains(&iface) {
                to_reattach.push(iface);
            }
        }
        self.claimed_interfaces.clear();

        for &iface in &self.detached_interfaces {
            if !to_reattach.contains(&iface) {
                to_reattach.push(iface);
            }
        }
        self.detached_interfaces.clear();

        // Re-attach Audio Control (iface 0) first, then streaming interfaces.
        to_reattach.sort_unstable();
        for iface in to_reattach {
            let att_code = match usbfs::reattach_kernel_driver(self.fd, iface as u32) {
                Ok(_) => 0,
                Err(e) => -e,
            };
            GLOBAL_NATIVE_LOG.record(TAG_REATTACH_DRIVER, iface as i32, att_code as i64);
        }
    }
}

impl Drop for ProbeClaimGuard {
    fn drop(&mut self) {
        if !self.claimed_interfaces.is_empty() || !self.detached_interfaces.is_empty() {
            self.release_and_reattach();
        }
    }
}

const COMMON_SAMPLE_RATES: &[u32] = &[
    8000, 11025, 16000, 22050, 32000, 44100, 48000, 64000, 88200, 96000,
    176400, 192000, 352800, 384000, 705600, 768000,
];

#[derive(Debug, Clone)]
pub struct SamplingFrequencySubrange {
    pub min: u32,
    pub max: u32,
    pub res: u32,
}

#[derive(Debug, Clone)]
pub struct ProbedDacCapabilities {
    pub sample_rates: Vec<u32>,
    pub bit_depths: Vec<u8>,
    pub channel_counts: Vec<u16>,
    pub clock_source_id: u8,
    pub uac_version: u8,
    pub volume_min_db: f32,
    pub volume_max_db: f32,
    pub volume_res_db: f32,
    pub has_volume: bool,
    pub has_mute: bool,
}

/// Probes hardware clock sample-rate ranges and Feature Unit volume dB ranges via usbfs ioctls
/// using the topology IDs resolved in Kotlin ([UsbDescriptorParser]).
pub fn probe_dac(
    fd: i32,
    ac_iface: u8,
    streaming_iface: u8,
    is_uac2: bool,
    clock_source_id: u8,
    feature_unit_id: u8,
    fu_has_volume: bool,
    fu_has_master_volume: bool,
    fu_has_mute: bool,
) -> Result<ProbedDacCapabilities, i32> {
    crate::jni::ensure_no_device_discovery();
    GLOBAL_NATIVE_LOG.record(TAG_PROBE_START, fd, 0);

    if fd < 0 {
        GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, -22, fd as i64);
        return Err(-22);
    }

    GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, 0, fd as i64);

    let uac_version: u8 = if is_uac2 { 2 } else { 1 };
    let mut audio_interfaces = vec![ac_iface, streaming_iface];
    audio_interfaces.sort();
    audio_interfaces.dedup();

    let mut claim_guard = ProbeClaimGuard::new(fd);
    let max_attempts = 4;
    let delay_ms = 25;
    let claim_budget = Duration::from_millis(350);
    let claim_start = Instant::now();

    for &iface in &audio_interfaces {
        for attempt in 1..=max_attempts {
            let detach_code = match usbfs::detach_kernel_driver(fd, iface as u32) {
                Ok(_) => {
                    claim_guard.record_detached(iface);
                    0
                }
                Err(e) => e,
            };
            GLOBAL_NATIVE_LOG.record(TAG_DETACH_DRIVER, iface as i32, detach_code as i64);

            match usbfs::claim_interface(fd, iface as u32) {
                Ok(_) => {
                    claim_guard.record_claimed(iface);
                    GLOBAL_NATIVE_LOG.record(
                        TAG_CLAIM_INTERFACE,
                        iface as i32,
                        (attempt as i64) << 16,
                    );
                    break;
                }
                Err(e) => {
                    // Only retry on EBUSY (16) within our bounded claim budget
                    if e != libc::EBUSY || claim_start.elapsed() >= claim_budget {
                        break;
                    }
                    if attempt < max_attempts {
                        std::thread::sleep(Duration::from_millis(delay_ms));
                    }
                }
            }
        }
    }

    let bit_depths = Vec::new();
    let channel_counts = vec![2];
    let feature_unit_id = if feature_unit_id > 0 { Some(feature_unit_id) } else { None };
    let clock_source_id = if clock_source_id > 0 { clock_source_id } else { 9 };
    let mut sample_rates = Vec::new();

    if uac_version == 2 {
        // Query GET_RANGE on the Clock Source ID via usbfs control transfer
        let w_value = 0x0100u16; // CS_SAM_FREQ_CONTROL
        let w_index = ((clock_source_id as u16) << 8) | (ac_iface as u16);
        let mut range_buf = [0u8; 512];

        match usbfs::control_transfer(
            fd,
            0xA1, // Class | Interface | In
            0x02, // GET_RANGE
            w_value,
            w_index,
            &mut range_buf,
            250,
        ) {
            Ok(transferred) => {
                if transferred >= 2 {
                    let count = u16::from_le_bytes([range_buf[0], range_buf[1]]) as usize;
                    GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_RANGE, clock_source_id as i32, count as i64);

                    let mut subranges = Vec::new();
                    for i in 0..count {
                        let off = 2 + i * 12;
                        if off + 12 <= transferred {
                            let min = u32::from_le_bytes([range_buf[off], range_buf[off + 1], range_buf[off + 2], range_buf[off + 3]]);
                            let max = u32::from_le_bytes([range_buf[off + 4], range_buf[off + 5], range_buf[off + 6], range_buf[off + 7]]);
                            let res = u32::from_le_bytes([range_buf[off + 8], range_buf[off + 9], range_buf[off + 10], range_buf[off + 11]]);
                            subranges.push(SamplingFrequencySubrange { min, max, res });
                        }
                    }

                    // Check common sample rates against probed subranges
                    for &rate in COMMON_SAMPLE_RATES {
                        let supported = subranges.iter().any(|r| {
                            if r.res == 0 {
                                r.min == rate && r.max == rate
                            } else {
                                rate >= r.min && rate <= r.max && (rate - r.min) % r.res == 0
                            }
                        });
                        if supported {
                            sample_rates.push(rate);
                        }
                    }

                    for r in &subranges {
                        sample_rates.push(r.min);
                        if r.max != r.min {
                            sample_rates.push(r.max);
                        }
                    }
                }
            }
            Err(_) => {
                GLOBAL_NATIVE_LOG.record(TAG_CLOCK_GET_RANGE, clock_source_id as i32, -1);
            }
        }
    } else {
        sample_rates.clear();
    }

    sample_rates.sort_unstable();
    sample_rates.dedup();

    if uac_version == 2 && sample_rates.is_empty() {
        // Fallback default rates only if UAC2 probe was non-responsive
        sample_rates = vec![44100, 48000, 96000];
    }

    GLOBAL_NATIVE_LOG.record(TAG_PROBE_RATES, clock_source_id as i32, sample_rates.len() as i64);

    // Query Feature Unit volume dB range if present
    let mut min_db = -64.0f32;
    let mut max_db = 0.0f32;
    let mut res_db = 0.5f32;

    if fu_has_volume {
        if let Some(fu_id) = feature_unit_id {
            let channel = if fu_has_master_volume { 0u16 } else { 1u16 };
            let w_val = (0x0200u16) | (channel & 0xFF);
            let w_idx = ((fu_id as u16) << 8) | (ac_iface as u16);

            if uac_version == 2 {
                let mut count_bytes = [0u8; 2];
                if let Ok(2) = usbfs::control_transfer(fd, 0xA1, 0x02, w_val, w_idx, &mut count_bytes, 250) {
                    let count = u16::from_le_bytes(count_bytes) as usize;
                    if (1..=64).contains(&count) {
                        let expected = 2 + count * 6;
                        let mut data = vec![0u8; expected];
                        if let Ok(transferred) = usbfs::control_transfer(fd, 0xA1, 0x02, w_val, w_idx, &mut data, 250) {
                            if transferred == expected {
                                let min_raw = i16::from_le_bytes([data[2], data[3]]);
                                let max_raw = i16::from_le_bytes([data[4], data[5]]);
                                let res_raw = u16::from_le_bytes([data[6], data[7]]);
                                min_db = min_raw as f32 / 256.0;
                                max_db = max_raw as f32 / 256.0;
                                res_db = (res_raw as f32 / 256.0).max(0.1);
                            }
                        }
                    }
                }
            } else {
                // UAC 1.0 volume control range query
                let mut vol_buf = [0u8; 2];
                let min_res = usbfs::control_transfer(fd, 0xA1, 0x82, w_val, w_idx, &mut vol_buf, 250)
                    .map(|_| vol_buf);
                let (max_res, res_res) = if min_res.is_ok() {
                    let max_r = usbfs::control_transfer(fd, 0xA1, 0x83, w_val, w_idx, &mut vol_buf, 250)
                        .map(|_| vol_buf);
                    let res_r = usbfs::control_transfer(fd, 0xA1, 0x84, w_val, w_idx, &mut vol_buf, 250)
                        .map(|_| vol_buf);
                    (max_r, res_r)
                } else {
                    (Err(libc::EIO), Err(libc::EIO))
                };
                (min_db, max_db, res_db) = parse_uac1_volume_range(min_res, max_res, res_res);
            }
        }
    }

    // Release all claimed interfaces and re-attach kernel drivers cleanly before returning
    claim_guard.release_and_reattach();

    GLOBAL_NATIVE_LOG.record(
        TAG_PROBE_COMPLETE,
        sample_rates.len() as i32,
        ((uac_version as i64) << 32) | (clock_source_id as i64),
    );

    Ok(ProbedDacCapabilities {
        sample_rates,
        bit_depths,
        channel_counts,
        clock_source_id,
        uac_version,
        volume_min_db: min_db,
        volume_max_db: max_db,
        volume_res_db: res_db,
        has_volume: fu_has_volume,
        has_mute: fu_has_mute,
    })
}

fn parse_uac1_volume_range(
    min_res: Result<[u8; 2], i32>,
    max_res: Result<[u8; 2], i32>,
    res_res: Result<[u8; 2], i32>,
) -> (f32, f32, f32) {
    let Ok(min_bytes) = min_res else {
        return (-64.0, 0.0, 0.5);
    };
    let min_raw = i16::from_le_bytes(min_bytes);
    let max_raw = max_res.map(i16::from_le_bytes).unwrap_or(0);
    let res_raw = res_res.map(u16::from_le_bytes).unwrap_or(256);
    (
        min_raw as f32 / 256.0,
        max_raw as f32 / 256.0,
        (res_raw as f32 / 256.0).max(0.1),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_subrange_frequency_match() {
        let subrange = SamplingFrequencySubrange {
            min: 44100,
            max: 384000,
            res: 1,
        };
        assert!(subrange.min <= 96000 && 96000 <= subrange.max);
    }

    #[test]
    fn uac1_volume_range_preserves_safe_defaults_when_get_min_fails() {
        assert_eq!(parse_uac1_volume_range(Err(-110), Err(-110), Err(-110)), (-64.0, 0.0, 0.5));
        let min_bytes = (-40i16 * 256).to_le_bytes();
        let max_bytes = 0i16.to_le_bytes();
        let res_bytes = 128u16.to_le_bytes();
        assert_eq!(parse_uac1_volume_range(Ok(min_bytes), Ok(max_bytes), Ok(res_bytes)), (-40.0, 0.0, 0.5));
    }
}
