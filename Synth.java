package com.tvkouame.studio;

import java.util.Random;

/** Sons de synthèse pour le Studio (sans dépendance Android). Tout est calculé en mono, 44,1 kHz. */
public final class Synth {
    public static final int RATE = 44100;
    public static final int KICK = 0, SNARE = 1, HAT = 2, OPENHAT = 3, CLAP = 4;

    private static final float[] TAB = new float[4097];

    static {
        for (int i = 0; i <= 4096; i++) TAB[i] = (float) Math.sin(2 * Math.PI * i / 4096.0);
    }

    private Synth() {}

    /** Sinus rapide : l'argument est en cycles (1.0 = un tour complet). */
    static float sin(double cycles) {
        cycles -= Math.floor(cycles);
        double p = cycles * 4096.0;
        int i = (int) p;
        float f = (float) (p - i);
        return TAB[i] + (TAB[i + 1] - TAB[i]) * f;
    }

    public static double hz(int midi) { return 440.0 * Math.pow(2.0, (midi - 69) / 12.0); }

    private static void fadeOut(float[] o, int n) {
        n = Math.min(n, o.length);
        for (int i = 0; i < n; i++) o[o.length - 1 - i] *= (float) i / n;
    }

    // ------------------------------------------------------------------
    // Batterie
    // ------------------------------------------------------------------
    public static float[] drum(int type) {
        Random rnd = new Random(1234 + type);
        float[] o;
        switch (type) {
            case KICK: {
                o = new float[(int) (0.5 * RATE)];
                double ph = 0;
                for (int i = 0; i < o.length; i++) {
                    double t = i / (double) RATE;
                    ph += (48 + 120 * Math.exp(-t * 32)) / RATE;
                    double s = Math.sin(2 * Math.PI * ph) * Math.exp(-t * 7.5);
                    if (t < 0.003) s += (rnd.nextDouble() * 2 - 1) * 0.4 * (1 - t / 0.003);
                    o[i] = (float) (Math.tanh(s * 1.6) * 0.9);
                }
                break;
            }
            case SNARE: {
                o = new float[(int) (0.3 * RATE)];
                double lp = 0;
                for (int i = 0; i < o.length; i++) {
                    double t = i / (double) RATE;
                    double nz = rnd.nextDouble() * 2 - 1;
                    lp += 0.35 * (nz - lp);
                    double s = (nz - lp) * Math.exp(-t * 20) * 0.8
                            + (Math.sin(2 * Math.PI * 185 * t) * 0.6 + Math.sin(2 * Math.PI * 330 * t) * 0.25) * Math.exp(-t * 30);
                    o[i] = (float) (Math.tanh(s * 1.3) * 0.8);
                }
                break;
            }
            case HAT:
            case OPENHAT: {
                boolean open = type == OPENHAT;
                o = new float[(int) ((open ? 0.45 : 0.12) * RATE)];
                double prev = 0;
                for (int i = 0; i < o.length; i++) {
                    double t = i / (double) RATE;
                    double nz = rnd.nextDouble() * 2 - 1;
                    double d = nz - prev;
                    prev = nz;
                    o[i] = (float) (d * Math.exp(-t * (open ? 9 : 65)) * (open ? 0.2 : 0.28));
                }
                break;
            }
            default: { // CLAP
                o = new float[(int) (0.3 * RATE)];
                double lp = 0;
                double[] burst = {0, 0.011, 0.022};
                for (int i = 0; i < o.length; i++) {
                    double t = i / (double) RATE;
                    double nz = rnd.nextDouble() * 2 - 1;
                    lp += 0.15 * (nz - lp);
                    double env = 0;
                    for (double b : burst) if (t >= b) env += Math.exp(-(t - b) * 150);
                    if (t > 0.03) env += 0.5 * Math.exp(-(t - 0.03) * 22);
                    o[i] = (float) Math.max(-1, Math.min(1, (nz - lp) * env * 0.4));
                }
            }
        }
        fadeOut(o, 200);
        return o;
    }

    /** Petit « clic » du décompte (accent sur le premier temps). */
    public static float[] click(boolean accent) {
        float[] o = new float[(int) (0.06 * RATE)];
        double f = accent ? 1600 : 1100;
        for (int i = 0; i < o.length; i++) {
            double t = i / (double) RATE;
            o[i] = (float) (Math.sin(2 * Math.PI * f * t) * Math.exp(-t * 70) * 0.5);
        }
        fadeOut(o, 100);
        return o;
    }

    // ------------------------------------------------------------------
    // Instruments mélodiques : len = durée de la note en samples (+ petite fin de note ajoutée)
    // ------------------------------------------------------------------
    public static float[] bass(int midi, int len) {
        int rel = (int) (0.05 * RATE);
        float[] o = new float[len + rel];
        double inc = hz(midi) / RATE, ph = 0;
        for (int i = 0; i < o.length; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1.0, t / 0.004) * (0.55 + 0.45 * Math.exp(-t * 7));
            if (i > len) env *= 1.0 - (double) (i - len) / rel;
            double s = sin(ph) + 0.4 * sin(2 * ph) + 0.18 * sin(3 * ph) + 0.08 * sin(4 * ph);
            ph += inc;
            o[i] = (float) (s * env * 0.42);
        }
        return o;
    }

    public static float[] piano(int midi, int len) {
        int rel = (int) (0.08 * RATE);
        float[] o = new float[len + rel];
        double f = hz(midi);
        double[] amp = {1, 0.6, 0.38, 0.26, 0.16, 0.1, 0.06};
        double base = 1.6 + Math.max(0, midi - 40) * 0.045;
        int H = 0;
        double[] ff = new double[amp.length];
        for (int h = 1; h <= amp.length; h++) {
            double fr = f * h * (1 + 0.0003 * h * h);
            if (fr > 12000) break;
            ff[H++] = fr;
        }
        for (int i = 0; i < o.length; i++) {
            double t = i / (double) RATE;
            double s = 0;
            for (int h = 0; h < H; h++)
                s += amp[h] * sin(ff[h] * t) * Math.exp(-t * base * (1 + 0.7 * h));
            double env = Math.min(1.0, t / 0.002);
            if (i > len) env *= 1.0 - (double) (i - len) / rel;
            o[i] = (float) (s * env * 0.28);
        }
        return o;
    }

    public static float[] lead(int midi, int len) {
        int rel = (int) (0.07 * RATE);
        float[] o = new float[len + rel];
        double f = hz(midi);
        int maxH = Math.max(1, Math.min(10, (int) (9000 / f)));
        double ph = 0;
        for (int i = 0; i < o.length; i++) {
            double t = i / (double) RATE;
            double depth = 0.005 * Math.min(1.0, Math.max(0.0, (t - 0.12) / 0.2));
            ph += f * (1 + depth * Math.sin(2 * Math.PI * 5.5 * t)) / RATE;
            double s = 0.25 * sin(ph);
            for (int h = 1; h <= maxH; h++) s += 0.55 * sin(h * ph) / h;
            double env = Math.min(1.0, t / 0.012) * (0.75 + 0.25 * Math.exp(-t * 10));
            if (i > len) env *= 1.0 - (double) (i - len) / rel;
            o[i] = (float) (s * env * 0.2);
        }
        return o;
    }
}
