package io.github.regloom.dsphotword;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Speaker verification with a neural speaker embedding (sherpa-onnx, 3D-Speaker CAM++ zh+en model
 * in assets/speaker.onnx). Offline tests on captures from the phone: the owner scores 0.52-0.85
 * cosine similarity to the enrolled centroid, another person 0.24-0.46, while the classic MFCC/VQ
 * distances of the two overlapped completely.
 *
 * <p>The extractor is loaded lazily on first use and kept, since the process is persistent. Any
 * failure (library, model) is reported to the caller, which then does not block the assistant.
 */
final class SpeakerEmbedding {
    private static final String TAG = "DspHotword";
    private static final String MODEL_ASSET = "speaker.onnx";
    private static final int PROFILE_VERSION = 3;
    /** Enrollment samples below this leave-one-out similarity are dropped as bad captures. */
    private static final float OUTLIER_COSINE = 0.5f;
    private static final int MIN_SAMPLES = 3;

    private static SpeakerEmbeddingExtractor sExtractor;

    private SpeakerEmbedding() {}

    private static synchronized SpeakerEmbeddingExtractor extractor(Context context) {
        if (sExtractor == null) {
            long start = System.currentTimeMillis();
            SpeakerEmbeddingExtractorConfig config =
                    new SpeakerEmbeddingExtractorConfig(MODEL_ASSET, 2, false, "cpu");
            sExtractor = new SpeakerEmbeddingExtractor(context.getAssets(), config);
            Log.i(TAG, "speaker model loaded in " + (System.currentTimeMillis() - start) + " ms");
        }
        return sExtractor;
    }

    /**
     * Frees the model (~70 MB of native memory). It is loaded again on the next verification,
     * which then takes ~0.8 s longer.
     */
    static synchronized void release() {
        if (sExtractor != null) {
            sExtractor.release();
            sExtractor = null;
            Log.i(TAG, "speaker model released");
        }
    }

    /** @return the L2-normalized embedding of 16 kHz PCM16 audio */
    static float[] embed(Context context, short[] pcm) {
        float[] samples = new float[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            samples[i] = pcm[i] / 32768f;
        }
        SpeakerEmbeddingExtractor ex = extractor(context);
        OnlineStream stream = ex.createStream();
        try {
            stream.acceptWaveform(samples, HotwordAudio.SAMPLE_RATE);
            stream.inputFinished();
            return normalize(ex.compute(stream));
        } finally {
            stream.release();
        }
    }

    /** Owner profile: centroid of the enrollment embeddings. */
    static final class Profile {
        final float[] centroid;
        final float looMean; // leave-one-out similarity of the enrollment samples

        Profile(float[] centroid, float looMean) {
            this.centroid = centroid;
            this.looMean = looMean;
        }
    }

    /**
     * Builds the owner's profile from keyphrase captures. Bad captures (near silence, cut phrase)
     * are dropped by leave-one-out similarity.
     * @return the number of samples used, or 0 if there were not enough good ones
     */
    static int enroll(Context context, File file, List<short[]> captures) {
        List<float[]> emb = new ArrayList<>();
        for (short[] pcm : captures) {
            emb.add(embed(context, pcm));
        }
        float[] loo = leaveOneOut(emb);
        List<float[]> good = new ArrayList<>();
        StringBuilder log = new StringBuilder("enroll loo:");
        for (int i = 0; i < emb.size(); i++) {
            log.append(' ').append(String.format(java.util.Locale.ROOT, "%.2f", loo[i]));
            if (loo[i] >= OUTLIER_COSINE) good.add(emb.get(i));
        }
        Log.i(TAG, log.toString());
        if (good.size() < MIN_SAMPLES) {
            return 0;
        }
        float[] goodLoo = leaveOneOut(good);
        float mean = 0;
        for (float v : goodLoo) mean += v;
        mean /= goodLoo.length;
        writeProfile(file, centroid(good), mean);
        Log.i(TAG, "speaker profile: " + good.size() + "/" + emb.size() + " samples, loo="
                + mean);
        return good.size();
    }

    /**
     * @param strictness 0..100; maps to a cosine threshold from 0.30 (lenient) to 0.65 (strict).
     */
    static boolean verify(Context context, Profile profile, short[] pcm, int strictness) {
        float cos = dot(embed(context, pcm), profile.centroid);
        float threshold = threshold(strictness);
        boolean ok = cos >= threshold;
        Log.i(TAG, String.format(java.util.Locale.ROOT,
                "speaker cos=%.3f threshold=%.3f (loo=%.2f) -> %s",
                cos, threshold, profile.looMean, ok ? "OWNER" : "reject"));
        return ok;
    }

    static float threshold(int strictness) {
        return 0.30f + 0.35f * Math.max(0, Math.min(100, strictness)) / 100f;
    }

    static Profile load(File file) {
        if (!file.exists()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != PROFILE_VERSION) return null;
            float looMean = in.readFloat();
            int dim = in.readInt();
            if (dim <= 0 || dim > 4096) return null;
            float[] c = new float[dim];
            for (int i = 0; i < dim; i++) c[i] = in.readFloat();
            return new Profile(c, looMean);
        } catch (IOException e) {
            Log.w(TAG, "Can't read speaker profile", e);
            return null;
        }
    }

    private static void writeProfile(File file, float[] centroid, float looMean) {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(PROFILE_VERSION);
            out.writeFloat(looMean);
            out.writeInt(centroid.length);
            for (float v : centroid) out.writeFloat(v);
        } catch (IOException e) {
            Log.w(TAG, "Can't write speaker profile", e);
        }
    }

    private static float[] leaveOneOut(List<float[]> emb) {
        float[] out = new float[emb.size()];
        for (int i = 0; i < emb.size(); i++) {
            List<float[]> others = new ArrayList<>(emb);
            others.remove(i);
            out[i] = others.isEmpty() ? 1f : dot(emb.get(i), centroid(others));
        }
        return out;
    }

    private static float[] centroid(List<float[]> emb) {
        float[] c = new float[emb.get(0).length];
        for (float[] e : emb) {
            for (int i = 0; i < c.length; i++) c[i] += e[i];
        }
        return normalize(c);
    }

    private static float[] normalize(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) {
            for (int i = 0; i < v.length; i++) v[i] /= (float) n;
        }
        return v;
    }

    private static float dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
        return (float) s;
    }
}
