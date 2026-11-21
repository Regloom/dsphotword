package io.github.regloom.dsphotword;

import android.app.KeyguardManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.app.IVoiceInteractionManagerService;

import java.util.List;

/** Starts the app that holds the ASSISTANT role ("Default digital assistant app"). */
final class AssistantLauncher {
    private static final String TAG = "DspHotword";

    /** AssistUtils.INVOCATION_TYPE_KEY / INVOCATION_TYPE_VOICE: invoked by voice, as after a hotword. */
    private static final String INVOCATION_TYPE_KEY = "invocation_type";
    private static final int INVOCATION_TYPE_VOICE = 3;
    private static final String INVOCATION_TIME_MS_KEY = "invocation_time_ms";
    /** VoiceInteractionSession.SHOW_WITH_ASSIST | SHOW_SOURCE_PUSH_TO_TALK (checked with Gemini in v2.4). */
    private static final int SHOW_FLAGS = 1 | (1 << 5);
    private static final String EXTRA_ASSIST_INPUT_HINT_KEYBOARD =
            "android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD";

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private AssistantLauncher() {}

    /** Called on the DSP thread when the keyphrase is detected. */
    static void onHotword(Context context) {
        sMain.post(() -> launch(context));
    }

    private static void launch(Context context) {
        String holder = getAssistantHolder(context);
        if (holder == null) {
            Log.w(TAG, "Hotword detected, but no default digital assistant app is selected");
            beep(context, ToneGenerator.TONE_PROP_NACK);
            return;
        }
        ComponentName activeVis = getActiveVoiceService();
        if (activeVis != null && holder.equals(activeVis.getPackageName())) {
            // The assistant has its own voice interaction service: show its session as if it
            // had been invoked by voice. It manages the keyguard and its own UI.
            wakeScreen(context);
            beep(context, ToneGenerator.TONE_PROP_BEEP);
            Log.i(TAG, "Hotword -> voice session of " + activeVis.flattenToShortString()
                    + ": " + showVoiceSession());
            return;
        }
        Intent assist = findAssistIntent(context, holder);
        if (assist == null) {
            Log.w(TAG, "Assistant " + holder + " has no ASSIST/VOICE_COMMAND activity");
            beep(context, ToneGenerator.TONE_PROP_NACK);
            return;
        }
        wakeScreen(context);
        beep(context, ToneGenerator.TONE_PROP_BEEP);
        try {
            if (launchOverKeyguard(context, assist)) return;
            UnlockTrampolineActivity.launch(context, assist);
            Log.i(TAG, "Hotword -> " + assist.getAction() + " " + assist.getComponent());
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to start assistant " + holder, e);
        }
    }

    /** RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE / EXTRA_SECURE. */
    private static final String ACTION_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE";
    private static final String EXTRA_SECURE = "android.speech.extras.EXTRA_SECURE";

    /**
     * Standard way for an assistant without a voice interaction service to be usable over the
     * lock screen: an activity for ACTION_VOICE_SEARCH_HANDS_FREE, which "may be launched while
     * device is locked in a secure mode" (RecognizerIntent docs), the same intent a Bluetooth
     * headset sends. The activity shows itself over the keyguard; EXTRA_SECURE tells it the
     * device is securely locked, so it must not perform actions that compromise security.
     */
    private static boolean launchHandsFree(Context context, KeyguardManager km, String pkg) {
        Intent intent = new Intent(ACTION_HANDS_FREE).setPackage(pkg);
        ResolveInfo ri = context.getPackageManager()
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null || !ri.activityInfo.exported) return false;
        intent.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                .putExtra(EXTRA_SECURE, km.isKeyguardSecure())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
        Log.i(TAG, "Hotword -> hands-free " + intent.getComponent().flattenToShortString()
                + " over the keyguard (secure=" + km.isKeyguardSecure() + ")");
        return true;
    }

    /**
     * Fallback for Dicio builds without ACTION_VOICE_SEARCH_HANDS_FREE (upstream Dicio, until it
     * gets merged), which shows itself over the lock screen only for its own wake word action.
     * Dicio's MainActivity (singleTop) calls
     * setShowWhenLocked/setTurnScreenOn only for its wake word action, and starts listening only
     * for ASSIST, so it gets both: the wake word action first, then ASSIST via onNewIntent.
     */
    /** Class name, not package: debug and forked builds add a suffix to the application id. */
    private static final String DICIO_ACTIVITY = "org.stypox.dicio.MainActivity";
    private static final String DICIO_WAKE_ACTION = "org.stypox.dicio.MainActivity.ACTION_WAKE_WORD";
    private static final long DICIO_ASSIST_DELAY_MS = 500L;

    private static boolean launchOverKeyguard(Context context, Intent assist) {
        KeyguardManager km = context.getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) return false;
        ComponentName target = assist.getComponent();
        if (target != null && launchHandsFree(context, km, target.getPackageName())) return true;
        if (target == null || !DICIO_ACTIVITY.equals(target.getClassName())) return false;
        context.startActivity(new Intent(DICIO_WAKE_ACTION).setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        Intent listen = new Intent(assist)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        sMain.postDelayed(() -> {
            try {
                context.startActivity(listen);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to send ASSIST to " + target, e);
            }
        }, DICIO_ASSIST_DELAY_MS);
        Log.i(TAG, "Hotword -> " + target.flattenToShortString() + " over the keyguard");
        return true;
    }

    private static String getAssistantHolder(Context context) {
        try {
            RoleManager rm = context.getSystemService(RoleManager.class);
            List<String> holders = rm != null ? rm.getRoleHolders(RoleManager.ROLE_ASSISTANT) : null;
            return holders == null || holders.isEmpty() ? null : holders.get(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "getRoleHolders failed", e);
            return null;
        }
    }

    private static Intent findAssistIntent(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        for (String action : new String[] {Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND}) {
            Intent intent = new Intent(action).setPackage(pkg);
            ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && ri.activityInfo.exported) {
                intent.setComponent(new ComponentName(ri.activityInfo.packageName,
                        ri.activityInfo.name));
                intent.putExtra(EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false);
                return intent;
            }
        }
        return null;
    }

    private static IVoiceInteractionManagerService vims() {
        return IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
    }

    private static ComponentName getActiveVoiceService() {
        try {
            IVoiceInteractionManagerService s = vims();
            return s != null ? s.getActiveServiceComponentName() : null;
        } catch (Exception e) {
            Log.w(TAG, "getActiveServiceComponentName failed", e);
            return null;
        }
    }

    private static boolean showVoiceSession() {
        try {
            IVoiceInteractionManagerService s = vims();
            if (s == null) return false;
            Bundle args = new Bundle();
            args.putInt(INVOCATION_TYPE_KEY, INVOCATION_TYPE_VOICE);
            args.putLong(INVOCATION_TIME_MS_KEY, SystemClock.elapsedRealtime());
            return s.showSessionForActiveService(args, SHOW_FLAGS, null, null, null);
        } catch (Exception e) {
            Log.e(TAG, "showSessionForActiveService failed", e);
            return false;
        }
    }

    @SuppressWarnings("deprecation") // SCREEN_BRIGHT_WAKE_LOCK still turns the screen on.
    private static void wakeScreen(Context context) {
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm == null || pm.isInteractive()) return;
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP, "DspHotword:wake");
        wl.acquire(3_000L);
    }

    private static void beep(Context context, int toneType) {
        if (!TriggerPrefs.isBeep(context)) return;
        try {
            // STREAM_MUSIC is audible in Do Not Disturb, unlike notification sounds.
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tone.startTone(toneType, 150);
            sMain.postDelayed(tone::release, 250L);
        } catch (RuntimeException e) {
            Log.w(TAG, "beep failed", e);
        }
    }
}
