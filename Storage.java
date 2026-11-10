package com.tvkouame.studio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Dossier de travail interne + copie publique dans Music/tvkouame. */
public final class Storage {
    public static final String FOLDER = "tvkouame";

    private Storage() {}

    public static File dir(Context c) {
        File base = c.getExternalFilesDir(FOLDER);
        if (base == null) base = new File(c.getFilesDir(), FOLDER);
        if (!base.exists()) base.mkdirs();
        return base;
    }

    public static File newFile(Context c) {
        String n = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        return new File(dir(c), "enregistrement_" + n + ".wav");
    }

    /** Fichier temporaire : l'enregistrement brut attend d'être nommé et converti. */
    public static File tempFile(Context c) {
        return new File(dir(c), "_en_attente.wav");
    }

    /** Nom de fichier sûr, sans doublon. */
    public static File target(Context c, String name, String ext) {
        String base = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (base.isEmpty()) base = "enregistrement";
        if (base.startsWith("_")) base = "enr" + base;
        File f = new File(dir(c), base + ext);
        int i = 2;
        while (f.exists()) f = new File(dir(c), base + "_" + (i++) + ext);
        return f;
    }

    public static String defaultName() {
        return "enregistrement_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
    }

    public static List<File> list(Context c) {
        File[] fs = dir(c).listFiles((d, name) -> {
            String n = name.toLowerCase(Locale.US);
            return !n.startsWith("_") && (n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".amr"));
        });
        if (fs == null) return new ArrayList<>();
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        List<File> l = new ArrayList<>();
        Collections.addAll(l, fs);
        return l;
    }

    /** Copie le fichier dans Music/tvkouame (visible dans le gestionnaire de fichiers). */
    public static String exportPublic(Context c, File src) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, src.getName());
                String ln = src.getName().toLowerCase(Locale.US);
                v.put(MediaStore.Audio.Media.MIME_TYPE,
                        ln.endsWith(".mp3") ? "audio/mpeg" : ln.endsWith(".amr") ? "audio/amr" : "audio/x-wav");
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/" + FOLDER);
                Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                try (OutputStream os = cr.openOutputStream(uri); InputStream is = new FileInputStream(src)) {
                    copy(is, os);
                }
                return "Music/" + FOLDER + "/" + src.getName();
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER);
                if (!d.exists() && !d.mkdirs()) return null;
                File dst = new File(d, src.getName());
                try (OutputStream os = new FileOutputStream(dst); InputStream is = new FileInputStream(src)) {
                    copy(is, os);
                }
                return "Music/" + FOLDER + "/" + src.getName();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(InputStream is, OutputStream os) throws Exception {
        byte[] b = new byte[16384];
        int n;
        while ((n = is.read(b)) > 0) os.write(b, 0, n);
        os.flush();
    }

    public static String size(File f) {
        long s = f.length();
        if (s > 1024 * 1024) return String.format(Locale.US, "%.1f Mo", s / 1048576f);
        return (s / 1024) + " Ko";
    }

    public static String duration(File f) {
        long sec;
        try {
            android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
            r.setDataSource(f.getAbsolutePath());
            sec = Long.parseLong(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000;
            r.release();
        } catch (Exception e) {
            sec = Math.max(0, (f.length() - 44) / (AudioEngine.RATE * 2L));
        }
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }
}
