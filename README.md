# DSP Hotword Trigger

Low-power **"OK Google" detection on the Qualcomm Hexagon DSP for any assistant app**, without Google apps.
[Русская версия](README.ru.md)

## What problem it solves

Many Qualcomm phones ship a vendor keyphrase model for "OK Google" that runs on the always-on, low-power DSP.
But only the Google app uses it, as the voice interaction service. On ROMs without Google apps (or with microG):
- the DSP hotword is never loaded;
- open-source assistants such as [Dicio](https://github.com/DicioTeam/dicio-android) have to detect their wake word on the
  CPU, with the microphone open all the time, which costs battery and needs a foreground service.

DSP Hotword Trigger loads the vendor model on the DSP through Android's SoundTrigger API. When the phrase is heard, it
starts the app chosen in **Settings → Apps → Default apps → Digital assistant app**. It is not an assistant itself and
does not take the assistant role.

## Features

- "OK Google" works with the screen off, at DSP power.
- **Starts the default assistant**, with the best available method:
  - **any assistant with its own voice interaction service:** its voice session is opened, also over the lock screen;
  - **assistants that handle `ACTION_VOICE_SEARCH_HANDS_FREE`:** started over the lock screen. The
    [Dicio fork](https://github.com/Regloom/dicio-android/tree/lineage) supports it;
  - **[Dicio](https://github.com/DicioTeam/dicio-android):** started over the lock screen through its wake word action;
  - **any other app that handles `ACTION_ASSIST`:** started after the user unlocks the phone.
- **Owner's voice only (optional).** On-device speaker verification with the 3D-Speaker CAM++ model, via
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), from the audio the DSP captured. It rejects other people,
  snoring and TV voices. With the model loaded, the check takes about 0.3 s.
- **Settings and toggle:** an entry in Settings → System, plus a quick settings tile. Interface in English and Russian.
- **Private:** no network access, nothing leaves the phone.

## Requirements

- A Qualcomm SoC whose SoundTrigger HAL supports keyphrase recognition, and the vendor enrollment app
  `com.android.hotwordenrollment.xgoogle` (keyphrase "X Google", id 101). Many Qualcomm phones (e.g. Xiaomi) include it.
- Android 14 or newer, arm64. Tested on a Poco F6 (Snapdragon 8s Gen 3) with LineageOS 24 (Android 17).
- **The APK must be signed with the platform key of the ROM** (it needs signature permissions). So it fits a ROM you
  build yourself, or a ROM signed with the AOSP test keys.

## Install

### In a ROM build

1. Add the repository to your tree. For example, in `.repo/local_manifests/dsp-hotword.xml`:
   ```xml
   <manifest>
     <remote name="github-dsphotword" fetch="https://github.com/" />
     <project path="packages/apps/DspHotwordTrigger" name="Regloom/dsphotword"
              remote="github-dsphotword" revision="main" />
   </manifest>
   ```
2. Add the app to the device makefile:
   ```make
   PRODUCT_PACKAGES += DspHotwordTrigger
   ```
3. **Optional:** pick the locale of the vendor model with an overlay of `config_hotwordLocale`, e.g. `ru-RU`. By default
   the trigger uses the system locale, with `en-US` as fallback. The phrase is "OK Google" in every locale: the locale
   only selects which enrolled copy of the model is used.

### As a KernelSU module (root, ROM with known platform key)

```sh
LINEAGE_TREE=/path/to/built/tree ./build-apk.sh   # or PLATFORM_KEY=/path/to/platform (pk8/x509.pem)
./build-ksu-module.sh
adb push build/DspHotwordTrigger-KSU-*.zip /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/DspHotwordTrigger-KSU-*.zip'
```

Then, in the KernelSU app profile of "DSP Hotword Trigger", **turn off "Umount modules"**: otherwise the app can't see
its own APK and crashes. Reboot. The app is persistent, so updates also need a reboot.

## Usage

1. Choose an assistant in **Settings → Apps → Default apps → Digital assistant app**, e.g. Dicio.
2. Say "OK Google".
3. Optional: in **Settings → System → "OK Google" voice activation**, record a voice sample (say "OK Google" 5 times) to
   start the assistant only for your voice.

Logs: `adb logcat -s DspHotword`. The KernelSU module's action button prints a diagnostic summary.

## How it works

- **`DspListener`:**
  - reads the enrolled vendor model through `KEYPHRASE_ENROLLMENT_APPLICATION`;
  - loads it on the hardware SoundTrigger module and starts recognition with `captureRequested`;
  - re-arms after each detection and backs off while another client (e.g. an assistant with its own voice
    interaction service) uses the DSP.
- **`HotwordAudio`:** reads the history buffer of the DSP after a detection.
- **`SpeakerEmbedding`:** compares that audio with the enrolled owner profile.
- **`AssistantLauncher`:** starts the `ASSISTANT` role holder. It wakes the screen, plays a short beep and handles the
  lock screen.

Notes from the research behind it (permissions, SELinux, the DSP limits) are in [docs/NOTES.md](docs/NOTES.md).

## Limitations

- The Qualcomm PAL SoundTrigger HAL echoes the requested confidence threshold and reports no real confidence. So false
  triggers are filtered by voice match, not by the DSP.
- **Voice match** was tuned on a small set of recordings. Adjust "Voice check strictness" if it rejects you or accepts
  others.
- **Under KernelSU module umount:**
  - Settings can't read resources of the module APK, so the Settings entry has no icon;
  - its title comes from the app through a content provider.

## Licenses

- **Code:** Apache-2.0, see [LICENSE](LICENSE).
- **Third-party files in `prebuilts/` and `assets/`:**
  - sherpa-onnx: Apache-2.0, with onnxruntime statically linked (MIT);
  - 3D-Speaker CAM++ model: Apache-2.0;
  - kotlin-stdlib: Apache-2.0.

  Versions, sources and checksums are in [prebuilts/README.md](prebuilts/README.md).
- "OK Google" and Google are trademarks of Google LLC. This project is not affiliated with Google, Qualcomm, LineageOS
  or Dicio.
