#!/usr/bin/env bash
# Packs the KernelSU module: the APK as a privileged app in /product, its JNI library, the
# privileged permission allowlist and the default runtime permission grant.
# Usage: ./build-apk.sh && ./build-ksu-module.sh [path/to/DspHotwordTrigger.apk]
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KSU_DIR="$SRC/ksu-module"
APP_DIR="$KSU_DIR/system/product/priv-app/DspHotwordTrigger"
ETC_DIR="$KSU_DIR/system/product/etc"
VERSION="$(sed -n 's/^version=//p' "$KSU_DIR/module.prop")"
OUT_ZIP="$SRC/build/DspHotwordTrigger-KSU-$VERSION.zip"
APK="${1:-$SRC/build/DspHotwordTrigger.apk}"

[ -f "$APK" ] || { echo "[!] APK not found: $APK (run ./build-apk.sh)" >&2; exit 1; }

rm -rf "$KSU_DIR/system"
mkdir -p "$APP_DIR/lib/arm64" "$ETC_DIR/permissions" "$ETC_DIR/default-permissions" \
    "$(dirname "$OUT_ZIP")"
cp "$APK" "$APP_DIR/DspHotwordTrigger.apk"
# System apps get their native libraries from lib/<arch> next to the APK.
cp "$SRC/prebuilts/sherpa-onnx/arm64-v8a/libsherpa-onnx-jni.so" "$APP_DIR/lib/arm64/"
cp "$SRC/privapp-permissions-dsphotword.xml" "$ETC_DIR/permissions/"
cp "$SRC/default-permissions-dsphotword.xml" "$ETC_DIR/default-permissions/"
chmod 0755 "$KSU_DIR"/*.sh

rm -f "$OUT_ZIP"
(cd "$KSU_DIR" && zip -qr "$OUT_ZIP" . -x '.apk.sha1')
echo "[+] $OUT_ZIP"
sha256sum "$OUT_ZIP"
