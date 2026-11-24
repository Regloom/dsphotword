package io.github.regloom.dsphotword;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Settings for the DSP trigger. Built in code so the app needs no layout resources. It is injected
 * into Settings → System through the manifest meta-data, and opened by the quick-settings tile.
 */
public class SettingsActivity extends Activity {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mVoiceStatus;
    private Button mEnrollButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 35+ is always edge-to-edge, and the action bar of this theme then overlaps the
        // content. Draw the title as part of the content instead, padded by the system insets.
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Headline);
        title.setPadding(0, pad, 0, pad);
        root.addView(title);

        Switch enabled = new Switch(this);
        enabled.setText(R.string.pref_enabled);
        enabled.setChecked(TriggerPrefs.isEnabled(this));
        enabled.setOnCheckedChangeListener((v, on) -> {
            TriggerPrefs.setEnabled(this, on);
            if (on) {
                DspListener.get(this).start();
            } else {
                DspListener.get(this).stop();
            }
        });
        root.addView(enabled);

        Switch beep = new Switch(this);
        beep.setText(R.string.pref_beep);
        beep.setChecked(TriggerPrefs.isBeep(this));
        beep.setOnCheckedChangeListener((v, on) -> TriggerPrefs.setBeep(this, on));
        root.addView(beep);

        addHeader(root, pad, getString(R.string.voice_match_header));
        TextView vmHint = new TextView(this);
        vmHint.setText(R.string.voice_match_hint);
        root.addView(vmHint);

        Switch voiceMatch = new Switch(this);
        voiceMatch.setText(R.string.pref_voice_match);
        voiceMatch.setChecked(TriggerPrefs.isVoiceMatch(this));
        voiceMatch.setEnabled(DspListener.get(this).hasVoiceProfile());
        voiceMatch.setOnCheckedChangeListener(
                (v, on) -> TriggerPrefs.setVoiceMatch(this, on));
        root.addView(voiceMatch);

        mVoiceStatus = new TextView(this);
        root.addView(mVoiceStatus);

        mEnrollButton = new Button(this);
        mEnrollButton.setOnClickListener(v -> startEnrollment(voiceMatch));
        root.addView(mEnrollButton);

        Button deleteProfile = new Button(this);
        deleteProfile.setText(R.string.voice_delete);
        deleteProfile.setOnClickListener(v -> {
            DspListener.get(this).deleteVoiceProfile();
            voiceMatch.setChecked(false);
            voiceMatch.setEnabled(false);
            updateVoiceStatus();
        });
        root.addView(deleteProfile);

        addHeader(root, pad, getString(R.string.strictness_header));
        SeekBar strictness = new SeekBar(this);
        strictness.setMax(100);
        strictness.setProgress(TriggerPrefs.getStrictness(this));
        strictness.setOnSeekBarChangeListener(new SimpleSeek(
                p -> TriggerPrefs.setStrictness(this, p)));
        root.addView(strictness);

        addHeader(root, pad, getString(R.string.confidence_header));
        SeekBar confidence = new SeekBar(this);
        confidence.setMax(100);
        confidence.setProgress(TriggerPrefs.getConfidence(this));
        confidence.setOnSeekBarChangeListener(new SimpleSeek(
                p -> TriggerPrefs.setConfidence(this, p)));
        root.addView(confidence);

        addHeader(root, pad, getString(R.string.dsp_model_header));
        TextView modelHint = new TextView(this);
        modelHint.setText(R.string.dsp_model_hint);
        root.addView(modelHint);
        String[] models = {TriggerPrefs.DSP_MODEL_AUTO, TriggerPrefs.DSP_MODEL_ENGLISH,
                TriggerPrefs.DSP_MODEL_UNIVERSAL};
        int[] labels = {R.string.dsp_model_auto, R.string.dsp_model_english,
                R.string.dsp_model_universal};
        RadioGroup modelGroup = new RadioGroup(this);
        String current = TriggerPrefs.getDspModel(this);
        for (int i = 0; i < models.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(i + 1);
            button.setText(labels[i]);
            button.setChecked(models[i].equals(current));
            modelGroup.addView(button);
        }
        modelGroup.setOnCheckedChangeListener((group, id) -> {
            TriggerPrefs.setDspModel(this, models[id - 1]);
            // load the other copy of the model on the DSP now
            if (TriggerPrefs.isEnabled(this)) {
                DspListener.get(this).restart();
            }
        });
        root.addView(modelGroup);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        setContentView(scroll);
        updateVoiceStatus();
    }

    private void addHeader(LinearLayout root, int pad, String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setPadding(0, pad, 0, 0);
        header.setTextAppearance(android.R.style.TextAppearance_Material_Medium);
        root.addView(header);
    }

    private void startEnrollment(Switch voiceMatch) {
        mEnrollButton.setEnabled(false);
        mVoiceStatus.setText(getString(R.string.voice_enroll_prompt, DspListener.ENROLL_TARGET, 0));
        DspListener.get(this).startEnrollment((captured, target, done, success) ->
                mHandler.post(() -> {
                    if (!done) {
                        mVoiceStatus.setText(
                                getString(R.string.voice_enroll_progress, captured, target));
                    } else {
                        mEnrollButton.setEnabled(true);
                        if (success) {
                            voiceMatch.setEnabled(true);
                            voiceMatch.setChecked(true);
                            mVoiceStatus.setText(R.string.voice_enroll_done);
                        } else {
                            mVoiceStatus.setText(R.string.voice_enroll_failed);
                        }
                        updateVoiceStatus();
                    }
                }));
    }

    private void updateVoiceStatus() {
        boolean has = DspListener.get(this).hasVoiceProfile();
        mEnrollButton.setText(has ? R.string.voice_rerecord : R.string.voice_record);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DspListener.get(this).cancelEnrollment();
    }

    /** SeekBar listener that only reacts to the final value. */
    private static final class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        interface OnSet {
            void set(int progress);
        }

        private final OnSet mOnSet;

        SimpleSeek(OnSet onSet) {
            mOnSet = onSet;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {}

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mOnSet.set(seekBar.getProgress());
        }
    }
}
