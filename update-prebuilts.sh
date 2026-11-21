#!/usr/bin/env bash
# Re-downloads the third-party files in prebuilts/ and assets/ with pinned versions and checksums.
# They are committed, so a normal build does not need this; use it to verify or update them.
#  - sherpa-onnx Android AAR with onnxruntime statically linked (speaker embedding extractor):
#    its classes.jar and arm64-v8a JNI library;
#  - 3D-Speaker CAM++ speaker embedding model (zh+en, works for other languages' voices too);
#  - kotlin-stdlib, needed at runtime by the Kotlin API classes of the AAR.
# Usage: ./update-prebuilts.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SHERPA_VERSION=2.0.2
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-static-link-onnxruntime-$SHERPA_VERSION.aar"
AAR_SHA256=cb73564809268c8b96bf98f1a32b0b3b611e01d028d22f10beb57ef55418c0cb

MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
MODEL_SHA256=53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b

KOTLIN_VERSION=2.2.20
KOTLIN_URL="https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/$KOTLIN_VERSION/kotlin-stdlib-$KOTLIN_VERSION.jar"
KOTLIN_SHA256=8836ccffd3585fadda9901244b20d42901d2f3cd581058d8434e2ffabcf3a3e7

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch() { # url file sha256
    curl -fsSL -o "$2" "$1"
    echo "$3  $2" | sha256sum -c --status - || { echo "[!] checksum mismatch: $1" >&2; exit 1; }
}

fetch "$AAR_URL" "$TMP/sherpa.aar" "$AAR_SHA256"
unzip -q -o "$TMP/sherpa.aar" classes.jar jni/arm64-v8a/libsherpa-onnx-jni.so -d "$TMP/aar"
mkdir -p prebuilts/sherpa-onnx/arm64-v8a prebuilts/kotlin assets
cp "$TMP/aar/classes.jar" prebuilts/sherpa-onnx/sherpa-onnx-classes.jar
cp "$TMP/aar/jni/arm64-v8a/libsherpa-onnx-jni.so" prebuilts/sherpa-onnx/arm64-v8a/
fetch "$MODEL_URL" assets/speaker.onnx "$MODEL_SHA256"
fetch "$KOTLIN_URL" prebuilts/kotlin/kotlin-stdlib.jar "$KOTLIN_SHA256"
sha256sum prebuilts/sherpa-onnx/sherpa-onnx-classes.jar \
    prebuilts/sherpa-onnx/arm64-v8a/libsherpa-onnx-jni.so prebuilts/kotlin/kotlin-stdlib.jar \
    assets/speaker.onnx
echo "[+] prebuilts updated"
