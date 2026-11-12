package com.tvkouame.studio;

import java.util.ArrayList;
import java.util.List;

/** Tout ce qui définit un beat du Studio (sans dépendance Android). */
public class BeatModel {
    public static final int DRUMS = 0, BASS = 1, PIANO = 2, SOLO = 3, NT = 4;
    public static final int MAXBARS = 32, MAXSTEPS = 24;

    public static final String[] TRACKS = {"Batterie", "Basse", "Piano", "Solo"};
    public static final String[] DRUM_ROWS = {"Grosse caisse", "Caisse claire", "Charley", "Charley ouvert", "Clap"};
    public static final String[] NOTES = {"Do", "Do#", "Ré", "Ré#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"};
    public static final String[] QUAL_NAMES = {"majeur", "mineur", "septième", "majeur 7", "mineur 7", "diminué", "suspendu 4"};
    public static final String[] QUAL_SUF = {"", "m", "7", "maj7", "m7", "dim", "sus4"};
    static final int[][] QUAL = {{0, 4, 7}, {0, 3, 7}, {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10}, {0, 3, 6}, {0, 5, 7}};
    public static final String[] PIANO_STYLES = {"Accords plaqués", "Arpège montant", "Arpège descendant", "Arpège aller-retour"};

    /** Progressions : {écart en demi-tons depuis la tonalité, type d'accord}. */
    public static final String[] PROG_NAMES = {
            "I – V – vi – IV  (pop)",
            "vi – IV – I – V",
            "I – IV – V – IV",
            "I – vi – IV – V  (années 50)",
            "ii – V – I – I  (jazz)",
            "i – VI – III – VII  (mineur)",
            "i – iv – VII – III  (mineur)",
            "i – VII – VI – V  (andalou)",
            "Canon : I V vi iii IV I IV V"
    };
    static final int[][][] PROGS = {
            {{0, 0}, {7, 0}, {9, 1}, {5, 0}},
            {{9, 1}, {5, 0}, {0, 0}, {7, 0}},
            {{0, 0}, {5, 0}, {7, 0}, {5, 0}},
            {{0, 0}, {9, 1}, {5, 0}, {7, 0}},
            {{2, 4}, {7, 2}, {0, 3}, {0, 3}},
            {{0, 1}, {8, 0}, {3, 0}, {10, 0}},
            {{0, 1}, {5, 1}, {10, 0}, {3, 0}},
            {{0, 1}, {10, 0}, {8, 0}, {7, 0}},
            {{0, 0}, {7, 0}, {9, 1}, {4, 1}, {5, 0}, {0, 0}, {5, 0}, {7, 0}}
    };

    /** Un son ajouté au projet : enregistré ou importé (fichier WAV 44,1 kHz mono dans le dossier du projet). */
    public static class Clip {
        public String id = "";
        public String name = "";
        public String file = "";
        public int startBar = 0;      // mesure de départ (0 = début)
        public float vol = 1f;        // 0 .. 2
        public boolean mute = false;
        public int samples = 0;
    }

    /** Résolution de l'éditeur libre : 24 pulsations par temps (double-croche = 6, triple-croche = 3, triolet de croches = 8). */
    public static final int PPQ = 24;

    /** Une note posée librement. Batterie : pitch = ligne (0..4). Autres : pitch = note MIDI. */
    public static class Note {
        public int inst, bar, tick, len, pitch;
        public float vel = 1f;
        public Note() {}
        public Note(int inst, int bar, int tick, int len, int pitch, float vel) {
            this.inst = inst; this.bar = bar; this.tick = tick; this.len = len; this.pitch = pitch; this.vel = vel;
        }
    }

    public String id = "";
    public String name = "Mon beat";
    public int bpm = 100;
    public int beatsPerBar = 4;
    public int bars = 8;
    public int key = 0;
    public int prog = 0;

    public int[] chordRoot = new int[MAXBARS];
    public int[] chordType = new int[MAXBARS];
    public boolean[][] play = new boolean[NT][MAXBARS];   // qui joue dans quelle mesure

    public int pianoStyle = 0;
    public boolean[] pianoHit = new boolean[MAXSTEPS];     // faux = pause
    public int[] bassStep = new int[MAXSTEPS];             // 0 rien, 1 fondamentale, 2 quinte, 3 octave
    public int[] soloStep = new int[MAXSTEPS];             // 0 rien, 1..6 notes de la gamme
    public boolean[][] drum = new boolean[5][MAXSTEPS];
    public float[] vol = {0.8f, 0.9f, 0.8f, 0.7f};
    public List<Clip> clips = new ArrayList<>();
    /** custom[piste][mesure] = vrai : cette mesure est jouée avec les notes libres au lieu du motif. */
    public boolean[][] custom = new boolean[NT][MAXBARS];
    public List<Note> notes = new ArrayList<>();

    public int barTicks() { return beatsPerBar * PPQ; }

    public List<Note> notesOf(int inst, int bar) {
        List<Note> l = new ArrayList<>();
        for (Note n : notes) if (n.inst == inst && n.bar == bar) l.add(n);
        return l;
    }

    public void clearNotes(int inst, int bar) {
        for (int i = notes.size() - 1; i >= 0; i--)
            if (notes.get(i).inst == inst && notes.get(i).bar == bar) notes.remove(i);
    }

    /** Transforme le motif d'une piste, pour une mesure, en notes libres modifiables. */
    public void patternToNotes(int inst, int bar) {
        clearNotes(inst, bar);
        int spb = stepsPerBar(), root = chordRoot[bar];
        int[] tones = chordTones(bar);
        int k = 0;
        for (int s = 0; s < spb; s++) {
            int t = s * (PPQ / 4);
            if (inst == DRUMS) {
                for (int r = 0; r < 5; r++) if (drum[r][s]) notes.add(new Note(inst, bar, t, 6, r, 1f));
            } else if (inst == BASS && bassStep[s] > 0) {
                int base = 36 + (root > 7 ? root - 12 : root);
                notes.add(new Note(inst, bar, t, 6, base + (bassStep[s] == 2 ? 7 : bassStep[s] == 3 ? 12 : 0), 1f));
            } else if (inst == PIANO && pianoHit[s]) {
                int pb = 48 + root; if (pb > 55) pb -= 12;
                if (pianoStyle == 0) { for (int tn : tones) notes.add(new Note(inst, bar, t, 12, pb + tn, 1f)); }
                else { notes.add(new Note(inst, bar, t, 12, pb + 12 + tones[k % tones.length], 1f)); k++; }
            } else if (inst == SOLO && soloStep[s] > 0) {
                int[] sc = minorish(bar) ? new int[]{0, 3, 5, 7, 10, 12} : new int[]{0, 2, 4, 7, 9, 12};
                int sb = 60 + (root > 6 ? root - 12 : root);
                notes.add(new Note(inst, bar, t, 6, sb + sc[Math.min(soloStep[s], 6) - 1], 1f));
            }
        }
        custom[inst][bar] = true;
    }

    /** Nouveau projet avec un petit beat prêt à jouer. */
    public static BeatModel create() {
        BeatModel m = new BeatModel();
        m.applyProgression(0, 0);
        for (int t = 0; t < NT; t++)
            for (int b = 0; b < MAXBARS; b++) m.play[t][b] = t != SOLO;
        int[] kick = {0, 8, 10}, snare = {4, 12}, open = {14};
        for (int s : kick) m.drum[0][s] = true;
        for (int s : snare) m.drum[1][s] = true;
        for (int s = 0; s < MAXSTEPS; s += 2) m.drum[2][s] = true;
        for (int s : open) m.drum[3][s] = true;
        m.bassStep[0] = 1; m.bassStep[3] = 1; m.bassStep[6] = 1; m.bassStep[8] = 3; m.bassStep[10] = 1; m.bassStep[12] = 2;
        m.pianoHit[0] = true; m.pianoHit[6] = true; m.pianoHit[10] = true;
        m.soloStep[0] = 3; m.soloStep[4] = 2; m.soloStep[6] = 1; m.soloStep[8] = 3; m.soloStep[12] = 5;
        return m;
    }

    public int stepsPerBar() { return beatsPerBar * 4; }

    public String chordName(int bar) { return NOTES[chordRoot[bar]] + QUAL_SUF[chordType[bar]]; }

    public int[] chordTones(int bar) { return QUAL[chordType[bar]]; }

    public boolean minorish(int bar) {
        int t = chordType[bar];
        return t == 1 || t == 4 || t == 5;
    }

    /** Remplit toutes les mesures avec la progression choisie, dans la tonalité donnée. */
    public void applyProgression(int k, int p) {
        key = k;
        prog = p;
        int[][] pr = PROGS[p];
        for (int b = 0; b < MAXBARS; b++) {
            int[] c = pr[b % pr.length];
            chordRoot[b] = (k + c[0]) % 12;
            chordType[b] = c[1];
        }
    }

    public Clip findClip(String cid) {
        for (Clip c : clips) if (c.id.equals(cid)) return c;
        return null;
    }
}
