package io.github.mlinescode.slimtv;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Protector de pantalla: los mismos paisajes a pantalla completa, reloj grande y un panel de vidrio
 * con lo relevante (lo que suena, lo último que veías, si se cayó el internet). Solo corre con la
 * TV inactiva. El bloque cambia de lugar cada 90 s para no dejar nada fijo en pantalla.
 */
public class Screensaver extends DreamService {
    private static final long MOVE_MS = 90_000;
    private static final long WATCHED_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;
    private static final Locale ES = new Locale("es", "MX");
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<MediaController> watched = new ArrayList<>();
    private WallpaperView wallpaper;
    private FrameLayout root;
    private LinearLayout block, panel;
    private TextView clock, date;
    private MediaSessionManager msm;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            refreshInfo();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    private final Runnable move = new Runnable() {
        @Override public void run() {
            block.animate().alpha(0f).setDuration(600).withLayer().withEndAction(() -> {
                place(true);
                block.animate().alpha(1f).setDuration(1200).withLayer().start();
            }).start();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> watch(list);

    private final MediaController.Callback onMedia = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { refreshInfo(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { refreshInfo(); }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        root = new FrameLayout(this);
        wallpaper = new WallpaperView(this);
        wallpaper.setRotateMs(getResources().getInteger(R.integer.screensaver_minutes) * 60_000L);
        root.addView(wallpaper, new FrameLayout.LayoutParams(MATCH, MATCH));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x22000000, 0x00000000, 0x55000000}));
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        clock = text(104, "sans-serif-thin");
        clock.setLetterSpacing(-0.03f);
        clock.setShadowLayer(dp(12), 0, dp(2), 0x55000000);
        block.addView(clock);
        date = text(20, "sans-serif-light");
        date.setAlpha(0.85f);
        date.setPadding(dp(4), 0, 0, dp(22));
        block.addView(date);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(16), dp(26), dp(16));
        panel.setBackground(new GlassDrawable(panel, wallpaper, dp(20), 0x1FFFFFFF, dp(1)));
        block.addView(panel, new LinearLayout.LayoutParams(WRAP, WRAP));

        root.addView(block, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START));
        // Si el bloque cambia de tamaño (aparece o se va una fila), que no se salga de la pantalla.
        block.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) place(false);
        });
        setContentView(root);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        wallpaper.start();
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, me, handler);
            watch(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            msm = null; // Sin el permiso de MediaWatcher: solo reloj y "Seguías viendo".
        }
        tick.run();
        block.setAlpha(0f);
        block.post(() -> {
            place(false);
            block.animate().alpha(1f).setDuration(1500).setInterpolator(new DecelerateInterpolator()).withLayer().start();
        });
        handler.postDelayed(move, MOVE_MS);
    }

    @Override public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        wallpaper.stop();
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        watch(null);
        super.onDreamingStopped();
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(onMedia);
        watched.clear();
        if (list != null) {
            for (MediaController c : list) {
                c.registerCallback(onMedia, handler);
                watched.add(c);
            }
        }
        refreshInfo();
    }

    /** Coloca el bloque: abajo a la izquierda al empezar, luego en un lugar al azar dentro de la pantalla. */
    private void place(boolean randomSpot) {
        int m = dp(56);
        int maxX = Math.max(m, root.getWidth() - block.getWidth() - m);
        int maxY = Math.max(m, root.getHeight() - block.getHeight() - m);
        float x = randomSpot ? m + random.nextInt(Math.max(1, maxX - m + 1)) : m;
        float y = randomSpot ? m + random.nextInt(Math.max(1, maxY - m + 1)) : maxY;
        if (!randomSpot && block.getTranslationY() != 0) {
            x = Math.min(block.getTranslationX(), maxX);
            y = Math.min(block.getTranslationY(), maxY);
        }
        block.setTranslationX(x);
        block.setTranslationY(y);
        panel.invalidate(); // el vidrio toma el recorte del fondo en su nueva posición
    }

    // ---------- Contenido ----------

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = h < 6 ? "Buenas noches" : h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches";
        String d = new SimpleDateFormat("EEEE d 'de' MMMM", ES).format(now);
        date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1) + "  ·  " + greet);
    }

    private void refreshInfo() {
        if (panel == null) return;
        panel.removeAllViews();
        String playingPkg = null;

        // 1. Lo que suena ahora (con la TV inactiva, es música o audio de fondo: Spotify, YouTube…)
        for (MediaController c : watched) {
            PlaybackState s = c.getPlaybackState();
            MediaMetadata m = c.getMetadata();
            if (s == null || s.getState() != PlaybackState.STATE_PLAYING || m == null) continue;
            String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) continue;
            String artist = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
            playingPkg = c.getPackageName();
            addRow("SONANDO AHORA", title, join(artist, label(playingPkg)));
            break;
        }

        // 2. Lo último que veías (de otra app, en los últimos 3 días)
        NowPlaying.Program last = null;
        for (NowPlaying.Program p : NowPlaying.load(this).values()) {
            if (p.pkg.equals(playingPkg)) continue;
            if (System.currentTimeMillis() - p.engaged > WATCHED_MAX_AGE_MS) continue;
            if (last == null || p.engaged > last.engaged) last = p;
        }
        if (last != null) {
            String left = last.duration > 0 && last.position > 0
                    ? "Quedan " + Math.max(1, (last.duration - last.position) / 60_000) + " min" : null;
            addRow("SEGUÍAS VIENDO", last.title, join(TextUtils.isEmpty(last.subtitle) ? null : last.subtitle,
                    join(label(last.pkg), left)));
        }

        // 3. Sin internet
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm.getActiveNetwork() == null) addRow("SIN INTERNET", "Revisa el Wi-Fi", null);

        panel.setVisibility(panel.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void addRow(String kicker, String title, String meta) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        if (panel.getChildCount() > 0) row.setPadding(0, dp(14), 0, 0);
        TextView k = text(11, "sans-serif-medium");
        k.setLetterSpacing(0.16f);
        k.setTextColor(0x99FFFFFF);
        k.setText(kicker);
        row.addView(k);
        TextView t = text(19, "sans-serif-medium");
        t.setText(title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(460));
        t.setPadding(0, dp(4), 0, dp(1));
        row.addView(t);
        if (!TextUtils.isEmpty(meta)) {
            TextView mt = text(13, "sans-serif");
            mt.setTextColor(0xB3FFFFFF);
            mt.setText(meta);
            mt.setSingleLine(true);
            mt.setEllipsize(TextUtils.TruncateAt.END);
            mt.setMaxWidth(dp(460));
            row.addView(mt);
        }
        panel.addView(row);
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String join(String a, String b) {
        if (TextUtils.isEmpty(a)) return b;
        if (TextUtils.isEmpty(b)) return a;
        return a + "  ·  " + b;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString().trim();
        }
        return null;
    }

    private TextView text(float sp, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
