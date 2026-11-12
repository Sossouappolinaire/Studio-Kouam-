package com.tvkouame.studio;

/** Compositions exemple d'une minute (intro, couplet, refrain avec solo, fin), avec roulements de batterie. */
public final class Examples {

    private Examples() {}

    public static final String[] NAMES = {
            "Pop lumineuse  (1 min · 120 bpm)",
            "Afro chill  (1 min · 100 bpm)",
            "Ballade piano  (1 min · 80 bpm)",
            "Dance mineur  (1 min · 128 bpm)",
            "Andalou  (1 min · 96 bpm)"
    };

    // bpm, mesures (4 temps : bpm × mesures = 1800 → 60 s), tonalité, progression, style de piano
    private static final int[][] CFG = {
            {120, 30, 0, 0, 0},
            {100, 25, 9, 1, 0},
            {80, 20, 7, 3, 1},
            {128, 32, 9, 5, 0},
            {96, 24, 4, 7, 3}
    };
    // kick, snare, hat, open, clap  (pas de 0 à 15)
    private static final int[][][] DR = {
            {{0, 8, 10}, {4, 12}, {0, 2, 4, 6, 8, 10, 12, 14}, {14}, {}},
            {{0, 6, 10}, {4, 12}, {0, 2, 4, 6, 8, 10, 12, 14}, {7, 15}, {12}},
            {{0, 8}, {12}, {4, 12}, {}, {}},
            {{0, 4, 8, 12}, {}, {}, {2, 6, 10, 14}, {4, 12}},
            {{0, 8, 10}, {6, 12}, {0, 2, 4, 6, 8, 10, 12, 14}, {}, {}}
    };
    private static final int[][] BASS = {
            {0, 6, 8, 10, 12},      // positions
            {0, 3, 6, 10, 12},
            {0, 8},
            {2, 6, 10, 14},
            {0, 4, 8, 12}
    };
    private static final int[][] BASSV = {
            {1, 1, 3, 1, 2}, {1, 1, 2, 1, 3}, {1, 1}, {1, 1, 1, 3}, {1, 1, 2, 1}
    };
    private static final int[][] PIANO = {
            {0, 6, 10}, {0, 3, 6, 10}, {0, 4, 8, 12}, {0, 4, 8, 12}, {0, 2, 4, 6, 8, 10, 12, 14}
    };
    private static final int[][] SOLO = {
            {0, 3, 4, 6, 8, 3, 12, 5}, {2, 3, 6, 4, 10, 5, 12, 3}, {0, 5, 4, 3, 8, 2, 12, 1},
            {0, 5, 3, 5, 8, 6, 12, 5, 14, 3}, {0, 3, 2, 4, 6, 3, 8, 2, 12, 1}
    };

    public static BeatModel build(int idx) {
        idx = Math.max(0, Math.min(CFG.length - 1, idx));
        int[] c = CFG[idx];
        BeatModel m = new BeatModel();
        m.name = "Exemple : " + NAMES[idx].split("  ")[0];
        m.bpm = c[0];
        m.bars = c[1];
        m.beatsPerBar = 4;
        m.applyProgression(c[2], c[3]);
        m.pianoStyle = c[4];
        for (int r = 0; r < 5; r++) for (int s : DR[idx][r]) m.drum[r][s] = true;
        for (int i = 0; i < BASS[idx].length; i++) m.bassStep[BASS[idx][i]] = BASSV[idx][i];
        for (int s : PIANO[idx]) m.pianoHit[s] = true;
        for (int i = 0; i + 1 < SOLO[idx].length; i += 2) m.soloStep[SOLO[idx][i]] = SOLO[idx][i + 1];

        int n = m.bars, introEnd = 4, outroStart = n - 2, verseEnd = introEnd + (n - 6) / 2;
        for (int b = 0; b < n; b++) {
            boolean intro = b < introEnd, outro = b >= outroStart, verse = !intro && !outro && b < verseEnd;
            boolean chorus = !intro && !outro && !verse;
            m.play[BeatModel.PIANO][b] = true;
            m.play[BeatModel.BASS][b] = b >= 2 && !outro;
            m.play[BeatModel.DRUMS][b] = verse || chorus;
            m.play[BeatModel.SOLO][b] = chorus;
        }
        m.play[BeatModel.DRUMS][introEnd - 1] = true;   // la batterie ne joue que le roulement de fin d'intro
        // roulements : fin d'intro (léger), fin de couplet, fin de refrain (rapide)
        roll(m, introEnd - 1, 2, 6, false);
        roll(m, verseEnd - 1, 1, 6, true);
        roll(m, outroStart - 1, 2, 3, true);
        return m;
    }

    /** Roulement de caisse claire sur les derniers temps de la mesure, avec crescendo. */
    private static void roll(BeatModel m, int bar, int beats, int div, boolean keepPattern) {
        int bt = m.barTicks(), from = bt - beats * BeatModel.PPQ;
        if (keepPattern) {
            m.patternToNotes(BeatModel.DRUMS, bar);
            for (int i = m.notes.size() - 1; i >= 0; i--) {
                BeatModel.Note x = m.notes.get(i);
                if (x.inst == BeatModel.DRUMS && x.bar == bar && x.tick >= from) m.notes.remove(i);
            }
        } else {
            m.clearNotes(BeatModel.DRUMS, bar);
            m.notes.add(new BeatModel.Note(BeatModel.DRUMS, bar, 0, 6, 0, 1f));
            m.custom[BeatModel.DRUMS][bar] = true;
        }
        int total = (bt - from) / div, k = 0;
        for (int t = from; t + div <= bt; t += div, k++)
            m.notes.add(new BeatModel.Note(BeatModel.DRUMS, bar, t, div, 1, 0.45f + 0.55f * k / Math.max(1, total - 1)));
        m.notes.add(new BeatModel.Note(BeatModel.DRUMS, bar, from, 6, 0, 1f));
    }
}
