# Third-party files

They are committed so that a ROM build (Soong can't download) and `build-apk.sh` work offline.
`../update-prebuilts.sh` downloads them again and checks the checksums below; `../SHA256SUMS`
lists them for CI.

| File | Source | License | SHA-256 |
|---|---|---|---|
| `sherpa-onnx/sherpa-onnx-classes.jar` | `classes.jar` of [sherpa-onnx-static-link-onnxruntime-2.0.2.aar](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.2) | Apache-2.0 | `44b37e25ca29e3f0c22fe855f8037b17192f22f2a8ce91358215f87c87353bfc` |
| `sherpa-onnx/arm64-v8a/libsherpa-onnx-jni.so` | `jni/arm64-v8a/` of the same AAR; [onnxruntime](https://github.com/microsoft/onnxruntime) is statically linked | Apache-2.0, onnxruntime MIT | `f99de9819a32c67c4011989e3cb7f0bb4a04d11ee128095da3402307a149a6eb` |
| `kotlin/kotlin-stdlib.jar` | [kotlin-stdlib 2.2.20](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.20/), Maven Central (used by `build-apk.sh`; the ROM build uses the tree's `kotlin-stdlib`) | Apache-2.0 | `8836ccffd3585fadda9901244b20d42901d2f3cd581058d8434e2ffabcf3a3e7` |
| `framework-stubs/hidden-api-stubs.jar` | Signatures only (no code) of the hidden/system framework classes the app uses, taken from the turbine header jar of an AOSP 17 / LineageOS 24 `framework.jar` by `../make-hidden-api-stubs.sh`. Compile-only, for `build-apk.sh` outside a ROM tree; not packaged. | Apache-2.0 (AOSP) | `dc83dd78e9ede9bc836778d08eac8cb42c28bc9c1730fb10c0bdfe3996b1bc21` |
| `../assets/speaker.onnx` | [3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models), converted by sherpa-onnx from [3D-Speaker](https://github.com/modelscope/3D-Speaker) CAM++ | Apache-2.0 | `53cab20ba8f2eb2f6e2d89ecc18e88ef3222169b61b4419cbc13666744a4574b` |

The full text of the Apache License 2.0 is in [../LICENSE](../LICENSE).
