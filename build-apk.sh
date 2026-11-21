#!/usr/bin/env bash
# Builds DspHotwordTrigger.apk without Soong: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs an already built LineageOS/AOSP tree for the build tools, the hidden framework classes
# (SoundTrigger, IVoiceInteractionManagerService) and the platform signing key; nothing is written
# into the tree. Inside a ROM build use Android.bp instead.
# Usage: LINEAGE_TREE=/path/to/tree ./build-apk.sh [output.apk]
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TREE="${LINEAGE_TREE:-/media/master/sources/lineage}"
OUT_APK="${1:-$SRC/build/DspHotwordTrigger.apk}"

BIN="$TREE/out/host/linux-x86/bin"
JDK="$TREE/prebuilts/jdk/jdk21/linux-x86/bin"
SDK_JAR="$TREE/prebuilts/sdk/current/system/android.jar"
FRAMEWORK_JAR="$TREE/out/soong/.intermediates/frameworks/base/framework/android_common/turbine-combined/framework.jar"
LAMBDA_JAR="$TREE/out/soong/.intermediates/libcore/core-lambda-stubs/android_common/turbine-combined/core-lambda-stubs.jar"
KEY="${PLATFORM_KEY:-$TREE/build/make/target/product/security/platform}"
SHERPA_JAR="$SRC/prebuilts/sherpa-onnx/sherpa-onnx-classes.jar"
KOTLIN_JAR="$SRC/prebuilts/kotlin/kotlin-stdlib.jar"
# The d8/apksigner wrappers run "java" from PATH; they need JDK 17+.
export JAVA_HOME="$TREE/prebuilts/jdk/jdk21/linux-x86"
export PATH="$JAVA_HOME/bin:$PATH"

for f in "$BIN/aapt2" "$BIN/d8" "$BIN/zipalign" "$BIN/apksigner" "$JDK/javac" \
         "$SDK_JAR" "$FRAMEWORK_JAR" "$LAMBDA_JAR" "$KEY.pk8" "$KEY.x509.pem" \
         "$SHERPA_JAR" "$KOTLIN_JAR" "$SRC/assets/speaker.onnx"; do
    [ -e "$f" ] || { echo "[!] Missing: $f" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/res" "$WORK/gen" "$WORK/classes" "$WORK/dex" "$(dirname "$OUT_APK")"

# Resources, manifest and the speaker model (stored uncompressed, -0 onnx).
"$BIN/aapt2" compile --dir "$SRC/res" -o "$WORK/res/res.zip"
"$BIN/aapt2" link -o "$WORK/unsigned.apk" -I "$SDK_JAR" -A "$SRC/assets" -0 onnx \
    --manifest "$SRC/AndroidManifest.xml" --java "$WORK/gen" "$WORK/res/res.zip"

# Java: hidden framework classes first, then the system SDK for java.* and SystemApi.
"$JDK/javac" -source 8 -target 8 -Xlint:-options -proc:none -encoding UTF-8 \
    -bootclasspath "$FRAMEWORK_JAR:$SDK_JAR:$LAMBDA_JAR" \
    -classpath "$SHERPA_JAR:$KOTLIN_JAR" \
    -d "$WORK/classes" $(find "$SRC/src" "$WORK/gen" -name '*.java')

# Dex, including the sherpa-onnx Kotlin API and the Kotlin standard library.
"$BIN/d8" --release --min-api 34 --lib "$SDK_JAR" --output "$WORK/dex" \
    $(find "$WORK/classes" -name '*.class') "$SHERPA_JAR" "$KOTLIN_JAR"
(cd "$WORK/dex" && zip -qj "$WORK/unsigned.apk" classes*.dex)

# The JNI library is not in the APK: as for ROM-built system apps, it is installed as
# lib/arm64/libsherpa-onnx-jni.so next to the APK (see build-ksu-module.sh, Android.bp).

# Align and sign with the platform key (the app needs signature|privileged permissions).
"$BIN/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BIN/apksigner" sign --v4-signing-enabled false --key "$KEY.pk8" --cert "$KEY.x509.pem" \
    --out "$OUT_APK" "$WORK/aligned.apk"
"$BIN/apksigner" verify --print-certs "$OUT_APK" | grep -E "certificate (DN|SHA-256)"
echo "[+] $OUT_APK"
