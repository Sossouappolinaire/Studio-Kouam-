package com.tvkouame.studio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Un projet = un dossier Studio/<id>/ contenant project.json et les sons ajoutés (WAV). */
public final class StudioStore {

    private StudioStore() {}

    public static File root(Context c) {
        File d = new File(Storage.dir(c), "studio");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File dirOf(Context c, String id) {
        File d = new File(root(c), id);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String newId() { return "p" + System.currentTimeMillis(); }

    // ------------------------------------------------------------------
    private static JSONArray ints(int[] a) {
        JSONArray j = new JSONArray();
        for (int v : a) j.put(v);
        return j;
    }

    private static JSONArray bools(boolean[] a) {
        JSONArray j = new JSONArray();
        for (boolean v : a) j.put(v ? 1 : 0);
        return j;
    }

    private static void readInts(JSONArray j, int[] dst) {
        if (j == null) return;
        for (int i = 0; i < dst.length && i < j.length(); i++) dst[i] = j.optInt(i, 0);
    }

    private static void readBools(JSONArray j, boolean[] dst) {
        if (j == null) return;
        for (int i = 0; i < dst.length && i < j.length(); i++) dst[i] = j.optInt(i, 0) != 0;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ------------------------------------------------------------------
    public static void save(Context c, BeatModel m) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", m.id);
            o.put("name", m.name);
            o.put("bpm", m.bpm);
            o.put("beats", m.beatsPerBar);
            o.put("bars", m.bars);
            o.put("key", m.key);
            o.put("prog", m.prog);
            o.put("chordRoot", ints(m.chordRoot));
            o.put("chordType", ints(m.chordType));
            JSONArray play = new JSONArray();
            for (int t = 0; t < BeatModel.NT; t++) play.put(bools(m.play[t]));
            o.put("play", play);
            o.put("pianoStyle", m.pianoStyle);
            o.put("pianoHit", bools(m.pianoHit));
            o.put("bass", ints(m.bassStep));
            o.put("solo", ints(m.soloStep));
            JSONArray dr = new JSONArray();
            for (int r = 0; r < 5; r++) dr.put(bools(m.drum[r]));
            o.put("drum", dr);
            JSONArray vol = new JSONArray();
            for (float v : m.vol) vol.put((double) v);
            o.put("vol", vol);
            JSONArray cl = new JSONArray();
            for (BeatModel.Clip k : m.clips) {
                JSONObject co = new JSONObject();
                co.put("id", k.id);
                co.put("name", k.name);
                co.put("file", k.file);
                co.put("start", k.startBar);
                co.put("vol", (double) k.vol);
                co.put("mute", k.mute);
                co.put("samples", k.samples);
                cl.put(co);
            }
            o.put("clips", cl);
            JSONArray cu = new JSONArray();
            for (int t = 0; t < BeatModel.NT; t++) cu.put(bools(m.custom[t]));
            o.put("custom", cu);
            JSONArray nj = new JSONArray();
            for (BeatModel.Note n : m.notes) {
                JSONArray a = new JSONArray();
                a.put(n.inst); a.put(n.bar); a.put(n.tick); a.put(n.len); a.put(n.pitch);
                a.put(Math.round(n.vel * 100));
                nj.put(a);
            }
            o.put("notes", nj);
            File f = new File(dirOf(c, m.id), "project.json");
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(o.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    public static BeatModel load(Context c, String id) {
        try {
            File f = new File(new File(root(c), id), "project.json");
            if (!f.exists()) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (FileInputStream is = new FileInputStream(f)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            }
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            BeatModel m = BeatModel.create();
            m.id = id;
            m.name = o.optString("name", "Mon beat");
            m.bpm = clamp(o.optInt("bpm", 100), 40, 200);
            m.beatsPerBar = clamp(o.optInt("beats", 4), 2, 6);
            m.bars = clamp(o.optInt("bars", 8), 1, BeatModel.MAXBARS);
            m.key = clamp(o.optInt("key", 0), 0, 11);
            m.prog = clamp(o.optInt("prog", 0), 0, BeatModel.PROG_NAMES.length - 1);
            readInts(o.optJSONArray("chordRoot"), m.chordRoot);
            readInts(o.optJSONArray("chordType"), m.chordType);
            for (int b = 0; b < BeatModel.MAXBARS; b++) {
                m.chordRoot[b] = clamp(m.chordRoot[b], 0, 11);
                m.chordType[b] = clamp(m.chordType[b], 0, BeatModel.QUAL_SUF.length - 1);
            }
            JSONArray play = o.optJSONArray("play");
            if (play != null)
                for (int t = 0; t < BeatModel.NT && t < play.length(); t++) readBools(play.optJSONArray(t), m.play[t]);
            m.pianoStyle = clamp(o.optInt("pianoStyle", 0), 0, BeatModel.PIANO_STYLES.length - 1);
            readBools(o.optJSONArray("pianoHit"), m.pianoHit);
            readInts(o.optJSONArray("bass"), m.bassStep);
            readInts(o.optJSONArray("solo"), m.soloStep);
            for (int i = 0; i < BeatModel.MAXSTEPS; i++) {
                m.bassStep[i] = clamp(m.bassStep[i], 0, 3);
                m.soloStep[i] = clamp(m.soloStep[i], 0, 6);
            }
            JSONArray dr = o.optJSONArray("drum");
            if (dr != null)
                for (int r = 0; r < 5 && r < dr.length(); r++) readBools(dr.optJSONArray(r), m.drum[r]);
            JSONArray vol = o.optJSONArray("vol");
            if (vol != null)
                for (int i = 0; i < BeatModel.NT && i < vol.length(); i++)
                    m.vol[i] = (float) Math.max(0, Math.min(1, vol.optDouble(i, m.vol[i])));
            JSONArray cl = o.optJSONArray("clips");
            if (cl != null) {
                for (int i = 0; i < cl.length(); i++) {
                    JSONObject co = cl.optJSONObject(i);
                    if (co == null) continue;
                    BeatModel.Clip k = new BeatModel.Clip();
                    k.id = co.optString("id", "c" + i);
                    k.name = co.optString("name", "Son " + (i + 1));
                    k.file = co.optString("file", "");
                    k.startBar = clamp(co.optInt("start", 0), 0, BeatModel.MAXBARS - 1);
                    k.vol = (float) Math.max(0, Math.min(2, co.optDouble("vol", 1)));
                    k.mute = co.optBoolean("mute", false);
                    k.samples = co.optInt("samples", 0);
                    if (!k.file.isEmpty() && new File(new File(root(c), id), k.file).exists()) m.clips.add(k);
                }
            }
            JSONArray cu = o.optJSONArray("custom");
            if (cu != null)
                for (int t = 0; t < BeatModel.NT && t < cu.length(); t++) readBools(cu.optJSONArray(t), m.custom[t]);
            JSONArray nj = o.optJSONArray("notes");
            if (nj != null) {
                for (int i = 0; i < nj.length(); i++) {
                    JSONArray a = nj.optJSONArray(i);
                    if (a == null || a.length() < 5) continue;
                    BeatModel.Note n = new BeatModel.Note(clamp(a.optInt(0), 0, 3), clamp(a.optInt(1), 0, BeatModel.MAXBARS - 1),
                            Math.max(0, a.optInt(2)), Math.max(1, a.optInt(3)), clamp(a.optInt(4), 0, 127),
                            clamp(a.optInt(5, 100), 5, 130) / 100f);
                    m.notes.add(n);
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** Liste des projets : {id, nom}, du plus récent au plus ancien. */
    public static List<String[]> list(Context c) {
        File[] dirs = root(c).listFiles();
        List<String[]> out = new ArrayList<>();
        if (dirs == null) return out;
        Arrays.sort(dirs, (a, b) -> Long.compare(new File(b, "project.json").lastModified(),
                new File(a, "project.json").lastModified()));
        for (File d : dirs) {
            File pj = new File(d, "project.json");
            if (!d.isDirectory() || !pj.exists()) continue;
            BeatModel m = load(c, d.getName());
            if (m != null) out.add(new String[]{d.getName(), m.name});
        }
        return out;
    }

    public static void delete(Context c, String id) {
        File d = new File(root(c), id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    /** Exporte un projet complet (réglages, notes, sons ajoutés) dans un seul fichier zip. */
    public static void exportZip(Context c, String id, OutputStream os) throws Exception {
        File[] fs = dirOf(c, id).listFiles();
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            if (fs != null) for (File f : fs) {
                if (!f.isFile()) continue;
                z.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream is = new FileInputStream(f)) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = is.read(b)) > 0) z.write(b, 0, n);
                }
                z.closeEntry();
            }
        }
    }

    /** Importe un fichier zip de projet : crée un nouveau projet et renvoie son identifiant (null si illisible). */
    public static String importZip(Context c, InputStream is) {
        String id = newId();
        File d = dirOf(c, id);
        boolean ok = false;
        try (ZipInputStream z = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = new File(e.getName()).getName();
                if (e.isDirectory() || n.isEmpty()) continue;
                try (FileOutputStream os = new FileOutputStream(new File(d, n))) {
                    byte[] b = new byte[16384];
                    int k;
                    while ((k = z.read(b)) > 0) os.write(b, 0, k);
                }
                if (n.equals("project.json")) ok = true;
            }
        } catch (Exception ex) {
            ok = false;
        }
        if (!ok || load(c, id) == null) { delete(c, id); return null; }
        return id;
    }
}
