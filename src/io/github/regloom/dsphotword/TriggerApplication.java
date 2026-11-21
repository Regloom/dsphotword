package io.github.regloom.dsphotword;

import android.app.Application;
import android.util.Log;

/**
 * Persistent system app: the process is started by the system after the user unlocks and kept
 * alive, so the DSP recognition session survives without a foreground notification.
 */
public final class TriggerApplication extends Application {
    private static final String TAG = "DspHotword";

    @Override
    public void onCreate() {
        super.onCreate();
        if (!getPackageName().equals(getProcessName())) return;
        try {
            if (TriggerPrefs.isEnabled(this)) {
                DspListener.get(this).start();
            } else {
                Log.i(TAG, "Trigger disabled in settings");
            }
        } catch (RuntimeException e) {
            // A crash here would make the system restart the persistent process in a loop.
            Log.e(TAG, "Failed to start DSP listener", e);
        }
    }

    /**
     * The speaker model is kept loaded for fast verification (~0.27 s instead of ~1.1 s from the
     * trigger to the assistant), but given back when the system runs low on memory.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            try {
                SpeakerEmbedding.release();
            } catch (RuntimeException | LinkageError e) {
                Log.w(TAG, "Failed to release speaker model", e);
            }
        }
    }
}
