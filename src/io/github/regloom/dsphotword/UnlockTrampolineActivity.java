package io.github.regloom.dsphotword;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Transparent activity: turns the screen on, optionally dismisses the keyguard (fingerprint,
 * face, swipe) and then starts the assistant. Assistant activities that are not shown over
 * the lock screen would otherwise start invisibly behind it.
 */
public class UnlockTrampolineActivity extends Activity {
    private static final String TAG = "DspHotword";
    private static final String EXTRA_TARGET = "io.github.regloom.dsphotword.extra.TARGET";
    private static final long SETTLE_MS = 400L;
    private static final long RETRY_MS = 500L;
    private static final long AUTO_CANCEL_MS = 2_000L;
    private static final int MAX_ATTEMPTS = 4;
    private static final long GIVE_UP_MS = 20_000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRequested;
    private int mAttempts;

    static void launch(Context ctx, Intent target) {
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        boolean locked = km != null && km.isKeyguardLocked();
        boolean screenOff = pm != null && !pm.isInteractive();
        if (!locked && !screenOff) {
            ctx.startActivity(new Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        ctx.startActivity(new Intent(ctx, UnlockTrampolineActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_TARGET, target));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) {
            proceed();
            return;
        }
        mHandler.postDelayed(this::finish, GIVE_UP_MS);
        // Fallback in case the window never reports focus.
        mHandler.postDelayed(() -> {
            if (!mRequested) scheduleDismiss(0);
        }, 1_500L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Asking while the screen is still leaving doze makes SystemUI cancel the request at
        // once (PRIMARY_BOUNCER -> DREAMING), so wait for focus and an interactive screen.
        if (hasFocus && !mRequested && !isFinishing()) scheduleDismiss(SETTLE_MS);
    }

    private void scheduleDismiss(long delayMs) {
        mRequested = true;
        mHandler.postDelayed(this::requestDismiss, delayMs);
    }

    private void requestDismiss() {
        if (isFinishing()) return;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km == null || !km.isKeyguardLocked()) {
            proceed();
            return;
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            mHandler.postDelayed(this::requestDismiss, SETTLE_MS);
            return;
        }
        mAttempts++;
        final long requestedAt = SystemClock.elapsedRealtime();
        Log.i(TAG, "Keyguard locked, requesting dismiss (attempt " + mAttempts + ")");
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                proceed();
            }

            @Override
            public void onDismissCancelled() {
                long after = SystemClock.elapsedRealtime() - requestedAt;
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                if (keyguard != null && !keyguard.isKeyguardLocked()) {
                    // Unlocked by fingerprint outside of this request: still start the assistant.
                    Log.i(TAG, "Unlock request cancelled, but the device is unlocked");
                    proceed();
                    return;
                }
                // A cancel within a fraction of a second comes from a keyguard transition, not
                // from the user closing the bouncer: ask again.
                if (after < AUTO_CANCEL_MS && mAttempts < MAX_ATTEMPTS && !isFinishing()) {
                    Log.i(TAG, "Unlock cancelled after " + after + " ms, retrying");
                    mHandler.postDelayed(UnlockTrampolineActivity.this::requestDismiss, RETRY_MS);
                } else {
                    Log.i(TAG, "Unlock cancelled");
                    finish();
                }
            }

            @Override
            public void onDismissError() {
                Log.w(TAG, "Unlock error");
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void proceed() {
        Intent target = getIntent().getParcelableExtra(EXTRA_TARGET, Intent.class);
        if (target != null) {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                Log.e(TAG, "Launch after unlock failed", e);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
