package io.github.regloom.dsphotword;

/**
 * Fixed configuration: the trigger is an invisible system layer that hands the hotword over to the
 * app chosen in "Default digital assistant app", so there is nothing for the user to set up here.
 */
final class TriggerConfig {
    /** Vendor enrollment app com.android.hotwordenrollment.xgoogle: keyphrase "X Google", id 101. */
    static final int KEYPHRASE_ID = 101;
    static final String KEYPHRASE_TEXT = "X Google";
    /** Used when no model is enrolled for the configured or the system locale. */
    static final String FALLBACK_LOCALE = "en-US";

    /**
     * Locales to look for the enrolled keyphrase model in, in order: the one configured with the
     * config_hotwordLocale resource (a device tree can set it with an overlay), the system locale,
     * then {@link #FALLBACK_LOCALE}.
     */
    static java.util.List<String> localeCandidates(android.content.Context context) {
        java.util.LinkedHashSet<String> locales = new java.util.LinkedHashSet<>();
        String configured = context.getString(R.string.config_hotwordLocale).trim();
        if (!configured.isEmpty()) {
            locales.add(java.util.Locale.forLanguageTag(configured).stripExtensions().toLanguageTag());
        }
        // The system locale carries user preferences as Unicode extensions
        // (en-US-u-fw-mon-mu-celsius); the keyphrase database and the enrollment app only know
        // language-region tags.
        locales.add(android.os.LocaleList.getDefault().get(0).stripExtensions().toLanguageTag());
        locales.add(FALLBACK_LOCALE);
        return new java.util.ArrayList<>(locales);
    }

    /**
     * Confidence threshold (0..100) asked of the DSP in the keyphrase recognition request. The
     * Qualcomm PAL STHAL does not report a confidence level in the event, so whether it honours
     * this is verified by counting false triggers. Stage C makes it a user setting.
     */
    static final int DEFAULT_DSP_CONFIDENCE = 60;

    /** Save the audio of each trigger to files/last-trigger.wav, to inspect what set it off. */
    static final boolean DEBUG_SAVE_TRIGGER_AUDIO = false;

    /** Keep the last voice-match captures in files/captures, to tune it offline. */
    static final boolean DEBUG_KEEP_CAPTURES = false;

    private TriggerConfig() {}
}
