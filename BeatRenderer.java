package com.tvkouame.studio;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/** Transforme un BeatModel en son : rendu du beat, décompte, mixage avec les sons ajoutés. */
public final class BeatRenderer {

    private BeatRenderer() {}

    /** Durée d'une double-croche en samples. */
    public static double stepLen(BeatModel m) { return Synth.RATE * 60.0 / m.bpm / 4.0; }

    public static int samplesPerBar(BeatModel m) { return (int) Math.round(m.stepsPerBar() * stepLen(m)); }

    /** Longueur du beat (sans la fin de résonance). */
    public static int totalSamples(BeatModel m) { return (int) Math.round(m.bars * m.stepsPerBar() * stepLen(m)); }

    /** Longueur du décompte d'une mesure. */
    public static int countInSamples(BeatModel m) {
        return m.beatsPerBar * (int) Math.round(Synth.RATE * 60.0 / m.bpm);
    }

    // ------------------------------------------------------------------
    private static float[] note(HashMap<Long, float[]> cache, int inst, int midi, int len) {
        long key = ((long) inst << 48) | ((long) midi << 32) | len;
        float[] n = cache.get(key);
        if (n == null) {
            switch (inst) {
                case BeatModel.BASS: n = Synth.bass(midi, len); break;
                case BeatModel.PIANO: n = Synth.piano(midi, len); break;
                default: n = Synth.lead(midi, len); break;
            }
            cache.put(key, n);
        }
        return n;
    }

    private static void add(float[] out, float[] src, int pos, float gain) {
        if (pos < 0 || pos >= out.length) return;
        int n = Math.min(src.length, out.length - pos);
        for (int i = 0; i < n; i++) out[pos + i] += src[i] * gain;
    }

    /** Distance (en pas) jusqu'au prochain pas actif dans la mesure, limitée par cap. */
    private static int span(boolean[] on, int s, int spb, int cap) {
        int e = s + 1;
        while (e < spb && !on[e]) e++;
        return Math.max(1, Math.min(e - s, cap));
    }

    /** Rend le beat complet (batterie, basse, piano, solo) : total + une demi-seconde de résonance. */
    public static float[] renderBeat(BeatModel m) {
        return renderBars(m, 0, Math.max(1, Math.min(m.bars, BeatModel.MAXBARS)), -1);
    }

    /** Une mesure seule (pour écouter ce qu'on compose) : length = samplesPerBar. */
    public static float[] renderBar(BeatModel m, int bar, int onlyInst) {
        return renderBars(m, bar, bar + 1, onlyInst);
    }

    /** Joue les notes libres d'une mesure pour une piste. */
    private static void renderNotes(BeatModel m, float[] out, HashMap<Long, float[]> cache, float[][] drums,
                                    int inst, int bar, int from) {
        double tl = Synth.RATE * 60.0 / m.bpm / BeatModel.PPQ;
        for (BeatModel.Note n : m.notes) {
            if (n.inst != inst || n.bar != bar) continue;
            int pos = (int) Math.round(((bar - from) * m.barTicks() + n.tick) * tl);
            float g = n.vel * m.vol[inst];
            if (inst == BeatModel.DRUMS) {
                if (n.pitch >= 0 && n.pitch < 5) add(out, drums[n.pitch], pos, g * (n.pitch >= 3 ? 0.9f : 1f));
            } else {
                int len = (int) Math.round(Math.max(1, n.len) * tl * 0.95);
                add(out, note(cache, inst, n.pitch, len), pos, g);
            }
        }
    }

    private static float[] renderBars(BeatModel m, int from, int to, int only) {
        int spb = m.stepsPerBar();
        double sl = stepLen(m);
        int total = (int) Math.round((to - from) * spb * sl);
        float[] out = new float[total + Synth.RATE / 2];
        HashMap<Long, float[]> cache = new HashMap<>();
        float[][] drums = new float[5][];
        for (int r = 0; r < 5; r++) drums[r] = Synth.drum(r);
        for (int bar = from; bar < to; bar++) {
            int root = m.chordRoot[bar];
            int[] tones = m.chordTones(bar);
            for (int t = 0; t < BeatModel.NT; t++) {
                if (m.custom[t][bar] && m.play[t][bar] && (only < 0 || only == t))
                    renderNotes(m, out, cache, drums, t, bar, from);
            }

            // --- Batterie
            if (m.play[BeatModel.DRUMS][bar] && !m.custom[BeatModel.DRUMS][bar] && (only < 0 || only == BeatModel.DRUMS)) {
                for (int s = 0; s < spb; s++) {
                    int pos = (int) Math.round(((bar - from) * spb + s) * sl);
                    for (int r = 0; r < 5; r++) {
                        if (!m.drum[r][s]) continue;
                        float v = 1f;
                        if (r == 2) v = (s % 4 == 0) ? 1f : (s % 2 == 0) ? 0.75f : 0.55f;
                        else if (r >= 3) v = 0.9f;
                        add(out, drums[r], pos, v * m.vol[BeatModel.DRUMS]);
                    }
                }
            }

            // --- Basse
            if (m.play[BeatModel.BASS][bar] && !m.custom[BeatModel.BASS][bar] && (only < 0 || only == BeatModel.BASS)) {
                boolean[] on = new boolean[spb];
                for (int s = 0; s < spb; s++) on[s] = m.bassStep[s] > 0;
                int base = 36 + (root > 7 ? root - 12 : root);
                for (int s = 0; s < spb; s++) {
                    int v = m.bassStep[s];
                    if (v <= 0) continue;
                    int midi = base + (v == 2 ? 7 : v == 3 ? 12 : 0);
                    int len = (int) Math.round(span(on, s, spb, 6) * sl * 0.95);
                    int pos = (int) Math.round(((bar - from) * spb + s) * sl);
                    add(out, note(cache, BeatModel.BASS, midi, len), pos, m.vol[BeatModel.BASS]);
                }
            }

            // --- Piano
            if (m.play[BeatModel.PIANO][bar] && !m.custom[BeatModel.PIANO][bar] && (only < 0 || only == BeatModel.PIANO)) {
                boolean[] on = new boolean[spb];
                for (int s = 0; s < spb; s++) on[s] = m.pianoHit[s];
                int pbase = 48 + root;
                if (pbase > 55) pbase -= 12;
                int style = m.pianoStyle;
                int count = 0;
                // notes de l'arpège : l'accord + la fondamentale à l'octave
                int L = tones.length + 1;
                for (int s = 0; s < spb; s++) {
                    if (!on[s]) continue;
                    int pos = (int) Math.round(((bar - from) * spb + s) * sl);
                    float vel = (s % 4 == 0) ? 1f : 0.82f;
                    if (style == 0) {
                        int len = (int) Math.round(span(on, s, spb, 8) * sl * 0.97);
                        for (int t : tones)
                            add(out, note(cache, BeatModel.PIANO, pbase + t, len), pos, vel * m.vol[BeatModel.PIANO]);
                    } else {
                        int idx;
                        if (style == 1) idx = count % L;
                        else if (style == 2) idx = L - 1 - (count % L);
                        else {
                            int cyc = Math.max(1, 2 * L - 2);
                            int k = count % cyc;
                            idx = k < L ? k : cyc - k;
                        }
                        int off = idx < tones.length ? tones[idx] : 12;
                        int len = (int) Math.round(span(on, s, spb, 6) * sl * 1.4);
                        add(out, note(cache, BeatModel.PIANO, pbase + off + 12, len), pos, vel * m.vol[BeatModel.PIANO] * 1.3f);
                        count++;
                    }
                }
            }

            // --- Solo (gamme pentatonique de l'accord)
            if (m.play[BeatModel.SOLO][bar] && !m.custom[BeatModel.SOLO][bar] && (only < 0 || only == BeatModel.SOLO)) {
                boolean[] on = new boolean[spb];
                for (int s = 0; s < spb; s++) on[s] = m.soloStep[s] > 0;
                int[] scale = m.minorish(bar) ? new int[]{0, 3, 5, 7, 10, 12} : new int[]{0, 2, 4, 7, 9, 12};
                int sbase = 60 + (root > 6 ? root - 12 : root);
                for (int s = 0; s < spb; s++) {
                    int v = m.soloStep[s];
                    if (v <= 0) continue;
                    int midi = sbase + scale[Math.min(v, 6) - 1];
                    int len = (int) Math.round(span(on, s, spb, 8) * sl * 0.92);
                    int pos = (int) Math.round(((bar - from) * spb + s) * sl);
                    add(out, note(cache, BeatModel.SOLO, midi, len), pos, m.vol[BeatModel.SOLO]);
                }
            }
        }
        return out;
    }

    /** Beat + sons ajoutés (enregistrés ou importés). */
    public static float[] mixAll(BeatModel m, File dir, boolean includeBeat, boolean includeClips) {
        float[] beat = includeBeat ? renderBeat(m) : new float[0];
        int perBar = samplesPerBar(m);
        List<BeatModel.Clip> clips = m.clips;
        short[][] pcm = new short[clips.size()][];
        int[] start = new int[clips.size()];
        int end = beat.length;
        if (includeClips) {
            for (int i = 0; i < clips.size(); i++) {
                BeatModel.Clip c = clips.get(i);
                if (c.mute) continue;
                try {
                    WavIO.Mono w = WavIO.readWav(new File(dir, c.file), 60 * 60 * WavIO.RATE);
                    pcm[i] = w.rate == WavIO.RATE ? w.data : WavIO.resample(w.data, w.len, w.rate, WavIO.RATE);
                    start[i] = Math.max(0, c.startBar) * perBar;
                    end = Math.max(end, start[i] + pcm[i].length);
                } catch (Exception ignored) {
                    pcm[i] = null;
                }
            }
        }
        float[] out = Arrays.copyOf(beat, Math.max(end, 1));
        for (int i = 0; i < clips.size(); i++) {
            if (pcm[i] == null) continue;
            float g = clips.get(i).vol / 32768f;
            short[] p = pcm[i];
            int st = start[i];
            for (int k = 0; k < p.length; k++) out[st + k] += p[k] * g;
        }
        return out;
    }

    /** Ajoute une mesure de clics au début (décompte avant l'enregistrement). */
    public static float[] withCountIn(BeatModel m, float[] body) {
        int beatLen = (int) Math.round(Synth.RATE * 60.0 / m.bpm);
        int pre = m.beatsPerBar * beatLen;
        float[] out = new float[pre + body.length];
        for (int k = 0; k < m.beatsPerBar; k++) {
            float[] c = Synth.click(k == 0);
            for (int i = 0; i < c.length && k * beatLen + i < pre; i++) out[k * beatLen + i] += c[i];
        }
        System.arraycopy(body, 0, out, pre, body.length);
        return out;
    }

    /** Convertit en 16 bits, en baissant le volume global seulement si ça saturerait. */
    public static short[] toShorts(float[] f) {
        float peak = 0f;
        for (float v : f) {
            float a = Math.abs(v);
            if (a > peak) peak = a;
        }
        float g = peak > 0.97f ? 0.97f / peak : 1f;
        short[] o = new short[f.length];
        for (int i = 0; i < f.length; i++) {
            float v = f[i] * g;
            if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
            o[i] = (short) Math.round(v * 32767f);
        }
        return o;
    }
}
