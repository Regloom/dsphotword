#!/usr/bin/env bash
# Builds DspHotwordTrigger.apk without Soong: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Inside a ROM build use Android.bp instead. Two ways to get the tools and the hidden APIs:
#  - tree mode (default when LINEAGE_TREE has a built out/): the tree's build tools, its
#    framework.jar and platform key;
#  - SDK mode (ANDROID_HOME, e.g. GitHub Actions): Android SDK build-tools and the public
#    android.jar, with the hidden APIs from prebuilts/framework-stubs/hidden-api-stubs.jar.
#    PLATFORM_KEY must point to the ROM's platform key (<prefix>.pk8 + <prefix>.x509.pem).
# Usage: [LINEAGE_TREE=/path/to/tree | ANDROID_HOME=/path/to/sdk] [PLATFORM_KEY=...] \
#        ./build-apk.sh [output.apk]
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TREE="${LINEAGE_TREE:-/media/master/sources/lineage}"
OUT_APK="${1:-$SRC/build/DspHotwordTrigger.apk}"
SHERPA_JAR="$SRC/prebuilts/sherpa-onnx/sherpa-onnx-classes.jar"
KOTLIN_JAR="$SRC/prebuilts/kotlin/kotlin-stdlib.jar"
COMPILE_SDK="${COMPILE_SDK:-36}"

if [ -z "${FORCE_SDK_MODE:-}" ] && [ -x "$TREE/out/host/linux-x86/bin/aapt2" ]; then
    echo "[*] Tree mode: $TREE"
    BIN="$TREE/out/host/linux-x86/bin"
    AAPT2="$BIN/aapt2" D8="$BIN/d8" ZIPALIGN="$BIN/zipalign" APKSIGNER="$BIN/apksigner"
    export JAVA_HOME="$TREE/prebuilts/jdk/jdk21/linux-x86"
    SDK_JAR="$TREE/prebuilts/sdk/current/system/android.jar"
    BOOTCLASSPATH="$TREE/out/soong/.intermediates/frameworks/base/framework/android_common/turbine-combined/framework.jar:$SDK_JAR:$TREE/out/soong/.intermediates/libcore/core-lambda-stubs/android_common/turbine-combined/core-lambda-stubs.jar"
    KEY="${PLATFORM_KEY:-$TREE/build/make/target/product/security/platform}"
else
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [ -n "$SDK" ] && [ -d "$SDK" ] || { echo "[!] No built tree at $TREE and no ANDROID_HOME" >&2; exit 1; }
    BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -n 1)"
    echo "[*] SDK mode: $BT, android-$COMPILE_SDK"
    AAPT2="$BT/aapt2" D8="$BT/d8" ZIPALIGN="$BT/zipalign" APKSIGNER="$BT/apksigner"
    SDK_JAR="$SDK/platforms/android-$COMPILE_SDK/android.jar"
    # the stubs come first: they replace the public classes with their full signatures
    BOOTCLASSPATH="$SRC/prebuilts/framework-stubs/hidden-api-stubs.jar:$SDK_JAR:$BT/core-lambda-stubs.jar"
    KEY="${PLATFORM_KEY:?set PLATFORM_KEY to the platform key prefix (<prefix>.pk8, <prefix>.x509.pem)}"
fi
JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a JDK 17+}"
JAVAC="$JAVA_HOME/bin/javac"
# the d8/apksigner wrappers run "java" from PATH
export PATH="$JAVA_HOME/bin:$PATH"

for f in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$JAVAC" "$SDK_JAR" "$KEY.pk8" "$KEY.x509.pem" \
         "$SHERPA_JAR" "$KOTLIN_JAR" "$SRC/assets/speaker.onnx" ${BOOTCLASSPATH//:/ }; do
    [ -e "$f" ] || { echo "[!] Missing: $f" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/res" "$WORK/gen" "$WORK/classes" "$WORK/dex" "$(dirname "$OUT_APK")"

# Resources, manifest and the speaker model (stored uncompressed, -0 onnx).
"$AAPT2" compile --dir "$SRC/res" -o "$WORK/res/res.zip"
"$AAPT2" link -o "$WORK/unsigned.apk" -I "$SDK_JAR" -A "$SRC/assets" -0 onnx \
    --manifest "$SRC/AndroidManifest.xml" --java "$WORK/gen" "$WORK/res/res.zip"

# Java: hidden framework classes first, then the SDK for java.* and the rest of the API.
"$JAVAC" -source 8 -target 8 -Xlint:-options -proc:none -encoding UTF-8 \
    -bootclasspath "$BOOTCLASSPATH" -classpath "$SHERPA_JAR:$KOTLIN_JAR" \
    -d "$WORK/classes" $(find "$SRC/src" "$WORK/gen" -name '*.java')

# Dex, including the sherpa-onnx Kotlin API and the Kotlin standard library.
"$D8" --release --min-api 34 --lib "$SDK_JAR" --output "$WORK/dex" \
    $(find "$WORK/classes" -name '*.class') "$SHERPA_JAR" "$KOTLIN_JAR"
(cd "$WORK/dex" && zip -qj "$WORK/unsigned.apk" classes*.dex)

# The JNI library is not in the APK: as for ROM-built system apps, it is installed as
# lib/arm64/libsherpa-onnx-jni.so next to the APK (see build-ksu-module.sh, Android.bp).

# Align and sign with the platform key (the app needs signature|privileged permissions).
"$ZIPALIGN" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$APKSIGNER" sign --v4-signing-enabled false --key "$KEY.pk8" --cert "$KEY.x509.pem" \
    --out "$OUT_APK" "$WORK/aligned.apk"
"$APKSIGNER" verify --print-certs "$OUT_APK" | grep -E "certificate (DN|SHA-256)"
echo "[+] $OUT_APK"
