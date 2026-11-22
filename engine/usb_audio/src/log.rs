use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, AtomicU8, AtomicUsize, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

pub const NATIVE_LOG_CAPACITY: usize = 256;

pub const TAG_URB_SUBMIT_ERR: u8 = 1;
pub const TAG_URB_REAP_ERR: u8 = 2;
pub const TAG_URB_PACKET_ERR: u8 = 3;
pub const TAG_PERIODIC_STATS: u8 = 4;
pub const TAG_STREAM_START: u8 = 5;
pub const TAG_STREAM_STOP: u8 = 6;
pub const TAG_HOT_UNPLUG: u8 = 7;
pub const TAG_CONTROL_REQ: u8 = 8;
pub const TAG_CONTROL_ERR: u8 = 9;
pub const TAG_OPEN_DEVICE: u8 = 10;
pub const TAG_DETACH_DRIVER: u8 = 11;
pub const TAG_CLAIM_INTERFACE: u8 = 12;
pub const TAG_SET_ALT_SETTING: u8 = 13;
pub const TAG_CLOCK_SET_CUR: u8 = 14;
pub const TAG_CLOCK_GET_CUR: u8 = 15;
pub const TAG_CLOCK_GET_RANGE: u8 = 16;
pub const TAG_PROBE_START: u8 = 17;
pub const TAG_PROBE_RATES: u8 = 18;
pub const TAG_PROBE_COMPLETE: u8 = 19;
pub const TAG_SET_CUR_VOL: u8 = 20;
pub const TAG_SET_CUR_MUTE: u8 = 21;
pub const TAG_GET_CUR_VOL: u8 = 22;
pub const TAG_GET_CUR_MUTE: u8 = 23;
pub const TAG_RELEASE_INTERFACE: u8 = 24;
pub const TAG_REATTACH_DRIVER: u8 = 25;

pub struct NativeLogEntry {
    pub timestamp_ms: AtomicU64,
    pub tag_code: AtomicU8,
    pub arg1: AtomicI32,
    pub arg2: AtomicI64,
}

impl NativeLogEntry {
    pub const fn new() -> Self {
        Self {
            timestamp_ms: AtomicU64::new(0),
            tag_code: AtomicU8::new(0),
            arg1: AtomicI32::new(0),
            arg2: AtomicI64::new(0),
        }
    }
}

pub struct NativeRingLog {
    cursor: AtomicUsize,
    entries: [NativeLogEntry; NATIVE_LOG_CAPACITY],
}

impl NativeRingLog {
    pub const fn new() -> Self {
        // Construct fixed-size array in const context
        const EMPTY: NativeLogEntry = NativeLogEntry::new();
        Self {
            cursor: AtomicUsize::new(0),
            entries: [EMPTY; NATIVE_LOG_CAPACITY],
        }
    }

    pub fn record(&self, tag_code: u8, arg1: i32, arg2: i64) {
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let idx = self.cursor.fetch_add(1, Ordering::Relaxed) % NATIVE_LOG_CAPACITY;
        let entry = &self.entries[idx];
        entry.timestamp_ms.store(now_ms, Ordering::Relaxed);
        entry.arg1.store(arg1, Ordering::Relaxed);
        entry.arg2.store(arg2, Ordering::Relaxed);
        entry.tag_code.store(tag_code, Ordering::Release);
    }

    pub fn dump_lines(&self) -> Vec<String> {
        let cursor = self.cursor.load(Ordering::Relaxed);
        let count = cursor.min(NATIVE_LOG_CAPACITY);
        let start = if cursor > NATIVE_LOG_CAPACITY {
            cursor - NATIVE_LOG_CAPACITY
        } else {
            0
        };

        let mut lines = Vec::with_capacity(count);
        for i in start..cursor {
            let entry = &self.entries[i % NATIVE_LOG_CAPACITY];
            let tag = entry.tag_code.load(Ordering::Acquire);
            if tag == 0 {
                continue;
            }
            let ts = entry.timestamp_ms.load(Ordering::Relaxed);
            let a1 = entry.arg1.load(Ordering::Relaxed);
            let a2 = entry.arg2.load(Ordering::Relaxed);

            let desc = match tag {
                TAG_URB_SUBMIT_ERR => format!("URB_SUBMIT_ERR: errno={} urb_idx={}", a1, a2),
                TAG_URB_REAP_ERR => format!("URB_REAP_ERR: errno={}", a1),
                TAG_URB_PACKET_ERR => format!("URB_PACKET_ERR: status={} packet_idx={}", a1, a2),
                TAG_PERIODIC_STATS => format!("PERIODIC_STATS: underruns={} ring_fill_bytes={}", a1, a2),
                TAG_STREAM_START => {
                    let ep = ((a2 >> 48) & 0xFF) as u8;
                    let fd = ((a2 >> 32) & 0xFFFF) as i16;
                    let handle_low = (a2 & 0xFFFFFFFF) as u32;
                    format!("STREAM_START: rate={} ep=0x{:02x} fd={} handle=0x{:08x}", a1, ep, fd, handle_low)
                }
                TAG_STREAM_STOP => format!("STREAM_STOP: frames_consumed={} underruns={}", a1, a2),
                TAG_HOT_UNPLUG => format!("HOT_UNPLUG_DETECTED: active_in_flight={}", a1),
                TAG_CONTROL_REQ => format!("CONTROL_REQ: bmRequestType=0x{:02x} bRequest=0x{:02x} wValue=0x{:04x} wIndex=0x{:04x}", a1 as u8, (a1 >> 8) as u8, (a2 & 0xFFFF) as u16, ((a2 >> 16) & 0xFFFF) as u16),
                TAG_CONTROL_ERR => format!("CONTROL_ERR: errno={} req_type=0x{:02x}", a1, a2 as u8),
                TAG_OPEN_DEVICE => format!("OPEN_DEVICE: fd={} handle=0x{:08x}", a1, a2 as u32),
                TAG_DETACH_DRIVER => format!("DETACH_DRIVER: iface={} res={}", a1, a2),
                TAG_CLAIM_INTERFACE => format!("CLAIM_INTERFACE: iface={} res={} attempt={}", a1, (a2 & 0xFFFF) as i16, (a2 >> 16) as i16),
                TAG_SET_ALT_SETTING => format!("SET_ALT_SETTING: iface={} alt={} res={}", a1, (a2 & 0xFF) as u8, (a2 >> 8) as i32),
                TAG_CLOCK_SET_CUR => format!("CLOCK_SET_CUR: id={} target_rate={} res={}", a1, a2 as u32, (a2 >> 32) as i32),
                TAG_CLOCK_GET_CUR => format!("CLOCK_GET_CUR: id={} read_rate={} res={}", a1, a2 as u32, (a2 >> 32) as i32),
                TAG_CLOCK_GET_RANGE => format!("CLOCK_GET_RANGE: id={} range_count={}", a1, a2),
                TAG_PROBE_START => format!("PROBE_START: fd={}", a1),
                TAG_PROBE_RATES => format!("PROBE_RATES: clock_id={} count={} rates_mask=0x{:x}", a1, (a2 & 0xFF) as u8, a2 >> 8),
                TAG_PROBE_COMPLETE => format!("PROBE_COMPLETE: sample_rate_count={} clock_id={} uac_version={}", a1, a2 as i32, (a2 >> 32) as i32),
                TAG_SET_CUR_VOL => format!("SET_CUR_VOL: bmReq=0x{:02x} bReq=0x{:02x} iface={} fu={} ch={} vol_raw={}", (a1 >> 8) as u8, a1 as u8, (a2 >> 32) as u8, (a2 >> 40) as u8, (a2 >> 16) as u8, a2 as i16),
                TAG_SET_CUR_MUTE => format!("SET_CUR_MUTE: bmReq=0x{:02x} bReq=0x{:02x} iface={} fu={} ch={} mute={}", (a1 >> 8) as u8, a1 as u8, (a2 >> 32) as u8, (a2 >> 40) as u8, (a2 >> 16) as u8, (a2 & 0xFF) != 0),
                TAG_GET_CUR_VOL => format!("GET_CUR_VOL: bmReq=0x{:02x} bReq=0x{:02x} iface={} fu={} ch={} read_raw={}", (a1 >> 8) as u8, a1 as u8, (a2 >> 32) as u8, (a2 >> 40) as u8, (a2 >> 16) as u8, a2 as i16),
                TAG_GET_CUR_MUTE => format!("GET_CUR_MUTE: bmReq=0x{:02x} bReq=0x{:02x} iface={} fu={} ch={} read_mute={}", (a1 >> 8) as u8, a1 as u8, (a2 >> 32) as u8, (a2 >> 40) as u8, (a2 >> 16) as u8, (a2 & 0xFF) != 0),
                TAG_RELEASE_INTERFACE => format!("RELEASE_INTERFACE: iface={} res={}", a1, a2),
                TAG_REATTACH_DRIVER => format!("REATTACH_DRIVER: iface={} res={}", a1, a2),
                _ => format!("UNKNOWN_TAG({}): arg1={} arg2={}", tag, a1, a2),
            };
            lines.push(format!("[native] @{}ms {}", ts, desc));
        }
        lines
    }
}

pub static GLOBAL_NATIVE_LOG: NativeRingLog = NativeRingLog::new();

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_native_ring_log_records_and_dumps() {
        GLOBAL_NATIVE_LOG.record(TAG_STREAM_START, 96000, 24);
        GLOBAL_NATIVE_LOG.record(TAG_PERIODIC_STATS, 0, 32768);
        GLOBAL_NATIVE_LOG.record(TAG_URB_SUBMIT_ERR, 19, 2);

        let lines = GLOBAL_NATIVE_LOG.dump_lines();
        assert!(lines.len() >= 3);
        assert!(lines.iter().any(|l| l.contains("STREAM_START") && l.contains("96000")));
        assert!(lines.iter().any(|l| l.contains("PERIODIC_STATS") && l.contains("32768")));
        assert!(lines.iter().any(|l| l.contains("URB_SUBMIT_ERR") && l.contains("errno=19")));
    }
}

