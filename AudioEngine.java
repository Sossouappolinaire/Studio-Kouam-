package com.tvkouame.studio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Moteur audio : écoute en continu (pour le vumètre), enregistre en WAV,
 * choisit la table de mixage dès qu'elle est branchée, nettoie le son.
 */
public class AudioEngine {

    public interface Listener {
        void onLevel(float level, boolean signal, float noiseFloor);
        void onError(String message);
    }

    public static final int RATE = 44100;
    public static final int MODE_AUTO = 0, MODE_PHONE = 1, MODE_MIXER = 2;
    public static final int PROF_MIX = 0, PROF_VOIX = 1, PROF_PIANO = 2, PROF_BASSE = 3, PROF_BATTERIE = 4;
    public static final String[] PROFILS = {"Mix complet", "Voix", "Piano solo", "Basse", "Batterie"};

    // Réglages (modifiables en direct)
    public volatile float gainDb = 0f;          // -12 .. +24
    public volatile float thresholdDb = -50f;   // seuil de signal (-70 .. -20)
    public volatile boolean noiseReduction = true;
    public volatile float reduction = 0.85f;    // force de réduction 0..1
    public volatile boolean clarity = true;
    public volatile int profile = PROF_MIX;
    public volatile int mode = MODE_AUTO;
    public volatile boolean hwNoise = true;     // réduction de bruit de la carte son Android
    public volatile boolean hwAgc = false;      // contrôle de gain automatique de la carte son
    public volatile String hwStatus = "";       // état des effets matériels (affiché dans l'app)
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;

    private final Context ctx;
    private final AudioManager am;
    private final Listener listener;

    private Thread thread;
    private volatile boolean running = false;
    private volatile boolean recording = false;
    private volatile File outFile;
    private volatile String currentDeviceName = "";
    private volatile boolean usingMixer = false;
    private volatile boolean waitingForMixer = false;
    private volatile boolean routeVerified = false;
    private volatile boolean routeWrong = false;
    private volatile boolean soundConfirmed = false;
    private volatile String routedName = "";
    private int signalFrames = 0;

    // Filtres
    private Biquad hp1, hp2, lp, presence;
    private int appliedProfile = -1;

    // Bruit
    private float noiseFloor = 0.002f;
    private float gateGain = 0f;
    private int holdFrames = 0;

    public AudioEngine(Context c, Listener l) {
        ctx = c.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        listener = l;
    }

    // ------------------------------------------------------------------
    // Détection de la table de mixage
    // ------------------------------------------------------------------
    public static boolean isExternalType(int t) {
        if (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET
                || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_LINE_ANALOG
                || t == AudioDeviceInfo.TYPE_LINE_DIGITAL) return true;
        return Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_AUX_LINE;
    }

    public AudioDeviceInfo findExternalInput() {
        if (Build.VERSION.SDK_INT < 23) return null;
        AudioDeviceInfo best = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (!isExternalType(d.getType())) continue;
            if (best == null || d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE
                    || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) best = d;
        }
        return best;
    }

    public static String typeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB casque";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Jack 3,5 mm";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "Ligne analogique";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "Ligne numérique";
            default: return "Entrée externe";
        }
    }

    /** Un écouteur, un casque ou une table (entrée ou sortie) est-il branché ? */
    public boolean externalPlugged() {
        if (Build.VERSION.SDK_INT < 23) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || isExternalType(t)) return true;
        }
        return false;
    }

    /** Micro du téléphone verrouillé : écouteur/table branché ou mode « Table » forcé. */
    public boolean isLocked() {
        return mode == MODE_MIXER || externalPlugged();
    }

    public boolean isWaitingForMixer() { return waitingForMixer; }
    /** L'entrée réellement utilisée par Android est bien celle de la table. */
    public boolean isRouteVerified() { return routeVerified; }
    public boolean isRouteWrong() { return routeWrong; }
    /** Un vrai son est arrivé depuis l'entrée vérifiée. */
    public boolean isSoundConfirmed() { return soundConfirmed; }
    public String getRoutedName() { return routedName; }
    /** Relance le test « son reçu » (bouton Vérifier maintenant). */
    public void resetSoundCheck() { soundConfirmed = false; signalFrames = 0; }
    public boolean isUsingMixer() { return usingMixer; }
    public String getDeviceName() { return currentDeviceName; }
    public boolean isRecording() { return recording; }
    public boolean isRunning() { return running; }

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------
    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "audio-engine");
        thread.start();
    }

    /** Redémarre l'écoute (changement de périphérique ou de mode). */
    public synchronized void restart() {
        if (recording) return;
        stop();
        start();
    }

    public synchronized void stop() {
        running = false;
        recording = false;
        if (thread != null) {
            try { thread.join(1500); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }

    public synchronized boolean startRecording(File f) {
        if (!running || waitingForMixer) return false;
        outFile = f;
        recording = true;
        return true;
    }

    public synchronized void stopRecording() { recording = false; }

    // ------------------------------------------------------------------
    // Boucle principale
    // ------------------------------------------------------------------
    private AudioRecord createRecord(int bufBytes, AudioDeviceInfo dev) {
        // Les effets de la carte son ne s'appliquent pas à la source UNPROCESSED :
        // si la réduction matérielle est active, on utilise la source MIC.
        int[] sources = (Build.VERSION.SDK_INT >= 24 && !(hwNoise || hwAgc))
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.MIC};
        for (int s : sources) {
            try {
                AudioRecord r = new AudioRecord(s, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    if (dev != null && Build.VERSION.SDK_INT >= 23) r.setPreferredDevice(dev);
                    return r;
                }
                r.release();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void loop() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int frame = 2048;
        // Dès qu'un écouteur / une table est branché(e), le micro du téléphone est verrouillé :
        // on attend uniquement le son venu de la table.
        AudioDeviceInfo ext = findExternalInput();
        while (running && ext == null && isLocked()) {
            waitingForMixer = true;
            usingMixer = false;
            currentDeviceName = "En attente du son de la table";
            listener.onLevel(0f, false, noiseFloor);
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            ext = findExternalInput();
        }
        waitingForMixer = false;
        routeVerified = false;
        routeWrong = false;
        soundConfirmed = false;
        routedName = "";
        signalFrames = 0;
        if (!running) return;
        if (ext != null) {
            usingMixer = true;
            currentDeviceName = typeName(ext.getType()) + " · " + ext.getProductName();
        } else {
            usingMixer = false;
            currentDeviceName = "Micro du téléphone";
        }

        AudioRecord rec = createRecord(Math.max(min, frame * 4), ext);
        if (rec == null) {
            running = false;
            listener.onError("Impossible d'ouvrir l'entrée audio (permission micro ?)");
            return;
        }

        attachEffects(rec.getAudioSessionId());

        short[] buf = new short[frame];
        RandomAccessFile raf = null;
        long dataBytes = 0;
        boolean wasRecording = false;
        int reads = 0, routeTries = 0, chk = 0;
        resetState();

        try {
            rec.startRecording();
            while (running) {
                int n = rec.read(buf, 0, frame);
                if (n <= 0) { if (n < 0) break; continue; }

                // Vérifie que le son vient bien de la table et jamais du micro du téléphone
                if (Build.VERSION.SDK_INT >= 23 && !routeVerified && ++reads == 6) {
                    AudioDeviceInfo routed = rec.getRoutedDevice();
                    if (routed != null) {
                        routedName = typeName(routed.getType()) + " · " + routed.getProductName();
                    }
                    if (ext == null) {
                        routeVerified = true; // mode téléphone, aucune table branchée
                    } else if (routed != null && routed.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        routeWrong = true;
                        rec.setPreferredDevice(ext);
                        reads = 0;
                        if (++routeTries >= 3) {
                            listener.onError("La table n'est pas reconnue comme entrée audio : le micro du téléphone reste bloqué.");
                            break;
                        }
                    } else {
                        routeWrong = false;
                        routeVerified = true;
                        if (routed == null) routedName = typeName(ext.getType()) + " · " + ext.getProductName();
                    }
                }

                // Table débranchée pendant l'écoute : on coupe tout, sans basculer sur le micro du téléphone
                if (ext != null && ++chk % 10 == 0 && findExternalInput() == null) {
                    listener.onError("Table débranchée : enregistrement arrêté et sauvegardé.");
                    break;
                }

                float[] x = new float[n];
                for (int i = 0; i < n; i++) x[i] = buf[i] / 32768f;
                float[] meter = process(x, n);

                short[] out = new short[n];
                for (int i = 0; i < n; i++) {
                    float v = x[i];
                    if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                    out[i] = (short) (v * 32767f);
                }

                if (recording && !wasRecording) {
                    try {
                        raf = new RandomAccessFile(outFile, "rw");
                        raf.setLength(0);
                        raf.write(new byte[44]);
                        dataBytes = 0;
                    } catch (IOException e) {
                        recording = false;
                        listener.onError("Fichier impossible : " + e.getMessage());
                    }
                }
                if (recording && raf != null) {
                    byte[] bytes = new byte[n * 2];
                    for (int i = 0; i < n; i++) {
                        bytes[2 * i] = (byte) (out[i] & 0xff);
                        bytes[2 * i + 1] = (byte) ((out[i] >> 8) & 0xff);
                    }
                    try { raf.write(bytes); dataBytes += bytes.length; } catch (IOException e) { recording = false; }
                }
                if (!recording && wasRecording && raf != null) {
                    closeWav(raf, dataBytes);
                    raf = null;
                }
                wasRecording = recording;

                // Confirmation : un vrai son venu de l'entrée vérifiée (≈ 0,5 s de signal continu)
                if (meter[1] > 0.5f && routeVerified && ext != null) {
                    if (++signalFrames >= 12) soundConfirmed = true;
                } else if (meter[1] <= 0.5f) {
                    signalFrames = 0;
                }

                listener.onLevel(meter[0], meter[1] > 0.5f, noiseFloor);
            }
        } catch (Exception e) {
            listener.onError("Erreur audio : " + e.getMessage());
        } finally {
            try { rec.stop(); } catch (Exception ignored) {}
            releaseEffects();
            rec.release();
            if (raf != null) closeWav(raf, dataBytes);
            recording = false;
            running = false;
        }
    }

    /** Active les effets de la carte son Android (réduction de bruit, gain auto) si disponibles. */
    private void attachEffects(int session) {
        StringBuilder sb = new StringBuilder();
        if (hwNoise) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(session);
                    if (ns != null) { ns.setEnabled(true); sb.append("Réduction de bruit carte son : active"); }
                    else sb.append("Réduction de bruit carte son : refusée");
                } else sb.append("Réduction de bruit carte son : non disponible sur ce téléphone");
            } catch (Exception e) { sb.append("Réduction de bruit carte son : erreur"); }
        }
        if (hwAgc) {
            try {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(session);
                    if (agc != null) { agc.setEnabled(true); sb.append(sb.length() > 0 ? "\n" : "").append("Gain automatique carte son : actif"); }
                } else sb.append(sb.length() > 0 ? "\n" : "").append("Gain automatique carte son : non disponible");
            } catch (Exception e) { /* ignoré */ }
        }
        hwStatus = sb.toString();
    }

    private void releaseEffects() {
        try { if (ns != null) { ns.release(); } } catch (Exception ignored) {}
        try { if (agc != null) { agc.release(); } } catch (Exception ignored) {}
        ns = null;
        agc = null;
    }

    private void closeWav(RandomAccessFile raf, long dataBytes) {
        try {
            raf.seek(0);
            int byteRate = RATE * 2;
            byte[] h = new byte[44];
            put(h, 0, "RIFF"); le32(h, 4, (int) (36 + dataBytes)); put(h, 8, "WAVE");
            put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1);
            le32(h, 24, RATE); le32(h, 28, byteRate); le16(h, 32, 2); le16(h, 34, 16);
            put(h, 36, "data"); le32(h, 40, (int) dataBytes);
            raf.write(h);
            raf.close();
        } catch (IOException ignored) {}
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int o, int v) { le16(b, o, v); le16(b, o + 2, v >> 16); }

    // ------------------------------------------------------------------
    // Traitement : gain, filtres, réduction de bruit, clarté, limiteur
    // ------------------------------------------------------------------
    private void resetState() {
        noiseFloor = 0.002f;
        gateGain = 0f;
        holdFrames = 0;
        appliedProfile = -1;
    }

    private void buildFilters() {
        int p = profile;
        appliedProfile = p;
        float hpF, lpF, presF, presG;
        switch (p) {
            case PROF_VOIX:     hpF = 90f;  lpF = 14000f; presF = 3500f; presG = 4f; break;
            case PROF_PIANO:    hpF = 40f;  lpF = 16000f; presF = 2500f; presG = 2f; break;
            case PROF_BASSE:    hpF = 30f;  lpF = 5000f;  presF = 800f;  presG = 2f; break;
            case PROF_BATTERIE: hpF = 45f;  lpF = 18000f; presF = 5000f; presG = 3f; break;
            default:            hpF = 50f;  lpF = 18000f; presF = 3000f; presG = 2f; break;
        }
        hp1 = Biquad.highpass(RATE, hpF);
        hp2 = Biquad.highpass(RATE, hpF);
        lp = Biquad.lowpass(RATE, lpF);
        presence = Biquad.peaking(RATE, presF, 1.0f, presG);
    }

    /** Traite le tampon sur place. Retourne {niveau 0..1 pour le vumètre, signal 0/1}. */
    private float[] process(float[] x, int n) {
        if (appliedProfile != profile || hp1 == null) buildFilters();
        float g = (float) Math.pow(10.0, gainDb / 20.0);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            float v = x[i] * g;
            v = hp1.process(v);
            v = hp2.process(v);
            v = lp.process(v);
            if (clarity) v = presence.process(v);
            x[i] = v;
            sum += v * v;
        }
        float rms = (float) Math.sqrt(sum / n);

        // Plancher de bruit adaptatif
        if (rms < noiseFloor * 1.5f) noiseFloor = 0.9f * noiseFloor + 0.1f * rms;
        else noiseFloor *= 1.0003f;
        if (noiseFloor < 0.0001f) noiseFloor = 0.0001f;
        if (noiseFloor > 0.05f) noiseFloor = 0.05f;

        float userThr = (float) Math.pow(10.0, thresholdDb / 20.0);
        float thr = noiseReduction ? Math.max(userThr, noiseFloor * 2.5f) : userThr;

        boolean above = rms > thr;
        if (above) holdFrames = 6; else if (holdFrames > 0) holdFrames--;
        boolean signal = above || holdFrames > 0;

        // Porte douce (expandeur)
        float target = signal ? 1f : (noiseReduction ? 1f - reduction : 1f);
        if (!noiseReduction) target = 1f;
        float step = signal ? 0.5f : 0.15f;
        for (int i = 0; i < n; i++) {
            gateGain += (target - gateGain) * (step / n * 8f);
            float v = x[i] * gateGain;
            // limiteur doux
            if (v > 0.8f) v = 0.8f + (float) Math.tanh((v - 0.8f) * 5f) * 0.2f;
            else if (v < -0.8f) v = -0.8f + (float) Math.tanh((v + 0.8f) * 5f) * 0.2f;
            x[i] = v;
        }

        // Le vumètre reste plat s'il n'y a pas de signal
        float level = 0f;
        if (signal) {
            float db = 20f * (float) Math.log10(Math.max(rms, 1e-5f));
            level = Math.max(0f, Math.min(1f, (db + 60f) / 60f));
        }
        return new float[]{level, signal ? 1f : 0f};
    }

    // ------------------------------------------------------------------
    // Filtre biquad (RBJ)
    // ------------------------------------------------------------------
    static class Biquad {
        float b0, b1, b2, a1, a2, z1, z2;

        static Biquad make(float b0, float b1, float b2, float a0, float a1, float a2) {
            Biquad q = new Biquad();
            q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0;
            q.a1 = a1 / a0; q.a2 = a2 / a0;
            return q;
        }

        static Biquad highpass(float fs, float f) {
            double w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / (2 * 0.7071);
            return make((float) ((1 + c) / 2), (float) -(1 + c), (float) ((1 + c) / 2),
                    (float) (1 + al), (float) (-2 * c), (float) (1 - al));
        }

        static Biquad lowpass(float fs, float f) {
            double w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / (2 * 0.7071);
            return make((float) ((1 - c) / 2), (float) (1 - c), (float) ((1 - c) / 2),
                    (float) (1 + al), (float) (-2 * c), (float) (1 - al));
        }

        static Biquad peaking(float fs, float f, float q, float gainDb) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f / fs;
            double c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return make((float) (1 + al * A), (float) (-2 * c), (float) (1 - al * A),
                    (float) (1 + al / A), (float) (-2 * c), (float) (1 - al / A));
        }

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
