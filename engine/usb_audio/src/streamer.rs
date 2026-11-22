//! High-priority real-time USB isochronous audio streaming engine.
//! Zero allocations after setup, zero locks in the audio path.
//! Driven by rusb and libusb-1.0 isochronous transfers.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use rusb::UsbContext;

use super::device::UsbDeviceSession;
use super::ring_buffer::SpscRingBuffer;
use super::session::apply_pcm_ramp_frame;
use super::transfer::IsoTransferSlot;
use crate::log::{
    record_thread_sched_detail, GLOBAL_NATIVE_LOG, TAG_HOT_UNPLUG, TAG_JITTER_STATS,
    TAG_PERIODIC_STATS, TAG_STREAM_START, TAG_STREAM_STOP, TAG_THREAD_SCHED, TAG_URB_PACKET_ERR,
    TAG_URB_REAP_ERR, TAG_URB_SUBMIT_ERR,
};

pub const NUM_AUDIO_SLOTS: usize = 8;
pub const LATE_CALLBACK_THRESHOLD_US: u64 = 15_000;

/// Per-5s window jitter & scheduling telemetry accumulator (zero allocations, zero locks).
pub(crate) struct JitterWindowStats {
    pub max_cb_gap_us: u64,
    pub late_cb_15ms: u32,
    pub max_slots_wakeup: u32,
    pub min_ring_fill: usize,
    pub saw_fill_sample: bool,
    pub cpu_mask: u64,
    pub last_cpu: i32,
}

impl JitterWindowStats {
    pub fn new(ring_capacity: usize) -> Self {
        Self {
            max_cb_gap_us: 0,
            late_cb_15ms: 0,
            max_slots_wakeup: 0,
            min_ring_fill: ring_capacity,
            saw_fill_sample: false,
            cpu_mask: 0,
            last_cpu: -1,
        }
    }

    #[inline]
    pub fn record_wakeup(&mut self, gap_us: Option<u64>, slots_completed: u32, cpu: i32) {
        if let Some(gap) = gap_us {
            if gap > self.max_cb_gap_us {
                self.max_cb_gap_us = gap;
            }
            if gap > LATE_CALLBACK_THRESHOLD_US {
                self.late_cb_15ms = self.late_cb_15ms.saturating_add(1);
            }
        }
        if slots_completed > self.max_slots_wakeup {
            self.max_slots_wakeup = slots_completed;
        }
        if cpu >= 0 {
            self.last_cpu = cpu;
            if cpu < 48 {
                self.cpu_mask |= 1u64 << (cpu as u64);
            }
        }
    }

    #[inline]
    pub fn record_fill(&mut self, fill_bytes: usize) {
        if !self.saw_fill_sample || fill_bytes < self.min_ring_fill {
            self.min_ring_fill = fill_bytes;
            self.saw_fill_sample = true;
        }
    }

    #[inline]
    pub fn reset(&mut self, current_fill: usize) {
        self.max_cb_gap_us = 0;
        self.late_cb_15ms = 0;
        self.max_slots_wakeup = 0;
        self.min_ring_fill = current_fill;
        self.saw_fill_sample = false;
        self.cpu_mask = 0;
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn read_thread_cgroup(tid: i32) -> String {
    let path = format!("/proc/self/task/{}/cgroup", tid);
    match std::fs::read_to_string(path) {
        Ok(text) => text
            .lines()
            .map(str::trim)
            .filter(|l| !l.is_empty())
            .collect::<Vec<_>>()
            .join(";"),
        Err(_) => "unreadable".to_string(),
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn read_thread_cgroup(_tid: i32) -> String {
    "unsupported".to_string()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[inline]
fn current_cpu() -> i32 {
    unsafe { libc::sched_getcpu() }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
#[inline]
fn current_cpu() -> i32 {
    -1
}

#[cfg(any(target_os = "linux", target_os = "android"))]
fn current_tid() -> i32 {
    unsafe { libc::syscall(libc::SYS_gettid) as i32 }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn current_tid() -> i32 {
    0
}

#[derive(Clone)]
pub struct StreamConfig {
    pub endpoint: u8,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channels: u32,
    pub subslot_size: u32,
    pub is_high_speed: bool,
    pub is_dop: bool,
    pub feedback_ep: u8,
}

pub struct UsbStreamer {
    config: StreamConfig,
    pub session: Arc<UsbDeviceSession>,
    ring_buffer: Arc<SpscRingBuffer>,
    running: Arc<AtomicBool>,
    pub paused: Arc<AtomicBool>,
    pub flushing: Arc<AtomicBool>,
    pub mute_ramp: Arc<AtomicBool>,
    thread_handle: Option<JoinHandle<()>>,
}

impl UsbStreamer {
    pub fn new(session: Arc<UsbDeviceSession>, config: StreamConfig) -> Self {
        Self::with_shared(
            session,
            config,
            Arc::new(SpscRingBuffer::new(1048576)),
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicBool::new(false)),
            Arc::new(AtomicBool::new(false)),
        )
    }

    pub fn with_shared(
        session: Arc<UsbDeviceSession>,
        config: StreamConfig,
        ring_buffer: Arc<SpscRingBuffer>,
        running: Arc<AtomicBool>,
        paused: Arc<AtomicBool>,
        flushing: Arc<AtomicBool>,
        mute_ramp: Arc<AtomicBool>,
    ) -> Self {
        Self {
            config,
            session,
            ring_buffer,
            running,
            paused,
            flushing,
            mute_ramp,
            thread_handle: None,
        }
    }

    pub fn update_config(&mut self, config: StreamConfig) {
        self.config = config;
    }

    #[inline]
    pub fn ring_buffer(&self) -> &Arc<SpscRingBuffer> {
        &self.ring_buffer
    }

    pub fn pause(&self) {
        self.paused.store(true, Ordering::Release);
    }

    pub fn resume(&self) {
        self.paused.store(false, Ordering::Release);
        self.flushing.store(false, Ordering::Release);
        self.mute_ramp.store(false, Ordering::Release);
    }

    pub fn resume_after_flush(&self) {
        self.flushing.store(false, Ordering::Release);
    }

    pub fn set_mute_ramp(&self, muted: bool) {
        self.mute_ramp.store(muted, Ordering::Release);
    }

    pub fn start(&mut self) -> Result<(), i32> {
        self.start_with_fade_in(false)
    }

    pub fn start_with_fade_in(&mut self, fade_in: bool) -> Result<(), i32> {
        if self.running.load(Ordering::Acquire) {
            return Ok(());
        }

        self.running.store(true, Ordering::Release);
        self.paused.store(false, Ordering::Release);
        self.flushing.store(initial_flushing_state(&self.ring_buffer), Ordering::Release);
        let config = self.config.clone();
        let session = Arc::clone(&self.session);
        let ring_buffer = Arc::clone(&self.ring_buffer);
        let running = Arc::clone(&self.running);
        let paused = Arc::clone(&self.paused);
        let flushing = Arc::clone(&self.flushing);
        let mute_ramp = Arc::clone(&self.mute_ramp);

        let handle = std::thread::Builder::new()
            .name("UsbIsoAudio".into())
            .spawn(move || {
                run_streaming_loop(
                    config,
                    session,
                    ring_buffer,
                    running,
                    paused,
                    flushing,
                    mute_ramp,
                    fade_in,
                );
            })
            .map_err(|_| -1)?;

        self.thread_handle = Some(handle);
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), i32> {
        if !self.running.load(Ordering::Acquire) {
            return Ok(());
        }

        // Click-free 5ms fade-out before stopping URBs if currently playing
        if !self.paused.swap(true, Ordering::AcqRel) {
            std::thread::sleep(Duration::from_millis(12));
        }

        self.running.store(false, Ordering::Release);
        if let Some(handle) = self.thread_handle.take() {
            let _ = handle.join();
        }
        Ok(())
    }

    /// Reconfigure streaming format (rate, alt, bit depth, channels, subslot) in place
    /// without closing the device handle or releasing claimed interfaces.
    pub fn reconfigure(
        &mut self,
        streaming_iface: u8,
        target_alt: u8,
        new_config: StreamConfig,
    ) -> Result<(), i32> {
        let _ = self.stop();
        self.clear();
        self.session.reconfigure_format(
            streaming_iface,
            target_alt,
            new_config.sample_rate,
            new_config.endpoint,
        )?;
        self.config = new_config;
        Ok(())
    }

    pub fn is_running(&self) -> bool {
        self.running.load(Ordering::Acquire)
    }

    pub fn is_paused(&self) -> bool {
        self.paused.load(Ordering::Acquire)
    }

    pub fn is_flushing(&self) -> bool {
        self.flushing.load(Ordering::Acquire)
    }

    pub fn clear(&self) {
        self.flushing.store(true, Ordering::Release);
        self.ring_buffer.clear();
    }
}

impl Drop for UsbStreamer {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

fn run_streaming_loop(
    config: StreamConfig,
    session: Arc<UsbDeviceSession>,
    ring_buffer: Arc<SpscRingBuffer>,
    running: Arc<AtomicBool>,
    paused: Arc<AtomicBool>,
    flushing: Arc<AtomicBool>,
    mute_ramp: Arc<AtomicBool>,
    fade_in_on_start: bool,
) {
    let _handle_pin = Arc::clone(&session.handle);
    let _context_pin = Arc::clone(&session.context);

    // Elevate thread priority for real-time isochronous audio and log scheduling state.
    let tid = current_tid();
    let (setprio_rc, setprio_errno, actual_nice, sched_policy) = {
        #[cfg(any(target_os = "linux", target_os = "android"))]
        unsafe {
            let rc = libc::setpriority(libc::PRIO_PROCESS, 0, -19);
            let err = if rc != 0 {
                std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)
            } else {
                0
            };
            let nice = libc::getpriority(libc::PRIO_PROCESS, 0);
            let policy = libc::sched_getscheduler(0);
            (rc, err, nice, policy)
        }
        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        {
            (0, 0, 0, 0)
        }
    };
    let start_cpu = current_cpu();
    let cgroup = read_thread_cgroup(tid);
    let sched_a2 = ((setprio_rc as i8 as u8) as u64)
        | (((setprio_errno as i8 as u8) as u64) << 8)
        | (((actual_nice as i8 as u8) as u64) << 16)
        | (((sched_policy as i8 as u8) as u64) << 24)
        | (((start_cpu as i8 as u8) as u64) << 32);
    GLOBAL_NATIVE_LOG.record3(TAG_THREAD_SCHED, tid, sched_a2 as i64, 0);
    record_thread_sched_detail(format!(
        "tid={} setprio_rc={} setprio_errno={} nice={} policy={} cpu={} cgroup={}",
        tid, setprio_rc, setprio_errno, actual_nice, sched_policy, start_cpu, cgroup
    ));

    let raw_handle = session.handle.as_raw();
    GLOBAL_NATIVE_LOG.record(
        TAG_STREAM_START,
        config.sample_rate as i32,
        ((config.endpoint as i64) << 48)
            | ((session.dup_fd as i64 & 0xFFFF) << 32)
            | (raw_handle as i64 & 0xFFFFFFFF),
    );
    let mut last_stat_instant = Instant::now();

    // Ensure audio streaming interface is claimed and in target alternate setting before submitting URBs
    let streaming_iface = session.streaming_iface.load(Ordering::Acquire);
    let target_alt = session.target_alt.load(Ordering::Acquire);
    let _ = session.handle.detach_kernel_driver(streaming_iface);
    let _ = session.handle.claim_interface(streaming_iface);
    let _ = session.handle.set_alternate_setting(streaming_iface, target_alt);

    // High-Speed (125 µs) uses 32 packets per slot: 8 slots * 32 packets = 256 packets = 32 ms cushion.
    // Full-Speed (1 ms) uses 4 packets per slot: 8 slots * 4 packets = 32 packets = 32 ms cushion.
    let packets_per_slot: usize = if config.is_high_speed { 32 } else { 4 };
    let service_interval_us: u64 = if config.is_high_speed { 125 } else { 1000 };

    let bytes_per_sample = config.subslot_size.max(1) as usize;
    let bytes_per_frame = bytes_per_sample * config.channels as usize;

    let nominal_rate = config.sample_rate;
    let max_frames_per_packet = (((nominal_rate as u64 * service_interval_us) / 1_000_000) as usize + 8).max(16);
    let max_packet_size = max_frames_per_packet * bytes_per_frame;

    // Pre-allocate audio transfer slots (zero allocations after this point)
    let mut slots: Vec<IsoTransferSlot> = (0..NUM_AUDIO_SLOTS)
        .filter_map(|_| IsoTransferSlot::new(packets_per_slot, max_packet_size).ok())
        .collect();

    if slots.len() != NUM_AUDIO_SLOTS {
        GLOBAL_NATIVE_LOG.record(TAG_STREAM_START, -12, 0); // ENOMEM
        return;
    }

    let mut packet_lengths = vec![0usize; packets_per_slot];
    let mut nominal_accum: u64 = 0;
    let mut dop_marker = 0x05u8;

    // Feedback endpoint handling (optional: only if feedback_ep != 0)
    let mut feedback_slot = if config.feedback_ep != 0 {
        IsoTransferSlot::new(1, 4).ok()
    } else {
        None
    };
    let mut target_rate_hz: f64 = nominal_rate as f64;

    let init_playing = !fade_in_on_start
        && !paused.load(Ordering::Acquire)
        && !flushing.load(Ordering::Acquire)
        && !mute_ramp.load(Ordering::Acquire);
    let mut current_gain: f32 = if init_playing { 1.0 } else { 0.0 };
    let ramp_frames = (nominal_rate as f32 * 0.003).max(1.0); // 3 ms linear gain ramp (2-4 ms spec)
    let gain_step = 1.0f32 / ramp_frames;
    let bit_depth = config.bit_depth;

    let initial_target_gain = if !paused.load(Ordering::Acquire)
        && !flushing.load(Ordering::Acquire)
        && !mute_ramp.load(Ordering::Acquire)
    {
        1.0f32
    } else {
        0.0f32
    };
    for (slot_idx, slot) in slots.iter_mut().enumerate() {
        fill_and_submit_audio_slot(
            slot,
            slot_idx,
            &mut packet_lengths,
            &mut nominal_accum,
            target_rate_hz.round() as u64,
            service_interval_us,
            bytes_per_frame,
            bytes_per_sample,
            config.channels as usize,
            bit_depth,
            config.is_dop,
            &mut dop_marker,
            &ring_buffer,
            session.handle.as_raw(),
            config.endpoint,
            initial_target_gain,
            &mut current_gain,
            gain_step,
        );
    }

    // Submit initial feedback transfer if endpoint present
    if let Some(fb) = feedback_slot.as_mut() {
        unsafe {
            let _ = fb.submit_in(session.handle.as_raw(), config.feedback_ep, 4, 1000);
        }
    }

    ring_buffer.active_slots.store(NUM_AUDIO_SLOTS as u32, Ordering::Relaxed);
    let mut jitter = JitterWindowStats::new(ring_buffer.available_read());
    let mut last_audio_cb: Option<Instant> = None;

    // Main real-time event loop
    while running.load(Ordering::Acquire) {
        // Handle events with a 5 ms timeout
        if let Err(e) = session.context.handle_events(Some(Duration::from_millis(5))) {
            GLOBAL_NATIVE_LOG.record(TAG_URB_REAP_ERR, -1, 0);
            if e == rusb::Error::NoDevice {
                GLOBAL_NATIVE_LOG.record(TAG_HOT_UNPLUG, -19, 0);
                break;
            }
        }

        // Process completed feedback transfer (if present)
        if let Some(fb) = feedback_slot.as_mut() {
            if fb.completion.completed.swap(false, Ordering::AcqRel) {
                fb.mark_completed();
                let actual_len = fb.completion.actual_length.load(Ordering::Acquire);
                if actual_len >= 3 {
                    process_feedback_report(
                        &fb.buffer()[..actual_len],
                        config.is_high_speed,
                        nominal_rate,
                        &mut target_rate_hz,
                    );
                }
                // Resubmit feedback transfer
                unsafe {
                    let _ = fb.submit_in(session.handle.as_raw(), config.feedback_ep, 4, 1000);
                }
            }
        }

        let target_gain = if !paused.load(Ordering::Acquire)
            && !flushing.load(Ordering::Acquire)
            && !mute_ramp.load(Ordering::Acquire)
        {
            1.0f32
        } else {
            0.0f32
        };

        // Process completed audio slots
        let mut completed_this_wakeup: u32 = 0;
        for (slot_idx, slot) in slots.iter_mut().enumerate() {
            if slot.completion.completed.swap(false, Ordering::AcqRel) {
                completed_this_wakeup = completed_this_wakeup.saturating_add(1);
                slot.mark_completed();
                let status = slot.completion.status.load(Ordering::Acquire);
                let pkt_errs = slot.completion.pkt_err_count.load(Ordering::Acquire);
                let pkt_shorts = slot.completion.pkt_short_count.load(Ordering::Acquire);
                let last_pkt_err = slot.completion.last_pkt_err_status.load(Ordering::Acquire);
                let first_err_idx = slot.completion.first_err_pkt_idx.load(Ordering::Acquire);

                if pkt_errs > 0 || pkt_shorts > 0 {
                    if pkt_errs > 0 {
                        ring_buffer.pkt_errors.fetch_add(pkt_errs as u64, Ordering::Relaxed);
                        ring_buffer.last_pkt_err_status.store(last_pkt_err, Ordering::Relaxed);
                    }
                    if pkt_shorts > 0 {
                        ring_buffer.pkt_shorts.fetch_add(pkt_shorts as u64, Ordering::Relaxed);
                    }
                    let packed_a2 = ((slot_idx as u64) & 0xFF)
                        | (((first_err_idx.max(0) as u64) & 0xFFFF) << 8)
                        | (((pkt_errs.min(0xFFFF) as u64) & 0xFFFF) << 24)
                        | (((pkt_shorts.min(0xFFFF) as u64) & 0xFFFF) << 40);
                    GLOBAL_NATIVE_LOG.record3(
                        TAG_URB_PACKET_ERR,
                        if pkt_errs > 0 { last_pkt_err } else { status },
                        packed_a2 as i64,
                        status as i64,
                    );
                }

                if status != libusb1_sys::constants::LIBUSB_TRANSFER_COMPLETED {
                    ring_buffer.urb_errors.fetch_add(1, Ordering::Relaxed);
                    if pkt_errs == 0 && pkt_shorts == 0 {
                        let packed_a2 = ((slot_idx as u64) & 0xFF) | (0xFFFFu64 << 8);
                        GLOBAL_NATIVE_LOG.record3(
                            TAG_URB_PACKET_ERR,
                            status,
                            packed_a2 as i64,
                            status as i64,
                        );
                    }
                    if status == libusb1_sys::constants::LIBUSB_TRANSFER_NO_DEVICE {
                        GLOBAL_NATIVE_LOG.record(TAG_HOT_UNPLUG, -19, 0);
                        running.store(false, Ordering::Release);
                        break;
                    }
                }

                if target_gain > 0.0 {
                    jitter.record_fill(ring_buffer.available_read());
                }

                // Refill slot from ring buffer and resubmit
                fill_and_submit_audio_slot(
                    slot,
                    slot_idx,
                    &mut packet_lengths,
                    &mut nominal_accum,
                    target_rate_hz.round() as u64,
                    service_interval_us,
                    bytes_per_frame,
                    bytes_per_sample,
                    config.channels as usize,
                    bit_depth,
                    config.is_dop,
                    &mut dop_marker,
                    &ring_buffer,
                    session.handle.as_raw(),
                    config.endpoint,
                    target_gain,
                    &mut current_gain,
                    gain_step,
                );
            }
        }

        if completed_this_wakeup > 0 {
            let now = Instant::now();
            let gap_us = last_audio_cb.map(|prev| now.duration_since(prev).as_micros() as u64);
            last_audio_cb = Some(now);
            let cpu = current_cpu();
            jitter.record_wakeup(gap_us, completed_this_wakeup, cpu);
            if let Some(gap) = gap_us {
                if gap > LATE_CALLBACK_THRESHOLD_US {
                    ring_buffer.late_callbacks.fetch_add(1, Ordering::Relaxed);
                }
                let prev_max = ring_buffer.max_cb_gap_us.load(Ordering::Relaxed);
                if gap > prev_max {
                    ring_buffer.max_cb_gap_us.store(gap, Ordering::Relaxed);
                }
            }
            let prev_max_w = ring_buffer.max_slots_per_wakeup.load(Ordering::Relaxed);
            if completed_this_wakeup > prev_max_w {
                ring_buffer.max_slots_per_wakeup.store(completed_this_wakeup, Ordering::Relaxed);
            }
        }

        // Periodic stats + jitter telemetry logging (once every 5 seconds)
        if last_stat_instant.elapsed() >= Duration::from_secs(5) {
            last_stat_instant = Instant::now();
            let underruns = ring_buffer.underruns.load(Ordering::Relaxed);
            let fill = ring_buffer.available_read();
            let pkt_errs = ring_buffer.pkt_errors.load(Ordering::Relaxed);
            let pkt_shorts = ring_buffer.pkt_shorts.load(Ordering::Relaxed);
            let last_pkt_err = ring_buffer.last_pkt_err_status.load(Ordering::Relaxed);
            let periodic_a3 = ((pkt_errs.min(0xFFFFFF) as u64) << 40)
                | ((pkt_shorts.min(0xFFFFFF) as u64) << 16)
                | ((last_pkt_err as i16 as u16) as u64);
            GLOBAL_NATIVE_LOG.record3(TAG_PERIODIC_STATS, underruns as i32, fill as i64, periodic_a3 as i64);

            let in_flight = slots.iter().filter(|s| s.in_flight).count() as u64;
            let min_fill = if jitter.saw_fill_sample { jitter.min_ring_fill } else { fill };
            ring_buffer.min_ring_fill.store(min_fill, Ordering::Relaxed);
            let jitter_a2 = (((jitter.late_cb_15ms.min(0xFFFFFF) as u64) & 0xFFFFFF) << 40)
                | (((jitter.max_slots_wakeup.min(0xFF) as u64) & 0xFF) << 32)
                | ((min_fill as u64) & 0xFFFF_FFFF);
            let jitter_a3 = ((in_flight.min(0xFF) & 0xFF) << 56)
                | ((((jitter.last_cpu as i8) as u8) as u64) << 48)
                | (jitter.cpu_mask & 0xFFFF_FFFF_FFFF);
            GLOBAL_NATIVE_LOG.record3(
                TAG_JITTER_STATS,
                jitter.max_cb_gap_us.min(i32::MAX as u64) as i32,
                jitter_a2 as i64,
                jitter_a3 as i64,
            );
            jitter.reset(fill);
        }
    }

    // Teardown: cancel in-flight audio and feedback transfers
    for slot in slots.iter_mut() {
        if slot.in_flight {
            let _ = slot.cancel();
        }
    }
    if let Some(fb) = feedback_slot.as_mut() {
        if fb.in_flight {
            let _ = fb.cancel();
        }
    }

    // Drain event loop until all transfers complete or timeout (2000 ms)
    let drain_deadline = Instant::now() + Duration::from_millis(2000);
    while (slots.iter().any(|s| s.in_flight) || feedback_slot.as_ref().map_or(false, |fb| fb.in_flight))
        && Instant::now() < drain_deadline
    {
        let _ = session.context.handle_events(Some(Duration::from_millis(10)));
        for slot in slots.iter_mut() {
            if slot.completion.completed.swap(false, Ordering::AcqRel) {
                slot.mark_completed();
            }
        }
        if let Some(fb) = feedback_slot.as_mut() {
            if fb.completion.completed.swap(false, Ordering::AcqRel) {
                fb.mark_completed();
            }
        }
    }

    let total_frames = ring_buffer.frames_consumed.load(Ordering::Relaxed);
    let total_underruns = ring_buffer.underruns.load(Ordering::Relaxed);
    GLOBAL_NATIVE_LOG.record(TAG_STREAM_STOP, total_frames as i32, total_underruns as i64);
}

fn fill_and_submit_audio_slot(
    slot: &mut IsoTransferSlot,
    slot_idx: usize,
    packet_lengths: &mut [usize],
    nominal_accum: &mut u64,
    effective_rate: u64,
    service_interval_us: u64,
    bytes_per_frame: usize,
    bytes_per_sample: usize,
    channels: usize,
    bit_depth: u32,
    is_dop: bool,
    dop_marker: &mut u8,
    ring_buffer: &Arc<SpscRingBuffer>,
    dev_handle: *mut libusb1_sys::libusb_device_handle,
    endpoint: u8,
    target_gain: f32,
    current_gain: &mut f32,
    gain_step: f32,
) {
    let mut total_bytes = 0usize;
    let buf = slot.buffer_mut();

    for (_p_idx, pkt_len) in packet_lengths.iter_mut().enumerate() {
        // Integer remainder packet scheduler (exact, no IEEE-754 float drift)
        let scheduled_frames = schedule_packet_frames(nominal_accum, effective_rate, service_interval_us);

        // Panic-free bounds clamp against the pre-allocated transfer buffer
        let frames = clamp_packet_frames(scheduled_frames, total_bytes, buf.len(), bytes_per_frame);
        let needed_bytes = frames * bytes_per_frame;
        *pkt_len = needed_bytes;

        let pkt_slice = &mut buf[total_bytes..total_bytes + needed_bytes];

        if is_dop {
            // DoP 1.1 packing: 16-bit DSD payload with alternating 0x05 / 0xFA marker in top byte
            let pcm24_needed = frames * channels * 3;
            if target_gain > 0.0 && ring_buffer.available_read() >= pcm24_needed {
                for f in 0..frames {
                    for c in 0..channels {
                        let mut raw_bytes = [0u8; 3];
                        ring_buffer.read(&mut raw_bytes);
                        let dst_idx = f * bytes_per_frame + c * bytes_per_sample;
                        pkt_slice[dst_idx] = raw_bytes[0];
                        pkt_slice[dst_idx + 1] = raw_bytes[1];
                        pkt_slice[dst_idx + 2] = *dop_marker;
                        if bytes_per_sample == 4 {
                            pkt_slice[dst_idx + 3] = 0;
                        }
                    }
                    *dop_marker = if *dop_marker == 0x05 { 0xFA } else { 0x05 };
                }
                ring_buffer.frames_consumed.fetch_add(frames as u64, Ordering::Relaxed);
            } else {
                // DoP silence: fill with DSD silence marker (no underrun counted when paused/flushing)
                if target_gain > 0.0 {
                    ring_buffer.underruns.fetch_add(1, Ordering::Relaxed);
                }
                for f in 0..frames {
                    for c in 0..channels {
                        let dst_idx = f * bytes_per_frame + c * bytes_per_sample;
                        pkt_slice[dst_idx] = 0x69;
                        pkt_slice[dst_idx + 1] = 0x69;
                        pkt_slice[dst_idx + 2] = *dop_marker;
                        if bytes_per_sample == 4 {
                            pkt_slice[dst_idx + 3] = 0;
                        }
                    }
                    *dop_marker = if *dop_marker == 0x05 { 0xFA } else { 0x05 };
                }
            }
            *current_gain = target_gain;
        } else {
            // Standard PCM streaming with click-free 5ms gain ramp on pause/resume
            let is_ramping = (*current_gain != target_gain) || (*current_gain > 0.0 && *current_gain < 1.0);
            if target_gain == 0.0 && *current_gain == 0.0 {
                // Fully paused: zero-fill, no underrun counted
                pkt_slice.fill(0);
            } else if !is_ramping && target_gain == 1.0 && *current_gain == 1.0 {
                // Fully playing: bit-perfect passthrough
                if ring_buffer.available_read() >= needed_bytes {
                    let read = ring_buffer.read(pkt_slice);
                    if read == needed_bytes {
                        ring_buffer.frames_consumed.fetch_add(frames as u64, Ordering::Relaxed);
                    } else {
                        pkt_slice[read..].fill(0);
                        ring_buffer.underruns.fetch_add(1, Ordering::Relaxed);
                    }
                } else {
                    pkt_slice.fill(0);
                    ring_buffer.underruns.fetch_add(1, Ordering::Relaxed);
                }
            } else {
                // Ramping gain 1.0 -> 0.0 (pause) or 0.0 -> 1.0 (resume)
                if ring_buffer.available_read() >= needed_bytes {
                    let read = ring_buffer.read(pkt_slice);
                    if read == needed_bytes {
                        ring_buffer.frames_consumed.fetch_add(frames as u64, Ordering::Relaxed);
                    } else {
                        pkt_slice[read..].fill(0);
                    }

                    for f in 0..frames {
                        if target_gain > *current_gain {
                            *current_gain = (*current_gain + gain_step).min(target_gain);
                        } else if target_gain < *current_gain {
                            *current_gain = (*current_gain - gain_step).max(target_gain);
                        }
                        let g = *current_gain;
                        if g < 0.9999f32 {
                            let off = f * bytes_per_frame;
                            apply_pcm_ramp_frame(
                                &mut pkt_slice[off..off + bytes_per_frame],
                                channels,
                                bit_depth,
                                bytes_per_sample,
                                g,
                            );
                        }
                    }
                } else {
                    pkt_slice.fill(0);
                    if target_gain > 0.0 {
                        ring_buffer.underruns.fetch_add(1, Ordering::Relaxed);
                    }
                    *current_gain = target_gain;
                }
            }
        }

        total_bytes += needed_bytes;
    }

    unsafe {
        if let Err(e) = slot.submit(dev_handle, endpoint, total_bytes, packet_lengths, 1000) {
            ring_buffer.urb_errors.fetch_add(1, Ordering::Relaxed);
            GLOBAL_NATIVE_LOG.record(TAG_URB_SUBMIT_ERR, e, slot_idx as i64);
        }
    }
}

/// Process explicit feedback reports from the DAC's feedback endpoint.
/// UAC 2.0 (High-Speed) reports 16.16 format in Fs per microframe.
/// UAC 1.0 (Full-Speed) reports 10.14 format in Fs per frame.
fn process_feedback_report(
    bytes: &[u8],
    is_high_speed: bool,
    nominal_rate: u32,
    target_rate_hz: &mut f64,
) {
    let raw_val: u32 = if bytes.len() >= 4 {
        u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
    } else if bytes.len() >= 3 {
        (bytes[0] as u32) | ((bytes[1] as u32) << 8) | ((bytes[2] as u32) << 16)
    } else {
        return;
    };

    if raw_val == 0 {
        return;
    }

    let measured_hz: f64 = if is_high_speed {
        (raw_val as f64 / 65536.0) * 8000.0
    } else {
        (raw_val as f64 / 16384.0) * 1000.0
    };

    // Clamp within +/- 2% of nominal rate
    let min_rate = nominal_rate as f64 * 0.98;
    let max_rate = nominal_rate as f64 * 1.02;
    let clamped_hz = measured_hz.clamp(min_rate, max_rate);

    // Exponential moving average: 80% previous + 20% measurement
    *target_rate_hz = (*target_rate_hz * 0.8) + (clamped_hz * 0.2);
}

#[inline]
pub(crate) fn schedule_packet_frames(
    nominal_accum: &mut u64,
    effective_rate: u64,
    service_interval_us: u64,
) -> usize {
    *nominal_accum += effective_rate.saturating_mul(service_interval_us);
    let frames = (*nominal_accum / 1_000_000) as usize;
    *nominal_accum %= 1_000_000;
    frames
}

#[inline]
pub(crate) fn clamp_packet_frames(
    scheduled_frames: usize,
    total_bytes: usize,
    buf_len: usize,
    bytes_per_frame: usize,
) -> usize {
    if bytes_per_frame == 0 {
        return 0;
    }
    let remaining_frames = buf_len.saturating_sub(total_bytes) / bytes_per_frame;
    scheduled_frames.min(remaining_frames)
}

#[inline]
pub(crate) fn initial_flushing_state(ring_buffer: &SpscRingBuffer) -> bool {
    ring_buffer.available_read() == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packet_frame_clamp_prevents_buffer_overflow() {
        // Normal packet fits
        assert_eq!(clamp_packet_frames(12, 0, 384, 8), 12);
        // Oversized scheduled packet clamps to remaining capacity (zero panic)
        assert_eq!(clamp_packet_frames(100, 360, 384, 8), 3);
        // Full buffer returns 0
        assert_eq!(clamp_packet_frames(10, 384, 384, 8), 0);
        // Zero bytes_per_frame cannot divide by zero
        assert_eq!(clamp_packet_frames(10, 0, 384, 0), 0);
    }

    #[test]
    fn feedback_report_clamps_within_two_percent() {
        let mut target = 44100.0;
        // Wild feedback report (10x nominal) must clamp to +2%
        let wild_raw = ((441000.0 / 8000.0) * 65536.0) as u32;
        process_feedback_report(&wild_raw.to_le_bytes(), true, 44100, &mut target);
        assert!(target <= 44100.0 * 1.02 + 0.01);
        assert!(target > 44100.0);
    }

    #[test]
    fn integer_remainder_scheduler_maintains_exact_average_rate() {
        for (rate, interval_us, packets) in [(44100u64, 125u64, 8000usize), (48000, 125, 8000), (176400, 1000, 1000)] {
            let mut accum = 0u64;
            let mut total_frames = 0u64;
            for _ in 0..packets {
                total_frames += schedule_packet_frames(&mut accum, rate, interval_us) as u64;
            }
            assert_eq!(total_frames, rate, "drift at rate={rate}, interval={interval_us}us");
            assert_eq!(accum, 0);
        }
    }

    #[test]
    fn initial_flushing_reflects_ring_buffer_fill() {
        let rb = SpscRingBuffer::new(1024);
        assert!(initial_flushing_state(&rb));
        let pcm = [1u8; 64];
        let written = unsafe { rb.write_raw(pcm.as_ptr(), pcm.len()) };
        assert_eq!(written, 64);
        assert!(!initial_flushing_state(&rb));
        rb.clear();
        assert!(initial_flushing_state(&rb));
    }

    #[test]
    fn jitter_window_tracks_max_gap_late_callbacks_min_fill_and_cpu_mask() {
        let mut jw = JitterWindowStats::new(1024 * 1024);
        jw.record_wakeup(None, 1, 2);
        jw.record_wakeup(Some(4_000), 1, 2);
        jw.record_wakeup(Some(18_500), 6, 7);
        jw.record_wakeup(Some(15_001), 2, 0);
        jw.record_fill(120_000);
        jw.record_fill(8_192);
        jw.record_fill(64_000);

        assert_eq!(jw.max_cb_gap_us, 18_500);
        assert_eq!(jw.late_cb_15ms, 2);
        assert_eq!(jw.max_slots_wakeup, 6);
        assert_eq!(jw.min_ring_fill, 8_192);
        assert_eq!(jw.last_cpu, 0);
        assert_eq!(jw.cpu_mask, (1u64 << 2) | (1u64 << 7) | (1u64 << 0));

        jw.reset(50_000);
        assert_eq!(jw.max_cb_gap_us, 0);
        assert_eq!(jw.late_cb_15ms, 0);
        assert_eq!(jw.max_slots_wakeup, 0);
        assert_eq!(jw.min_ring_fill, 50_000);
        assert_eq!(jw.cpu_mask, 0);
    }
}
