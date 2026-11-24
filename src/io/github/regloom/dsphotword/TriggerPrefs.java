package io.github.regloom.dsphotword;

import android.content.Context;
import android.content.SharedPreferences;

/** User-facing settings for the DSP trigger, shown in Settings → System and the quick tile. */
final class TriggerPrefs {
    private static final String FILE = "trigger";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BEEP = "beep";
    private static final String KEY_VOICE_MATCH = "voice_match";
    private static final String KEY_STRICTNESS = "strictness";
    private static final String KEY_CONFIDENCE = "confidence";
    private static final String KEY_DSP_MODEL = "dsp_model";

    /** Which copy of the vendor keyphrase model to use, see TriggerConfig#localeCandidates. */
    static final String DSP_MODEL_AUTO = "auto";
    static final String DSP_MODEL_ENGLISH = "english";
    static final String DSP_MODEL_UNIVERSAL = "universal";

    private TriggerPrefs() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    static boolean isBeep(Context c) {
        return prefs(c).getBoolean(KEY_BEEP, true);
    }

    static void setBeep(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_BEEP, v).apply();
    }

    /** Verify the owner's voice before launching the assistant. */
    static boolean isVoiceMatch(Context c) {
        return prefs(c).getBoolean(KEY_VOICE_MATCH, false);
    }

    static void setVoiceMatch(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_VOICE_MATCH, v).apply();
    }

    /** 0..100, higher rejects more non-owner voices. */
    static int getStrictness(Context c) {
        return prefs(c).getInt(KEY_STRICTNESS, 50);
    }

    static void setStrictness(Context c, int v) {
        prefs(c).edit().putInt(KEY_STRICTNESS, v).apply();
    }

    /** 0..100, confidence asked of the DSP. */
    static int getConfidence(Context c) {
        return prefs(c).getInt(KEY_CONFIDENCE, TriggerConfig.DEFAULT_DSP_CONFIDENCE);
    }

    static void setConfidence(Context c, int v) {
        prefs(c).edit().putInt(KEY_CONFIDENCE, v).apply();
    }

    static String getDspModel(Context c) {
        return prefs(c).getString(KEY_DSP_MODEL, DSP_MODEL_AUTO);
    }

    static void setDspModel(Context c, String v) {
        prefs(c).edit().putString(KEY_DSP_MODEL, v).apply();
    }
}
