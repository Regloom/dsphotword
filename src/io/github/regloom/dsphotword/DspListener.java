package io.github.regloom.dsphotword;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTriggerModule;
import android.media.permission.Identity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Direct SoundTrigger client for the vendor "OK Google" keyphrase model (id 101).
 *
 * <p>It does not own the voice interaction slot. The enrolled model is read through
 * KEYPHRASE_ENROLLMENT_APPLICATION and loaded on the hardware module; no audio is requested.
 * If another client (an assistant with its own voice interaction service) holds the DSP, the
 * HAL reports BUSY and this listener waits instead of competing.
 */
final class DspListener {
    private static final String TAG = "DspHotword";
    private static final long RETRY_MIN_MS = 1_000L;
    private static final long RETRY_MAX_MS = 60_000L;
    private static final long TRIGGER_DEBOUNCE_MS = 1_500L;
    private static final long ENROLL_WAIT_MS = 5_000L;
    private static final int MAX_ENROLL_ATTEMPTS = 3;

    private static DspListener sInstance;

    private final Context mContext;
    private final HandlerThread mThread;
    private final Handler mWorker;

    // Accessed on mWorker only.
    private SoundTriggerModule mModule;
    private int mModelHandle = -1;
    private SoundTrigger.RecognitionConfig mConfig;
    private boolean mListening;
    private boolean mPreempted;
    private boolean mRunning;
    private long mBackoffMs = RETRY_MIN_MS;
    private long mLastTriggerMs;
    private int mEnrollAttempts;


    static synchronized DspListener get(Context context) {
        if (sInstance == null) sInstance = new DspListener(context.getApplicationContext());
        return sInstance;
    }

    private DspListener(Context context) {
        mContext = context;
        mThread = new HandlerThread("DspHotword-DSP");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
    }


    void start() {
        mWorker.post(() -> {
            if (mRunning) return;
            mRunning = true;
            mEnrollAttempts = 0;
            mBackoffMs = RETRY_MIN_MS;
            state("Connecting to SoundTrigger");
            connect();
        });
    }

    void stop() {
        mWorker.post(() -> {
            mRunning = false;
            mWorker.removeCallbacks(mReconnect);
            releaseModule();
            state("Stopped");
        });
    }

    /** Re-read settings (locale) and reconnect. */
    void restart() {
        mWorker.post(() -> {
            mWorker.removeCallbacks(mReconnect);
            releaseModule();
            mRunning = false;
        });
        start();
    }

    private void state(String value) {
        Log.i(TAG, value);
    }

    private void connect() {
        if (!mRunning) return;
        try {
            IVoiceInteractionManagerService vims = IVoiceInteractionManagerService.Stub.asInterface(
                    ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
            if (vims == null) {
                retry("VoiceInteractionManagerService unavailable", SoundTrigger.STATUS_DEAD_OBJECT);
                return;
            }
            // The keyphrase is "OK Google" in every locale; the locale only selects which
            // enrolled copy of the vendor model to use. Use the first one that is enrolled.
            List<String> locales = TriggerConfig.localeCandidates(mContext);
            SoundTrigger.KeyphraseSoundModel model = null;
            String locale = null;
            for (String candidate : locales) {
                SoundTrigger.KeyphraseSoundModel m =
                        vims.getKeyphraseSoundModel(TriggerConfig.KEYPHRASE_ID, candidate);
                if (isExpectedModel(m)) {
                    model = m;
                    locale = candidate;
                    break;
                }
            }
            if (model == null) {
                // ask the enrollment app for the preferred locale first, then the fallbacks
                requestEnrollment(locales.get(Math.min(mEnrollAttempts, locales.size() - 1)));
                return;
            }

            ArrayList<SoundTrigger.ModuleProperties> modules = new ArrayList<>();
            Identity originator = new Identity();
            originator.packageName = mContext.getOpPackageName();
            originator.attributionTag = mContext.getAttributionTag();
            int status = SoundTrigger.listModulesAsOriginator(modules, originator);
            if (status != SoundTrigger.STATUS_OK) {
                retry("listModules", status);
                return;
            }
            SoundTrigger.ModuleProperties hardware = null;
            for (SoundTrigger.ModuleProperties candidate : modules) {
                if (!SoundTrigger.FAKE_HAL_ARCH.equals(candidate.getSupportedModelArch())
                        && (candidate.getRecognitionModes()
                                & SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER) != 0) {
                    hardware = candidate;
                    break;
                }
            }
            if (hardware == null) {
                fail("No hardware SoundTrigger module found");
                return;
            }
            mModule = SoundTrigger.attachModuleAsOriginator(hardware.getId(), mEvents, mWorker,
                    originator);
            if (mModule == null) {
                retry("attachModuleAsOriginator failed", SoundTrigger.STATUS_ERROR);
                return;
            }
            int[] handle = new int[1];
            status = mModule.loadSoundModel(model, handle);
            if (status != SoundTrigger.STATUS_OK) {
                retry(status == SoundTrigger.STATUS_BUSY
                        ? "DSP busy with another client" : "loadSoundModel", status);
                return;
            }
            mModelHandle = handle[0];
            mPreempted = false;
            Log.i(TAG, "moduleId=" + hardware.getId() + " modelHandle=" + mModelHandle
                    + " locale=" + locale);
            SoundTrigger.KeyphraseRecognitionExtra extra =
                    new SoundTrigger.KeyphraseRecognitionExtra(TriggerConfig.KEYPHRASE_ID,
                            SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER,
                            TriggerPrefs.getConfidence(mContext));
            // captureRequested=true so the audio the DSP buffered for the trigger can be read back
            // (to filter false triggers and, later, to verify the owner's voice).
            mConfig = new SoundTrigger.RecognitionConfig.Builder()
                    .setCaptureRequested(true)
                    .setKeyphrases(Collections.singletonList(extra))
                    .build();
            startRecognition();
        } catch (SecurityException e) {
            Log.e(TAG, "SoundTrigger permission denied", e);
            fail("SoundTrigger permission denied (check the privapp allowlist and RECORD_AUDIO)");
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "SoundTrigger connection failed", e);
            retry("SoundTrigger error: " + e.getClass().getSimpleName(),
                    SoundTrigger.STATUS_ERROR);
        }
    }

    private static boolean isExpectedModel(SoundTrigger.KeyphraseSoundModel model) {
        return model != null && model.getKeyphrases().length == 1
                && model.getKeyphrases()[0].getId() == TriggerConfig.KEYPHRASE_ID
                && TriggerConfig.KEYPHRASE_TEXT.equals(model.getKeyphrases()[0].getText());
    }

    /** Ask the vendor enrollment app to write its model for this locale, then try again. */
    private void requestEnrollment(String locale) {
        if (mEnrollAttempts >= MAX_ENROLL_ATTEMPTS) {
            fail("Keyphrase model " + TriggerConfig.KEYPHRASE_ID + " is not enrolled for any locale");
            return;
        }
        mEnrollAttempts++;
        state("Asking the vendor app to enroll \"" + TriggerConfig.KEYPHRASE_TEXT + "\" for "
                + locale);
        try {
            Intent enroll = new Intent("com.android.intent.action.MANAGE_VOICE_KEYPHRASES")
                    .setComponent(new ComponentName("com.android.hotwordenrollment.xgoogle",
                            "com.android.hotwordenrollment.xgoogle.EnrollmentDelegateReceiver"))
                    .putExtra("com.android.intent.extra.VOICE_KEYPHRASE_ACTION", 0) // enroll
                    .putExtra("com.android.intent.extra.VOICE_KEYPHRASE_HINT_TEXT",
                            TriggerConfig.KEYPHRASE_TEXT)
                    .putExtra("com.android.intent.extra.VOICE_KEYPHRASE_LOCALE", locale);
            mContext.sendBroadcast(enroll);
        } catch (RuntimeException e) {
            Log.w(TAG, "Enroll broadcast failed", e);
        }
        mWorker.postDelayed(mReconnect, ENROLL_WAIT_MS);
    }

    private void startRecognition() {
        if (!mRunning || mModule == null || mModelHandle < 0 || mListening) return;
        int status = mModule.startRecognition(mModelHandle, mConfig);
        if (status == SoundTrigger.STATUS_OK) {
            mListening = true;
            mBackoffMs = RETRY_MIN_MS;
            mWorker.removeCallbacks(mReconnect);
            state("Listening for \"OK Google\" on the DSP");
        } else {
            retry(status == SoundTrigger.STATUS_BUSY ? "DSP resources busy" : "startRecognition",
                    status);
        }
    }

    private final SoundTrigger.StatusListener mEvents = new SoundTrigger.StatusListener() {
        @Override
        public void onRecognition(SoundTrigger.RecognitionEvent event) {
            if (!mRunning || event.soundModelHandle != mModelHandle) return;
            Log.i(TAG, "onRecognition status=" + event.status
                    + " stillActive=" + event.recognitionStillActive);
            if (!event.recognitionStillActive) mListening = false;
            boolean hotword = false;
            int captureSession = event.captureSession;
            boolean captureAvailable = event.captureAvailable;
            if (event.status == SoundTrigger.RECOGNITION_STATUS_SUCCESS
                    && event instanceof SoundTrigger.KeyphraseRecognitionEvent) {
                for (SoundTrigger.KeyphraseRecognitionExtra extra :
                        ((SoundTrigger.KeyphraseRecognitionEvent) event).keyphraseExtras) {
                    if (extra.id == TriggerConfig.KEYPHRASE_ID) {
                        hotword = true;
                        Log.i(TAG, "trigger confidence=" + extra.coarseConfidenceLevel
                                + " captureAvailable=" + captureAvailable
                                + " captureSession=" + captureSession);
                    }
                }
            }
            boolean stillActive = event.recognitionStillActive;
            if (!hotword) { // ABORT / FAILURE / GET_STATE_RESPONSE
                if (!stillActive) startRecognition();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - mLastTriggerMs < TRIGGER_DEBOUNCE_MS) {
                if (!stillActive) startRecognition();
                return;
            }
            mLastTriggerMs = now;
            onHotwordConfirmed(captureSession, captureAvailable, stillActive);
        }

        @Override
        public void onModelUnloaded(int handle) {
            if (!mRunning || handle != mModelHandle) return;
            mListening = false;
            mPreempted = true;
            retry("Model unloaded by the system", SoundTrigger.STATUS_BUSY);
        }

        @Override
        public void onResourcesAvailable() {
            if (!mRunning) return;
            if (mPreempted || mModule == null) {
                mWorker.removeCallbacks(mReconnect);
                mWorker.post(mReconnect);
            } else if (mModelHandle >= 0 && !mListening) {
                startRecognition();
            }
        }

        @Override
        public void onServiceDied() {
            if (mRunning) retry("SoundTrigger service restarted", SoundTrigger.STATUS_DEAD_OBJECT);
        }
    };

    /**
     * Runs on the DSP worker thread after a debounced keyphrase trigger.
     *
     * <p>When voice match is enabled, the trigger audio must be read <em>before</em> re-arming
     * (re-arming invalidates the capture session), the capture is checked for speech and the
     * owner's voice, and the assistant starts only if it passes. This adds a short delay, so it
     * only happens when the user turned voice match on. Otherwise the assistant starts right away.
     */
    private void onHotwordConfirmed(int captureSession, boolean captureAvailable,
            boolean stillActive) {
        if (mEnrolling) {
            short[] sample = captureAvailable
                    ? HotwordAudio.capture(captureSession, HotwordAudio.DEFAULT_MAX_MS) : null;
            if (!stillActive) startRecognition();
            onEnrollCapture(sample);
            return;
        }
        boolean voiceMatch = TriggerPrefs.isVoiceMatch(mContext);
        if (!voiceMatch || !captureAvailable) {
            if (!stillActive) startRecognition();
            if (voiceMatch) {
                Log.w(TAG, "voice match on but no capture available, launching anyway");
            }
            AssistantLauncher.onHotword(mContext);
            maybeSaveDebug(captureAvailable ? captureSession : -1);
            return;
        }

        short[] audio = HotwordAudio.capture(captureSession, HotwordAudio.DEFAULT_MAX_MS);
        if (!stillActive) startRecognition(); // re-arm as soon as capture is done
        keepCapture("verify", audio);

        if (audio == null || !VoiceMatch.looksLikeSpeech(audio)) {
            Log.i(TAG, "rejected: capture is not speech");
            return; // snoring, noise or silence
        }
        SpeakerEmbedding.Profile profile;
        try {
            profile = SpeakerEmbedding.load(profileFile());
        } catch (RuntimeException e) {
            Log.w(TAG, "voice profile load failed, launching anyway", e);
            AssistantLauncher.onHotword(mContext);
            return;
        }
        if (profile == null) {
            // voice match on but not enrolled yet: speech gate passed, launch
            AssistantLauncher.onHotword(mContext);
            return;
        }
        boolean owner;
        try {
            owner = SpeakerEmbedding.verify(mContext, profile, audio,
                    TriggerPrefs.getStrictness(mContext));
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "voice verify failed, launching anyway", e); // fail-open
            owner = true;
        }
        if (owner) {
            AssistantLauncher.onHotword(mContext);
        }
    }

    private void maybeSaveDebug(int captureSession) {
        if (!TriggerConfig.DEBUG_SAVE_TRIGGER_AUDIO || captureSession < 0) {
            return;
        }
        new Thread(() -> {
            short[] audio = HotwordAudio.capture(captureSession, HotwordAudio.DEFAULT_MAX_MS);
            if (audio != null) {
                HotwordAudio.writeWav(
                        new java.io.File(mContext.getFilesDir(), "last-trigger.wav"), audio);
            }
        }, "DspHotword-capture").start();
    }

    // ---- Voice match enrollment ----
    // Enrollment reuses the real trigger path: the next ENROLL_TARGET keyphrases are captured and
    // turned into the owner's profile instead of launching the assistant, so the recording channel
    // matches verification exactly.

    static final int ENROLL_TARGET = 5;

    interface EnrollListener {
        /** @param captured usable samples so far; done is true when enrollment finished */
        void onProgress(int captured, int target, boolean done, boolean success);
    }

    private volatile boolean mEnrolling;
    private final java.util.List<short[]> mEnrollSamples = new java.util.ArrayList<>();
    private EnrollListener mEnrollListener;

    void startEnrollment(EnrollListener listener) {
        mWorker.post(() -> {
            mEnrollSamples.clear();
            mEnrollListener = listener;
            mEnrolling = true;
            Log.i(TAG, "enrollment started");
        });
    }

    void cancelEnrollment() {
        mWorker.post(() -> {
            mEnrolling = false;
            mEnrollSamples.clear();
            mEnrollListener = null;
        });
    }

    /** Keeps the last captures in files/captures, to tune voice match offline. */
    private void keepCapture(String kind, short[] audio) {
        if (!TriggerConfig.DEBUG_KEEP_CAPTURES || audio == null) return;
        java.io.File dir = new java.io.File(mContext.getFilesDir(), "captures");
        dir.mkdirs();
        HotwordAudio.writeWav(new java.io.File(dir,
                System.currentTimeMillis() + "-" + kind + ".wav"), audio);
        java.io.File[] files = dir.listFiles();
        if (files != null && files.length > 60) {
            java.util.Arrays.sort(files);
            for (int i = 0; i < files.length - 60; i++) files[i].delete();
        }
    }

    private void onEnrollCapture(short[] sample) {
        keepCapture("enroll", sample);
        if (sample != null && VoiceMatch.looksLikeSpeech(sample)) {
            mEnrollSamples.add(sample);
        } else {
            Log.i(TAG, "enrollment: sample ignored (not enough speech)");
        }
        int captured = mEnrollSamples.size();
        boolean done = captured >= ENROLL_TARGET;
        boolean success = false;
        if (done) {
            mEnrolling = false;
            int used;
            try {
                used = SpeakerEmbedding.enroll(mContext, profileFile(), mEnrollSamples);
            } catch (RuntimeException | LinkageError e) {
                Log.e(TAG, "speaker enrollment failed", e);
                used = 0;
            }
            success = used > 0;
            if (success) {
                TriggerPrefs.setVoiceMatch(mContext, true);
            }
            mEnrollSamples.clear();
        }
        EnrollListener l = mEnrollListener;
        if (l != null) {
            l.onProgress(captured, ENROLL_TARGET, done, success);
        }
        if (done) {
            mEnrollListener = null;
        }
    }

    private java.io.File profileFile() {
        return new java.io.File(mContext.getFilesDir(), "speaker.bin");
    }

    boolean hasVoiceProfile() {
        return SpeakerEmbedding.load(profileFile()) != null;
    }

    void deleteVoiceProfile() {
        mWorker.post(() -> {
            profileFile().delete();
            new java.io.File(mContext.getFilesDir(), "voicematch.bin").delete(); // old MFCC profile
            TriggerPrefs.setVoiceMatch(mContext, false);
        });
    }

    private void retry(String reason, int status) {
        if (!mRunning) return;
        long delay = mBackoffMs;
        mBackoffMs = Math.min(mBackoffMs * 2, RETRY_MAX_MS);
        state(reason + " (" + status + "), retrying in " + delay / 1_000 + " s");
        mWorker.removeCallbacks(mReconnect);
        mWorker.postDelayed(mReconnect, delay);
    }

    private final Runnable mReconnect = () -> {
        if (!mRunning) return;
        releaseModule();
        connect();
    };

    private void fail(String reason) {
        mRunning = false;
        mWorker.removeCallbacks(mReconnect);
        releaseModule();
        state(reason);
    }

    private void releaseModule() {
        if (mModule == null) return;
        if (mModelHandle >= 0) {
            if (mListening) {
                try {
                    int status = mModule.stopRecognition(mModelHandle);
                    if (status != SoundTrigger.STATUS_OK) Log.w(TAG, "stopRecognition: " + status);
                } catch (RuntimeException e) {
                    Log.w(TAG, "stopRecognition failed during cleanup", e);
                }
            }
            try {
                int status = mModule.unloadSoundModel(mModelHandle);
                if (status != SoundTrigger.STATUS_OK) Log.w(TAG, "unloadSoundModel: " + status);
            } catch (RuntimeException e) {
                Log.w(TAG, "unloadSoundModel failed during cleanup", e);
            }
        }
        try {
            mModule.detach();
        } catch (RuntimeException e) {
            Log.w(TAG, "detach failed during cleanup", e);
        }
        mModule = null;
        mModelHandle = -1;
        mListening = false;
        mPreempted = false;
        mConfig = null;
    }
}
