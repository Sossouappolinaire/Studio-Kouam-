package com.tvkouame.studio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** Lecture / écriture de fichiers WAV mono 16 bits et ré-échantillonnage. */
public final class WavIO {
    public static final int RATE = 44100;

    private WavIO() {}

    /** Tampon de samples mono. */
    public static final class Mono {
        public short[] data;
        public int len;
        public int rate;
    }

    /** Tableau de shorts extensible. */
    public static final class ShortBuf {
        private short[] a = new short[1 << 16];
        private int n = 0;

        public void add(short v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        public int size() { return n; }

        public short[] array() { return a; }
    }

    // ------------------------------------------------------------------
    public static void writeMono16(File f, short[] pcm, int len) throws IOException {
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16)) {
            os.write(header(len));
            byte[] b = new byte[8192];
            int pos = 0;
            while (pos < len) {
                int n = Math.min(4096, len - pos);
                for (int i = 0; i < n; i++) {
                    short v = pcm[pos + i];
                    b[2 * i] = (byte) (v & 0xff);
                    b[2 * i + 1] = (byte) ((v >> 8) & 0xff);
                }
                os.write(b, 0, n * 2);
                pos += n;
            }
            os.flush();
        }
    }

    private static byte[] header(int samples) {
        int dataBytes = samples * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataBytes); put(h, 8, "WAVE");
        put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1);
        le32(h, 24, RATE); le32(h, 28, RATE * 2); le16(h, 32, 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataBytes);
        return h;
    }

    private static void put(byte[] b, int o, String s) {
        for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i);
    }

    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }

    private static void le32(byte[] b, int o, int v) { le16(b, o, v); le16(b, o + 2, v >> 16); }

    private static int rd16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }

    private static int rd32(byte[] b, int o) { return rd16(b, o) | (rd16(b, o + 2) << 16); }

    // ------------------------------------------------------------------
    /**
     * Lit un WAV PCM (8, 16, 24, 32 bits ou float 32 bits, mono ou plus) et le ramène en mono.
     * Lève IOException si le fichier n'est pas un WAV pris en charge.
     */
    public static Mono readWav(File f, int maxFrames) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[12];
            r.readFully(h);
            if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F' || h[8] != 'W' || h[9] != 'A' || h[10] != 'V' || h[11] != 'E')
                throw new IOException("Ce n'est pas un fichier WAV");
            int tag = 0, ch = 0, rate = 0, bits = 0;
            long dataPos = -1, dataLen = 0;
            long total = r.length();
            while (r.getFilePointer() + 8 <= total) {
                byte[] ck = new byte[8];
                r.readFully(ck);
                String id = new String(ck, 0, 4, "US-ASCII");
                long sz = rd32(ck, 4) & 0xFFFFFFFFL;
                long pos = r.getFilePointer();
                if (id.equals("fmt ")) {
                    byte[] q = new byte[(int) Math.min(sz, 40)];
                    r.readFully(q);
                    if (q.length < 16) throw new IOException("En-tête WAV invalide");
                    tag = rd16(q, 0); ch = rd16(q, 2); rate = rd32(q, 4); bits = rd16(q, 14);
                    if (tag == 0xFFFE && q.length >= 26) tag = rd16(q, 24);
                } else if (id.equals("data")) {
                    dataPos = pos;
                    dataLen = sz;
                    if (dataLen == 0 || dataLen > total - pos) dataLen = total - pos;
                    break;
                }
                r.seek(pos + sz + (sz & 1));
            }
            if (dataPos < 0 || ch <= 0 || rate <= 0) throw new IOException("En-tête WAV invalide");
            boolean isFloat = tag == 3 && bits == 32;
            if (!(tag == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32)) && !isFloat)
                throw new IOException("Format WAV non pris en charge (" + bits + " bits)");

            int bps = bits / 8;
            int frameBytes = bps * ch;
            long frames = Math.min(dataLen / frameBytes, (long) maxFrames);
            short[] out = new short[(int) frames];
            r.seek(dataPos);
            byte[] blk = new byte[frameBytes * 4096];
            int done = 0;
            while (done < frames) {
                int want = (int) Math.min(4096, frames - done);
                r.readFully(blk, 0, want * frameBytes);
                for (int i = 0; i < want; i++) {
                    long sum = 0;
                    for (int c = 0; c < ch; c++) {
                        int o = i * frameBytes + c * bps;
                        int v;
                        if (isFloat) {
                            float fl = Float.intBitsToFloat(rd32(blk, o));
                            if (fl > 1f) fl = 1f; else if (fl < -1f) fl = -1f;
                            v = (int) (fl * 32767f);
                        } else if (bits == 8) v = ((blk[o] & 0xff) - 128) << 8;
                        else if (bits == 16) v = (short) rd16(blk, o);
                        else if (bits == 24) v = ((blk[o + 2] << 16) | ((blk[o + 1] & 0xff) << 8) | (blk[o] & 0xff)) >> 8;
                        else v = rd32(blk, o) >> 16;
                        sum += v;
                    }
                    out[done + i] = (short) (sum / ch);
                }
                done += want;
            }
            Mono m = new Mono();
            m.data = out; m.len = out.length; m.rate = rate;
            return m;
        }
    }

    // ------------------------------------------------------------------
    /** Ré-échantillonnage linéaire (avec lissage si on réduit beaucoup la fréquence). */
    public static short[] resample(short[] in, int len, int inRate, int outRate) {
        if (inRate == outRate) return java.util.Arrays.copyOf(in, len);
        double ratio = (double) inRate / outRate;
        short[] src = in;
        int srcLen = len;
        if (ratio > 1.5) {
            int w = (int) Math.round(ratio);
            short[] sm = new short[len];
            for (int i = 0; i < len; i++) {
                int from = Math.max(0, i - w / 2), to = Math.min(len - 1, i + w / 2);
                long s = 0;
                for (int k = from; k <= to; k++) s += in[k];
                sm[i] = (short) (s / (to - from + 1));
            }
            src = sm;
        }
        int outLen = (int) ((long) srcLen * outRate / inRate);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double p = i * ratio;
            int i0 = (int) p;
            double fr = p - i0;
            int a = src[Math.min(i0, srcLen - 1)];
            int b = src[Math.min(i0 + 1, srcLen - 1)];
            out[i] = (short) Math.round(a + (b - a) * fr);
        }
        return out;
    }
}
