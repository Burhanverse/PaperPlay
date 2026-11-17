fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        // Ensure 16 KB ELF LOAD segment alignment on Android 15+ devices.
        println!("cargo:rustc-cdylib-link-arg=-Wl,-z,max-page-size=16384");

        // Only link libusb-1.0.so when the optional `usb` feature is explicitly enabled.
        // Default `libengine.so` stays pure DSP with zero USB dependency.
        if std::env::var_os("CARGO_FEATURE_USB").is_some() {
            let manifest_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
            let target_arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
            let abi = match target_arch.as_str() {
                "aarch64" => "arm64-v8a",
                "arm" => "armeabi-v7a",
                "x86_64" => "x86_64",
                "x86" => "x86",
                _ => "arm64-v8a",
            };
            let lib_dir = format!("{}/../androidApp/src/main/jniLibs/{}", manifest_dir, abi);
            println!("cargo:rustc-link-search=native={}", lib_dir);
            println!("cargo:rustc-link-lib=dylib=usb-1.0");
        }
    }
}

