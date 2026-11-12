package com.tvkouame.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Éditeur libre : on pose chaque note ou chaque coup de batterie exactement où on veut (grille fine, roulements). */
public class ComposeActivity extends Activity {

    private static final int[] TCOL = {0xFFE67E22, 0xFF9B59B6, 0xFF2E86DE, 0xFF2ECC71};
    /** Pulsations par case de grille : 1/4, 1/8, 1/16, 1/32, triolet de croches, triolet de doubles. */
    private static final String[] DIV_NAMES = {"Noire", "Croche", "Double-croche", "Triple-croche", "Triolet de croches", "Triolet de doubles"};
    private static final int[] DIV = {24, 12, 6, 3, 8, 4};
    private static final int[] LOW = {0, 28, 48, 55};
    private static final int[] ROWS = {5, 25, 37, 37};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BeatModel m;
    private int inst, bar, grid = 2, lenDiv = 2;
    private RollView roll;
    private TextView barLabel, gridBtnInfo;
    private PcmPlayer loop;
    private boolean looping = false;
    private Button playBtn;
    private final Runnable saveRun = () -> StudioStore.save(this, m);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        m = StudioStore.load(this, getIntent().getStringExtra("id"));
        if (m == null) { finish(); return; }
        inst = Math.max(0, Math.min(3, getIntent().getIntExtra("track", 0)));
        bar = Math.max(0, Math.min(m.bars - 1, getIntent().getIntExtra("bar", 0)));
        if (!m.custom[inst][bar]) m.patternToNotes(inst, bar);
        build();
        refresh();
    }

    @Override protected void onStop() { super.onStop(); stopLoop(); ui.removeCallbacks(saveRun); StudioStore.save(this, m); }

    private void changed() { ui.removeCallbacks(saveRun); ui.postDelayed(saveRun, 400); }

    private Button btn(String t, int color) {
        Button x = Ui.button(this, t, color);
        x.setTextSize(12);
        return x;
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 10), Ui.dp(this, 28), Ui.dp(this, 10), Ui.dp(this, 10));

        TextView title = Ui.text(this, "Composer : " + BeatModel.TRACKS[inst], 18, TCOL[inst], true);
        root.addView(title);

        LinearLayout nav = Ui.row(this);
        Button prev = btn("◀", 0xFF2C3A4A), next = btn("▶", 0xFF2C3A4A);
        barLabel = Ui.text(this, "", 15, Ui.TEXT, true);
        barLabel.setGravity(Gravity.CENTER);
        prev.setOnClickListener(v -> goBar(bar - 1));
        next.setOnClickListener(v -> goBar(bar + 1));
        nav.addView(prev, Ui.weight(this, 2));
        nav.addView(barLabel, new LinearLayout.LayoutParams(0, -2, 2f));
        nav.addView(next, Ui.weight(this, 2));
        root.addView(nav);

        LinearLayout r1 = Ui.row(this);
        Button g = btn("Grille", 0xFF2C3A4A), l = btn("Longueur", 0xFF2C3A4A);
        g.setOnClickListener(v -> pick("Grille (taille d'une case)", DIV_NAMES, i -> { grid = i; refresh(); }));
        l.setOnClickListener(v -> pick("Longueur des notes posées", DIV_NAMES, i -> { lenDiv = i; refresh(); }));
        r1.addView(g, Ui.weight(this, 2));
        r1.addView(l, Ui.weight(this, 2));
        root.addView(r1);
        gridBtnInfo = Ui.text(this, "", 12, Ui.MUTED, false);
        root.addView(gridBtnInfo);

        roll = new RollView(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(roll);
        ScrollView vs = new ScrollView(this);
        vs.addView(hs);
        root.addView(vs, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout r2 = Ui.row(this);
        playBtn = btn("▶ Écouter la mesure", Ui.GREEN);
        playBtn.setOnClickListener(v -> { if (looping) stopLoop(); else startLoop(); });
        r2.addView(playBtn, Ui.weight(this, 2));
        if (inst == BeatModel.DRUMS) {
            Button roulBtn = btn("Roulement", TCOL[0]);
            roulBtn.setOnClickListener(v -> rollDialog());
            r2.addView(roulBtn, Ui.weight(this, 2));
        }
        root.addView(r2);

        LinearLayout r3 = Ui.row(this);
        Button clr = btn("Effacer", 0xFF2C3A4A), all = btn("Copier partout", 0xFF2C3A4A), back = btn("Terminé", Ui.ACCENT);
        clr.setOnClickListener(v -> { m.clearNotes(inst, bar); stopLoop(); roll.invalidate(); changed(); });
        all.setOnClickListener(v -> {
            List<BeatModel.Note> src = m.notesOf(inst, bar);
            for (int b2 = 0; b2 < m.bars; b2++) {
                if (b2 == bar) continue;
                m.clearNotes(inst, b2);
                for (BeatModel.Note n : src) m.notes.add(new BeatModel.Note(inst, b2, n.tick, n.len, n.pitch, n.vel));
                m.custom[inst][b2] = true;
            }
            Toast.makeText(this, "Mesure copiée sur les " + m.bars + " mesures", Toast.LENGTH_SHORT).show();
            changed();
        });
        back.setOnClickListener(v -> finish());
        r3.addView(clr, Ui.weight(this, 2));
        r3.addView(all, Ui.weight(this, 2));
        r3.addView(back, Ui.weight(this, 2));
        root.addView(r3);
        setContentView(root);
    }

    private interface IntCb { void on(int i); }

    private void pick(String title, String[] items, IntCb cb) {
        new AlertDialog.Builder(this).setTitle(title).setItems(items, (d, i) -> cb.on(i)).show();
    }

    private void goBar(int b2) {
        if (b2 < 0 || b2 >= m.bars) return;
        stopLoop();
        bar = b2;
        if (!m.custom[inst][bar]) m.patternToNotes(inst, bar);
        refresh();
        changed();
    }

    private void refresh() {
        barLabel.setText("Mesure " + (bar + 1) + " / " + m.bars + "  ·  " + m.chordName(bar));
        gridBtnInfo.setText("Grille : " + DIV_NAMES[grid] + "   ·   Notes posées : " + DIV_NAMES[lenDiv]
                + "   (touchez une case pour poser ou retirer)");
        roll.requestLayout();
        roll.invalidate();
    }

    // ---------------- Roulement de batterie ----------------
    private void rollDialog() {
        final String[] dens = {"Croches (1/8)", "Doubles-croches (1/16)", "Triples-croches (1/32)", "Triolets de croches"};
        final int[] dt = {12, 6, 3, 8};
        pick("Roulement : vitesse", dens, d -> {
            final String[] span = {"Dernier temps", "Deux derniers temps", "Mesure entière"};
            pick("Roulement : où ?", span, sp -> {
                final String[] who = {"Caisse claire", "Grosse caisse", "Charley", "Clap"};
                final int[] row = {1, 0, 2, 4};
                pick("Roulement : sur quel son ?", who, w -> {
                    int bt = m.barTicks();
                    int from = sp == 0 ? bt - BeatModel.PPQ : sp == 1 ? bt - 2 * BeatModel.PPQ : 0;
                    from = Math.max(0, from);
                    for (int i = m.notes.size() - 1; i >= 0; i--) {
                        BeatModel.Note n = m.notes.get(i);
                        if (n.inst == inst && n.bar == bar && n.tick >= from && n.pitch == row[w]) m.notes.remove(i);
                    }
                    int cnt = 0, total = (bt - from) / dt[d];
                    for (int t = from; t + dt[d] <= bt; t += dt[d]) {
                        float vel = 0.45f + 0.55f * cnt / Math.max(1, total - 1);   // crescendo
                        m.notes.add(new BeatModel.Note(inst, bar, t, dt[d], row[w], vel));
                        cnt++;
                    }
                    roll.invalidate();
                    changed();
                });
            });
        });
    }

    // ---------------- Écoute ----------------
    private void startLoop() {
        stopLoop();
        looping = true;
        playBtn.setText("■ Stop");
        final BeatModel mm = m;
        final int b2 = bar;
        new Thread(() -> {
            float[] f = BeatRenderer.renderBar(mm, b2, -1);
            final short[] s = BeatRenderer.toShorts(f);
            final int len = Math.min(s.length, BeatRenderer.samplesPerBar(mm));
            ui.post(() -> {
                if (isFinishing() || !looping) return;
                loop = new PcmPlayer(s, len, true);
                if (!loop.start()) loop = null;
            });
        }).start();
    }

    private void stopLoop() {
        looping = false;
        if (playBtn != null) playBtn.setText("▶ Écouter la mesure");
        if (loop != null) { loop.stop(); loop = null; }
    }

    private void audition(int pitch) {
        final int ins = inst;
        new Thread(() -> {
            float[] f;
            if (ins == BeatModel.DRUMS) f = Synth.drum(pitch);
            else {
                int len = Synth.RATE / 3;
                f = ins == BeatModel.BASS ? Synth.bass(pitch, len) : ins == BeatModel.PIANO ? Synth.piano(pitch, len) : Synth.lead(pitch, len);
            }
            short[] s = BeatRenderer.toShorts(f);
            new PcmPlayer(s, s.length, false).start();
        }).start();
    }

    // ---------------- Grille ----------------
    private class RollView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int labelW = Ui.dp(ComposeActivity.this, 54), rowH = Ui.dp(ComposeActivity.this, 30);
        private final int colMin = Ui.dp(ComposeActivity.this, 30);

        RollView(Context c) { super(c); }

        private int cols() { return Math.max(1, m.barTicks() / DIV[grid]); }
        private int rowsN() { return ROWS[inst]; }

        /** Rangée 0 = en haut (note la plus aiguë, ou dernier son de batterie). */
        private int pitchOf(int row) { return inst == 0 ? row : LOW[inst] + rowsN() - 1 - row; }

        private String nameOf(int pitch) {
            if (inst == 0) return BeatModel.DRUM_ROWS[pitch];
            return BeatModel.NOTES[pitch % 12] + (pitch / 12 - 1);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(labelW + cols() * colMin, rowsN() * rowH);
        }

        @Override protected void onDraw(Canvas c) {
            int cw = colMin, nc = cols();
            int[] tones = m.chordTones(bar);
            p.setTextSize(Ui.dp(ComposeActivity.this, 10));
            for (int r = 0; r < rowsN(); r++) {
                int pitch = pitchOf(r);
                boolean inChord = false;
                if (inst > 0) for (int t : tones) if ((pitch - m.chordRoot[bar] - t + 120) % 12 == 0) inChord = true;
                boolean black = inst > 0 && java.util.Arrays.binarySearch(new int[]{1, 3, 6, 8, 10}, pitch % 12) >= 0;
                p.setColor(inChord ? 0xFF2A3B52 : black ? 0xFF141B24 : 0xFF1B2430);
                c.drawRect(0, r * rowH, getWidth(), (r + 1) * rowH, p);
                p.setColor(0xFF0B0F14);
                c.drawLine(0, (r + 1) * rowH, getWidth(), (r + 1) * rowH, p);
                p.setColor(inChord ? Ui.ACCENT : Ui.MUTED);
                c.drawText(nameOf(pitch), Ui.dp(ComposeActivity.this, 3), r * rowH + rowH * 0.65f, p);
            }
            for (int i = 0; i <= nc; i++) {
                int x = labelW + i * cw;
                boolean beat = (i * DIV[grid]) % BeatModel.PPQ == 0;
                p.setColor(beat ? 0xFF5A6B80 : 0xFF2C3A4A);
                c.drawLine(x, 0, x, getHeight(), p);
            }
            for (BeatModel.Note n : m.notes) {
                if (n.inst != inst || n.bar != bar) continue;
                int row = inst == 0 ? n.pitch : LOW[inst] + rowsN() - 1 - n.pitch;
                if (row < 0 || row >= rowsN()) continue;
                float x0 = labelW + (float) n.tick / DIV[grid] * cw;
                float x1 = Math.max(x0 + Ui.dp(ComposeActivity.this, 6), labelW + (float) (n.tick + n.len) / DIV[grid] * cw - 2);
                p.setColor(TCOL[inst]);
                p.setAlpha((int) (110 + 145 * Math.min(1f, n.vel)));
                c.drawRoundRect(x0 + 1, row * rowH + 2, x1, (row + 1) * rowH - 2, 8, 8, p);
                p.setAlpha(255);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            if (e.getX() < labelW) {
                int r = (int) (e.getY() / rowH);
                if (r >= 0 && r < rowsN()) audition(pitchOf(r));
                return true;
            }
            int row = (int) (e.getY() / rowH);
            int col = (int) ((e.getX() - labelW) / colMin);
            if (row < 0 || row >= rowsN() || col < 0 || col >= cols()) return true;
            int pitch = pitchOf(row), tick = col * DIV[grid];
            for (int i = 0; i < m.notes.size(); i++) {
                BeatModel.Note n = m.notes.get(i);
                if (n.inst == inst && n.bar == bar && n.pitch == pitch && tick >= n.tick && tick < n.tick + n.len) {
                    m.notes.remove(i);
                    invalidate();
                    changed();
                    return true;
                }
            }
            int len = inst == 0 ? Math.min(DIV[lenDiv], 12) : DIV[lenDiv];
            len = Math.min(len, m.barTicks() - tick);
            m.notes.add(new BeatModel.Note(inst, bar, tick, Math.max(1, len), pitch, 1f));
            audition(pitch);
            invalidate();
            changed();
            return true;
        }
    }
}
