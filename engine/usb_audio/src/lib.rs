//! Standalone USB exclusive audio native library (`libusb_audio.so`).
//! Loaded lazily on Android ONLY when USB exclusive mode is actually used,
//! keeping `libengine.so` free of any `libusb-1.0.so` dependency.

#[cfg(feature = "dev")]
pub mod debug;
pub mod device;
pub mod jni;
pub mod log;
pub mod probe;
pub mod ring_buffer;
pub mod session;
pub mod streamer;
pub mod transfer;
pub mod usbfs;

pub use device::UsbDeviceSession;
pub use ring_buffer::SpscRingBuffer;
pub use session::{
    apply_pcm_ramp_frame, pack_f32_to_i24_pow2, EndpointFormatConfig, FeatureUnitTarget,
    SavedFuSetting, SessionState, UsbControlTransport, UsbSessionCore,
};
pub use streamer::{StreamConfig, UsbStreamer};
pub use transfer::IsoTransferSlot;
