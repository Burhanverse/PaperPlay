//! Standalone USB exclusive audio native library (`libusb_audio.so`).
//! Loaded lazily on Android ONLY when USB exclusive mode is actually used,
//! keeping `libengine.so` free of any `libusb-1.0.so` dependency.

#[path = "../../src/usb/mod.rs"]
pub mod usb;
