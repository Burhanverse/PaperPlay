use std::collections::VecDeque;
use std::sync::atomic::{AtomicI32, AtomicI64, AtomicU64, AtomicU8, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub const NATIVE_LOG_CAPACITY: usize = 2048;
const MAX_SCHED_DETAIL_LINES: usize = 16;

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
pub const TAG_JITTER_STATS: u8 = 26;
pub const TAG_THREAD_SCHED: u8 = 27;
pub const TAG_CUSHION_ESCALATE: u8 = 28;
pub const TAG_CTRL_CMD: u8 = 29;

pub struct NativeLogEntry {
    pub timestamp_ms: AtomicU64,
    pub tag_code: AtomicU8,
    pub arg1: AtomicI32,
    pub arg2: AtomicI64,
    pub arg3: AtomicI64,
}

impl NativeLogEntry {
    pub const fn new() -> Self {
        Self {
            timestamp_ms: AtomicU64::new(0),
            tag_code: AtomicU8::new(0),
            arg1: AtomicI32::new(0),
            arg2: AtomicI64::new(0),
            arg3: AtomicI64::new(0),
        }
    }
}

pub struct NativeRingLog {
    cursor: AtomicUsize,
    entries: [NativeLogEntry; NATIVE_LOG_CAPACITY],
}

static THREAD_SCHED_DETAILS: Mutex<Option<VecDeque<String>>> = Mutex::new(None);

/// Records human-readable thread scheduling & cgroup detail ONCE at stream startup
/// (never called from the real-time audio loop).
pub fn record_thread_sched_detail(detail: String) {
    let now_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    if let Ok(mut guard) = THREAD_SCHED_DETAILS.lock() {
        let q = guard.get_or_insert_with(|| VecDeque::with_capacity(MAX_SCHED_DETAIL_LINES));
        if q.len() >= MAX_SCHED_DETAIL_LINES {
            q.pop_front();
        }
        q.push_back(format!("[native] @{}ms THREAD_SCHED_DETAIL: {}", now_ms, detail));
    }
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
        self.record3(tag_code, arg1, arg2, 0);
    }

    pub fn record3(&self, tag_code: u8, arg1: i32, arg2: i64, arg3: i64) {
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        let idx = self.cursor.fetch_add(1, Ordering::Relaxed) % NATIVE_LOG_CAPACITY;
        let entry = &self.entries[idx];
        entry.timestamp_ms.store(now_ms, Ordering::Relaxed);
        entry.arg1.store(arg1, Ordering::Relaxed);
        entry.arg2.store(arg2, Ordering::Relaxed);
        entry.arg3.store(arg3, Ordering::Relaxed);
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

        let mut lines = Vec::with_capacity(count + MAX_SCHED_DETAIL_LINES);
        for i in start..cursor {
            let entry = &self.entries[i % NATIVE_LOG_CAPACITY];
            let tag = entry.tag_code.load(Ordering::Acquire);
            if tag == 0 {
                continue;
            }
            let ts = entry.timestamp_ms.load(Ordering::Relaxed);
            let a1 = entry.arg1.load(Ordering::Relaxed);
            let a2 = entry.arg2.load(Ordering::Relaxed);
            let a3 = entry.arg3.load(Ordering::Relaxed);

            let desc = match tag {
                TAG_URB_SUBMIT_ERR => format!("URB_SUBMIT_ERR: errno={} urb_idx={}", a1, a2),
                TAG_URB_REAP_ERR => format!("URB_REAP_ERR: errno={}", a1),
                TAG_URB_PACKET_ERR => {
                    let slot_idx = (a2 & 0xFF) as u8;
                    let pkt_idx = ((a2 >> 8) & 0xFFFF) as u16;
                    let pkt_errs = ((a2 >> 24) & 0xFFFF) as u16;
                    let pkt_shorts = ((a2 >> 40) & 0xFFFF) as u16;
                    let top_status = a3 as i32;
                    format!(
                        "URB_PACKET_ERR: status={} top_status={} slot={} pkt_idx={} pkt_errs={} pkt_shorts={}",
                        a1, top_status, slot_idx, pkt_idx, pkt_errs, pkt_shorts
                    )
                }
                TAG_PERIODIC_STATS => {
                    let pkt_errs = ((a3 as u64) >> 40) & 0xFFFFFF;
                    let pkt_shorts = ((a3 as u64) >> 16) & 0xFFFFFF;
                    let last_pkt_err = (a3 & 0xFFFF) as i16;
                    format!(
                        "PERIODIC_STATS: underruns={} ring_fill_bytes={} pkt_errs={} pkt_shorts={} last_pkt_err={}",
                        a1, a2, pkt_errs, pkt_shorts, last_pkt_err
                    )
                }
                TAG_JITTER_STATS => {
                    let max_cb_gap_us = a1 as u32;
                    let late_cb_15ms = ((a2 as u64) >> 40) & 0xFFFFFF;
                    let max_slots_wakeup = ((a2 as u64) >> 32) & 0xFF;
                    let min_ring_fill = (a2 as u64) & 0xFFFF_FFFF;
                    let slots_in_flight = ((a3 as u64) >> 56) & 0xFF;
                    let last_cpu = (((a3 as u64) >> 48) & 0xFF) as i8;
                    let cpu_mask = (a3 as u64) & 0xFFFF_FFFF_FFFF;
                    format!(
                        "JITTER_STATS: max_cb_gap_us={} late_cb_15ms={} max_slots_wakeup={} min_ring_fill={} slots_in_flight={} cpu_mask=0x{:x} last_cpu={}",
                        max_cb_gap_us, late_cb_15ms, max_slots_wakeup, min_ring_fill, slots_in_flight, cpu_mask, last_cpu
                    )
                }
                TAG_THREAD_SCHED => {
                    let tid = a1;
                    let setprio_rc = (a2 & 0xFF) as i8;
                    let setprio_errno = ((a2 >> 8) & 0xFF) as i8;
                    let actual_nice = ((a2 >> 16) & 0xFF) as i8;
                    let policy = ((a2 >> 24) & 0xFF) as i8;
                    let cpu = ((a2 >> 32) & 0xFF) as i8;
                    let fifo_rc = (a3 & 0xFF) as i8;
                    let fifo_errno = ((a3 >> 8) & 0xFF) as i8;
                    let jni_rc = ((a3 >> 16) & 0xFF) as i8;
                    let adpf_on = ((a3 >> 24) & 0xFF) != 0;
                    format!(
                        "THREAD_SCHED: tid={} setprio_rc={} setprio_errno={} nice={} policy={} cpu={} fifo_rc={} fifo_errno={} jni_prio_rc={} adpf={}",
                        tid, setprio_rc, setprio_errno, actual_nice, policy, cpu, fifo_rc, fifo_errno, jni_rc, adpf_on
                    )
                }
                TAG_CUSHION_ESCALATE => {
                    let old_slots = (a2 & 0xFFFF) as u16;
                    let new_slots = ((a2 >> 16) & 0xFFFF) as u16;
                    let reason_str = match a1 {
                        1 => "late_cb",
                        2 => "ring_drain",
                        3 => "underrun_policy",
                        4 => "multi_wakeup",
                        _ => "other",
                    };
                    format!(
                        "CUSHION_ESCALATE: reason={} old_slots={} new_slots={} trigger_val={}",
                        reason_str, old_slots, new_slots, a3
                    )
                }
                TAG_CTRL_CMD => {
                    let kind = match a1 {
                        1 => "SET_VOL",
                        2 => "SET_MUTE",
                        3 => "GET_VOL",
                        4 => "GET_MUTE",
                        _ => "OTHER",
                    };
                    format!("CTRL_CMD: kind={} ch={} val_or_res={}", kind, a2, a3)
                }
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
                _ => format!("UNKNOWN_TAG({}): arg1={} arg2={} arg3={}", tag, a1, a2, a3),
            };
            lines.push(format!("[native] @{}ms {}", ts, desc));
        }
        if let Ok(guard) = THREAD_SCHED_DETAILS.lock() {
            if let Some(q) = guard.as_ref() {
                for line in q.iter() {
                    lines.push(line.clone());
                }
            }
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
        let periodic_a3 = ((3u64 & 0xFFFFFF) << 40) | ((2u64 & 0xFFFFFF) << 16) | (1u16 as u64);
        GLOBAL_NATIVE_LOG.record3(TAG_PERIODIC_STATS, 0, 32768, periodic_a3 as i64);
        GLOBAL_NATIVE_LOG.record(TAG_URB_SUBMIT_ERR, 19, 2);
        let jitter_a2 = ((4u64 & 0xFFFFFF) << 40) | ((6u64 & 0xFF) << 32) | 4096u64;
        let jitter_a3 = ((16u64 & 0xFF) << 56) | ((5u64 & 0xFF) << 48) | 0x30u64;
        GLOBAL_NATIVE_LOG.record3(TAG_JITTER_STATS, 18500, jitter_a2 as i64, jitter_a3 as i64);
        record_thread_sched_detail("tid=123 cgroup=0::/top-app".to_string());

        let lines = GLOBAL_NATIVE_LOG.dump_lines();
        assert!(lines.len() >= 4);
        assert!(lines.iter().any(|l| l.contains("STREAM_START") && l.contains("96000")));
        assert!(lines.iter().any(|l| {
            l.contains("PERIODIC_STATS: underruns=0 ring_fill_bytes=32768 pkt_errs=3 pkt_shorts=2 last_pkt_err=1")
        }));
        assert!(lines.iter().any(|l| l.contains("URB_SUBMIT_ERR") && l.contains("errno=19")));
        assert!(lines.iter().any(|l| {
            l.contains("JITTER_STATS: max_cb_gap_us=18500 late_cb_15ms=4 max_slots_wakeup=6 min_ring_fill=4096 slots_in_flight=16 cpu_mask=0x30 last_cpu=5")
        }));
        assert!(lines.iter().any(|l| l.contains("THREAD_SCHED_DETAIL: tid=123 cgroup=0::/top-app")));
    }
}

