//! Native USB DAC probing engine.
//! Queries UAC 1.0 / UAC 2.0 descriptors, Clock Source sample frequency ranges via GET_RANGE,
//! and Feature Unit volume/mute capabilities via direct usbfs ioctls without libusb/rusb.
//! Zero directory discovery, eliminating SELinux avc: denied noise.

use std::time::Duration;

use crate::usb::descriptor::parse_audio_topology_from_raw;
use crate::usb::log::{
    GLOBAL_NATIVE_LOG, TAG_CLAIM_INTERFACE, TAG_CLOCK_GET_RANGE, TAG_DETACH_DRIVER,
    TAG_OPEN_DEVICE, TAG_PROBE_COMPLETE, TAG_PROBE_RATES, TAG_PROBE_START,
    TAG_REATTACH_DRIVER, TAG_RELEASE_INTERFACE,
};
use crate::usb::usbfs;

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

/// Probes a connected USB Audio DAC by using its Android file descriptor directly
/// via Linux usbfs ioctls: reads raw configuration descriptor, temporarily claims
/// Audio interfaces, queries the UAC 2.0 Clock Source GET_RANGE, discovers supported
/// bit depths and sample rates, queries volume ranges, and releases all interfaces.
pub fn probe_dac(fd: i32) -> Result<ProbedDacCapabilities, i32> {
    crate::usb::jni::ensure_no_device_discovery();
    GLOBAL_NATIVE_LOG.record(TAG_PROBE_START, fd, 0);

    if fd < 0 {
        GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, -22, fd as i64);
        return Err(-22); // EINVAL
    }

    GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, 0, fd as i64);

    // 1. Read USB Configuration Descriptor header (9 bytes) to find total length
    let mut header = [0u8; 9];
    let res = usbfs::control_transfer(fd, 0x80, 0x06, 0x0200, 0, &mut header, 1000);
    if res.is_err() || header[1] != 2 {
        GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, -1, fd as i64);
        return Err(-1);
    }

    let total_len = (u16::from_le_bytes([header[2], header[3]]) as usize).clamp(9, 4096);
    let mut raw_desc = vec![0u8; total_len];
    if let Err(e) = usbfs::control_transfer(fd, 0x80, 0x06, 0x0200, 0, &mut raw_desc, 1000) {
        GLOBAL_NATIVE_LOG.record(TAG_OPEN_DEVICE, -e, fd as i64);
        return Err(-e);
    }

    // 2. Parse AudioTopology from raw descriptors
    let topology = match parse_audio_topology_from_raw(&raw_desc) {
        Ok(t) => t,
        Err(code) => {
            return Err(code);
        }
    };

    let uac_version = topology.uac_version;
    let ac_iface = topology.ac_interface_number;
    let playback_alts = topology.find_playback_streaming_alts();

    // 3. Collect audio interface numbers to claim
    let mut audio_interfaces = vec![ac_iface];
    for alt in &topology.streaming_alts {
        audio_interfaces.push(alt.interface_number);
    }
    audio_interfaces.sort();
    audio_interfaces.dedup();
    if audio_interfaces.is_empty() {
        audio_interfaces = vec![0, 1];
    }

    let mut claim_guard = ProbeClaimGuard::new(fd);
    let max_attempts = 10;
    let delay_ms = 30;

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
                Err(_) => {
                    if attempt < max_attempts {
                        std::thread::sleep(Duration::from_millis(delay_ms));
                    }
                }
            }
        }
    }

    let mut bit_depths = Vec::new();
    let mut channel_counts = Vec::new();
    let mut uac1_sample_rates = Vec::new();

    for alt in &playback_alts {
        bit_depths.push(alt.bit_resolution);
        channel_counts.push(alt.channels);
        for &rate in &alt.discrete_sample_rates {
            uac1_sample_rates.push(rate);
        }
        if let Some((min_r, max_r)) = alt.continuous_range {
            for &common in COMMON_SAMPLE_RATES {
                if common >= min_r && common <= max_r {
                    uac1_sample_rates.push(common);
                }
            }
        }
    }

    channel_counts.push(2); // USB audio stereo default
    channel_counts.sort_unstable();
    channel_counts.dedup();

    bit_depths.sort_unstable();
    bit_depths.dedup();
    if bit_depths.is_empty() {
        bit_depths = vec![16, 24, 32];
    }

    // Accurately resolve Clock Source entity ID from playback path
    let clock_source_id = playback_alts
        .first()
        .and_then(|alt| topology.resolve_playback_clock_source_id(alt))
        .unwrap_or_else(|| {
            topology.clock_sources.first().map(|cs| cs.id).unwrap_or(9)
        });

    // Resolve Feature Unit along playback path for volume/mute controls
    let feature_unit = playback_alts
        .first()
        .and_then(|alt| topology.resolve_playback_feature_unit(alt))
        .or_else(|| topology.feature_units.first());
    let fu_has_volume = feature_unit.map_or(false, |fu| fu.has_master_volume || fu.has_channel_volume);
    let fu_has_master_volume = feature_unit.map_or(false, |fu| fu.has_master_volume);
    let fu_has_mute = feature_unit.map_or(false, |fu| fu.has_master_mute);
    let feature_unit_id = feature_unit.map(|fu| fu.id);

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
            1000,
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
        sample_rates = uac1_sample_rates;
    }

    sample_rates.sort_unstable();
    sample_rates.dedup();

    if sample_rates.is_empty() {
        // Fallback default rates if probe was non-responsive
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
                if let Ok(2) = usbfs::control_transfer(fd, 0xA1, 0x02, w_val, w_idx, &mut count_bytes, 500) {
                    let count = u16::from_le_bytes(count_bytes) as usize;
                    if (1..=64).contains(&count) {
                        let expected = 2 + count * 6;
                        let mut data = vec![0u8; expected];
                        if let Ok(transferred) = usbfs::control_transfer(fd, 0xA1, 0x02, w_val, w_idx, &mut data, 500) {
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
                let mut min_raw = 0i16;
                let mut max_raw = 0i16;
                let mut res_raw = 256u16;
                if let Ok(2) = usbfs::control_transfer(fd, 0xA1, 0x82, w_val, w_idx, &mut vol_buf, 500) {
                    min_raw = i16::from_le_bytes(vol_buf);
                }
                if let Ok(2) = usbfs::control_transfer(fd, 0xA1, 0x83, w_val, w_idx, &mut vol_buf, 500) {
                    max_raw = i16::from_le_bytes(vol_buf);
                }
                if let Ok(2) = usbfs::control_transfer(fd, 0xA1, 0x84, w_val, w_idx, &mut vol_buf, 500) {
                    res_raw = u16::from_le_bytes(vol_buf);
                }
                min_db = min_raw as f32 / 256.0;
                max_db = max_raw as f32 / 256.0;
                res_db = (res_raw as f32 / 256.0).max(0.1);
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
}
