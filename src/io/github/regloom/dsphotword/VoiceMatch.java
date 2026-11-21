package io.github.regloom.dsphotword;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Text-dependent speaker verification on the CPU, from the DSP trigger capture. The keyphrase is
 * always the same ("OK Google") and is always recorded through the same hotword-capture path, so a
 * simple classic pipeline is enough and needs no model download:
 *
 * <ul>
 *   <li>MFCC features (mel-frequency cepstral coefficients) per 25 ms frame;
 *   <li>a per-speaker codebook built with LBG vector quantization from the owner's samples;
 *   <li>verification = average quantization distance of the new audio to the codebook.
 * </ul>
 *
 * Method from Minh N. Do, "An Automatic Speaker Recognition System", University of Illinois:
 * https://minhdo.ece.illinois.edu/teaching/speaker_recognition/speaker_recognition.html
 *
 * Before speaker matching, {@link #looksLikeSpeech} rejects non-speech captures (snoring, silence,
 * noise) cheaply, which is useful even without an enrolled profile.
 */
final class VoiceMatch {
    private static final String TAG = "DspHotword";

    private static final int SAMPLE_RATE = HotwordAudio.SAMPLE_RATE;
    private static final int FRAME_LEN = 400;   // 25 ms
    private static final int FRAME_STEP = 160;  // 10 ms
    private static final int FFT_SIZE = 512;
    private static final int MEL_FILTERS = 26;
    private static final int NUM_MFCC = 13;     // includes c0, dropped below for liftering
    private static final int CEP_LO = 1;        // drop c0 (energy) for channel robustness
    private static final int CEP_HI = 13;       // -> 12-dim feature
    private static final int FEATURE_DIM = CEP_HI - CEP_LO;

    private static final int CODEBOOK_SIZE = 16;
    private static final double VInf = Double.MAX_VALUE;

    // A frame counts as voiced if its log-energy is this far above the capture's own floor.
    private static final double VOICED_MARGIN = 1.0;
    private static final int MIN_VOICED_FRAMES = 12;  // ~120 ms of speech
    private static final int MAX_GAP_FRAMES = 15;     // pauses inside the phrase, ~150 ms
    private static final int PROFILE_VERSION = 2;

    private static float[] sMelLo;
    private static int[][] sMelBins;

    private VoiceMatch() {}

    // ---- public API ----

    /** @return true if the capture plausibly contains spoken speech, not snoring/noise/silence */
    static boolean looksLikeSpeech(short[] pcm) {
        List<float[]> frames = mfcc(pcm);
        return frames.size() >= MIN_VOICED_FRAMES;
    }

    /**
     * Builds a speaker profile from several captures of the keyphrase and writes it to {@code file}.
     * The accept threshold comes from leave-one-out: each sample is scored against a codebook
     * trained on the other samples, which estimates how far a new owner capture usually lands.
     * @return the number of usable samples, or 0 if there were not enough
     */
    static int enroll(File file, List<short[]> samples) {
        List<List<float[]>> perSample = new ArrayList<>();
        for (short[] pcm : samples) {
            List<float[]> f = mfcc(pcm);
            if (f.size() >= MIN_VOICED_FRAMES) {
                perSample.add(f);
            }
        }
        if (perSample.size() < 3) {
            return 0;
        }
        List<float[]> all = new ArrayList<>();
        for (List<float[]> f : perSample) all.addAll(f);
        float[] scale = inverseStd(all);

        double[] loo = new double[perSample.size()];
        for (int k = 0; k < perSample.size(); k++) {
            List<float[]> train = new ArrayList<>();
            for (int j = 0; j < perSample.size(); j++) {
                if (j != k) train.addAll(perSample.get(j));
            }
            loo[k] = avgDistance(perSample.get(k), lbg(train, CODEBOOK_SIZE, scale), scale);
        }
        double mean = 0;
        for (double d : loo) mean += d;
        mean /= loo.length;
        double var = 0;
        for (double d : loo) var += (d - mean) * (d - mean);
        double std = Math.sqrt(var / loo.length);

        float[][] codebook = lbg(all, CODEBOOK_SIZE, scale);
        writeProfile(file, codebook, scale, mean, std);
        Log.i(TAG, "VoiceMatch enrolled: " + perSample.size() + " samples, loo mean="
                + (float) mean + " std=" + (float) std);
        return perSample.size();
    }

    /** Loaded speaker profile. */
    static final class Profile {
        final float[][] codebook;
        final float[] scale;     // 1/std per feature dimension
        final double looMean;    // leave-one-out distance of the owner's samples
        final double looStd;

        Profile(float[][] codebook, float[] scale, double looMean, double looStd) {
            this.codebook = codebook;
            this.scale = scale;
            this.looMean = looMean;
            this.looStd = looStd;
        }
    }

    /** @return the profile, or null if missing or written by an older version (re-enroll) */
    static Profile load(File file) {
        if (!file.exists()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != PROFILE_VERSION) {
                return null;
            }
            double looMean = in.readDouble();
            double looStd = in.readDouble();
            int n = in.readInt();
            int dim = in.readInt();
            if (dim != FEATURE_DIM || n <= 0 || n > 4096) {
                return null;
            }
            float[] scale = new float[dim];
            for (int j = 0; j < dim; j++) scale[j] = in.readFloat();
            float[][] cb = new float[n][dim];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < dim; j++) {
                    cb[i][j] = in.readFloat();
                }
            }
            return new Profile(cb, scale, looMean, looStd);
        } catch (IOException e) {
            Log.w(TAG, "Can't read voice profile", e);
            return null;
        }
    }

    /**
     * @param strictness 0..100 from the setting; higher rejects more. The threshold is the
     *                   owner's leave-one-out mean plus k standard deviations, k from 3 (lenient)
     *                   down to 0 (strict).
     * @return true if the capture matches the owner
     */
    static boolean verify(Profile profile, short[] pcm, int strictness) {
        List<float[]> f = mfcc(pcm);
        if (f.size() < MIN_VOICED_FRAMES) {
            Log.i(TAG, "VoiceMatch reject: not enough speech (" + f.size() + " frames)");
            return false;
        }
        double dist = avgDistance(f, profile.codebook, profile.scale);
        double k = 3.0 * (1.0 - Math.max(0, Math.min(100, strictness)) / 100.0);
        double threshold = profile.looMean + k * Math.max(profile.looStd, 0.05 * profile.looMean);
        boolean ok = dist <= threshold;
        Log.i(TAG, "VoiceMatch dist=" + (float) dist + " threshold=" + (float) threshold
                + " (loo=" + (float) profile.looMean + "±" + (float) profile.looStd + ") -> "
                + (ok ? "OWNER" : "reject"));
        return ok;
    }

    // ---- MFCC ----

    private static List<float[]> mfcc(short[] pcm) {
        ensureMel();
        List<float[]> raw = new ArrayList<>();
        double[] logEnergy = new double[Math.max(0, (pcm.length - FRAME_LEN) / FRAME_STEP + 1)];
        List<float[]> ceps = new ArrayList<>();
        int idx = 0;
        for (int start = 0; start + FRAME_LEN <= pcm.length; start += FRAME_STEP) {
            double[] frame = new double[FFT_SIZE];
            double energy = 0;
            double prev = 0;
            for (int i = 0; i < FRAME_LEN; i++) {
                double sample = pcm[start + i] / 32768.0;
                double pre = sample - 0.97 * prev; // pre-emphasis
                prev = sample;
                double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME_LEN - 1)); // Hamming
                frame[i] = pre * w;
                energy += pre * pre;
            }
            logEnergy[idx++] = Math.log(energy + 1e-10);
            double[] re = new double[FFT_SIZE];
            double[] im = new double[FFT_SIZE];
            System.arraycopy(frame, 0, re, 0, FFT_SIZE);
            fft(re, im);
            double[] mel = new double[MEL_FILTERS];
            for (int m = 0; m < MEL_FILTERS; m++) {
                double sum = 0;
                for (int k = sMelBins[m][0]; k < sMelBins[m][1]; k++) {
                    double mag = re[k] * re[k] + im[k] * im[k];
                    sum += mag;
                }
                mel[m] = Math.log(sum + 1e-10);
            }
            float[] cep = new float[FEATURE_DIM];
            for (int c = CEP_LO; c < CEP_HI; c++) {
                double s = 0;
                for (int m = 0; m < MEL_FILTERS; m++) {
                    s += mel[m] * Math.cos(Math.PI * c * (m + 0.5) / MEL_FILTERS);
                }
                cep[c - CEP_LO] = (float) s;
            }
            ceps.add(cep);
        }

        // keep only voiced frames: log-energy well above the frame-energy floor
        if (ceps.isEmpty()) {
            return raw;
        }
        double floor = percentile(logEnergy, 0.2);
        double peak = percentile(logEnergy, 0.98);
        double gate = floor + Math.max(VOICED_MARGIN, (peak - floor) * 0.3);
        // the keyphrase is the voiced segment (short pauses merged) with the most energy above
        // the gate; other sounds in the window (clicks, background talk) are left out
        int bestStart = -1, bestEnd = -1;
        double bestScore = 0;
        int segStart = -1, lastVoiced = -1;
        double segScore = 0;
        for (int i = 0; i <= ceps.size(); i++) {
            boolean voiced = i < ceps.size() && logEnergy[i] >= gate;
            if (voiced) {
                if (segStart < 0 || i - lastVoiced > MAX_GAP_FRAMES) {
                    if (segStart >= 0 && segScore > bestScore) {
                        bestScore = segScore; bestStart = segStart; bestEnd = lastVoiced;
                    }
                    segStart = i;
                    segScore = 0;
                }
                segScore += logEnergy[i] - gate;
                lastVoiced = i;
            }
        }
        if (segStart >= 0 && segScore > bestScore) {
            bestStart = segStart; bestEnd = lastVoiced;
        }
        for (int i = Math.max(0, bestStart); bestStart >= 0 && i <= bestEnd; i++) {
            if (logEnergy[i] >= gate) {
                raw.add(ceps.get(i));
            }
        }
        // cepstral mean normalization over the voiced frames (removes channel/gain)
        if (!raw.isEmpty()) {
            float[] mean = new float[FEATURE_DIM];
            for (float[] v : raw) {
                for (int j = 0; j < FEATURE_DIM; j++) mean[j] += v[j];
            }
            for (int j = 0; j < FEATURE_DIM; j++) mean[j] /= raw.size();
            for (float[] v : raw) {
                for (int j = 0; j < FEATURE_DIM; j++) v[j] -= mean[j];
            }
        }
        return raw;
    }

    private static double percentile(double[] a, double p) {
        if (a.length == 0) return 0;
        double[] c = a.clone();
        java.util.Arrays.sort(c);
        return c[Math.max(0, Math.min(c.length - 1, (int) (p * c.length)))];
    }

    // ---- LBG vector quantization ----

    private static float[][] lbg(List<float[]> data, int size, float[] scale) {
        float[] centroid = new float[FEATURE_DIM];
        for (float[] v : data) {
            for (int j = 0; j < FEATURE_DIM; j++) centroid[j] += v[j];
        }
        for (int j = 0; j < FEATURE_DIM; j++) centroid[j] /= data.size();
        List<float[]> cb = new ArrayList<>();
        cb.add(centroid);
        final double eps = 0.2; // fraction of the per-dimension std
        while (cb.size() < size) {
            List<float[]> split = new ArrayList<>();
            for (float[] c : cb) {
                float[] a = c.clone();
                float[] b = c.clone();
                for (int j = 0; j < FEATURE_DIM; j++) {
                    // additive split: after mean normalization the centroids are close to 0,
                    // so a multiplicative split would produce identical code vectors
                    float delta = (float) (eps / scale[j]);
                    a[j] += delta;
                    b[j] -= delta;
                }
                split.add(a);
                split.add(b);
            }
            cb = split;
            for (int iter = 0; iter < 10; iter++) {
                float[][] sum = new float[cb.size()][FEATURE_DIM];
                int[] count = new int[cb.size()];
                for (float[] v : data) {
                    int best = nearest(v, cb.toArray(new float[0][]), scale);
                    count[best]++;
                    for (int j = 0; j < FEATURE_DIM; j++) sum[best][j] += v[j];
                }
                for (int i = 0; i < cb.size(); i++) {
                    if (count[i] > 0) {
                        for (int j = 0; j < FEATURE_DIM; j++) cb.get(i)[j] = sum[i][j] / count[i];
                    }
                }
            }
        }
        return cb.toArray(new float[0][]);
    }

    private static int nearest(float[] v, float[][] cb, float[] scale) {
        int best = 0;
        double bestD = VInf;
        for (int i = 0; i < cb.length; i++) {
            double d = 0;
            for (int j = 0; j < FEATURE_DIM; j++) {
                double diff = (v[j] - cb[i][j]) * scale[j];
                d += diff * diff;
            }
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static double avgDistance(List<float[]> frames, float[][] cb, float[] scale) {
        double total = 0;
        for (float[] v : frames) {
            double bestD = VInf;
            for (float[] c : cb) {
                double d = 0;
                for (int j = 0; j < FEATURE_DIM; j++) {
                    double diff = (v[j] - c[j]) * scale[j];
                    d += diff * diff;
                }
                if (d < bestD) bestD = d;
            }
            total += bestD;
        }
        return total / frames.size();
    }

    /** 1/std of each feature dimension over the owner's frames, to weigh dimensions equally. */
    private static float[] inverseStd(List<float[]> data) {
        float[] scale = new float[FEATURE_DIM];
        for (int j = 0; j < FEATURE_DIM; j++) {
            double sum = 0, sq = 0;
            for (float[] v : data) {
                sum += v[j];
                sq += v[j] * v[j];
            }
            double mean = sum / data.size();
            double std = Math.sqrt(Math.max(1e-6, sq / data.size() - mean * mean));
            scale[j] = (float) (1.0 / std);
        }
        return scale;
    }

    // ---- mel filterbank + FFT ----

    private static synchronized void ensureMel() {
        if (sMelBins != null) {
            return;
        }
        double melMax = 2595 * Math.log10(1 + (SAMPLE_RATE / 2.0) / 700);
        double[] points = new double[MEL_FILTERS + 2];
        for (int i = 0; i < points.length; i++) {
            double mel = melMax * i / (MEL_FILTERS + 1);
            double hz = 700 * (Math.pow(10, mel / 2595) - 1);
            points[i] = hz * FFT_SIZE / SAMPLE_RATE;
        }
        sMelBins = new int[MEL_FILTERS][2];
        sMelLo = new float[MEL_FILTERS];
        for (int m = 0; m < MEL_FILTERS; m++) {
            sMelBins[m][0] = (int) Math.floor(points[m]);
            sMelBins[m][1] = Math.max(sMelBins[m][0] + 1, (int) Math.ceil(points[m + 2]));
            if (sMelBins[m][1] > FFT_SIZE / 2) sMelBins[m][1] = FFT_SIZE / 2;
        }
    }

    /** In-place iterative radix-2 FFT, FFT_SIZE must be a power of two. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    private static void writeProfile(File file, float[][] codebook, float[] scale,
            double looMean, double looStd) {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(PROFILE_VERSION);
            out.writeDouble(looMean);
            out.writeDouble(looStd);
            out.writeInt(codebook.length);
            out.writeInt(FEATURE_DIM);
            for (float v : scale) {
                out.writeFloat(v);
            }
            for (float[] c : codebook) {
                for (float v : c) {
                    out.writeFloat(v);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Can't write voice profile", e);
        }
    }
}
