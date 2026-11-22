#!/usr/bin/env bash
set -euo pipefail

# Build standalone LGPL-2.1 libusb-1.0.so for Android (arm64-v8a) using NDK
# Strictly dynamically linked by libengine.so as required by AGENTS.md

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -d "/home/aqua/Android/Sdk/ndk/29.0.14206865" ]; then
        NDK_HOME="/home/aqua/Android/Sdk/ndk/29.0.14206865"
    elif [ -d "/home/aqua/Android/Sdk/ndk/27.2.12479018" ]; then
        NDK_HOME="/home/aqua/Android/Sdk/ndk/27.2.12479018"
    else
        NDK_HOME="$(find /home/aqua/Android/Sdk/ndk -maxdepth 1 -mindepth 1 | sort -V | tail -n1)"
    fi
else
    NDK_HOME="${ANDROID_NDK_HOME}"
fi

if [ ! -f "${NDK_HOME}/ndk-build" ]; then
    echo "Error: ndk-build not found at ${NDK_HOME}/ndk-build" >&2
    exit 1
fi

BUILD_DIR="/tmp/paperplay_libusb_build"
LIBUSB_SRC="${BUILD_DIR}/src"
TARGET_ABI="arm64-v8a"
OUT_JNI="${ROOT_DIR}/androidApp/src/main/jniLibs/${TARGET_ABI}"

mkdir -p "${BUILD_DIR}" "${OUT_JNI}"

if [ ! -d "${LIBUSB_SRC}" ]; then
    echo "Cloning official libusb v1.0.27 into ${LIBUSB_SRC}..."
    git clone --depth 1 --branch v1.0.27 https://github.com/libusb/libusb.git "${LIBUSB_SRC}"
fi

echo "Building libusb-1.0.so for ${TARGET_ABI} using NDK..."
(
    cd "${LIBUSB_SRC}/android/jni"
    "${NDK_HOME}/ndk-build" -B \
        APP_ABI="${TARGET_ABI}" \
        APP_PLATFORM=android-21 \
        USE_PC_NAME=1 \
        APP_MODULES=usb-1.0 \
        APP_ALLOW_MISSING_DEPS=true \
        APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true
)

BUILT_SO="${LIBUSB_SRC}/android/libs/${TARGET_ABI}/libusb-1.0.so"
if [ ! -f "${BUILT_SO}" ]; then
    echo "Error: Failed to produce ${BUILT_SO}" >&2
    exit 1
fi

cp "${BUILT_SO}" "${OUT_JNI}/libusb-1.0.so"
echo "Successfully installed ${OUT_JNI}/libusb-1.0.so"

# Create pkg-config file for cargo / libusb1-sys to dynamically link against libusb-1.0.so
PKG_DIR="${BUILD_DIR}/pkgconfig"
mkdir -p "${PKG_DIR}"
cat > "${PKG_DIR}/libusb-1.0.pc" <<EOF
prefix=${LIBUSB_SRC}
exec_prefix=\${prefix}
libdir=${OUT_JNI}
includedir=\${prefix}/libusb

Name: libusb-1.0
Description: C API for USB device access from Linux, Mac OS X, Windows, OpenBSD/NetBSD and Solaris userspace
Version: 1.0.27
Libs: -L\${libdir} -lusb-1.0
Cflags: -I\${includedir}
EOF

echo "Generated pkgconfig at ${PKG_DIR}/libusb-1.0.pc"
