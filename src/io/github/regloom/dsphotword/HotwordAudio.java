package io.github.regloom.dsphotword;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the audio that the DSP buffered for a keyphrase trigger, using the capture session handle
 * from the recognition event. The HOTWORD audio source plus the session id let the recording come
 * from the sound trigger pipeline instead of opening the microphone fresh.
 *
 * <p>Requires CAPTURE_AUDIO_HOTWORD (already held). The recording is short (see
 * {@link #DEFAULT_MAX_MS}) and must be closed before the assistant starts, so the assistant can
 * open the microphone itself.
 */
final class HotwordAudio {
    private static final String TAG = "DspHotword";

    static final int SAMPLE_RATE = 16_000;
    /** Upper bound only: reading stops as soon as the buffered history is exhausted. */
    static final int DEFAULT_MAX_MS = 4_000;
    /** Live chunks kept after the history, in case the keyphrase tail is still arriving. */
    private static final int LIVE_CHUNKS_TO_KEEP = 2;

    private HotwordAudio() {}

    /**
     * Captures up to {@code maxMs} of 16 kHz mono PCM16 audio from the trigger's capture session.
     *
     * @param captureSession the {@code captureSession} field of the recognition event
     * @return the recorded little-endian PCM16 samples, or {@code null} if capture failed
     */
    @SuppressLint("MissingPermission") // CAPTURE_AUDIO_HOTWORD is declared and granted
    static short[] capture(int captureSession, int maxMs) {
        final int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final int bufferBytes = Math.max(minBuf, SAMPLE_RATE); // >= 0.5 s
        AudioRecord record = null;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setInternalCapturePreset(MediaRecorder.AudioSource.HOTWORD)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            AudioRecord.Builder builder = new AudioRecord.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes);
            if (captureSession > 0) {
                builder.setSessionId(captureSession);
            }
            record = builder.build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "HOTWORD AudioRecord not initialized");
                return null;
            }

            // The DSP hands over its history buffer much faster than real time, then the stream
            // continues with live audio. Read in short chunks and stop once chunks start arriving
            // at real-time pace: everything before that is the buffered keyphrase.
            final int maxSamples = SAMPLE_RATE * maxMs / 1000;
            final int chunk = SAMPLE_RATE / 20; // 50 ms
            short[] buffer = new short[maxSamples];
            int total = 0;
            int liveChunks = 0;
            record.startRecording();
            while (total < maxSamples) {
                long start = System.nanoTime();
                int read = record.read(buffer, total, Math.min(chunk, maxSamples - total));
                if (read <= 0) {
                    break; // no more audio
                }
                total += read;
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                // a 50 ms chunk that took >= 35 ms to arrive is live audio, not history
                if (elapsedMs >= 35) {
                    if (++liveChunks >= LIVE_CHUNKS_TO_KEEP) {
                        break;
                    }
                } else {
                    liveChunks = 0;
                }
            }
            record.stop();
            if (total == 0) {
                return null;
            }
            short[] out = new short[total];
            System.arraycopy(buffer, 0, out, 0, total);
            return out;
        } catch (RuntimeException e) {
            Log.w(TAG, "Hotword capture failed", e);
            return null;
        } finally {
            if (record != null) {
                try {
                    record.release();
                } catch (RuntimeException e) {
                    Log.w(TAG, "AudioRecord release failed", e);
                }
            }
        }
    }

    /** Writes a 16 kHz mono PCM16 WAV file, for debugging the capture. */
    static void writeWav(File file, short[] samples) {
        final int dataLen = samples.length * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        buf.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        buf.put("data".getBytes()).putInt(dataLen);
        for (short s : samples) {
            buf.putShort(s);
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(buf.array());
        } catch (IOException e) {
            Log.w(TAG, "Can't write " + file, e);
        }
    }
}
