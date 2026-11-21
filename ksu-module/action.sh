#!/system/bin/sh
PKG=io.github.regloom.dsphotword
echo "=== DSP Hotword Trigger ==="
echo "APK:        $(pm path $PKG 2>/dev/null)"
echo "Process:    $(pidof $PKG || echo 'not running')"
echo "Assistant:  $(cmd role get-role-holders android.app.role.ASSISTANT 2>/dev/null)"
echo "VIS:        $(settings get secure voice_interaction_service)"
echo ""
echo "[1] Permissions:"
dumpsys package $PKG | grep -E "(CAPTURE_AUDIO_HOTWORD|MANAGE_SOUND_TRIGGER|KEYPHRASE_ENROLLMENT_APPLICATION|RECORD_AUDIO|START_ACTIVITIES_FROM_BACKGROUND): granted" | sed 's/^ */    /'
echo ""
echo "[2] Enrolled keyphrase models:"
dumpsys voiceinteraction | grep -E "keyphrase_id|locale|hint_text" | head -n 9 | sed 's/^ */    /'
echo ""
echo "[3] SoundTrigger sessions:"
dumpsys soundtrigger_middleware 2>/dev/null | grep -i -E "packageName|state|modelHandle|activeModels" | head -n 20 | sed 's/^ */    /'
echo ""
echo "[4] Log:"
logcat -d -s DspHotword:V | tail -n 25
