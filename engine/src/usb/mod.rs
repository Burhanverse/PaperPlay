//! USB Isochronous Audio Engine module for PaperPlay.
//! rusb / libusb-1.0 transport, lock-free ring buffer, and real-time streaming.

pub mod debug;
pub mod decoder;
pub mod descriptor;
pub mod device;
pub mod jni;
pub mod log;
pub mod probe;
pub mod ring_buffer;
pub mod streamer;
pub mod transfer;
pub mod usbfs;

pub use device::UsbDeviceSession;
pub use ring_buffer::SpscRingBuffer;
pub use streamer::{StreamConfig, UsbStreamer};
pub use transfer::IsoTransferSlot;
