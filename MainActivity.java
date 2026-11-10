package com.tvkouame.studio;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements AudioEngine.Listener {

    private AudioEngine engine;
    private AudioManager am;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Vues
    private View statusDot;
    private TextView statusTitle, statusDevice, signalText, timerText, hintText;
    private LevelMeterView meter;
    private Button recBtn, playLastBtn;
    private Button[] modeBtns = new Button[3];
    private LinearLayout listBox;
    private TextView gainLabel, thrLabel, redLabel;

    // État
    private long recStart = 0;
    private File currentFile;
    private MediaPlayer player;
    private File playingFile;
    private boolean permOk = false;
    private AudioDeviceCallback deviceCallback;

    // Vérification de la connexion
    private final TextView[] chk = new TextView[4];
    private TextView verdict;
    private long lastCheck = 0;
    private boolean proposed = false;
    private boolean wasBlocked = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = getSharedPreferences("studio", MODE_PRIVATE);
        engine = new AudioEngine(this, this);
        loadPrefs();
        buildUi();
        refreshList();

        if (Build.VERSION.SDK_INT >= 23) {
            deviceCallback = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { onDevicesChanged(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { onDevicesChanged(); }
            };
            am.registerAudioDeviceCallback(deviceCallback, ui);
        }
        askPermissions();
    }

    // ------------------------------------------------------------------
    // Permissions / cycle de vie
    // ------------------------------------------------------------------
    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            boolean needStorage = Build.VERSION.SDK_INT <= 28
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
            boolean needMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
            if (needMic || needStorage) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
                return;
            }
        }
        permOk = true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        permOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (permOk) { engine.start(); updateStatus(); }
        else hintText.setText("Permission micro refusée : autorisez-la dans les réglages du téléphone.");
    }

    @Override protected void onStart() {
        super.onStart();
        if (permOk && !engine.isRunning()) { engine.start(); }
        updateStatus();
    }

    @Override protected void onStop() {
        super.onStop();
        if (!engine.isRecording()) { engine.stop(); meter.clear(); }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= 23 && deviceCallback != null) am.unregisterAudioDeviceCallback(deviceCallback);
        engine.stop();
        stopPlayback();
    }

    private void onDevicesChanged() {
        if (!engine.isRecording() && permOk) {
            engine.stop();
            engine.start();
        }
        updateStatus();
    }

    // ------------------------------------------------------------------
    // Préférences
    // ------------------------------------------------------------------
    private void loadPrefs() {
        engine.gainDb = prefs.getFloat("gain", 0f);
        engine.thresholdDb = prefs.getFloat("thr", -50f);
        engine.reduction = prefs.getFloat("red", 0.85f);
        engine.noiseReduction = prefs.getBoolean("nr", true);
        engine.clarity = prefs.getBoolean("clarity", true);
        engine.profile = prefs.getInt("profile", 0);
        engine.mode = prefs.getInt("mode", 0);
        engine.hwNoise = prefs.getBoolean("hwnoise", true);
        engine.hwAgc = prefs.getBoolean("hwagc", false);
    }

    // ------------------------------------------------------------------
    // Interface
    // ------------------------------------------------------------------
    private void buildUi() {
        int pad = Ui.dp(this, 16);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(root);

        TextView title = Ui.text(this, "TvKouame Studio", 24, Ui.TEXT, true);
        TextView sub = Ui.text(this, "Enregistrement table de mixage · son nettoyé", 13, Ui.MUTED, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 14));
        root.addView(title);
        root.addView(sub);

        // --- Carte statut table
        LinearLayout st = Ui.card(this);
        LinearLayout r1 = Ui.row(this);
        statusDot = new View(this);
        statusDot.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16)));
        Ui.setDot(statusDot, Ui.ORANGE);
        statusTitle = Ui.text(this, "", 17, Ui.TEXT, true);
        statusTitle.setPadding(Ui.dp(this, 10), 0, 0, 0);
        r1.addView(statusDot);
        r1.addView(statusTitle);
        statusDevice = Ui.text(this, "", 13, Ui.MUTED, false);
        statusDevice.setPadding(0, Ui.dp(this, 6), 0, 0);
        signalText = Ui.text(this, "", 14, Ui.MUTED, true);
        signalText.setPadding(0, Ui.dp(this, 6), 0, 0);
        st.addView(r1);
        st.addView(statusDevice);
        st.addView(signalText);
        root.addView(st);

        // --- Vérification de la connexion
        LinearLayout vc = Ui.card(this);
        vc.addView(Ui.text(this, "Vérification de la connexion", 17, Ui.TEXT, true));
        for (int i = 0; i < 4; i++) {
            chk[i] = Ui.text(this, "", 13, Ui.MUTED, false);
            chk[i].setPadding(0, Ui.dp(this, 8), 0, 0);
            vc.addView(chk[i]);
        }
        verdict = Ui.text(this, "", 14, Ui.MUTED, true);
        verdict.setPadding(0, Ui.dp(this, 12), 0, 0);
        vc.addView(verdict);
        Button verifyBtn = Ui.button(this, "Vérifier maintenant", 0xFF2C3A4A);
        verifyBtn.setOnClickListener(v -> {
            engine.resetSoundCheck();
            proposed = false;
            lastCheck = 0;
            restartEngine();
            updateChecklist();
        });
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2);
        vlp.setMargins(0, Ui.dp(this, 10), 0, 0);
        vc.addView(verifyBtn, vlp);
        root.addView(vc);

        // --- Vumètre + enregistrement
        LinearLayout rc = Ui.card(this);
        meter = new LevelMeterView(this);
        meter.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 130)));
        rc.addView(meter);
        timerText = Ui.text(this, "00:00", 30, Ui.TEXT, true);
        timerText.setGravity(Gravity.CENTER);
        timerText.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        rc.addView(timerText);
        hintText = Ui.text(this, "", 12, Ui.MUTED, false);
        hintText.setGravity(Gravity.CENTER);
        rc.addView(hintText);

        LinearLayout br = Ui.row(this);
        br.setPadding(0, Ui.dp(this, 10), 0, 0);
        recBtn = Ui.button(this, "● Enregistrer", Ui.RED);
        recBtn.setOnClickListener(v -> toggleRecord());
        playLastBtn = Ui.button(this, "▶ Dernier", 0xFF2C3A4A);
        playLastBtn.setOnClickListener(v -> {
            List<File> l = Storage.list(this);
            if (l.isEmpty()) toast("Aucun enregistrement"); else togglePlay(l.get(0));
        });
        br.addView(recBtn, Ui.weight(this, Ui.dp(this, 4)));
        br.addView(playLastBtn, Ui.weight(this, Ui.dp(this, 4)));
        rc.addView(br);
        root.addView(rc);

        // --- Paramètres
        LinearLayout set = Ui.card(this);
        set.addView(Ui.text(this, "Paramètres de réglage", 17, Ui.TEXT, true));

        set.addView(label("Source d'entrée"));
        LinearLayout mr = Ui.row(this);
        String[] mn = {"Auto", "Téléphone", "Table"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            modeBtns[i] = Ui.button(this, mn[i], 0xFF2C3A4A);
            modeBtns[i].setOnClickListener(v -> setMode(idx));
            mr.addView(modeBtns[i], Ui.weight(this, Ui.dp(this, 3)));
        }
        set.addView(mr);

        set.addView(label("Instrument à rendre clair"));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, AudioEngine.PROFILS) {
            @Override public View getView(int p, View v, android.view.ViewGroup g) {
                TextView t = (TextView) super.getView(p, v, g); t.setTextColor(Ui.TEXT); return t;
            }
            @Override public View getDropDownView(int p, View v, android.view.ViewGroup g) {
                TextView t = (TextView) super.getDropDownView(p, v, g);
                t.setTextColor(Color.WHITE); t.setBackgroundColor(Ui.CARD); t.setPadding(30, 24, 30, 24); return t;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(engine.profile);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                engine.profile = pos; prefs.edit().putInt("profile", pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        set.addView(sp);

        Switch hw = new Switch(this);
        hw.setText("Réduction de bruit de la carte son");
        hw.setTextColor(Ui.TEXT);
        hw.setChecked(engine.hwNoise);
        hw.setOnCheckedChangeListener((CompoundButton c, boolean on) -> {
            engine.hwNoise = on; prefs.edit().putBoolean("hwnoise", on).apply(); restartEngine();
        });
        hw.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        set.addView(hw);

        Switch agcSw = new Switch(this);
        agcSw.setText("Gain automatique de la carte son");
        agcSw.setTextColor(Ui.TEXT);
        agcSw.setChecked(engine.hwAgc);
        agcSw.setOnCheckedChangeListener((CompoundButton c, boolean on) -> {
            engine.hwAgc = on; prefs.edit().putBoolean("hwagc", on).apply(); restartEngine();
        });
        agcSw.setPadding(0, Ui.dp(this, 6), 0, 0);
        set.addView(agcSw);

        gainLabel = label("");
        set.addView(gainLabel);
        SeekBar gs = new SeekBar(this);
        gs.setMax(36);
        gs.setProgress((int) (engine.gainDb + 12));
        gs.setOnSeekBarChangeListener(new Seek(p -> {
            engine.gainDb = p - 12; prefs.edit().putFloat("gain", p - 12).apply(); updateLabels();
        }));
        set.addView(gs);

        thrLabel = label("");
        set.addView(thrLabel);
        SeekBar ts = new SeekBar(this);
        ts.setMax(50);
        ts.setProgress((int) (engine.thresholdDb + 70));
        ts.setOnSeekBarChangeListener(new Seek(p -> {
            engine.thresholdDb = p - 70; prefs.edit().putFloat("thr", p - 70).apply(); updateLabels();
        }));
        set.addView(ts);

        Switch nr = new Switch(this);
        nr.setText("Suppression du bruit");
        nr.setTextColor(Ui.TEXT);
        nr.setChecked(engine.noiseReduction);
        nr.setOnCheckedChangeListener((CompoundButton c, boolean on) -> {
            engine.noiseReduction = on; prefs.edit().putBoolean("nr", on).apply();
        });
        nr.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        set.addView(nr);

        redLabel = label("");
        set.addView(redLabel);
        SeekBar rs = new SeekBar(this);
        rs.setMax(100);
        rs.setProgress((int) (engine.reduction * 100));
        rs.setOnSeekBarChangeListener(new Seek(p -> {
            engine.reduction = p / 100f; prefs.edit().putFloat("red", p / 100f).apply(); updateLabels();
        }));
        set.addView(rs);

        Switch cl = new Switch(this);
        cl.setText("Clarté (filtres selon l'instrument)");
        cl.setTextColor(Ui.TEXT);
        cl.setChecked(engine.clarity);
        cl.setOnCheckedChangeListener((CompoundButton c, boolean on) -> {
            engine.clarity = on; prefs.edit().putBoolean("clarity", on).apply();
        });
        cl.setPadding(0, Ui.dp(this, 10), 0, 0);
        set.addView(cl);
        root.addView(set);

        // --- Liste des enregistrements
        LinearLayout lc = Ui.card(this);
        lc.addView(Ui.text(this, "Enregistrements (dossier tvkouame)", 17, Ui.TEXT, true));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        lc.addView(listBox);
        root.addView(lc);

        setContentView(sv);
        updateLabels();
        updateModeButtons();
        updateStatus();
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        return t;
    }

    private interface IntCb { void on(int p); }

    private static class Seek implements SeekBar.OnSeekBarChangeListener {
        final IntCb cb;
        Seek(IntCb c) { cb = c; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) cb.on(p); }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }

    private void updateLabels() {
        gainLabel.setText(String.format(Locale.US, "Gain d'entrée : %+d dB", (int) engine.gainDb));
        thrLabel.setText(String.format(Locale.US, "Seuil de détection du signal : %d dB", (int) engine.thresholdDb));
        redLabel.setText(String.format(Locale.US, "Force de réduction du bruit : %d %%", (int) (engine.reduction * 100)));
    }

    private void setMode(int m) {
        engine.mode = m;
        prefs.edit().putInt("mode", m).apply();
        updateModeButtons();
        if (!engine.isRecording() && permOk) { engine.stop(); engine.start(); }
        updateStatus();
    }

    private void restartEngine() {
        if (!engine.isRecording() && permOk) {
            engine.stop();
            engine.start();
        } else if (engine.isRecording()) {
            toast("Le changement sera appliqué au prochain enregistrement");
        }
    }

    private void updateModeButtons() {
        for (int i = 0; i < 3; i++)
            modeBtns[i].setBackground(Ui.rounded(i == engine.mode ? Ui.ACCENT : 0xFF2C3A4A, Ui.dp(this, 14)));
        for (int i = 0; i < 3; i++)
            modeBtns[i].setTextColor(i == engine.mode ? Color.BLACK : Color.WHITE);
    }

    private void updateStatus() {
        boolean ext = engine.findExternalInput() != null;
        boolean locked = engine.isLocked();
        if (engine.mode == AudioEngine.MODE_PHONE && !locked) {
            Ui.setDot(statusDot, Ui.ORANGE);
            statusTitle.setText("Micro du téléphone");
            statusDevice.setText("Aucun écouteur ni table branché.");
        } else if (!ext && locked) {
            Ui.setDot(statusDot, Ui.RED);
            statusTitle.setText("Micro du téléphone BLOQUÉ");
            statusDevice.setText("En attente du son de la table. Branchez la table avec un câble qui transporte l'entrée audio (USB ou jack à 4 contacts).");
        } else if (ext) {
            Ui.setDot(statusDot, Ui.GREEN);
            statusTitle.setText("TABLE DE MIXAGE CONNECTÉE");
            AudioDeviceInfo d = engine.findExternalInput();
            statusDevice.setText(AudioEngine.typeName(d.getType()) + " · " + d.getProductName()
                    + "\nLe micro du téléphone n'est plus utilisé.");
        } else {
            Ui.setDot(statusDot, Ui.ORANGE);
            statusTitle.setText("Table non branchée");
            statusDevice.setText("Micro du téléphone utilisé en attendant. Dès que la table est branchée, il est bloqué.");
        }
        if (!engine.isRecording()) {
            recBtn.setEnabled(ext || !locked);
        }
        modeBtns[AudioEngine.MODE_PHONE].setEnabled(!locked);
        modeBtns[AudioEngine.MODE_PHONE].setAlpha(locked ? 0.35f : 1f);
    }

    // ------------------------------------------------------------------
    // Vérification : branchement, blocage du micro, source réelle, son reçu
    // ------------------------------------------------------------------
    private void setCheck(int i, int state, String s) {
        // state : 1 = ok, 0 = en cours, -1 = problème
        String icon = state > 0 ? "✓  " : state == 0 ? "…  " : "✗  ";
        chk[i].setText(icon + s);
        chk[i].setTextColor(state > 0 ? Ui.GREEN : state == 0 ? Ui.ORANGE : Ui.RED);
    }

    private void updateChecklist() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 250) return;
        lastCheck = now;

        AudioDeviceInfo dev = engine.findExternalInput();
        boolean ext = dev != null;
        boolean locked = engine.isLocked();
        boolean route = ext && engine.isRouteVerified();
        boolean sound = ext && engine.isSoundConfirmed();

        if (ext) setCheck(0, 1, "Table branchée : " + AudioEngine.typeName(dev.getType()) + " · " + dev.getProductName());
        else if (locked) setCheck(0, -1, "Écouteur détecté mais aucune entrée audio reçue");
        else setCheck(0, -1, "Aucune table ni écouteur branché");

        if (locked) setCheck(1, 1, "Micro du téléphone bloqué");
        else setCheck(1, 0, "Micro du téléphone actif (rien n'est branché)");

        if (!ext) setCheck(2, 0, "Entrée utilisée : en attente de la table");
        else if (route) setCheck(2, 1, "Entrée utilisée par Android : "
                + (engine.getRoutedName().isEmpty() ? "la table" : engine.getRoutedName()));
        else if (engine.isRouteWrong()) setCheck(2, -1, "Android utilise le micro du téléphone : nouvelle tentative…");
        else setCheck(2, 0, "Vérification de l'entrée en cours…");

        if (!route) setCheck(3, 0, "Son reçu de la table : en attente");
        else if (sound) setCheck(3, 1, "Son reçu de la table");
        else setCheck(3, 0, "Jouez ou parlez dans la table pour confirmer le son");

        boolean blocked = locked && !ext;
        if (blocked && !wasBlocked) alertBlocked();
        wasBlocked = blocked;

        if (!ext) proposed = false;

        if (route && sound) {
            verdict.setText("✅ Tout est bon : le son vient bien de la table.");
            verdict.setTextColor(Ui.GREEN);
            if (!proposed && !engine.isRecording() && !isFinishing()) {
                proposed = true;
                proposeRecording(dev);
            }
        } else if (blocked) {
            verdict.setText("⛔ Micro du téléphone bloqué : en attente du son de la table.");
            verdict.setTextColor(Ui.RED);
        } else {
            verdict.setText("");
        }
    }

    private void alertBlocked() {
        toast("Micro du téléphone bloqué : en attente du son de la table");
        try {
            android.os.Vibrator vib = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null) vib.vibrate(350);
        } catch (Exception ignored) {}
    }

    private void proposeRecording(AudioDeviceInfo dev) {
        String name = dev != null ? String.valueOf(dev.getProductName()) : "la table";
        new android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Prêt à enregistrer")
                .setMessage("Le son arrive bien depuis « " + name + " ». Le micro du téléphone est bloqué.\n\nVoulez-vous commencer l'enregistrement ?")
                .setPositiveButton("Enregistrer", (d, w) -> { if (!engine.isRecording()) toggleRecord(); })
                .setNegativeButton("Plus tard", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Callbacks moteur (thread audio)
    // ------------------------------------------------------------------
    @Override
    public void onLevel(float level, boolean signal, float noiseFloor) {
        ui.post(() -> {
            meter.push(level, signal);
            updateChecklist();
            boolean ext = engine.isUsingMixer();
            if (signal) {
                signalText.setText("● Signal reçu");
                signalText.setTextColor(Ui.GREEN);
                hintText.setText("");
            } else {
                signalText.setText(engine.isWaitingForMixer() ? "○ En attente du son de la table (micro du téléphone bloqué)"
                        : ext ? "○ Table connectée — aucun signal (rien ne joue sur la table)"
                        : "○ Aucun signal");
                signalText.setTextColor(Ui.MUTED);
            }
            if (!engine.hwStatus.isEmpty() && !engine.isRecording()) hintText.setText(engine.hwStatus);
            if (engine.isRecording()) {
                long s = (System.currentTimeMillis() - recStart) / 1000;
                timerText.setText(String.format(Locale.US, "%02d:%02d", s / 60, s % 60));
            }
        });
    }

    @Override
    public void onError(String message) {
        ui.post(() -> {
            toast(message);
            hintText.setText(message);
            Ui.setDot(statusDot, Ui.RED);
            ui.postDelayed(() -> {
                afterRecordingStopped();
                if (permOk && !engine.isRunning()) engine.start();
            }, 700);
        });
    }

    // ------------------------------------------------------------------
    // Enregistrement / lecture
    // ------------------------------------------------------------------
    private void toggleRecord() {
        if (!permOk) { askPermissions(); toast("Autorisez le micro d'abord"); return; }
        if (engine.isRecording()) {
            engine.stopRecording();
            ui.postDelayed(this::afterRecordingStopped, 400);
        } else {
            stopPlayback();
            if (!engine.isRunning()) engine.start();
            currentFile = Storage.tempFile(this);
            ui.postDelayed(() -> {
                if (engine.startRecording(currentFile)) {
                    recStart = System.currentTimeMillis();
                    recBtn.setText("■ Arrêter");
                    recBtn.setBackground(Ui.rounded(0xFF7F1D1D, Ui.dp(this, 14)));
                    hintText.setText(engine.isUsingMixer() ? "Enregistrement depuis la table de mixage"
                            : "Enregistrement depuis le micro du téléphone");
                } else toast("Moteur audio non prêt");
            }, 250);
        }
    }

    /** Fin d'enregistrement (arrêt manuel ou table débranchée) : sauvegarde dans tvkouame. */
    private void afterRecordingStopped() {
        recBtn.setText("● Enregistrer");
        recBtn.setBackground(Ui.rounded(Ui.RED, Ui.dp(this, 14)));
        final File f = currentFile;
        currentFile = null;
        refreshList();
        updateStatus();
        if (f != null && f.exists() && f.length() > 44 && !isFinishing()) askNameAndFormat(f);
    }

    /** Demande le nom et le format (MP3, AMR, WAV), puis convertit et sauvegarde dans tvkouame. */
    private void askNameAndFormat(final File raw) {
        final EditText name = new EditText(this);
        name.setText(Storage.defaultName());
        name.setSingleLine(true);
        name.setSelectAllOnFocus(true);
        name.setTextColor(Color.WHITE);

        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"MP3", "AMR", "WAV"};
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(100 + i);
            rb.setText(labels[i]);
            rb.setTextColor(Color.WHITE);
            rb.setPadding(0, 0, Ui.dp(this, 18), 0);
            rg.addView(rb);
        }
        rg.check(100 + prefs.getInt("format", Converter.MP3));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        TextView l1 = Ui.text(this, "Nom de l'enregistrement", 13, Ui.MUTED, false);
        TextView l2 = Ui.text(this, "Format", 13, Ui.MUTED, false);
        l2.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        box.addView(l1);
        box.addView(name);
        box.addView(l2);
        box.addView(rg);

        new android.app.AlertDialog.Builder(this, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Enregistrer sous…")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    int fmt = Math.max(0, Math.min(2, rg.getCheckedRadioButtonId() - 100));
                    prefs.edit().putInt("format", fmt).apply();
                    saveAs(raw, name.getText().toString(), fmt);
                })
                .setNegativeButton("Supprimer", (d, w) -> { raw.delete(); toast("Enregistrement supprimé"); })
                .show();
    }

    private void saveAs(final File raw, final String name, final int fmt) {
        toast("Conversion en " + Converter.NAMES[fmt] + "…");
        new Thread(() -> {
            String err = null;
            File out = Storage.target(this, name, Converter.EXT[fmt]);
            try {
                Converter.convert(raw, out, fmt);
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
                out.delete();
            }
            final String error = err;
            final File result = out;
            String pub = null;
            if (error == null) pub = Storage.exportPublic(this, result);
            final String pubPath = pub;
            ui.post(() -> {
                if (error == null) {
                    raw.delete();
                    toast("Sauvegardé : " + (pubPath != null ? pubPath : result.getName()));
                } else {
                    // l'enregistrement brut est conservé pour réessayer
                    File keep = Storage.target(this, name, ".wav");
                    raw.renameTo(keep);
                    toast("Conversion " + Converter.NAMES[fmt] + " impossible (" + error + "). Enregistré en WAV : " + keep.getName());
                }
                refreshList();
            });
        }).start();
    }

    private void togglePlay(File f) {
        if (player != null && f.equals(playingFile)) { stopPlayback(); refreshList(); return; }
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> { stopPlayback(); refreshList(); });
            player.prepare();
            player.start();
            playingFile = f;
        } catch (Exception e) {
            toast("Lecture impossible");
            stopPlayback();
        }
        refreshList();
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        playingFile = null;
    }

    private void refreshList() {
        listBox.removeAllViews();
        List<File> files = Storage.list(this);
        if (files.isEmpty()) {
            TextView t = Ui.text(this, "Aucun enregistrement pour l'instant.", 13, Ui.MUTED, false);
            t.setPadding(0, Ui.dp(this, 10), 0, 0);
            listBox.addView(t);
            return;
        }
        for (final File f : files) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 10), 0, 0);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            String n = f.getName();
            String ext = n.substring(n.lastIndexOf('.') + 1).toUpperCase(Locale.US);
            n = n.substring(0, n.lastIndexOf('.'));
            info.addView(Ui.text(this, n, 14, Ui.TEXT, true));
            info.addView(Ui.text(this, ext + " · " + Storage.duration(f) + " · " + Storage.size(f), 12, Ui.MUTED, false));
            boolean playing = f.equals(playingFile);
            Button play = Ui.button(this, playing ? "■" : "▶", playing ? Ui.ORANGE : Ui.GREEN);
            play.setMinWidth(Ui.dp(this, 52));
            play.setMinimumWidth(Ui.dp(this, 52));
            play.setOnClickListener(v -> togglePlay(f));
            Button del = Ui.button(this, "✕", 0xFF2C3A4A);
            del.setMinWidth(Ui.dp(this, 52));
            del.setMinimumWidth(Ui.dp(this, 52));
            del.setOnClickListener(v -> {
                if (f.equals(playingFile)) stopPlayback();
                f.delete();
                refreshList();
            });
            row.addView(info);
            row.addView(play);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            row.addView(del, lp);
            listBox.addView(row);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
