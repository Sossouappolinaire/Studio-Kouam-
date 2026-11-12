package com.tvkouame.studio;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Accueil : « Enregistrer » (l'enregistreur table de mixage) et « Studio » (créer un beat et enregistrer dessus). */
public class HomeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Ui.dp(this, 20);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(this, "TvKouame Studio", 30, Ui.TEXT, true);
        title.setGravity(Gravity.CENTER);
        TextView sub = Ui.text(this, "Que voulez-vous faire ?", 15, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 28));
        root.addView(title);
        root.addView(sub);

        root.addView(bigButton("🎙", "Enregistrer",
                "Enregistrer depuis la table de mixage ou le micro, son nettoyé, export MP3 / AMR / WAV.",
                Ui.RED, RecordActivity.class));
        root.addView(bigButton("🎹", "Studio",
                "Créer un beat (batterie, basse, piano, solo), choisir le tempo et les accords, importer ou enregistrer un son dessus, mélanger et exporter.",
                0xFF2B6CB0, StudioActivity.class));

        setContentView(sv);
    }

    private LinearLayout bigButton(String icon, String label, String desc, int color, final Class<?> target) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 22);
        card.setPadding(p, p, p, p);
        card.setBackground(Ui.rounded(color, Ui.dp(this, 22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, Ui.dp(this, 16));
        card.setLayoutParams(lp);
        card.setClickable(true);
        card.setOnClickListener(v -> startActivity(new Intent(this, target)));

        TextView ic = Ui.text(this, icon, 44, 0xFFFFFFFF, false);
        ic.setGravity(Gravity.CENTER);
        TextView t = Ui.text(this, label, 24, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        TextView d = Ui.text(this, desc, 13, 0xFFE6EEF7, false);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(ic);
        card.addView(t);
        card.addView(d);
        return card;
    }
}
