//! Coarse-grained USB DAC session state machine and zero-alloc PCM helpers.
//!
//! Enforces the safety invariants of the USB exclusive path cleanly under
//! both real libusb sessions and deterministic unit-test mocks:
//! - Kernel drivers detached & audio interfaces claimed in ascending order.
//! - Pre-session Feature Unit mute/volume saved ONCE on open (preserved across reconfigures).
//! - Sample rate (`CLOCK_GET_CUR`) and active alt (`GET_INTERFACE`) verified on reconfigure.
//! - Hardware volume forced + `GET_CUR`-verified before the first URB of any session/reconfigure.
//! - Pause grace halts URB streaming (0 spinning threads) while preserving claim + alt;
//!   resume restarts with a click-free 3 ms fade-in without re-claiming.
//! - Close restores baseline FU mute/volume while claimed -> alt 0 -> reverse release -> ascending reattach.

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;

use crate::device::{interface_release_and_reattach_plan, UsbDeviceSession};
use crate::ring_buffer::{SpscRingBuffer, DEFAULT_RING_CAPACITY};
use crate::streamer::{StreamConfig, UsbStreamer};

#[repr(i64)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionState {
    Closed = 0,
    Opened = 1,
    Configured = 2,
    Streaming = 3,
    PausedGrace = 4,
    Faulted = 5,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EndpointFormatConfig {
    pub streaming_iface: u8,
    pub target_alt: u8,
    pub endpoint: u8,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub channels: u32,
    pub subslot_size: u32,
    pub is_high_speed: bool,
    pub is_dop: bool,
    pub feedback_ep: u8,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeatureUnitTarget {
    pub fu_id: u8,
    pub control_iface: u8,
    pub channels: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SavedFuSetting {
    pub fu_id: u8,
    pub control_iface: u8,
    pub channel: u8,
    pub mute: Option<bool>,
    pub volume_raw: Option<i16>,
}

/// Hardware/OS USB control plane seam enabling deterministic testing without real hardware.
pub trait UsbControlTransport {
    fn detach_and_claim(&mut self, ifaces: &[u8]) -> Result<(), i32>;
    fn set_alt(&mut self, iface: u8, alt: u8) -> Result<(), i32>;
    fn get_alt(&mut self, iface: u8) -> Result<u8, i32>;
    fn set_clock_rate(&mut self, sample_rate: u32, endpoint: u8) -> Result<(), i32>;
    fn get_clock_rate(&mut self, endpoint: u8) -> Result<Option<u32>, i32>;
    fn read_fu_mute(&mut self, fu_id: u8, control_iface: u8, ch: u8) -> Result<bool, i32>;
    fn write_fu_mute(&mut self, fu_id: u8, control_iface: u8, ch: u8, mute: bool) -> Result<bool, i32>;
    fn read_fu_volume(&mut self, fu_id: u8, control_iface: u8, ch: u8) -> Result<i16, i32>;
    fn write_fu_volume(&mut self, fu_id: u8, control_iface: u8, ch: u8, vol_raw: i16) -> Result<i16, i32>;
    fn start_streaming(&mut self, cfg: &EndpointFormatConfig, fade_in: bool) -> Result<(), i32>;
    fn stop_streaming(&mut self) -> Result<(), i32>;
    fn release_interface(&mut self, iface: u8) -> Result<(), i32>;
    fn attach_kernel_driver(&mut self, iface: u8) -> Result<(), i32>;
}

pub struct UsbSessionCore<T: UsbControlTransport> {
    pub transport: T,
    state: SessionState,
    claimed_ifaces: Vec<u8>,
    has_hw_vol: bool,
    saved_baseline: Vec<SavedFuSetting>,
    baseline_restored: bool,
    volume_verified: bool,
    active_cfg: Option<EndpointFormatConfig>,
}

impl<T: UsbControlTransport> UsbSessionCore<T> {
    pub fn open(
        mut transport: T,
        claimed_ifaces: Vec<u8>,
        feature_units: Vec<FeatureUnitTarget>,
        has_hw_vol: bool,
    ) -> Result<Self, i32> {
        let mut ifaces = claimed_ifaces;
        ifaces.sort_unstable();
        ifaces.dedup();
        if let Err(err) = transport.detach_and_claim(&ifaces) {
            Self::release_all_then_reattach(&mut transport, &ifaces);
            return Err(err);
        }

        let mut saved_baseline = Vec::new();
        for fu in &feature_units {
            for &ch in &fu.channels {
                let mute = transport.read_fu_mute(fu.fu_id, fu.control_iface, ch);
                let vol = transport.read_fu_volume(fu.fu_id, fu.control_iface, ch);
                if mute.is_ok() || vol.is_ok() {
                    saved_baseline.push(SavedFuSetting {
                        fu_id: fu.fu_id,
                        control_iface: fu.control_iface,
                        channel: ch,
                        mute: mute.ok(),
                        volume_raw: vol.ok(),
                    });
                }
            }
        }

        Ok(Self {
            transport,
            state: SessionState::Opened,
            claimed_ifaces: ifaces,
            has_hw_vol,
            saved_baseline,
            baseline_restored: false,
            volume_verified: false,
            active_cfg: None,
        })
    }

    #[inline]
    pub fn state(&self) -> SessionState {
        self.state
    }

    #[inline]
    pub fn is_volume_verified(&self) -> bool {
        self.volume_verified
    }

    #[inline]
    pub fn active_config(&self) -> Option<&EndpointFormatConfig> {
        self.active_cfg.as_ref()
    }

    #[inline]
    pub fn saved_baseline(&self) -> &[SavedFuSetting] {
        &self.saved_baseline
    }

    pub fn reconfigure(&mut self, cfg: EndpointFormatConfig) -> Result<(), i32> {
        if self.state == SessionState::Closed || self.state == SessionState::Faulted {
            return Err(-22);
        }
        if self.state == SessionState::Streaming {
            let _ = self.transport.stop_streaming();
        }
        if let Some(prev) = self.active_cfg {
            if prev.streaming_iface != cfg.streaming_iface {
                let _ = self.transport.set_alt(prev.streaming_iface, 0);
            }
        }
        if let Err(e) = self.transport.set_alt(cfg.streaming_iface, 0) {
            self.state = SessionState::Faulted;
            return Err(e);
        }
        if let Err(e) = self.transport.set_clock_rate(cfg.sample_rate, cfg.endpoint) {
            self.state = SessionState::Faulted;
            return Err(e);
        }
        match self.transport.get_clock_rate(cfg.endpoint) {
            Ok(Some(actual_rate)) if actual_rate != cfg.sample_rate => {
                self.state = SessionState::Faulted;
                return Err(-22);
            }
            Err(e) => {
                self.state = SessionState::Faulted;
                return Err(e);
            }
            _ => {}
        }
        if let Err(e) = self.transport.set_alt(cfg.streaming_iface, cfg.target_alt) {
            self.state = SessionState::Faulted;
            return Err(e);
        }
        match self.transport.get_alt(cfg.streaming_iface) {
            Ok(actual_alt) if actual_alt != cfg.target_alt => {
                self.state = SessionState::Faulted;
                return Err(-22);
            }
            Err(e) => {
                self.state = SessionState::Faulted;
                return Err(e);
            }
            _ => {}
        }

        // Reconfigure invalidates previous volume verification; never overwrites pre-session baseline.
        self.volume_verified = false;
        self.active_cfg = Some(cfg);
        self.state = SessionState::Configured;
        Ok(())
    }

    fn write_mute_with_master_fallback(
        &mut self,
        fu_id: u8,
        control_iface: u8,
        channel: u8,
        mute: bool,
    ) -> Result<bool, i32> {
        match self.transport.write_fu_mute(fu_id, control_iface, channel, mute) {
            Ok(m) => Ok(m),
            Err(_) if channel != 0 => match self.transport.write_fu_mute(fu_id, control_iface, 0, mute) {
                Ok(m) => Ok(m),
                Err(e) if mute => Err(e),
                Err(_) => Ok(false),
            },
            Err(e) if mute => Err(e),
            Err(_) => Ok(false),
        }
    }

    pub fn apply_volume(
        &mut self,
        fu_id: u8,
        control_iface: u8,
        channel: u8,
        volume_raw: i16,
        mute: bool,
    ) -> Result<(i16, bool), i32> {
        if self.state == SessionState::Closed || self.state == SessionState::Faulted {
            return Err(-22);
        }
        // Safe blast-free write order: mute before dropping volume when muting;
        // write volume before unmuting when unmuting.
        let (read_vol, read_mute) = if mute {
            let rm = self.write_mute_with_master_fallback(fu_id, control_iface, channel, true)?;
            let rv = self.transport.write_fu_volume(fu_id, control_iface, channel, volume_raw)?;
            (rv, rm)
        } else {
            let rv = self.transport.write_fu_volume(fu_id, control_iface, channel, volume_raw)?;
            let rm = self.write_mute_with_master_fallback(fu_id, control_iface, channel, false)?;
            (rv, rm)
        };
        self.volume_verified = true;
        Ok((read_vol, read_mute))
    }

    pub fn read_volume(&mut self, fu_id: u8, control_iface: u8, channel: u8) -> Result<i16, i32> {
        if self.state == SessionState::Closed || self.state == SessionState::Faulted {
            return Err(-22);
        }
        self.transport.read_fu_volume(fu_id, control_iface, channel)
    }

    pub fn read_mute(&mut self, fu_id: u8, control_iface: u8, channel: u8) -> Result<bool, i32> {
        if self.state == SessionState::Closed || self.state == SessionState::Faulted {
            return Err(-22);
        }
        self.transport.read_fu_mute(fu_id, control_iface, channel)
    }

    pub fn start(&mut self) -> Result<(), i32> {
        match self.state {
            SessionState::Streaming => return Ok(()),
            SessionState::Configured | SessionState::PausedGrace => {}
            _ => return Err(-22),
        }
        if self.has_hw_vol && !self.volume_verified {
            return Err(-11);
        }
        let cfg = self.active_cfg.ok_or(-22)?;
        let fade_in = self.state == SessionState::PausedGrace;
        self.transport.start_streaming(&cfg, fade_in)?;
        self.state = SessionState::Streaming;
        Ok(())
    }

    pub fn pause_grace(&mut self) -> Result<(), i32> {
        if self.state == SessionState::PausedGrace {
            return Ok(());
        }
        if self.state != SessionState::Streaming && self.state != SessionState::Configured {
            return Err(-22);
        }
        self.transport.stop_streaming()?;
        self.state = SessionState::PausedGrace;
        Ok(())
    }

    pub fn resume_grace(&mut self) -> Result<(), i32> {
        if self.state != SessionState::PausedGrace {
            return self.start();
        }
        if self.has_hw_vol && !self.volume_verified {
            return Err(-11);
        }
        let cfg = self.active_cfg.ok_or(-22)?;
        self.transport.start_streaming(&cfg, true)?;
        self.state = SessionState::Streaming;
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), i32> {
        if self.state == SessionState::Closed {
            return Ok(());
        }
        if self.state == SessionState::Streaming || self.state == SessionState::PausedGrace {
            let _ = self.transport.stop_streaming();
        }
        if let Some(cfg) = self.active_cfg {
            let _ = self.transport.set_alt(cfg.streaming_iface, 0);
        }
        if self.state != SessionState::Opened && self.state != SessionState::Faulted {
            self.state = SessionState::Configured;
        }
        Ok(())
    }

    pub fn close(&mut self) {
        if self.state == SessionState::Closed {
            return;
        }
        let _ = self.transport.stop_streaming();

        if !self.baseline_restored {
            for b in &self.saved_baseline {
                if b.mute == Some(true) {
                    let _ = self.transport.write_fu_mute(b.fu_id, b.control_iface, b.channel, true);
                }
            }
            for b in &self.saved_baseline {
                if let Some(v) = b.volume_raw {
                    let _ = self.transport.write_fu_volume(b.fu_id, b.control_iface, b.channel, v);
                }
            }
            for b in &self.saved_baseline {
                if b.mute == Some(false) {
                    let _ = self.transport.write_fu_mute(b.fu_id, b.control_iface, b.channel, false);
                }
            }
            self.baseline_restored = true;
        }

        if let Some(cfg) = self.active_cfg {
            let _ = self.transport.set_alt(cfg.streaming_iface, 0);
        }

        Self::release_all_then_reattach(&mut self.transport, &self.claimed_ifaces);
        self.claimed_ifaces.clear();
        self.state = SessionState::Closed;
    }

    fn release_all_then_reattach(transport: &mut T, claimed: &[u8]) {
        let (release_order, reattach_order) = interface_release_and_reattach_plan(claimed);
        for iface in release_order {
            let _ = transport.release_interface(iface);
        }
        for iface in reattach_order {
            let _ = transport.attach_kernel_driver(iface);
        }
    }
}

impl<T: UsbControlTransport> Drop for UsbSessionCore<T> {
    fn drop(&mut self) {
        self.close();
    }
}

/// Converts normalized float32 sample (`2^23` scale) back to signed 24-bit integer without dither.
#[inline]
pub fn pack_f32_to_i24_pow2(sample: f32) -> i32 {
    const SCALE_I24: f32 = 8_388_608.0; // 2^23
    const MIN_I24: f32 = -8_388_608.0;
    const MAX_I24: f32 = 8_388_607.0;
    if !sample.is_finite() {
        return 0;
    }
    (sample * SCALE_I24).round().clamp(MIN_I24, MAX_I24) as i32
}

/// Applies linear scalar gain [0.0, 1.0] in place to one multi-channel PCM frame.
/// Zero allocations, zero locks, panic-free on bounds mismatches.
#[inline]
pub fn apply_pcm_ramp_frame(
    frame_bytes: &mut [u8],
    channels: usize,
    bit_depth: u32,
    bytes_per_sample: usize,
    gain: f32,
) {
    if gain >= 0.9999f32 || channels == 0 || bytes_per_sample == 0 {
        return;
    }
    let g = gain.clamp(0.0, 1.0);
    let needed = channels.saturating_mul(bytes_per_sample);
    if frame_bytes.len() < needed {
        return;
    }
    match bytes_per_sample {
        2 => {
            for c in 0..channels {
                let off = c * 2;
                let sample = i16::from_le_bytes([frame_bytes[off], frame_bytes[off + 1]]);
                let scaled = (sample as f32 * g).round().clamp(-32768.0, 32767.0) as i16;
                let b = scaled.to_le_bytes();
                frame_bytes[off] = b[0];
                frame_bytes[off + 1] = b[1];
            }
        }
        3 => {
            for c in 0..channels {
                let off = c * 3;
                let raw = (frame_bytes[off] as i32)
                    | ((frame_bytes[off + 1] as i32) << 8)
                    | ((frame_bytes[off + 2] as i8 as i32) << 16);
                let scaled = (raw as f64 * g as f64).round().clamp(-8388608.0, 8388607.0) as i32;
                frame_bytes[off] = (scaled & 0xFF) as u8;
                frame_bytes[off + 1] = ((scaled >> 8) & 0xFF) as u8;
                frame_bytes[off + 2] = ((scaled >> 16) & 0xFF) as u8;
            }
        }
        4 => {
            if bit_depth == 32 {
                for c in 0..channels {
                    let off = c * 4;
                    let sample = i32::from_le_bytes([
                        frame_bytes[off],
                        frame_bytes[off + 1],
                        frame_bytes[off + 2],
                        frame_bytes[off + 3],
                    ]);
                    let scaled = (sample as f64 * g as f64)
                        .round()
                        .clamp(-2147483648.0, 2147483647.0) as i32;
                    let b = scaled.to_le_bytes();
                    frame_bytes[off..off + 4].copy_from_slice(&b);
                }
            } else {
                for c in 0..channels {
                    let off = c * 4;
                    let raw = (frame_bytes[off] as i32)
                        | ((frame_bytes[off + 1] as i32) << 8)
                        | ((frame_bytes[off + 2] as i8 as i32) << 16);
                    let scaled = (raw as f64 * g as f64).round().clamp(-8388608.0, 8388607.0) as i32;
                    frame_bytes[off] = (scaled & 0xFF) as u8;
                    frame_bytes[off + 1] = ((scaled >> 8) & 0xFF) as u8;
                    frame_bytes[off + 2] = ((scaled >> 16) & 0xFF) as u8;
                    frame_bytes[off + 3] = 0;
                }
            }
        }
        _ => {}
    }
}

/// Real libusb/usbfs transport backing the coarse [UsbSessionCore] JNI handle.
pub struct RealUsbControlTransport {
    session: Option<Arc<UsbDeviceSession>>,
    streamer: Option<UsbStreamer>,
    ring_buffer: Arc<SpscRingBuffer>,
    paused: Arc<AtomicBool>,
    flushing: Arc<AtomicBool>,
    mute_ramp: Arc<AtomicBool>,
    streaming_running: Arc<AtomicBool>,
    sample_rate: Arc<AtomicU32>,
    claimed_done_in_open: bool,
}

impl RealUsbControlTransport {
    pub fn open_device(
        fd: i32,
        control_iface: u8,
        streaming_iface: u8,
        is_uac2: bool,
        clock_source_id: u8,
        has_hw_vol: bool,
        feature_unit_id: u8,
    ) -> Result<UsbSessionInstance, i32> {
        let dev_session = Arc::new(UsbDeviceSession::open_claimed(
            fd,
            streaming_iface,
            is_uac2,
            clock_source_id,
            control_iface,
            feature_unit_id,
        )?);
        let claimed = dev_session.claimed_interfaces.clone();
        let fus: Vec<FeatureUnitTarget> = dev_session
            .feature_units
            .iter()
            .map(|&(fu_id, ac)| FeatureUnitTarget {
                fu_id,
                control_iface: ac,
                channels: vec![0, 1, 2],
            })
            .collect();

        let ring_buffer = Arc::new(SpscRingBuffer::new(DEFAULT_RING_CAPACITY));
        let paused = Arc::new(AtomicBool::new(false));
        let flushing = Arc::new(AtomicBool::new(false));
        let mute_ramp = Arc::new(AtomicBool::new(false));
        let streaming_running = Arc::new(AtomicBool::new(false));
        let sample_rate = Arc::new(AtomicU32::new(0));

        let transport = Self {
            session: Some(Arc::clone(&dev_session)),
            streamer: None,
            ring_buffer: Arc::clone(&ring_buffer),
            paused: Arc::clone(&paused),
            flushing: Arc::clone(&flushing),
            mute_ramp: Arc::clone(&mute_ramp),
            streaming_running: Arc::clone(&streaming_running),
            sample_rate: Arc::clone(&sample_rate),
            claimed_done_in_open: true,
        };
        let core = UsbSessionCore::open(transport, claimed, fus, has_hw_vol)?;
        Ok(UsbSessionInstance {
            core: std::sync::Mutex::new(core),
            ring_buffer,
            paused,
            flushing,
            mute_ramp,
            streaming_running,
            sample_rate,
        })
    }
}

/// Top-level coarse JNI handle combining lifecycle state machine with lock-free ring/atomic flags.
pub struct UsbSessionInstance {
    pub core: std::sync::Mutex<UsbSessionCore<RealUsbControlTransport>>,
    pub ring_buffer: Arc<SpscRingBuffer>,
    pub paused: Arc<AtomicBool>,
    pub flushing: Arc<AtomicBool>,
    pub mute_ramp: Arc<AtomicBool>,
    pub streaming_running: Arc<AtomicBool>,
    pub sample_rate: Arc<AtomicU32>,
}

impl UsbControlTransport for RealUsbControlTransport {
    fn detach_and_claim(&mut self, _ifaces: &[u8]) -> Result<(), i32> {
        if self.claimed_done_in_open && self.session.is_some() {
            Ok(())
        } else {
            Err(-19)
        }
    }

    fn set_alt(&mut self, iface: u8, alt: u8) -> Result<(), i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        s.set_alt_setting(iface, alt)
    }

    fn get_alt(&mut self, iface: u8) -> Result<u8, i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        s.streaming_iface.store(iface, Ordering::Release);
        s.read_active_alt()
    }

    fn set_clock_rate(&mut self, sample_rate: u32, endpoint: u8) -> Result<(), i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        let streaming_iface = s.streaming_iface.load(Ordering::Acquire);
        crate::device::program_sample_rate(
            &s.handle,
            s.is_uac2,
            s.clock_source_id,
            s.control_iface,
            streaming_iface,
            endpoint,
            sample_rate,
        )?;
        self.sample_rate.store(sample_rate, Ordering::Release);
        Ok(())
    }

    fn get_clock_rate(&mut self, _endpoint: u8) -> Result<Option<u32>, i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        match s.read_clock_rate() {
            Ok(rate) => Ok(Some(rate)),
            Err(_) => Ok(None),
        }
    }

    fn read_fu_mute(&mut self, fu_id: u8, control_iface: u8, ch: u8) -> Result<bool, i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        s.read_hardware_mute(fu_id, control_iface, ch)
    }

    fn write_fu_mute(&mut self, fu_id: u8, control_iface: u8, ch: u8, mute: bool) -> Result<bool, i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        s.set_hardware_mute(fu_id, control_iface, ch, mute)
    }

    fn read_fu_volume(&mut self, fu_id: u8, control_iface: u8, ch: u8) -> Result<i16, i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        s.read_hardware_volume(fu_id, control_iface, ch)
    }

    fn write_fu_volume(&mut self, fu_id: u8, control_iface: u8, ch: u8, vol_raw: i16) -> Result<i16, i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        s.set_hardware_volume(fu_id, control_iface, ch, vol_raw)
    }

    fn start_streaming(&mut self, cfg: &EndpointFormatConfig, fade_in: bool) -> Result<(), i32> {
        let s = self.session.as_ref().ok_or(-19)?;
        s.streaming_iface.store(cfg.streaming_iface, Ordering::Release);
        s.target_alt.store(cfg.target_alt, Ordering::Release);
        s.endpoint.store(cfg.endpoint, Ordering::Release);
        self.sample_rate.store(cfg.sample_rate, Ordering::Release);

        let stream_cfg = StreamConfig {
            endpoint: cfg.endpoint,
            sample_rate: cfg.sample_rate,
            bit_depth: cfg.bit_depth,
            channels: cfg.channels,
            subslot_size: cfg.subslot_size,
            is_high_speed: cfg.is_high_speed,
            is_dop: cfg.is_dop,
            feedback_ep: cfg.feedback_ep,
        };
        if let Some(ref mut streamer) = self.streamer {
            streamer.update_config(stream_cfg);
        } else {
            self.streamer = Some(UsbStreamer::with_shared(
                Arc::clone(s),
                stream_cfg,
                Arc::clone(&self.ring_buffer),
                Arc::clone(&self.streaming_running),
                Arc::clone(&self.paused),
                Arc::clone(&self.flushing),
                Arc::clone(&self.mute_ramp),
            ));
        }
        self.paused.store(false, Ordering::Release);
        let ring_empty = self.ring_buffer.available_read() == 0;
        self.flushing.store(ring_empty, Ordering::Release);
        self.mute_ramp.store(false, Ordering::Release);
        self.streamer.as_mut().ok_or(-1)?.start_with_fade_in(fade_in)
    }

    fn stop_streaming(&mut self) -> Result<(), i32> {
        if let Some(ref mut streamer) = self.streamer {
            streamer.stop()?;
        }
        self.streaming_running.store(false, Ordering::Release);
        Ok(())
    }

    fn release_interface(&mut self, _iface: u8) -> Result<(), i32> {
        Ok(())
    }

    fn attach_kernel_driver(&mut self, _iface: u8) -> Result<(), i32> {
        self.streamer = None;
        super::device::set_active_session(None);
        if let Some(s) = self.session.take() {
            // Ensure Drop runs immediately upon releasing final Arc ref (restores baseline + releases + reattaches in order)
            drop(s);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    #[derive(Debug, Clone, PartialEq, Eq)]
    enum MockCall {
        DetachAndClaim(Vec<u8>),
        SetAlt(u8, u8),
        SetClock(u32),
        ReadFuMute(u8, u8),
        WriteFuMute(u8, u8, bool),
        ReadFuVol(u8, u8),
        WriteFuVol(u8, u8, i16),
        StartStreaming(u32, bool),
        StopStreaming,
        ReleaseInterface(u8),
        AttachKernelDriver(u8),
    }

    struct MockUsbTransport {
        calls: Vec<MockCall>,
        active_alt: BTreeMap<u8, u8>,
        clock_rate: u32,
        readback_clock_override: Option<u32>,
        readback_alt_override: Option<u8>,
        fu_mute: BTreeMap<(u8, u8), bool>,
        fu_vol: BTreeMap<(u8, u8), i16>,
        streaming_active: bool,
        fail_per_channel_mute: bool,
        fail_master_vol: bool,
    }

    impl MockUsbTransport {
        fn new(initial_vol: i16, initial_mute: bool) -> Self {
            let mut fu_mute = BTreeMap::new();
            let mut fu_vol = BTreeMap::new();
            fu_mute.insert((2, 0), initial_mute);
            fu_vol.insert((2, 0), initial_vol);
            Self {
                calls: Vec::new(),
                active_alt: BTreeMap::new(),
                clock_rate: 44100,
                readback_clock_override: None,
                readback_alt_override: None,
                fu_mute,
                fu_vol,
                streaming_active: false,
                fail_per_channel_mute: false,
                fail_master_vol: false,
            }
        }
    }

    impl UsbControlTransport for MockUsbTransport {
        fn detach_and_claim(&mut self, ifaces: &[u8]) -> Result<(), i32> {
            self.calls.push(MockCall::DetachAndClaim(ifaces.to_vec()));
            Ok(())
        }
        fn set_alt(&mut self, iface: u8, alt: u8) -> Result<(), i32> {
            self.calls.push(MockCall::SetAlt(iface, alt));
            self.active_alt.insert(iface, alt);
            Ok(())
        }
        fn get_alt(&mut self, iface: u8) -> Result<u8, i32> {
            Ok(self.readback_alt_override.unwrap_or(*self.active_alt.get(&iface).unwrap_or(&0)))
        }
        fn set_clock_rate(&mut self, sample_rate: u32, _endpoint: u8) -> Result<(), i32> {
            self.calls.push(MockCall::SetClock(sample_rate));
            self.clock_rate = sample_rate;
            Ok(())
        }
        fn get_clock_rate(&mut self, _endpoint: u8) -> Result<Option<u32>, i32> {
            Ok(Some(self.readback_clock_override.unwrap_or(self.clock_rate)))
        }
        fn read_fu_mute(&mut self, fu_id: u8, _ci: u8, ch: u8) -> Result<bool, i32> {
            self.calls.push(MockCall::ReadFuMute(fu_id, ch));
            if self.fail_per_channel_mute && ch != 0 {
                return Err(-32);
            }
            Ok(*self.fu_mute.get(&(fu_id, ch)).unwrap_or(&false))
        }
        fn write_fu_mute(&mut self, fu_id: u8, _ci: u8, ch: u8, mute: bool) -> Result<bool, i32> {
            self.calls.push(MockCall::WriteFuMute(fu_id, ch, mute));
            if self.fail_per_channel_mute && ch != 0 {
                return Err(-32);
            }
            self.fu_mute.insert((fu_id, ch), mute);
            Ok(mute)
        }
        fn read_fu_volume(&mut self, fu_id: u8, _ci: u8, ch: u8) -> Result<i16, i32> {
            self.calls.push(MockCall::ReadFuVol(fu_id, ch));
            if self.fail_master_vol && ch == 0 {
                return Err(-32);
            }
            Ok(*self.fu_vol.get(&(fu_id, ch)).unwrap_or(&0))
        }
        fn write_fu_volume(&mut self, fu_id: u8, _ci: u8, ch: u8, vol_raw: i16) -> Result<i16, i32> {
            self.calls.push(MockCall::WriteFuVol(fu_id, ch, vol_raw));
            if self.fail_master_vol && ch == 0 {
                return Err(-32);
            }
            self.fu_vol.insert((fu_id, ch), vol_raw);
            Ok(vol_raw)
        }
        fn start_streaming(&mut self, cfg: &EndpointFormatConfig, fade_in: bool) -> Result<(), i32> {
            self.calls.push(MockCall::StartStreaming(cfg.sample_rate, fade_in));
            self.streaming_active = true;
            Ok(())
        }
        fn stop_streaming(&mut self) -> Result<(), i32> {
            self.calls.push(MockCall::StopStreaming);
            self.streaming_active = false;
            Ok(())
        }
        fn release_interface(&mut self, iface: u8) -> Result<(), i32> {
            self.calls.push(MockCall::ReleaseInterface(iface));
            Ok(())
        }
        fn attach_kernel_driver(&mut self, iface: u8) -> Result<(), i32> {
            self.calls.push(MockCall::AttachKernelDriver(iface));
            Ok(())
        }
    }

    fn cfg_48k() -> EndpointFormatConfig {
        EndpointFormatConfig {
            streaming_iface: 1,
            target_alt: 1,
            endpoint: 0x01,
            sample_rate: 48000,
            bit_depth: 24,
            channels: 2,
            subslot_size: 4,
            is_high_speed: true,
            is_dop: false,
            feedback_ep: 0x81,
        }
    }

    #[test]
    fn open_reconfigure_volume_start_stop_close_exact_order() {
        let mock = MockUsbTransport::new(-512, false);
        let fus = vec![FeatureUnitTarget { fu_id: 2, control_iface: 0, channels: vec![0] }];
        let mut s = UsbSessionCore::open(mock, vec![0, 1, 2], fus, true).unwrap();
        s.reconfigure(cfg_48k()).unwrap();
        s.apply_volume(2, 0, 0, -2048, false).unwrap();
        s.start().unwrap();
        s.close();

        let calls = &s.transport.calls;
        let first_release = calls.iter().position(|c| matches!(c, MockCall::ReleaseInterface(_))).unwrap();
        let last_release = calls.iter().rposition(|c| matches!(c, MockCall::ReleaseInterface(_))).unwrap();
        let first_attach = calls.iter().position(|c| matches!(c, MockCall::AttachKernelDriver(_))).unwrap();
        let vol_restore = calls.iter().rposition(|c| matches!(c, MockCall::WriteFuVol(2, 0, -512))).unwrap();

        // Baseline restored while interfaces still claimed; all releases finish before any reattach (iface 0 first).
        assert!(vol_restore < first_release);
        assert!(last_release < first_attach);
        let releases: Vec<u8> = calls[first_release..=last_release]
            .iter()
            .filter_map(|c| match c { MockCall::ReleaseInterface(i) => Some(*i), _ => None })
            .collect();
        let attaches: Vec<u8> = calls[first_attach..]
            .iter()
            .filter_map(|c| match c { MockCall::AttachKernelDriver(i) => Some(*i), _ => None })
            .collect();
        assert_eq!(releases, vec![2, 1, 0]);
        assert_eq!(attaches, vec![0, 1, 2]);
    }

    #[test]
    fn reconfigure_rejects_clock_rate_and_alt_mismatch() {
        let mut mock1 = MockUsbTransport::new(0, false);
        mock1.readback_clock_override = Some(44100);
        let mut s1 = UsbSessionCore::open(mock1, vec![0, 1], vec![], false).unwrap();
        assert_eq!(s1.reconfigure(cfg_48k()), Err(-22));
        assert_eq!(s1.state(), SessionState::Faulted);

        let mut mock2 = MockUsbTransport::new(0, false);
        mock2.readback_alt_override = Some(0);
        let mut s2 = UsbSessionCore::open(mock2, vec![0, 1], vec![], false).unwrap();
        assert_eq!(s2.reconfigure(cfg_48k()), Err(-22));
        assert_eq!(s2.state(), SessionState::Faulted);
    }

    #[test]
    fn start_refused_until_volume_verified_on_open_and_reconfigure() {
        let mock = MockUsbTransport::new(-256, false);
        let fus = vec![FeatureUnitTarget { fu_id: 2, control_iface: 0, channels: vec![0] }];
        let mut s = UsbSessionCore::open(mock, vec![0, 1], fus, true).unwrap();
        s.reconfigure(cfg_48k()).unwrap();
        assert_eq!(s.start(), Err(-11));

        s.apply_volume(2, 0, 0, -1024, false).unwrap();
        assert_eq!(s.start(), Ok(()));

        // Second rate reconfigure must invalidate cached volume verification.
        let mut c96 = cfg_48k();
        c96.sample_rate = 96000;
        s.reconfigure(c96).unwrap();
        assert_eq!(s.start(), Err(-11));
        s.apply_volume(2, 0, 0, -1024, false).unwrap();
        assert_eq!(s.start(), Ok(()));
    }

    #[test]
    fn pause_grace_halts_streaming_keeps_claim_and_resumes_with_fade_in() {
        let mock = MockUsbTransport::new(0, false);
        let mut s = UsbSessionCore::open(mock, vec![0, 1], vec![], false).unwrap();
        s.reconfigure(cfg_48k()).unwrap();
        s.start().unwrap();
        assert!(s.transport.streaming_active);

        s.pause_grace().unwrap();
        assert_eq!(s.state(), SessionState::PausedGrace);
        assert!(!s.transport.streaming_active);
        assert!(!s.transport.calls.iter().any(|c| matches!(c, MockCall::ReleaseInterface(_))));

        s.resume_grace().unwrap();
        assert_eq!(s.state(), SessionState::Streaming);
        assert!(s.transport.streaming_active);
        assert_eq!(s.transport.calls.last(), Some(&MockCall::StartStreaming(48000, true)));
        let claim_count = s.transport.calls.iter().filter(|c| matches!(c, MockCall::DetachAndClaim(_))).count();
        assert_eq!(claim_count, 1);

        s.close();
        assert_eq!(s.state(), SessionState::Closed);
        assert!(s.transport.calls.iter().any(|c| matches!(c, MockCall::AttachKernelDriver(0))));
    }

    #[test]
    fn presession_saved_volume_preserved_across_reconfigures_and_restored_once() {
        let mock = MockUsbTransport::new(-640, true);
        let fus = vec![FeatureUnitTarget { fu_id: 2, control_iface: 0, channels: vec![0] }];
        let mut s = UsbSessionCore::open(mock, vec![0, 1], fus, true).unwrap();

        s.reconfigure(cfg_48k()).unwrap();
        s.apply_volume(2, 0, 0, -2560, false).unwrap();

        let mut c96 = cfg_48k();
        c96.sample_rate = 96000;
        s.reconfigure(c96).unwrap();
        s.apply_volume(2, 0, 0, -3200, false).unwrap();

        assert_eq!(s.saved_baseline()[0].volume_raw, Some(-640));
        assert_eq!(s.saved_baseline()[0].mute, Some(true));

        s.close();
        s.close(); // idempotent

        let vol_restores = s.transport.calls.iter().filter(|c| matches!(c, MockCall::WriteFuVol(2, 0, -640))).count();
        let mute_restores = s.transport.calls.iter().filter(|c| matches!(c, MockCall::WriteFuMute(2, 0, true))).count();
        assert_eq!(vol_restores, 1);
        assert_eq!(mute_restores, 1);
    }

    #[test]
    fn pcm_fade_ramp_monotonic_and_clip_free_across_formats() {
        for (bit_depth, subslot) in [(16u32, 2usize), (24, 3), (24, 4), (32, 4)] {
            let mut prev_mag = i64::MAX;
            for step in 0..=10 {
                let gain = 1.0f32 - (step as f32 / 10.0f32);
                let mut frame = vec![0u8; subslot * 2];
                match subslot {
                    2 => {
                        for c in 0..2 {
                            let b = 20000i16.to_le_bytes();
                            frame[c * 2..c * 2 + 2].copy_from_slice(&b);
                        }
                    }
                    3 | 4 if bit_depth == 24 => {
                        for c in 0..2 {
                            let v = 6_000_000i32;
                            frame[c * subslot] = (v & 0xFF) as u8;
                            frame[c * subslot + 1] = ((v >> 8) & 0xFF) as u8;
                            frame[c * subslot + 2] = ((v >> 16) & 0xFF) as u8;
                        }
                    }
                    _ => {
                        for c in 0..2 {
                            let b = 1_500_000_000i32.to_le_bytes();
                            frame[c * 4..c * 4 + 4].copy_from_slice(&b);
                        }
                    }
                }
                apply_pcm_ramp_frame(&mut frame, 2, bit_depth, subslot, gain);
                let mag: i64 = match subslot {
                    2 => i16::from_le_bytes([frame[0], frame[1]]) as i64,
                    3 | 4 if bit_depth == 24 => ((frame[0] as i32) | ((frame[1] as i32) << 8) | ((frame[2] as i8 as i32) << 16)) as i64,
                    _ => i32::from_le_bytes([frame[0], frame[1], frame[2], frame[3]]) as i64,
                };
                assert!(mag >= 0, "sign flip/clipping at depth={bit_depth} subslot={subslot} step={step}");
                assert!(mag <= prev_mag, "non-monotonic fade at depth={bit_depth} subslot={subslot}: {mag} > {prev_mag}");
                prev_mag = mag;
            }
            assert_eq!(prev_mag, 0, "gain=0.0 must silence sample cleanly");
        }
    }

    #[test]
    fn exhaustive_i24_f32_pow2_pack_identity() {
        for sample in -8_388_608i32..=8_388_607i32 {
            let f = (sample as f32) / 8_388_608.0f32;
            let back = pack_f32_to_i24_pow2(f);
            if back != sample {
                panic!("i24 pow2 pack mismatch at {sample}: f={f} back={back}");
            }
        }
    }

    #[test]
    fn apply_volume_falls_back_to_master_mute_for_per_channel_dac() {
        let mut mock = MockUsbTransport::new(-2560, false);
        mock.fail_per_channel_mute = true;
        let fus = vec![FeatureUnitTarget { fu_id: 2, control_iface: 0, channels: vec![1, 2] }];
        let mut s = UsbSessionCore::open(mock, vec![0, 1], fus, true).unwrap();
        s.reconfigure(cfg_48k()).unwrap();
        assert_eq!(s.apply_volume(2, 0, 1, -4096, false), Ok((-4096, false)));
        assert_eq!(s.apply_volume(2, 0, 2, -4096, false), Ok((-4096, false)));
        assert!(s.is_volume_verified());
        assert_eq!(s.start(), Ok(()));
    }

    #[test]
    fn read_volume_and_mute_reflect_latest_hardware_state() {
        let mut mock = MockUsbTransport::new(-1280, true);
        mock.fu_vol.insert((2, 1), -2048);
        mock.fu_mute.insert((2, 0), true);
        let fus = vec![FeatureUnitTarget { fu_id: 2, control_iface: 0, channels: vec![0, 1] }];
        let mut s = UsbSessionCore::open(mock, vec![0, 1], fus, true).unwrap();
        assert_eq!(s.read_volume(2, 0, 1), Ok(-2048));
        assert_eq!(s.read_mute(2, 0, 0), Ok(true));

        s.reconfigure(cfg_48k()).unwrap();
        assert_eq!(s.apply_volume(2, 0, 1, -512, false), Ok((-512, false)));
        assert_eq!(s.read_volume(2, 0, 1), Ok(-512));
        assert_eq!(s.read_mute(2, 0, 1), Ok(false));
        assert_eq!(s.apply_volume(2, 0, 0, -256, false), Ok((-256, false)));
        assert_eq!(s.read_mute(2, 0, 0), Ok(false));

        s.close();
        assert_eq!(s.read_volume(2, 0, 1), Err(-22));
        assert_eq!(s.read_mute(2, 0, 0), Err(-22));
    }

    #[test]
    fn write_order_and_per_channel_baseline_restore() {
        let mut mock = MockUsbTransport::new(0, false);
        mock.fail_master_vol = true;
        mock.fail_per_channel_mute = true;
        mock.fu_vol.remove(&(2, 0));
        mock.fu_mute.insert((2, 0), false);
        mock.fu_vol.insert((2, 1), -1536);
        mock.fu_vol.insert((2, 2), -1536);

        let fus = vec![FeatureUnitTarget { fu_id: 2, control_iface: 0, channels: vec![0, 1, 2] }];
        let mut s = UsbSessionCore::open(mock, vec![0, 1], fus, true).unwrap();
        assert_eq!(s.saved_baseline().len(), 3);
        assert_eq!(s.saved_baseline()[0].channel, 0);
        assert_eq!(s.saved_baseline()[0].mute, Some(false));
        assert_eq!(s.saved_baseline()[0].volume_raw, None);
        assert_eq!(s.saved_baseline()[1].volume_raw, Some(-1536));
        assert_eq!(s.saved_baseline()[2].volume_raw, Some(-1536));

        s.reconfigure(cfg_48k()).unwrap();
        s.transport.calls.clear();

        // When muting, mute MUST be written before volume drop.
        s.apply_volume(2, 0, 1, -18944, true).unwrap();
        let mute_idx = s.transport.calls.iter().position(|c| *c == MockCall::WriteFuMute(2, 0, true)).unwrap();
        let vol_idx = s.transport.calls.iter().position(|c| *c == MockCall::WriteFuVol(2, 1, -18944)).unwrap();
        assert!(mute_idx < vol_idx, "expected mute before volume drop: {:?}", s.transport.calls);

        // When unmuting, volume MUST be written before unmute.
        s.transport.calls.clear();
        s.apply_volume(2, 0, 1, -2048, false).unwrap();
        let vol_idx2 = s.transport.calls.iter().position(|c| *c == MockCall::WriteFuVol(2, 1, -2048)).unwrap();
        let unmute_idx = s.transport.calls.iter().position(|c| *c == MockCall::WriteFuMute(2, 0, false)).unwrap();
        assert!(vol_idx2 < unmute_idx, "expected volume before unmute: {:?}", s.transport.calls);

        // Close must restore both ch=1,2 volumes BEFORE ch=0 unmute, and all before set_alt(0) / release / attach.
        s.transport.calls.clear();
        s.close();
        let v1 = s.transport.calls.iter().position(|c| *c == MockCall::WriteFuVol(2, 1, -1536)).unwrap();
        let v2 = s.transport.calls.iter().position(|c| *c == MockCall::WriteFuVol(2, 2, -1536)).unwrap();
        let m0 = s.transport.calls.iter().position(|c| *c == MockCall::WriteFuMute(2, 0, false)).unwrap();
        let alt0 = s.transport.calls.iter().position(|c| *c == MockCall::SetAlt(1, 0)).unwrap();
        let rel = s.transport.calls.iter().position(|c| matches!(c, MockCall::ReleaseInterface(_))).unwrap();
        let att = s.transport.calls.iter().position(|c| matches!(c, MockCall::AttachKernelDriver(_))).unwrap();
        assert!(v1 < m0 && v2 < m0);
        assert!(m0 < alt0 && alt0 < rel && rel < att);
    }
}
