package com.tvkouame.studio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.Locale;

/**
 * Studio : on crée un beat (tempo, mesures, accords, batterie, basse, piano, solo), on le place dans le temps,
 * on l'écoute, puis on enregistre ou on importe un son dessus. Le tout se mélange et s'exporte.
 */
public class StudioActivity extends Activity {

    private static final int REQ_MIC = 21, REQ_IMPORT = 31, REQ_PEXP = 41, REQ_PIMP = 42;
    private String pendingExportId = "";
    private static final int IDLE = 0, BEAT = 1, REC = 2, MIX = 3, CLIP = 4;
    private static final int OFF = 0xFF2C3A4A, OFF_BEAT = 0xFF3C4D63;
    private static final int[] TCOL = {0xFFE67E22, 0xFF9B59B6, 0xFF2E86DE, 0xFF2ECC71};

    private SharedPreferences prefs;
    private AudioManager am;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BeatModel m;

    // Vues
    private EditText nameEdit;
    private TextView bpmLabel, barsLabel, syncLabel, timeText, statusText;
    private SeekBar bpmSeek;
    private Spinner sigSpin, keySpin, progSpin;
    private LinearLayout chordBox, arrBox, editorBox, clipsBox;
    private final Button[] tabBtns = new Button[4];
    private Button playBtn, recBtn, mixBtn;
    private TextView[] barHeads = new TextView[0];
    private int tab = 0;
    private boolean needReload = false;

    // Lecture / enregistrement
    private PcmPlayer player;
    private int mode = IDLE;
    private boolean renderBusy = false;
    private int prefixFrames = 0;
    private AudioRecord rec;
    private Thread recThread;
    private volatile boolean recRun = false;
    private File rawFile;
    private int recCountIn = 0, recSync = 0;

    private final Runnable saveRun = () -> StudioStore.save(this, m);

    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        prefs = getSharedPreferences("studio_beat", MODE_PRIVATE);
        String last = prefs.getString("last", "");
        BeatModel loaded = last.isEmpty() ? null : StudioStore.load(this, last);
        if (loaded == null) {
            loaded = Examples.build(0);
            loaded.id = StudioStore.newId();
        }
        m = loaded;
        buildUi();
        applyModelToUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needReload) {
            needReload = false;
            BeatModel fresh = StudioStore.load(this, m.id);
            if (fresh != null) { m = fresh; applyModelToUi(); }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopAll();
        flushSave();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAll();
    }

    // ------------------------------------------------------------------
    // Petits utilitaires
    // ------------------------------------------------------------------
    private interface IntCb { void on(int v); }

    private interface Cell {
        void style(TextView c, int r, int col);
        void click(TextView c, int r, int col);
    }

    private static class Seek implements SeekBar.OnSeekBarChangeListener {
        final IntCb cb;
        Seek(IntCb c) { cb = c; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) cb.on(p); }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void setStatus(String s) { if (statusText != null) statusText.setText(s); }

    private void changed() {
        ui.removeCallbacks(saveRun);
        ui.postDelayed(saveRun, 500);
    }

    private void flushSave() {
        ui.removeCallbacks(saveRun);
        StudioStore.save(this, m);
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 2));
        return t;
    }

    private TextView hint(String s) {
        TextView t = Ui.text(this, s, 12, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        return t;
    }

    private LinearLayout card(String title) {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, title, 17, Ui.TEXT, true));
        return c;
    }

    private Button mini(String text, int color) {
        Button b = Ui.button(this, text, color);
        b.setMinHeight(Ui.dp(this, 40));
        b.setMinimumHeight(Ui.dp(this, 40));
        b.setMinWidth(Ui.dp(this, 44));
        b.setMinimumWidth(Ui.dp(this, 44));
        b.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        return b;
    }

    private Spinner spinner(String[] items) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getView(p, v, g);
                t.setTextColor(Ui.TEXT);
                return t;
            }
            @Override public View getDropDownView(int p, View v, ViewGroup g) {
                TextView t = (TextView) super.getDropDownView(p, v, g);
                t.setTextColor(Color.WHITE);
                t.setBackgroundColor(Ui.CARD);
                t.setPadding(30, 24, 30, 24);
                return t;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private AlertDialog.Builder dialog(String title) {
        return new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(title);
    }

    // ------------------------------------------------------------------
    // Construction de l'interface
    // ------------------------------------------------------------------
    private void buildUi() {
        int pad = Ui.dp(this, 16);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(root);

        TextView back = Ui.text(this, "‹ Accueil", 15, Ui.ACCENT, true);
        back.setPadding(0, 0, 0, Ui.dp(this, 10));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        root.addView(Ui.text(this, "Studio", 24, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Créez un beat, enregistrez ou importez un son dessus, mélangez, exportez", 13, Ui.MUTED, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 14));
        root.addView(sub);

        // --- Projet
        LinearLayout pc = card("Projet");
        nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        nameEdit.setTextColor(Color.WHITE);
        nameEdit.setHint("Nom du projet");
        nameEdit.setHintTextColor(Ui.MUTED);
        nameEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String n = s.toString();
                if (!n.equals(m.name)) { m.name = n; changed(); }
            }
        });
        pc.addView(nameEdit);
        LinearLayout pr = Ui.row(this);
        Button projBtn = Ui.button(this, "Mes projets", OFF);
        projBtn.setOnClickListener(v -> showProjects());
        Button newBtn = Ui.button(this, "Nouveau", OFF);
        newBtn.setOnClickListener(v -> newDialog());
        pr.addView(projBtn, Ui.weight(this, Ui.dp(this, 3)));
        pr.addView(newBtn, Ui.weight(this, Ui.dp(this, 3)));
        pc.addView(pr);
        pc.addView(hint("Le projet est sauvegardé automatiquement."));
        root.addView(pc);

        // --- Rythme
        LinearLayout rc = card("Rythme");
        bpmLabel = label("");
        rc.addView(bpmLabel);
        bpmSeek = new SeekBar(this);
        bpmSeek.setMax(160);
        bpmSeek.setOnSeekBarChangeListener(new Seek(p -> {
            m.bpm = 40 + p;
            updateRhythmLabels();
            changed();
        }));
        rc.addView(bpmSeek);
        rc.addView(label("Mesure (le temps)"));
        LinearLayout rr = Ui.row(this);
        sigSpin = spinner(new String[]{"2/4", "3/4", "4/4", "5/4", "6/4"});
        sigSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (pos + 2 == m.beatsPerBar) return;
                m.beatsPerBar = pos + 2;
                changed();
                rebuildEditor();
            }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        rr.addView(sigSpin, new LinearLayout.LayoutParams(0, -2, 1f));
        Button barsMinus = mini("−", OFF);
        barsMinus.setOnClickListener(v -> changeBars(-1));
        barsLabel = Ui.text(this, "", 14, Ui.TEXT, true);
        barsLabel.setGravity(Gravity.CENTER);
        barsLabel.setMinWidth(Ui.dp(this, 96));
        Button barsPlus = mini("+", OFF);
        barsPlus.setOnClickListener(v -> changeBars(1));
        rr.addView(barsMinus);
        rr.addView(barsLabel);
        rr.addView(barsPlus);
        rc.addView(rr);
        root.addView(rc);

        // --- Accords
        LinearLayout cc = card("Accords");
        cc.addView(label("Tonalité et suite d'accords"));
        LinearLayout cr = Ui.row(this);
        keySpin = spinner(BeatModel.NOTES);
        progSpin = spinner(BeatModel.PROG_NAMES);
        cr.addView(keySpin, new LinearLayout.LayoutParams(0, -2, 1f));
        cr.addView(progSpin, new LinearLayout.LayoutParams(0, -2, 3f));
        cc.addView(cr);
        Button applyProg = Ui.button(this, "Appliquer cette suite d'accords", 0xFF2C3A4A);
        applyProg.setOnClickListener(v -> {
            m.applyProgression(keySpin.getSelectedItemPosition(), progSpin.getSelectedItemPosition());
            rebuildChords();
            rebuildArrangement();
            changed();
        });
        cc.addView(applyProg, lp(-1, -2, 0, Ui.dp(this, 8), 0, Ui.dp(this, 8)));
        chordBox = new LinearLayout(this);
        chordBox.setOrientation(LinearLayout.VERTICAL);
        cc.addView(chordBox);
        cc.addView(hint("Touchez un accord pour le changer (note et type)."));
        root.addView(cc);

        // --- Arrangement
        LinearLayout ac = card("Qui joue où ?");
        arrBox = new LinearLayout(this);
        arrBox.setOrientation(LinearLayout.VERTICAL);
        ac.addView(arrBox);
        ac.addView(hint("Touchez une case : l'instrument joue (couleur) ou fait une pause (gris) dans cette mesure. "
                + "Touchez le nom d'un instrument pour choisir à quelle mesure il commence."));
        root.addView(ac);

        // --- Instruments
        LinearLayout ic = card("Instruments");
        LinearLayout tr = Ui.row(this);
        tr.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabBtns[i] = mini(BeatModel.TRACKS[i], OFF);
            tabBtns[i].setTextSize(13);
            tabBtns[i].setOnClickListener(v -> { tab = idx; rebuildEditor(); });
            tr.addView(tabBtns[i], Ui.weight(this, Ui.dp(this, 2)));
        }
        ic.addView(tr);
        editorBox = new LinearLayout(this);
        editorBox.setOrientation(LinearLayout.VERTICAL);
        ic.addView(editorBox);
        root.addView(ic);

        // --- Lecture / enregistrement
        LinearLayout pl = card("Écouter et enregistrer sur le beat");
        playBtn = Ui.button(this, "▶ Écouter le beat", 0xFF1E8449);
        playBtn.setOnClickListener(v -> {
            if (mode == BEAT) stopAll(); else startPlayback(BEAT, null);
        });
        pl.addView(playBtn, lp(-1, -2, 0, Ui.dp(this, 10), 0, 0));
        timeText = Ui.text(this, "0:00", 28, Ui.TEXT, true);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, Ui.dp(this, 10), 0, 0);
        pl.addView(timeText);
        statusText = Ui.text(this, "", 12, Ui.MUTED, false);
        statusText.setGravity(Gravity.CENTER);
        pl.addView(statusText);

        Switch countSw = new Switch(this);
        countSw.setText("Décompte d'une mesure avant d'enregistrer");
        countSw.setTextColor(Ui.TEXT);
        countSw.setChecked(prefs.getBoolean("countin", true));
        countSw.setOnCheckedChangeListener((CompoundButton c, boolean on) -> prefs.edit().putBoolean("countin", on).apply());
        countSw.setPadding(0, Ui.dp(this, 12), 0, 0);
        pl.addView(countSw);

        Switch hearSw = new Switch(this);
        hearSw.setText("Entendre aussi les sons déjà ajoutés");
        hearSw.setTextColor(Ui.TEXT);
        hearSw.setChecked(prefs.getBoolean("hear_clips", false));
        hearSw.setOnCheckedChangeListener((CompoundButton c, boolean on) -> prefs.edit().putBoolean("hear_clips", on).apply());
        hearSw.setPadding(0, Ui.dp(this, 8), 0, 0);
        pl.addView(hearSw);

        syncLabel = label("");
        pl.addView(syncLabel);
        SeekBar syncSeek = new SeekBar(this);
        syncSeek.setMax(400);
        syncSeek.setProgress(prefs.getInt("sync", 100));
        syncSeek.setOnSeekBarChangeListener(new Seek(p -> {
            prefs.edit().putInt("sync", p).apply();
            updateSyncLabel();
        }));
        pl.addView(syncSeek);
        updateSyncLabel();

        recBtn = Ui.button(this, "● Enregistrer sur le beat", Ui.RED);
        recBtn.setOnClickListener(v -> {
            if (mode == REC) stopAll(); else startPlayback(REC, null);
        });
        pl.addView(recBtn, lp(-1, -2, 0, Ui.dp(this, 12), 0, 0));
        pl.addView(hint("Mettez un casque : sinon le micro reprend le beat du haut-parleur. "
                + "À l'arrêt (ou à la fin du beat), votre enregistrement est ajouté et mélangé au beat. "
                + "Si votre voix est en retard ou en avance sur le beat, réglez la synchronisation."));
        root.addView(pl);

        // --- Sons ajoutés
        LinearLayout sc = card("Sons ajoutés (enregistrés ou importés)");
        Button impBtn = Ui.button(this, "＋ Importer un audio (mp3, amr, wav…)", 0xFF2C3A4A);
        impBtn.setOnClickListener(v -> pickAudio());
        sc.addView(impBtn, lp(-1, -2, 0, Ui.dp(this, 10), 0, 0));
        clipsBox = new LinearLayout(this);
        clipsBox.setOrientation(LinearLayout.VERTICAL);
        sc.addView(clipsBox);
        root.addView(sc);

        // --- Mixage final
        LinearLayout mc = card("Mixage final");
        mixBtn = Ui.button(this, "▶ Écouter le mix", 0xFF1E8449);
        mixBtn.setOnClickListener(v -> {
            if (mode == MIX) stopAll(); else startPlayback(MIX, null);
        });
        mc.addView(mixBtn, lp(-1, -2, 0, Ui.dp(this, 10), 0, 0));
        Button expBtn = Ui.button(this, "Exporter le mix…", Ui.ACCENT);
        expBtn.setTextColor(Color.BLACK);
        expBtn.setOnClickListener(v -> exportDialog());
        mc.addView(expBtn, lp(-1, -2, 0, Ui.dp(this, 8), 0, 0));
        Button pexp = Ui.button(this, "Exporter le projet (fichier)", OFF);
        pexp.setOnClickListener(v -> startProjectExport(m.id, m.name));
        mc.addView(pexp, lp(-1, -2, 0, Ui.dp(this, 8), 0, 0));
        Button pimp = Ui.button(this, "Importer un projet", OFF);
        pimp.setOnClickListener(v -> pickProject());
        mc.addView(pimp, lp(-1, -2, 0, Ui.dp(this, 8), 0, 0));
        mc.addView(hint("Le mix = le beat + tous les sons ajoutés. Pour modifier : supprimez un son (✕), changez le beat, "
                + "puis réenregistrez. L'export est aussi visible dans l'écran « Enregistrer »."));
        root.addView(mc);

        setContentView(sv);
    }

    private void applyModelToUi() {
        prefs.edit().putString("last", m.id).apply();
        nameEdit.setText(m.name);
        bpmSeek.setProgress(m.bpm - 40);
        sigSpin.setSelection(m.beatsPerBar - 2);
        keySpin.setSelection(m.key);
        progSpin.setSelection(m.prog);
        updateRhythmLabels();
        rebuildChords();
        rebuildArrangement();
        rebuildEditor();
        rebuildClips();
        updateButtons();
    }

    private void updateRhythmLabels() {
        bpmLabel.setText(String.format(Locale.US, "Tempo : %d battements par minute", m.bpm));
        barsLabel.setText(m.bars + (m.bars > 1 ? " mesures" : " mesure"));
    }

    private void updateSyncLabel() {
        syncLabel.setText(String.format(Locale.US, "Synchronisation du micro : %d ms", prefs.getInt("sync", 100)));
    }

    private void changeBars(int d) {
        int nb = Math.max(1, Math.min(BeatModel.MAXBARS, m.bars + d));
        if (nb == m.bars) return;
        m.bars = nb;
        updateRhythmLabels();
        rebuildChords();
        rebuildArrangement();
        changed();
    }

    // ------------------------------------------------------------------
    // Grille générique (noms à gauche, cases qui défilent à droite)
    // ------------------------------------------------------------------
    private LinearLayout buildGrid(String[] rowLabels, int cols, String[] heads, int cellW, int headH, int group,
                                   Cell cell, IntCb labelClick, TextView[] headOut) {
        final int cellH = Ui.dp(this, 36), mg = Ui.dp(this, 2), nameW = Ui.dp(this, 88), gap = Ui.dp(this, 6);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(new View(this), lp(nameW, headH, 0, mg, gap, mg));
        for (int r = 0; r < rowLabels.length; r++) {
            final int rr = r;
            TextView t = Ui.text(this, rowLabels[r], 12, Ui.TEXT, true);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setSingleLine(true);
            if (labelClick != null) {
                t.setBackground(Ui.rounded(OFF, Ui.dp(this, 8)));
                t.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 4), 0);
                t.setOnClickListener(v -> labelClick.on(rr));
            }
            names.addView(t, lp(nameW, cellH, 0, mg, gap, mg));
        }
        wrap.addView(names);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout hr = new LinearLayout(this);
        hr.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = 0; c < cols; c++) {
            TextView h = Ui.text(this, heads[c], 10, Ui.MUTED, false);
            h.setGravity(Gravity.CENTER);
            if (headOut != null && c < headOut.length) headOut[c] = h;
            int left = mg + ((group > 0 && c > 0 && c % group == 0) ? Ui.dp(this, 5) : 0);
            hr.addView(h, lp(cellW, headH, left, mg, mg, mg));
        }
        col.addView(hr);
        for (int r = 0; r < rowLabels.length; r++) {
            final int rr = r;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                final int cc = c;
                final TextView t = new TextView(this);
                t.setGravity(Gravity.CENTER);
                t.setTextSize(13);
                t.setTextColor(Color.WHITE);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                cell.style(t, rr, cc);
                t.setOnClickListener(v -> cell.click(t, rr, cc));
                int left = mg + ((group > 0 && c > 0 && c % group == 0) ? Ui.dp(this, 5) : 0);
                row.addView(t, lp(cellW, cellH, left, mg, mg, mg));
            }
            col.addView(row);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(col);
        wrap.addView(hs, new LinearLayout.LayoutParams(0, -2, 1f));
        return wrap;
    }

    // ------------------------------------------------------------------
    // Accords
    // ------------------------------------------------------------------
    private void rebuildChords() {
        chordBox.removeAllViews();
        int per = 4;
        for (int start = 0; start < m.bars; start += per) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < per; k++) {
                final int bar = start + k;
                if (bar < m.bars) {
                    TextView t = Ui.text(this, "Mes. " + (bar + 1) + "\n" + m.chordName(bar), 14, Ui.TEXT, true);
                    t.setGravity(Gravity.CENTER);
                    t.setBackground(Ui.rounded(0xFF22303F, Ui.dp(this, 10)));
                    t.setOnClickListener(v -> editChord(bar));
                    LinearLayout.LayoutParams tp = lp(0, Ui.dp(this, 58), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
                    tp.weight = 1f;
                    row.addView(t, tp);
                } else {
                    View v = new View(this);
                    LinearLayout.LayoutParams p = lp(0, Ui.dp(this, 58), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
                    p.weight = 1f;
                    row.addView(v, p);
                }
            }
            chordBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void editChord(final int bar) {
        dialog("Mesure " + (bar + 1) + " : note de l'accord")
                .setItems(BeatModel.NOTES, (d, w) -> pickChordType(bar, w))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void pickChordType(final int bar, final int root) {
        String[] items = new String[BeatModel.QUAL_SUF.length];
        for (int i = 0; i < items.length; i++)
            items[i] = BeatModel.NOTES[root] + BeatModel.QUAL_SUF[i] + "   (" + BeatModel.QUAL_NAMES[i] + ")";
        dialog("Type d'accord")
                .setItems(items, (d, w) -> {
                    m.chordRoot[bar] = root;
                    m.chordType[bar] = w;
                    rebuildChords();
                    rebuildArrangement();
                    changed();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Arrangement : qui joue dans quelle mesure
    // ------------------------------------------------------------------
    private void rebuildArrangement() {
        arrBox.removeAllViews();
        String[] heads = new String[m.bars];
        for (int b = 0; b < m.bars; b++) heads[b] = (b + 1) + "\n" + m.chordName(b);
        barHeads = new TextView[m.bars];
        arrBox.addView(buildGrid(BeatModel.TRACKS, m.bars, heads, Ui.dp(this, 44), Ui.dp(this, 32), 0,
                new Cell() {
                    @Override public void style(TextView c, int r, int col) {
                        c.setBackground(Ui.rounded(m.play[r][col] ? TCOL[r] : OFF, Ui.dp(StudioActivity.this, 8)));
                        c.setText(m.play[r][col] ? "♪" : "");
                    }
                    @Override public void click(TextView c, int r, int col) {
                        m.play[r][col] = !m.play[r][col];
                        style(c, r, col);
                        changed();
                    }
                },
                r -> instrumentMenu(r), barHeads));
    }

    private void instrumentMenu(final int t) {
        dialog(BeatModel.TRACKS[t])
                .setItems(new String[]{"Jouer dans toutes les mesures", "Commencer à la mesure…", "Pause dans toutes les mesures"},
                        (d, w) -> {
                            if (w == 0) setPlayFrom(t, 0);
                            else if (w == 2) setPlayFrom(t, BeatModel.MAXBARS);
                            else pickStartBar(t);
                        })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void pickStartBar(final int t) {
        String[] items = new String[m.bars];
        for (int i = 0; i < items.length; i++) items[i] = "Mesure " + (i + 1);
        dialog(BeatModel.TRACKS[t] + " commence à…")
                .setItems(items, (d, w) -> setPlayFrom(t, w))
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** L'instrument joue à partir de la mesure firstBar (0 = partout, MAXBARS = nulle part). */
    private void setPlayFrom(int t, int firstBar) {
        for (int b = 0; b < BeatModel.MAXBARS; b++) m.play[t][b] = b >= firstBar;
        rebuildArrangement();
        changed();
    }

    private void highlightBar(int bar) {
        for (int i = 0; i < barHeads.length; i++) {
            if (barHeads[i] == null) continue;
            barHeads[i].setTextColor(i == bar ? Ui.ACCENT : Ui.MUTED);
            barHeads[i].setTypeface(i == bar ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
    }

    // ------------------------------------------------------------------
    // Éditeurs d'instruments
    // ------------------------------------------------------------------
    private void rebuildEditor() {
        editorBox.removeAllViews();
        for (int i = 0; i < 4; i++) {
            tabBtns[i].setBackground(Ui.rounded(i == tab ? TCOL[i] : OFF, Ui.dp(this, 14)));
        }
        final int spb = m.stepsPerBar();
        String[] heads = new String[spb];
        for (int s = 0; s < spb; s++) heads[s] = (s % 4 == 0) ? String.valueOf(s / 4 + 1) : "·";
        final int cw = Ui.dp(this, 34), hh = Ui.dp(this, 20), rad = Ui.dp(this, 7);

        switch (tab) {
            case BeatModel.DRUMS:
                editorBox.addView(buildGrid(BeatModel.DRUM_ROWS, spb, heads, cw, hh, 4, new Cell() {
                    @Override public void style(TextView c, int r, int col) {
                        c.setBackground(Ui.rounded(m.drum[r][col] ? TCOL[0] : (col % 4 == 0 ? OFF_BEAT : OFF), rad));
                    }
                    @Override public void click(TextView c, int r, int col) {
                        m.drum[r][col] = !m.drum[r][col];
                        style(c, r, col);
                        changed();
                    }
                }, null, null));
                editorBox.addView(hint("Touchez les cases pour poser les coups. Chaque colonne est une double-croche ; "
                        + "les chiffres sont les temps de la mesure."));
                break;

            case BeatModel.BASS:
                editorBox.addView(buildGrid(new String[]{"Basse"}, spb, heads, cw, hh, 4, new Cell() {
                    @Override public void style(TextView c, int r, int col) {
                        int v = m.bassStep[col];
                        c.setBackground(Ui.rounded(v > 0 ? TCOL[1] : (col % 4 == 0 ? OFF_BEAT : OFF), rad));
                        c.setText(v == 1 ? "R" : v == 2 ? "5" : v == 3 ? "8" : "");
                    }
                    @Override public void click(TextView c, int r, int col) {
                        m.bassStep[col] = (m.bassStep[col] + 1) % 4;
                        style(c, r, col);
                        changed();
                    }
                }, null, null));
                editorBox.addView(hint("Touchez une case pour changer la note : R = note de l'accord, 5 = quinte, "
                        + "8 = octave, vide = silence."));
                break;

            case BeatModel.PIANO:
                editorBox.addView(label("Style du piano"));
                Spinner ps = spinner(BeatModel.PIANO_STYLES);
                ps.setSelection(m.pianoStyle);
                ps.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                        if (pos == m.pianoStyle) return;
                        m.pianoStyle = pos;
                        changed();
                    }
                    @Override public void onNothingSelected(AdapterView<?> a) {}
                });
                editorBox.addView(ps);
                editorBox.addView(buildGrid(new String[]{"Piano"}, spb, heads, cw, hh, 4, new Cell() {
                    @Override public void style(TextView c, int r, int col) {
                        c.setBackground(Ui.rounded(m.pianoHit[col] ? TCOL[2] : (col % 4 == 0 ? OFF_BEAT : OFF), rad));
                        c.setText(m.pianoHit[col] ? "♪" : "");
                    }
                    @Override public void click(TextView c, int r, int col) {
                        m.pianoHit[col] = !m.pianoHit[col];
                        style(c, r, col);
                        changed();
                    }
                }, null, null));
                editorBox.addView(hint("Case allumée : le piano joue l'accord de la mesure. Case éteinte : pause. "
                        + "Le style choisit comment l'accord est joué (plaqué ou arpège)."));
                break;

            default:
                editorBox.addView(buildGrid(new String[]{"Solo"}, spb, heads, cw, hh, 4, new Cell() {
                    @Override public void style(TextView c, int r, int col) {
                        int v = m.soloStep[col];
                        c.setBackground(Ui.rounded(v > 0 ? TCOL[3] : (col % 4 == 0 ? OFF_BEAT : OFF), rad));
                        c.setText(v > 0 ? String.valueOf(v) : "");
                    }
                    @Override public void click(TextView c, int r, int col) {
                        m.soloStep[col] = (m.soloStep[col] + 1) % 7;
                        style(c, r, col);
                        changed();
                    }
                }, null, null));
                editorBox.addView(hint("Touchez une case pour changer la note : 1 à 6 = notes de la gamme de l'accord "
                        + "(1 = note de base, 6 = octave aiguë), vide = silence. Le solo suit les accords."));
                break;
        }

        // Éditeur libre (notes, roulements, grille fine)
        Button free = Ui.button(this, tab == BeatModel.DRUMS ? "Composer librement (roulements, temps)" : "Composer librement (notes du " + BeatModel.TRACKS[tab].toLowerCase() + ")", TCOL[tab]);
        free.setOnClickListener(v -> {
            flushSave();
            needReload = true;
            Intent it = new Intent(this, ComposeActivity.class);
            it.putExtra("id", m.id);
            it.putExtra("track", tab);
            it.putExtra("bar", 0);
            startActivity(it);
        });
        editorBox.addView(free, lp(-1, -2, 0, Ui.dp(this, 10), 0, 0));
        editorBox.addView(hint("Place chaque note à la main, sur une grille fine (jusqu'à la triple-croche et aux triolets), "
                + "mesure par mesure. Les mesures composées remplacent le motif ci-dessus."));

        // Volume + effacer
        final TextView volLabel = label("");
        editorBox.addView(volLabel);
        volLabel.setText(String.format(Locale.US, "Volume %s : %d %%", BeatModel.TRACKS[tab], Math.round(m.vol[tab] * 100)));
        SeekBar vs = new SeekBar(this);
        vs.setMax(100);
        vs.setProgress(Math.round(m.vol[tab] * 100));
        final int tt = tab;
        vs.setOnSeekBarChangeListener(new Seek(p -> {
            m.vol[tt] = p / 100f;
            volLabel.setText(String.format(Locale.US, "Volume %s : %d %%", BeatModel.TRACKS[tt], p));
            changed();
        }));
        editorBox.addView(vs);
        Button clear = Ui.button(this, "Effacer ce motif", OFF);
        clear.setOnClickListener(v -> {
            switch (tt) {
                case BeatModel.DRUMS: for (int r = 0; r < 5; r++) java.util.Arrays.fill(m.drum[r], false); break;
                case BeatModel.BASS: java.util.Arrays.fill(m.bassStep, 0); break;
                case BeatModel.PIANO: java.util.Arrays.fill(m.pianoHit, false); break;
                default: java.util.Arrays.fill(m.soloStep, 0); break;
            }
            rebuildEditor();
            changed();
        });
        editorBox.addView(clear, lp(-1, -2, 0, Ui.dp(this, 10), 0, 0));
    }

    // ------------------------------------------------------------------
    // Sons ajoutés
    // ------------------------------------------------------------------
    private String fmtDur(int samples) {
        int s = samples / Synth.RATE;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void rebuildClips() {
        clipsBox.removeAllViews();
        if (m.clips.isEmpty()) {
            clipsBox.addView(hint("Aucun son pour l'instant. Enregistrez sur le beat ou importez un fichier audio."));
            return;
        }
        for (final BeatModel.Clip c : m.clips) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(0, Ui.dp(this, 12), 0, 0);

            LinearLayout r1 = Ui.row(this);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            info.addView(Ui.text(this, c.name, 14, c.mute ? Ui.MUTED : Ui.TEXT, true));
            info.addView(Ui.text(this, fmtDur(c.samples) + (c.mute ? " · coupé" : ""), 12, Ui.MUTED, false));
            boolean playing = mode == CLIP && c.id.equals(playingClipId);
            Button play = mini(playing ? "■" : "▶", playing ? Ui.ORANGE : Ui.GREEN);
            play.setOnClickListener(v -> {
                if (mode == CLIP && c.id.equals(playingClipId)) stopAll(); else startPlayback(CLIP, c);
            });
            Button mute = mini(c.mute ? "🔇" : "🔊", OFF);
            mute.setOnClickListener(v -> { c.mute = !c.mute; rebuildClips(); changed(); });
            Button del = mini("✕", 0xFF7F1D1D);
            del.setOnClickListener(v -> confirmDeleteClip(c));
            r1.addView(info);
            r1.addView(play, lp(-2, -2, Ui.dp(this, 6), 0, 0, 0));
            r1.addView(mute, lp(-2, -2, Ui.dp(this, 6), 0, 0, 0));
            r1.addView(del, lp(-2, -2, Ui.dp(this, 6), 0, 0, 0));
            box.addView(r1);

            LinearLayout r2 = Ui.row(this);
            r2.setPadding(0, Ui.dp(this, 6), 0, 0);
            final TextView startLbl = Ui.text(this, "Début : mesure " + (c.startBar + 1), 12, Ui.MUTED, false);
            startLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            Button sm = mini("−", OFF);
            sm.setOnClickListener(v -> {
                if (c.startBar > 0) { c.startBar--; startLbl.setText("Début : mesure " + (c.startBar + 1)); changed(); }
            });
            Button sp = mini("+", OFF);
            sp.setOnClickListener(v -> {
                if (c.startBar < m.bars - 1) { c.startBar++; startLbl.setText("Début : mesure " + (c.startBar + 1)); changed(); }
            });
            r2.addView(startLbl);
            r2.addView(sm);
            r2.addView(sp, lp(-2, -2, Ui.dp(this, 6), 0, 0, 0));
            box.addView(r2);

            LinearLayout r3 = Ui.row(this);
            r3.setPadding(0, Ui.dp(this, 6), 0, 0);
            final TextView volLbl = Ui.text(this, "Volume : " + Math.round(c.vol * 100) + " %", 12, Ui.MUTED, false);
            volLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            Button vm = mini("−", OFF);
            vm.setOnClickListener(v -> {
                c.vol = Math.max(0f, Math.round(c.vol * 10) / 10f - 0.1f);
                volLbl.setText("Volume : " + Math.round(c.vol * 100) + " %");
                changed();
            });
            Button vp = mini("+", OFF);
            vp.setOnClickListener(v -> {
                c.vol = Math.min(2f, Math.round(c.vol * 10) / 10f + 0.1f);
                volLbl.setText("Volume : " + Math.round(c.vol * 100) + " %");
                changed();
            });
            r3.addView(volLbl);
            r3.addView(vm);
            r3.addView(vp, lp(-2, -2, Ui.dp(this, 6), 0, 0, 0));
            box.addView(r3);

            View sep = new View(this);
            sep.setBackgroundColor(0xFF22303F);
            box.addView(sep, lp(-1, 2, 0, Ui.dp(this, 12), 0, 0));
            clipsBox.addView(box);
        }
    }

    private String playingClipId = "";

    private void confirmDeleteClip(final BeatModel.Clip c) {
        dialog("Supprimer ce son ?")
                .setMessage("« " + c.name + " » sera retiré du mix.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    stopAll();
                    m.clips.remove(c);
                    new File(StudioStore.dirOf(this, m.id), c.file).delete();
                    rebuildClips();
                    flushSave();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Lecture
    // ------------------------------------------------------------------
    private void updateButtons() {
        playBtn.setText(mode == BEAT ? "■ Arrêter" : "▶ Écouter le beat");
        playBtn.setBackground(Ui.rounded(mode == BEAT ? Ui.ORANGE : 0xFF1E8449, Ui.dp(this, 14)));
        recBtn.setText(mode == REC ? "■ Stop (mixer)" : "● Enregistrer sur le beat");
        recBtn.setBackground(Ui.rounded(mode == REC ? 0xFF7F1D1D : Ui.RED, Ui.dp(this, 14)));
        mixBtn.setText(mode == MIX ? "■ Arrêter" : "▶ Écouter le mix");
        mixBtn.setBackground(Ui.rounded(mode == MIX ? Ui.ORANGE : 0xFF1E8449, Ui.dp(this, 14)));
        playBtn.setEnabled(!renderBusy);
        recBtn.setEnabled(!renderBusy);
        mixBtn.setEnabled(!renderBusy);
        if (mode == IDLE) { timeText.setText("0:00"); highlightBar(-1); }
    }

    /** Arrête tout : lecture, enregistrement (qui est alors mixé). */
    private void stopAll() {
        if (mode == REC) finishRecording();
        PcmPlayer p = player;
        player = null;
        mode = IDLE;
        playingClipId = "";
        if (p != null) p.stop();
        if (playBtn != null) { updateButtons(); rebuildClipsIfReady(); }
    }

    private void rebuildClipsIfReady() { if (clipsBox != null && m != null) rebuildClips(); }

    private void startPlayback(final int kind, final BeatModel.Clip only) {
        if (renderBusy) return;
        if (kind == REC && !hasMic()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        stopAll();
        renderBusy = true;
        setStatus("Préparation du son…");
        updateButtons();
        final BeatModel mm = m;
        final boolean hear = prefs.getBoolean("hear_clips", false);
        final boolean countIn = prefs.getBoolean("countin", true);
        new Thread(() -> {
            short[] buf = null;
            int pre = 0;
            String err = null;
            try {
                File dir = StudioStore.dirOf(this, mm.id);
                if (kind == CLIP) {
                    WavIO.Mono w = WavIO.readWav(new File(dir, only.file), 60 * 60 * WavIO.RATE);
                    buf = w.rate == WavIO.RATE ? w.data : WavIO.resample(w.data, w.len, w.rate, WavIO.RATE);
                } else {
                    float[] f;
                    if (kind == BEAT) {
                        f = BeatRenderer.renderBeat(mm);
                    } else if (kind == MIX) {
                        f = BeatRenderer.mixAll(mm, dir, true, true);
                    } else {
                        f = BeatRenderer.mixAll(mm, dir, true, hear);
                        if (countIn) {
                            f = BeatRenderer.withCountIn(mm, f);
                            pre = BeatRenderer.countInSamples(mm);
                        }
                    }
                    buf = BeatRenderer.toShorts(f);
                }
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final short[] fb = buf;
            final int fpre = pre;
            final String ferr = err;
            ui.post(() -> {
                renderBusy = false;
                if (fb == null || isFinishing()) {
                    setStatus(fb == null ? "Erreur : " + ferr : "");
                    updateButtons();
                    return;
                }
                launch(kind, fb, fpre, only);
            });
        }).start();
    }

    private void launch(int kind, short[] buf, int pre, BeatModel.Clip only) {
        final PcmPlayer p = new PcmPlayer(buf, buf.length, kind == BEAT);
        p.setOnEnd(() -> ui.post(() -> { if (p == player) onPlayEnd(); }));
        if (kind == REC) {
            if (!startRecorder()) {
                setStatus("Impossible d'ouvrir le micro");
                updateButtons();
                return;
            }
            recCountIn = pre;
            recSync = prefs.getInt("sync", 100) * Synth.RATE / 1000;
        }
        player = p;
        mode = kind;
        prefixFrames = pre;
        playingClipId = (kind == CLIP && only != null) ? only.id : "";
        if (!p.start()) {
            player = null;
            mode = IDLE;
            if (kind == REC) abortRecorder();
            toast("Lecture impossible");
            updateButtons();
            return;
        }
        setStatus(kind == REC ? "Enregistrement en cours… jouez ou chantez sur le beat"
                : kind == BEAT ? "Le beat tourne en boucle" : kind == MIX ? "Lecture du mix" : "Lecture du son");
        updateButtons();
        if (kind == CLIP) rebuildClips();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void onPlayEnd() {
        int k = mode;
        player = null;
        mode = IDLE;
        playingClipId = "";
        setStatus("");
        updateButtons();
        rebuildClips();
        if (k == REC) finishRecording();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            PcmPlayer p = player;
            if (mode == IDLE || p == null) return;
            long pos = p.positionFrames();
            if (mode == REC && pos < prefixFrames) {
                timeText.setText("Décompte…");
                highlightBar(-1);
            } else {
                long rel = Math.max(0, pos - (mode == REC ? prefixFrames : 0));
                long sec = rel / Synth.RATE;
                timeText.setText(String.format(Locale.US, "%d:%02d", sec / 60, sec % 60));
                if (mode == BEAT || mode == REC || mode == MIX) {
                    int per = Math.max(1, BeatRenderer.samplesPerBar(m));
                    int bar = (int) (rel / per);
                    highlightBar(bar < m.bars ? bar : -1);
                } else highlightBar(-1);
            }
            ui.postDelayed(this, 100);
        }
    };

    // ------------------------------------------------------------------
    // Enregistrement sur le beat
    // ------------------------------------------------------------------
    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startPlayback(REC, null);
        else toast("Autorisez le micro dans les réglages pour enregistrer");
    }

    private AudioDeviceInfo findExternalInput() {
        if (Build.VERSION.SDK_INT < 23) return null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if (AudioEngine.isExternalType(d.getType())) return d;
        return null;
    }

    private boolean startRecorder() {
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord(MediaRecorder.AudioSource.MIC, Synth.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16384));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return false; }
            AudioDeviceInfo ext = findExternalInput();
            if (ext != null) r.setPreferredDevice(ext);
            rawFile = new File(getCacheDir(), "studio_rec.raw");
            final FileOutputStream fos = new FileOutputStream(rawFile);
            final AudioRecord rr = r;
            rec = r;
            recRun = true;
            r.startRecording();
            recThread = new Thread(() -> {
                short[] b = new short[2048];
                byte[] bb = new byte[4096];
                try {
                    while (recRun) {
                        int n = rr.read(b, 0, b.length);
                        if (n <= 0) { if (n < 0) break; continue; }
                        for (int i = 0; i < n; i++) {
                            bb[2 * i] = (byte) (b[i] & 0xff);
                            bb[2 * i + 1] = (byte) ((b[i] >> 8) & 0xff);
                        }
                        fos.write(bb, 0, n * 2);
                    }
                } catch (Exception ignored) {
                } finally {
                    try { rr.stop(); } catch (Exception ignored2) {}
                    rr.release();
                    try { fos.close(); } catch (Exception ignored3) {}
                }
            }, "studio-rec");
            recThread.start();
            return true;
        } catch (Exception e) {
            recRun = false;
            if (r != null) { try { r.release(); } catch (Exception ignored) {} }
            return false;
        }
    }

    private void abortRecorder() {
        recRun = false;
        if (recThread != null) { try { recThread.join(1500); } catch (InterruptedException ignored) {} }
        if (rawFile != null) rawFile.delete();
    }

    /** Fin de l'enregistrement : on coupe le micro, on recale le son sur le beat et on l'ajoute au mix. */
    private void finishRecording() {
        if (!recRun) return;
        recRun = false;
        final Thread t = recThread;
        final File raw = rawFile;
        final int countIn = recCountIn, sync = recSync;
        final BeatModel mm = m;
        final int limit = BeatRenderer.totalSamples(mm);
        setStatus("Mixage de l'enregistrement…");
        new Thread(() -> {
            String err = null;
            BeatModel.Clip clip = null;
            try {
                if (t != null) t.join(3000);
                int total = (int) (raw.length() / 2);
                int skip = countIn + sync;
                if (total <= skip + Synth.RATE / 10) throw new IOException("Rien n'a été enregistré");
                int n = Math.min(total - skip, limit);
                short[] pcm = new short[n];
                try (RandomAccessFile raf = new RandomAccessFile(raw, "r")) {
                    raf.seek(skip * 2L);
                    byte[] bb = new byte[8192];
                    int done = 0;
                    while (done < n) {
                        int want = Math.min(4096, n - done);
                        raf.readFully(bb, 0, want * 2);
                        for (int i = 0; i < want; i++)
                            pcm[done + i] = (short) ((bb[2 * i] & 0xff) | (bb[2 * i + 1] << 8));
                        done += want;
                    }
                }
                String cid = "c" + System.currentTimeMillis();
                File out = new File(StudioStore.dirOf(this, mm.id), cid + ".wav");
                WavIO.writeMono16(out, pcm, n);
                clip = new BeatModel.Clip();
                clip.id = cid;
                clip.file = out.getName();
                clip.samples = n;
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            raw.delete();
            final BeatModel.Clip fc = clip;
            final String ferr = err;
            ui.post(() -> {
                if (fc == null) {
                    setStatus("");
                    toast("Enregistrement non ajouté : " + ferr);
                    return;
                }
                fc.name = nextName(mm, "Enregistrement");
                mm.clips.add(fc);
                StudioStore.save(this, mm);
                setStatus("Enregistrement ajouté au mix.");
                if (mm == m) rebuildClips();
                if (!isFinishing()) {
                    dialog("Enregistrement terminé")
                            .setMessage("Votre enregistrement est mélangé au beat. Voulez-vous écouter le mix ?")
                            .setPositiveButton("Écouter le mix", (d, w) -> startPlayback(MIX, null))
                            .setNegativeButton("Plus tard", null)
                            .show();
                }
            });
        }).start();
    }

    private String nextName(BeatModel mm, String base) {
        int n = 1;
        boolean used = true;
        while (used) {
            used = false;
            for (BeatModel.Clip c : mm.clips) if (c.name.equals(base + " " + n)) { used = true; n++; break; }
        }
        return base + " " + n;
    }

    // ------------------------------------------------------------------
    // Import d'un fichier audio
    // ------------------------------------------------------------------
    private void pickAudio() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/ogg", "application/octet-stream"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Choisir un fichier audio"), REQ_IMPORT);
        } catch (Exception e) {
            toast("Aucun gestionnaire de fichiers trouvé");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PEXP && res == RESULT_OK && data != null && data.getData() != null)
            doProjectExport(data.getData(), pendingExportId);
        if (req == REQ_PIMP && res == RESULT_OK && data != null && data.getData() != null)
            doProjectImport(data.getData());
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null)
            importAudio(data.getData());
    }

    private String displayName(Uri uri) {
        String n = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) {}
        if (n == null || n.isEmpty()) n = uri.getLastPathSegment();
        if (n == null || n.isEmpty()) n = "Son importé";
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n;
    }

    private void importAudio(final Uri uri) {
        stopAll();
        final String name = displayName(uri);
        final BeatModel mm = m;
        setStatus("Import de « " + name + " »…");
        toast("Import en cours, un instant…");
        new Thread(() -> {
            String err = null;
            BeatModel.Clip clip = null;
            try {
                String cid = "c" + System.currentTimeMillis();
                File out = new File(StudioStore.dirOf(this, mm.id), cid + ".wav");
                int n = AudioDecoder.importToWav(this, uri, out);
                clip = new BeatModel.Clip();
                clip.id = cid;
                clip.name = name;
                clip.file = out.getName();
                clip.samples = n;
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final BeatModel.Clip fc = clip;
            final String ferr = err;
            ui.post(() -> {
                setStatus("");
                if (fc == null) {
                    toast("Import impossible : " + ferr);
                    return;
                }
                mm.clips.add(fc);
                StudioStore.save(this, mm);
                if (mm == m) rebuildClips();
                toast(fc.samples >= AudioDecoder.MAX_SECONDS * Synth.RATE
                        ? "Son ajouté (limité à 5 minutes)" : "Son ajouté au mix");
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------
    private void exportDialog() {
        final EditText name = new EditText(this);
        name.setText(m.name == null || m.name.trim().isEmpty() ? "mon_beat" : m.name);
        name.setSingleLine(true);
        name.setSelectAllOnFocus(true);
        name.setTextColor(Color.WHITE);

        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(100 + i);
            rb.setText(Converter.NAMES[i]);
            rb.setTextColor(Color.WHITE);
            rb.setPadding(0, 0, Ui.dp(this, 18), 0);
            rg.addView(rb);
        }
        rg.check(100 + prefs.getInt("format", Converter.MP3));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        TextView l1 = Ui.text(this, "Nom du fichier", 13, Ui.MUTED, false);
        TextView l2 = Ui.text(this, "Format", 13, Ui.MUTED, false);
        l2.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        box.addView(l1);
        box.addView(name);
        box.addView(l2);
        box.addView(rg);

        dialog("Exporter le mix")
                .setView(box)
                .setPositiveButton("Exporter", (d, w) -> {
                    int fmt = Math.max(0, Math.min(2, rg.getCheckedRadioButtonId() - 100));
                    prefs.edit().putInt("format", fmt).apply();
                    doExport(name.getText().toString(), fmt);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void doExport(final String name, final int fmt) {
        stopAll();
        final BeatModel mm = m;
        setStatus("Mixage et conversion en " + Converter.NAMES[fmt] + "…");
        toast("Export en cours…");
        new Thread(() -> {
            String err = null;
            File out = null;
            String pub = null;
            try {
                float[] f = BeatRenderer.mixAll(mm, StudioStore.dirOf(this, mm.id), true, true);
                short[] s = BeatRenderer.toShorts(f);
                f = null;
                File tmp = new File(Storage.dir(this), "_studio_mix.wav");
                WavIO.writeMono16(tmp, s, s.length);
                out = Storage.target(this, name, Converter.EXT[fmt]);
                try {
                    Converter.convert(tmp, out, fmt);
                } finally {
                    tmp.delete();
                }
                pub = Storage.exportPublic(this, out);
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
                if (out != null) out.delete();
            }
            final String ferr = err;
            final String fpub = pub;
            final File fout = out;
            ui.post(() -> {
                setStatus("");
                if (ferr != null) toast("Export impossible : " + ferr);
                else toast("Exporté : " + (fpub != null ? fpub : fout.getName()));
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // Projets
    // ------------------------------------------------------------------
    private void showProjects() {
        flushSave();
        final List<String[]> l = StudioStore.list(this);
        if (l.isEmpty()) { toast("Aucun projet enregistré"); return; }
        String[] names = new String[l.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = (l.get(i)[1].trim().isEmpty() ? "Sans nom" : l.get(i)[1]) + (l.get(i)[0].equals(m.id) ? "   (ouvert)" : "");
        dialog("Mes projets")
                .setItems(names, (d, w) -> projectActions(l.get(w)[0], l.get(w)[1]))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void projectActions(final String id, final String name) {
        dialog(name)
                .setItems(new String[]{"Ouvrir", "Exporter (fichier)", "Supprimer"}, (d, w) -> {
                    if (w == 0) openProject(id);
                    else if (w == 1) startProjectExport(id, name);
                    else confirmDeleteProject(id, name);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void openProject(String id) {
        if (id.equals(m.id)) return;
        stopAll();
        flushSave();
        BeatModel loaded = StudioStore.load(this, id);
        if (loaded == null) { toast("Projet illisible"); return; }
        m = loaded;
        applyModelToUi();
    }

    private void newDialog() {
        String[] items = new String[Examples.NAMES.length + 2];
        items[0] = "Projet vide";
        for (int i = 0; i < Examples.NAMES.length; i++) items[i + 1] = "Exemple : " + Examples.NAMES[i];
        items[items.length - 1] = "Importer un projet (fichier)…";
        dialog("Nouveau projet")
                .setItems(items, (d, w) -> {
                    if (w == 0) newProject();
                    else if (w == items.length - 1) pickProject();
                    else newFromExample(w - 1);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void newFromExample(int idx) {
        stopAll();
        flushSave();
        m = Examples.build(idx);
        m.id = StudioStore.newId();
        StudioStore.save(this, m);
        applyModelToUi();
        toast("Exemple chargé : modifiez-le comme vous voulez");
    }

    private void startProjectExport(String id, String name) {
        flushSave();
        pendingExportId = id;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        String base = (name == null || name.trim().isEmpty() ? "projet" : name.trim()).replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        i.putExtra(Intent.EXTRA_TITLE, base + ".tkstudio.zip");
        try { startActivityForResult(i, REQ_PEXP); } catch (Exception e) { toast("Aucun gestionnaire de fichiers trouvé"); }
    }

    private void pickProject() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_PIMP); } catch (Exception e) { toast("Aucun gestionnaire de fichiers trouvé"); }
    }

    private void doProjectExport(final Uri uri, final String id) {
        toast("Export du projet…");
        new Thread(() -> {
            String err = null;
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                StudioStore.exportZip(this, id, os);
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final String ferr = err;
            ui.post(() -> toast(ferr == null ? "Projet exporté (avec ses sons)" : "Export impossible : " + ferr));
        }).start();
    }

    private void doProjectImport(final Uri uri) {
        toast("Import du projet…");
        new Thread(() -> {
            String id = null;
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                id = StudioStore.importZip(this, is);
            } catch (Throwable ignored) {
            }
            final String fid = id;
            ui.post(() -> {
                if (fid == null) { toast("Fichier de projet illisible"); return; }
                stopAll();
                flushSave();
                BeatModel loaded = StudioStore.load(this, fid);
                if (loaded == null) { toast("Fichier de projet illisible"); return; }
                m = loaded;
                applyModelToUi();
                toast("Projet importé : " + m.name);
            });
        }).start();
    }

    private void newProject() {
        stopAll();
        flushSave();
        m = BeatModel.create();
        m.id = StudioStore.newId();
        m.name = "Mon beat";
        StudioStore.save(this, m);
        applyModelToUi();
        toast("Nouveau projet créé");
    }

    private void confirmDeleteProject(final String id, String name) {
        dialog("Supprimer ce projet ?")
                .setMessage("« " + name + " » et tous ses sons seront supprimés.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    stopAll();
                    boolean current = id.equals(m.id);
                    ui.removeCallbacks(saveRun);
                    StudioStore.delete(this, id);
                    if (current) newProject();
                    else toast("Projet supprimé");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
