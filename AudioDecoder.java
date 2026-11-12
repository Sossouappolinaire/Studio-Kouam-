package com.tvkouame.studio;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** Importe un fichier audio quelconque : le décode, le passe en mono 44,1 kHz et l'écrit en WAV. */
public final class AudioDecoder {
    /** Durée maximale d'un son importé (en secondes). */
    public static final int MAX_SECONDS = 300;

    private AudioDecoder() {}

    /** Retourne le nombre de samples écrits dans outWav. */
    public static int importToWav(Context c, Uri uri, File outWav) throws Exception {
        File tmp = new File(c.getCacheDir(), "import_audio.tmp");
        try (InputStream is = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(tmp)) {
            if (is == null) throw new IOException("Fichier illisible");
            byte[] b = new byte[16384];
            int n;
            while ((n = is.read(b)) > 0) os.write(b, 0, n);
        }
        try {
            WavIO.Mono m;
            try {
                m = WavIO.readWav(tmp, MAX_SECONDS * 48000);
            } catch (IOException notWav) {
                m = decodeWithCodec(tmp);
            }
            if (m.len <= 0) throw new IOException("Fichier audio vide");
            short[] out = WavIO.resample(m.data, m.len, m.rate, WavIO.RATE);
            if (out.length > MAX_SECONDS * WavIO.RATE) out = java.util.Arrays.copyOf(out, MAX_SECONDS * WavIO.RATE);
            WavIO.writeMono16(outWav, out, out.length);
            return out.length;
        } finally {
            tmp.delete();
        }
    }

    private static WavIO.Mono decodeWithCodec(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat tf = ex.getTrackFormat(i);
                String mime = tf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = tf; break; }
            }
            if (track < 0) throw new IOException("Aucune piste audio dans ce fichier");
            ex.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            WavIO.ShortBuf buf = new WavIO.ShortBuf();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int guard = 0;
            while (!outDone) {
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int n = ex.readSampleData(ib, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int frames = sb.remaining() / Math.max(1, ch);
                        for (int k = 0; k < frames; k++) {
                            int sum = 0;
                            for (int c = 0; c < ch; c++) sum += sb.get();
                            buf.add((short) (sum / ch));
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    if (buf.size() >= (long) MAX_SECONDS * rate) outDone = true;
                    guard = 0;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inDone && ++guard > 300) throw new IOException("Décodage bloqué");
                }
            }
            WavIO.Mono m = new WavIO.Mono();
            m.data = buf.array();
            m.len = buf.size();
            m.rate = rate;
            return m;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            ex.release();
        }
    }
}
