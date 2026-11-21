# Заметки: Qualcomm Hexagon DSP, SoundTrigger и голосовая активация

Исследования, на которых построен DSP Hotword Trigger. Проверено на Poco F6 (`peridot`, Snapdragon 8s Gen 3),
LineageOS 24.0 (AOSP 17), KernelSU, ноябрь 2026. Пути в коде AOSP даны относительно корня дерева.
Названия классов и пакетов в старых разделах относятся к прежнему имени приложения (`org.lineageos.hotwordtrigger`).

## 1. Что есть в прошивке
- SoundTrigger HAL: Qualcomm PAL STHAL. `ModuleProperties.getSupportedModelArch()` возвращает **пустую строку** (у Pixel там «ver,uuid» DSP).
- Enrollment-приложения (`HotwordEnrollment*HEXAGON_WIDEBAND.apk`, `com.android.hotwordenrollment.common.util.jar`):

  | Пакет | keyphrase id | hint_text |
  |---|---|---|
  | `com.android.hotwordenrollment.xgoogle` | 101 | `X Google` |
  | `com.android.hotwordenrollment.okgoogle` / `ygoogle` | 102 | `Y Google` |

  `vendor_uuid` = `7038ddc8-30f2-11e6-b0ac-40a8f03d3f15`, модель `wb_small_universal.tflite` (слушает «OK Google»).
- `privapp-permissions-hotword.xml` из vendor: даёт enrollment-пакетам `INTERACT_ACROSS_USERS`, `KEYPHRASE_ENROLLMENT_APPLICATION`, `MANAGE_VOICE_KEYPHRASES`
  (блока для `ygoogle` там нет).

## 2. Регистрация модели в базе SoundTrigger
```sh
am broadcast -a com.android.intent.action.MANAGE_VOICE_KEYPHRASES \
    -n com.android.hotwordenrollment.xgoogle/.EnrollmentDelegateReceiver \
    --ei com.android.intent.extra.VOICE_KEYPHRASE_ACTION 0 \
    --es com.android.intent.extra.VOICE_KEYPHRASE_HINT_TEXT "X Google" \
    --es com.android.intent.extra.VOICE_KEYPHRASE_LOCALE "ru-RU"
```
Проверка: `dumpsys voiceinteraction | sed -n "/KeyphraseSoundModels/,/Active/p"` → `keyphrase_id: 101, locale: ru-RU, hint_text: X Google`.
Логи: `EnrollmentUtil EnrollmentIntentHandler EnrollmentDelegateRcv`. Модель 101/ru-RU на телефоне уже зарегистрирована.

## 3. Права: кому DSP доступен (AOSP 17)
| Право | protectionLevel | Зачем |
|---|---|---|
| `CAPTURE_AUDIO_HOTWORD` | `signature\|privileged\|role` | `startRecognition` / `startListeningFromMic` (`VoiceInteractionManagerService`) |
| `MANAGE_SOUND_TRIGGER` | `signature\|privileged` | `SoundTrigger.listModules/attachModule`, свойства модуля |
| `MANAGE_VOICE_KEYPHRASES` | `signature\|privileged` | менять модели в базе (вместе с «текущий VIS») |
| `KEYPHRASE_ENROLLMENT_APPLICATION` | `signature\|privileged` | читать/писать модели **без** роли текущего VIS |
| `BIND_HOTWORD_DETECTION_SERVICE` | `signature` | только для режима с изолированным `HotwordDetectionService` |
| `ACCESS_VOICE_INTERACTION_SERVICE` | `signature` | скрытый `IVoiceInteractionManagerService` (showSession и т.п.) |

- Роль, дающая `CAPTURE_AUDIO_HOTWORD`, — `android.app.role.SYSTEM_AMBIENT_AUDIO_INTELLIGENCE` (системная, задаётся конфигом), **не** ASSISTANT.
  Роль ASSISTANT этих прав не даёт.
- Вывод: **приложение из `/data/app` DSP не получит.** Нужен priv-app (KSU-оверлей `/product/priv-app/<App>/` + privapp-xml,
  `ro.control_privapp_permissions=enforce` → в xml должны быть *все* privileged-права манифеста, иначе system_server падает при загрузке).
- Signature-права и hidden API (`isAllowedToUseHiddenApis`) требуют platform-подписи. Прошивка подписана AOSP test-keys
  (platform cert `b4addb29…`), поэтому APK, собранный в дереве с `certificate: "platform"`, их получает.
- Для обновлённого системного приложения privileged-право выдаётся, только если его запрашивает **системная** копия
  (`AppIdPermissionPolicy.shouldGrantPermissionByProtectionFlags`). Ставить обновления поверх priv-app можно, только сохранив набор прав.

## 4. Два способа слушать DSP
**А. `AlwaysOnHotwordDetector` в активном VoiceInteractionService** (путь Google):
- Сервис должен стоять в `Settings.Secure.voice_interaction_service`. Вызывающий проверяется `enforceIsCurrentVoiceInteractionService()`.
- Сначала прямой детектор без `HotwordDetectionService`. Если не вышло, запасной вариант с HDS (`PersistableBundle`/`SharedMemory`), HDS сразу принимает срабатывание.
- `startRecognition()` можно вызывать только после `onAvailabilityChanged(STATE_KEYPHRASE_ENROLLED)`, иначе `UnsupportedOperationException`.
  Если состояние «не записано», один раз за жизнь детектора отправить enroll-broadcast (иначе цикл при сломанном enrollment).
- После срабатывания HAL может остановить распознавание (`recognitionStillActive=false`). Нужно перезапустить (stop+start), тогда DSP слушает и во время ответа (barge-in).
- `onError()` → пересоздание с экспоненциальной задержкой. Колбэки идут в главном потоке.

**Б. Прямой клиент `SoundTrigger`** (эксперимент `HotwordListener`, слот не нужен):
- `IVoiceInteractionManagerService.getKeyphraseSoundModel(101, "ru-RU")` работает при `KEYPHRASE_ENROLLMENT_APPLICATION`, без роли VIS. Базу не менять.
- `SoundTrigger.listModulesAsOriginator` → модуль с `RECOGNITION_MODE_VOICE_TRIGGER` и не `FAKE_HAL_ARCH` →
  `attachModuleAsOriginator` → `loadSoundModel` → `startRecognition(KeyphraseRecognitionExtra(101, VOICE_TRIGGER, 0), captureRequested=false)`.
- `STATUS_BUSY` = модель/DSP держит другой клиент (например, активный VIS). Восстанавливаться по `onModelUnloaded` / `onResourcesAvailable` / `onServiceDied`.
- В `onRecognition` фильтровать: hotword только при `RECOGNITION_STATUS_SUCCESS` + `KeyphraseRecognitionEvent` с id 101 (ABORT/FAILURE/GET_STATE_RESPONSE — не hotword). Debounce ~1 с.

Звук срабатывания (`captureRequested=true`): см. раздел 11.

## 5. Слот VIS, роль ассистента, загрузка
- Смена роли ASSISTANT заставляет систему перезаписать `voice_interaction_service` VIS-ом держателя роли. Свой слот выставлять после роли и при каждой загрузке.
- VIS, который не `directBootAware`, система привязывает только после первой разблокировки (`dumpsys user` → `RUNNING_UNLOCKED`).
- Чтобы приложению можно было отдать роль ASSISTANT, в `voice_interaction_service.xml` нужен `android:recognitionService` (хватает прокси) и `sessionService`.
- Слот и DSP-модель одни: на телефоне одновременно может работать только один слушатель (свой ассистент **или** Google, см. `~/Projects/gsa-hotword-fix`).

## 7. Подводные камни KernelSU
- ReZygisk/KSU «Отмонтировать модули» для приложения → процесс не видит свой APK в `/product/priv-app` и падает. В профиле приложения выключить.
- hybrid_mount показывает файлам модуля mtime 1970 → PackageManager берёт устаревший разбор из `/data/system/package_cache/*/<Dir>-16-*`
  (старый манифест, или bootloop из-за проверки privapp). При смене APK в модуле удалять эти файлы в `post-fs-data.sh`.
- Если телефон зациклился на загрузке, в это время работают adb + su: удалить кэш или `touch /data/adb/modules/<id>/disable`.

## 10. Роль ассистента, Dicio, свой hotword (2026-11-20)
- Роль ASSISTANT (`packages/modules/Permission/.../AssistantRoleBehavior.java`): кандидаты — приложения с VIS
  (`sessionService` + `recognitionService` + `supportsAssist`) **или** с экспортированной Activity `ACTION_ASSIST`.
  У держателя без VIS слот `voice_interaction_service` пуст, поэтому hotword держит отдельный триггер, не зависящий от роли.
- AOSP/Lineage: телефонного приложения с VIS нет (только `packages/apps/Car/VoiceControl` и тесты). Модель 101 записана, но
  без слушателя её никто не загружает на DSP.
- Dicio (`DicioTeam/dicio-android`, GPL-3.0): wake word программный (openWakeWord + `AudioRecord` в foreground-сервисе),
  API DSP/VIS нет. По `ACTION_ASSIST` сразу слушает (`MainActivity.kt:55-64`). STT: Vosk (`vosk-model-small-ru-0.22`),
  «External popup» = `ACTION_RECOGNIZE_SPEECH` (например, whisperIME). Свой wake word — `.tflite` openWakeWord, на DSP не переносится.
- Свой hotword на DSP: в вендоре движки Qualcomm `libcapiv2svacnnvendor`, `libcapiv2svarnnvendor`, `libcapiv2uvvendor`,
  `libcapiv2udk7vendor` (User Defined Keyword). Инструмента создания модели (`liblistensoundmodel2`) нет. STHAL-конфиг на телефоне не проверен.
- Права триггера (protectionLevel): `MANAGE_ROLE_HOLDERS` signature|installer|module, `ACCESS_VOICE_INTERACTION_SERVICE` signature,
  `KEYPHRASE_ENROLLMENT_APPLICATION` / `SOUND_TRIGGER_RUN_IN_BATTERY_SAVER` signature|privileged,
  `MANAGE_SOUND_TRIGGER` / `CAPTURE_AUDIO_HOTWORD` signature|privileged|role. Enrollment-приложение xgoogle требует от отправителя
  `MANAGE_VOICE_KEYPHRASES` (атрибут `android:permission` у `<application>`).

## 11. Звук срабатывания DSP и Voice Match (2026-11-21, триггер v1.7.2)
- `captureRequested=true` работает, но звук можно прочитать **только до** повторного `startRecognition`: после него сессия отдаёт нули.
- PAL возвращает в событии наш же `confidenceLevel` (60), реальной уверенности нет; `recognitionModes: 9`, без USER_IDENTIFICATION, у модели `users: 0`.
- Буфер отдаётся пачкой за ~70 мс, ровно 1,5 с, но **не выровнен по фразе**: «OK Google» оказывается в любом месте окна, бывает обрезан,
  часть захватов почти тишина (peak 150–250). 26 записей с телефона: 7 обучение, 19 проверка (в т.ч. чужой голос).
- MFCC + LBG/VQ (Minh N. Do, UIUC: https://minhdo.ece.illinois.edu/teaching/speaker_recognition/speaker_recognition.html)
  на таких окнах ненадёжен: свой голос даёт дистанцию 240–540 при «своей» 127, фильтр речи отклоняет ~30 % настоящих фраз.
  После правок (чтение всего буфера, сегмент фразы, нормировка, leave-one-out) MFCC/VQ всё равно не различает людей:
  владелец 4,8–6,2, другой человек 4,9–6,9 (полное перекрытие).
- Буфер истории DSP приходит быстрее реального времени (~2,1 с за десятки мс); читать до момента, когда куски пошли в реальном темпе.
- **Рабочий вариант (v1.8):** sherpa-onnx 2.0.2 (AAR со статическим onnxruntime, `libsherpa-onnx-jni.so` 25 МБ) +
  `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` (27 МБ). Косинус к центроиду 5 образцов:
  владелец 0,50–0,85, другой человек 0,24–0,46 (три сессии). Порог 0,475 при «строгости» 50.
  Модель `wespeaker_en_voxceleb_CAM++` (только английский) людей не разделила (0,83 у чужого).
- Время от события DSP до запуска ассистента: 0,27 с с загруженной моделью, ~1,1 с при первой загрузке (0,78 с).
  Процесс: ~8 МБ PSS без модели, ~100 МБ с моделью.
- Системное приложение из модуля: ABI (`primaryCpuAbi`) вычисляется только при первой загрузке/обновлении прошивки, при
  смене versionCode не пересчитывается; для «кластерной» установки `nativeLibraryDir` = `codePath/lib/<ISA>` и без ABI,
  поэтому `.so` кладётся в модуль как `priv-app/LineageHotwordTrigger/lib/arm64/`.
- KernelSU «Отмонтировать модули» прячет файлы модуля от Settings и SystemUI: они не могут читать ресурсы нашего APK.
  Поэтому заголовок пункта в Settings — литеральные meta-data, иконка плитки — битмап.
- Свой код на aDSP невозможен (подпись Qualcomm; Unsigned PD и freethedsp — только cDSP без аудио), проверка только на CPU.
