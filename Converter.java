package com.tvkouame.studio;

import android.media.MediaCodec;
import android.media.MediaFormat;

import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Convertit l'enregistrement WAV (qualité studio) en MP3, AMR ou WAV. */
public final class Converter {
    public static final int MP3 = 0, AMR = 1, WAV = 2;
    public static final String[] NAMES = {"MP3", "AMR", "WAV"};
    public static final String[] EXT = {".mp3", ".amr", ".wav"};

    private Converter() {}

    public static File convert(File wav, File out, int format) throws Exception {
        switch (format) {
            case MP3: toMp3(wav, out); break;
            case AMR: toAmr(wav, out); break;
            default: copy(wav, out); break;
        }
        return out;
    }

    // ------------------------------------------------------------------
    private static short[] readPcm(File wav) throws IOException {
        long len = wav.length() - 44;
        if (len <= 0) throw new IOException("Enregistrement vide");
        byte[] b = new byte[(int) len];
        try (InputStream is = new FileInputStream(wav)) {
            long skipped = 0;
            while (skipped < 44) { long s = is.skip(44 - skipped); if (s <= 0) break; skipped += s; }
            int off = 0;
            while (off < b.length) {
                int n = is.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        short[] pcm = new short[b.length / 2];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return pcm;
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(dst)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) os.write(b, 0, n);
        }
    }

    // ------------------------------------------------------------------
    // MP3 (LAME)
    // ------------------------------------------------------------------
    private static void toMp3(File wav, File out) throws Exception {
        short[] pcm = readPcm(wav);
        AndroidLame lame = new LameBuilder()
                .setInSampleRate(AudioEngine.RATE)
                .setOutChannels(1)
                .setOutBitrate(192)
                .setOutSampleRate(AudioEngine.RATE)
                .build();
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            int chunk = 8192;
            short[] part = new short[chunk];
            byte[] mp3 = new byte[(int) (chunk * 1.25 + 7200)];
            for (int pos = 0; pos < pcm.length; pos += chunk) {
                int n = Math.min(chunk, pcm.length - pos);
                System.arraycopy(pcm, pos, part, 0, n);
                int w = lame.encode(part, part, n, mp3);
                if (w > 0) os.write(mp3, 0, w);
            }
            int f = callFlush(lame, mp3);
            if (f > 0) os.write(mp3, 0, f);
        } finally {
            callClose(lame);
        }
    }

    // Le nom de ces deux méthodes varie selon la version de la bibliothèque.
    private static int callFlush(Object lame, byte[] buf) {
        for (String name : new String[]{"flush", "lameFlush"}) {
            try {
                Method m = lame.getClass().getMethod(name, byte[].class);
                Object r = m.invoke(lame, (Object) buf);
                return r instanceof Integer ? (Integer) r : 0;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) { return 0; }
        }
        return 0;
    }

    private static void callClose(Object lame) {
        try { lame.getClass().getMethod("close").invoke(lame); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------
    // AMR-NB (8 kHz, codec Android)
    // ------------------------------------------------------------------
    private static short[] resampleTo8k(short[] in) {
        double ratio = AudioEngine.RATE / 8000.0;
        int outLen = (int) (in.length / ratio);
        short[] out = new short[outLen];
        int half = 3;
        for (int i = 0; i < outLen; i++) {
            int c = (int) (i * ratio);
            int from = Math.max(0, c - half), to = Math.min(in.length - 1, c + half);
            long sum = 0;
            for (int k = from; k <= to; k++) sum += in[k];
            out[i] = (short) (sum / (to - from + 1));
        }
        return out;
    }

    private static void toAmr(File wav, File out) throws Exception {
        short[] pcm = resampleTo8k(readPcm(wav));
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 12200);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            os.write("#!AMR\n".getBytes("US-ASCII"));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int pos = 0;
            long pts = 0;
            int guard = 0;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        ib.clear();
                        ib.order(ByteOrder.LITTLE_ENDIAN);
                        int samples = Math.min(ib.capacity() / 2, pcm.length - pos);
                        if (samples <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            for (int k = 0; k < samples; k++) ib.putShort(pcm[pos + k]);
                            codec.queueInputBuffer(ii, 0, samples * 2, pts, 0);
                            pts += samples * 1000000L / 8000;
                            pos += samples;
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        byte[] b = new byte[info.size];
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.get(b);
                        os.write(b);
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    guard = 0;
                } else if (++guard > 3000) {
                    throw new IOException("Encodeur AMR bloqué");
                }
            }
        } finally {
            try { codec.stop(); } catch (Exception ignored) {}
            codec.release();
        }
    }
}
