#!/usr/bin/env bash
# Regenerates prebuilts/framework-stubs/hidden-api-stubs.jar from a built AOSP/LineageOS tree.
# The jar has the class signatures (no code: it comes from the turbine header jar of the
# framework) of the hidden and system APIs the app uses, so build-apk.sh can compile the app
# against the public SDK android.jar outside a ROM tree (e.g. on GitHub Actions).
# Run it again when the app starts using another hidden class; build-apk.sh in SDK mode fails
# with "cannot find symbol" otherwise.
# Usage: LINEAGE_TREE=/path/to/tree ./make-hidden-api-stubs.sh
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TREE="${LINEAGE_TREE:-/media/master/sources/lineage}"
FRAMEWORK_JAR="$TREE/out/soong/.intermediates/frameworks/base/framework/android_common/turbine-combined/framework.jar"
JAR="$TREE/prebuilts/jdk/jdk21/linux-x86/bin/jar"
OUT="$SRC/prebuilts/framework-stubs/hidden-api-stubs.jar"

# Classes (with their nested classes) whose hidden or @SystemApi members the app uses, plus
# the classes javac needs to read their signatures.
CLASSES=(
    'android/hardware/soundtrigger/SoundTrigger*'
    'android/media/permission/Identity*'
    'android/os/ServiceManager*'
    'android/os/RemoteException*'
    'com/android/internal/app/IVoiceInteractionManagerService*'
    'com/android/internal/app/IVoiceInteractionSessionShowCallback*'
    'android/content/Context.class'
    'android/media/AudioAttributes*'
    'android/media/AudioRecord*'
    'android/media/MediaRecorder*'
    'android/app/role/RoleManager*'
    'android/annotation/SystemApi*'
    'android/ravenwood/annotation/RavenwoodSupported*'
)

[ -f "$FRAMEWORK_JAR" ] || { echo "[!] Missing: $FRAMEWORK_JAR (build the tree first)" >&2; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$FRAMEWORK_JAR" "${CLASSES[@]}" -d "$WORK"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
# fixed timestamps, so the jar only changes when the classes do
find "$WORK" -exec touch -h -d '2000-01-01 00:00:00 UTC' {} +
(cd "$WORK" && find . -name '*.class' | LC_ALL=C sort | "$JAR" cfM "$OUT" @/dev/stdin)
echo "[+] $OUT ($(find "$WORK" -name '*.class' | wc -l) classes)"
# CI checks these
(cd "$SRC" && sha256sum prebuilts/sherpa-onnx/sherpa-onnx-classes.jar \
    prebuilts/sherpa-onnx/arm64-v8a/libsherpa-onnx-jni.so prebuilts/kotlin/kotlin-stdlib.jar \
    prebuilts/framework-stubs/hidden-api-stubs.jar assets/speaker.onnx > SHA256SUMS)
