package io.github.mlinescode.slimtv;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla de inicio de SlimTV. Toda la UI se construye en código (sin layouts, sin AndroidX).
 * El vidrio, la barra por uso reciente, "Seguías viendo", el mini reproductor y el menú de cada app
 * están pensados para TVs de 1 GB: nada se anima ni se redibuja cuadro por cuadro sin necesidad.
 */
public class MainActivity extends Activity {

    // Lo personalizable (favoritas, ocultas, apps de música, columnas) vive en res/values/config.xml.
    private List<String> favorites;
    private Set<String> hidden, music;
    private int rowMax, gridColumns;
    private static final int CARD_H_DP = 119;
    private static final int TRACK_W_DP = 150;
    private static final long USAGE_WINDOW_MS = 60L * 24 * 60 * 60 * 1000;
    private static final Locale ES = new Locale("es", "MX");
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static class App {
        String pkg, label;
        ComponentName component;
        Drawable banner, icon;
        Integer glow;
        long lastUsed;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private SharedPreferences launches, layout;

    // Menú de cada app (OK sostenido)
    private FrameLayout menu;
    private LinearLayout menuItems;
    private TextView menuTitle;
    private View menuAnchor, heroView;

    private WallpaperView wallpaper;
    // El color de la app tiñe el vidrio de la barra. Un brillo radial grande detrás costaba
    // ~15 ms por cuadro en una Hisense 2K ATV4 (bajaba a ~45 fps), así que no se usa.
    private GlassDrawable shelfGlass;

    private TextView clock, date, wifiLabel;
    private ImageView wifiIcon;
    private LinearLayout actions;

    private LinearLayout titleBlock;
    private TextView appName, appMeta;

    // Mini reproductor
    private LinearLayout player, playerButtons;
    private ImageView playerArt, playButton;
    private TextView playerTitle, playerArtist;
    private MediaSessionManager msm;
    private final List<MediaController> musicSessions = new ArrayList<>();
    private MediaController playerSession;
    private String playerArtKey;
    private FrameLayout card;
    private GlassDrawable cardGlass;
    private ImageView cardArt;
    private FrameLayout cardArtBox;
    private LinearLayout cardText;
    private TextView cardKicker, cardTitle, cardSub, cardLeft;
    private GradientDrawable cardFill, cardTrackBg, playBg;
    private View cardProgress;
    private FrameLayout cardTrack;
    private int cardAccent = Color.WHITE;

    // Vincular un celular (control remoto): código QR + código de 6 dígitos
    private Remote remote;
    private FrameLayout pairPanel;
    private ImageView pairQr;
    private TextView pairTitle, pairStep, pairUrl, pairCode, pairFoot;
    private long pairUntil;

    private Shelf rowScroll;
    private LinearLayout row;
    private FrameLayout overlay;
    private ScrollView gridScroll;
    private GridLayout grid;

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final Map<String, View> rowTiles = new HashMap<>();
    private Map<String, NowPlaying.Program> programs = new HashMap<>();
    private List<String> rowOrder = new ArrayList<>();
    private String focusedPkg;
    private boolean appsDirty = true;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            if (hasWindowFocus()) loadApps();
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { handler.post(MainActivity.this::updateWifi); }
        @Override public void onLost(Network n) { handler.post(MainActivity.this::updateWifi); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    private final Runnable showFocused = this::bindHero;

    // Lo que se cambia desde el celular (fijadas, ocultas, saludo) se aplica al momento.
    private final SharedPreferences.OnSharedPreferenceChangeListener layoutListener = (sp, key) ->
            handler.post(() -> {
                if ("greet".equals(key)) updateClock();
                else if ("pinned".equals(key) || "excluded".equals(key)) reorder();
            });

    private final Runnable pairTick = new Runnable() {
        @Override public void run() {
            long left = pairUntil - SystemClock.elapsedRealtime();
            if (left <= 0) { newPairCode(); left = pairUntil - SystemClock.elapsedRealtime(); }
            long s = (left + 999) / 1000;
            int n = remote.pairedCount();
            pairFoot.setText(String.format(ES, "El código cambia en %d:%02d", s / 60, s % 60)
                    + (n == 0 ? "" : n == 1 ? "  ·  1 celular vinculado" : "  ·  " + n + " celulares vinculados"));
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        favorites = Arrays.asList(getResources().getStringArray(R.array.favorite_apps));
        hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        music = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.music_apps)));
        rowMax = getResources().getInteger(R.integer.row_max);
        gridColumns = getResources().getInteger(R.integer.grid_columns);
        launches = getSharedPreferences("launches", MODE_PRIVATE);
        layout = getSharedPreferences("layout", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        wallpaper = new WallpaperView(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(MATCH, MATCH));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000, 0x22000000, 0xAA000000}));
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        View side = new View(this);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x88000000, 0x22000000, 0x00000000}));
        root.addView(side, new FrameLayout.LayoutParams(MATCH, MATCH));

        root.addView(buildTop(), new FrameLayout.LayoutParams(MATCH, MATCH));
        heroView = buildHero();
        root.addView(heroView, heroParams());
        root.addView(buildShelf(), shelfParams());
        root.addView(buildOverlay(), new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(buildMenu(), new FrameLayout.LayoutParams(dp(270), WRAP, Gravity.TOP | Gravity.START));
        root.addView(buildPair(), new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
        remote = Remote.start(this);
        layout.registerOnSharedPreferenceChangeListener(layoutListener);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
        NowPlaying.onChange = () -> handler.post(this::refreshPrograms);
        MediaWatcher.onConnected = () -> {
            if (msm == null && hasWindowFocus()) startMusic();
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
    }

    // ---------- Construcción de la UI ----------

    private View buildTop() {
        FrameLayout top = new FrameLayout(this);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        clock = text(64, "sans-serif-thin");
        clock.setLetterSpacing(-0.02f);
        date = text(15, "sans-serif");
        date.setAlpha(0.8f);
        info.addView(clock);
        info.addView(date);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        ilp.setMargins(dp(48), dp(26), 0, 0);
        top.addView(info, ilp);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(4), dp(4), dp(4), dp(4));
        actions.setBackground(new GlassDrawable(actions, wallpaper, dp(24), 0x1AFFFFFF, dp(1)));
        actions.addView(action(R.drawable.ic_phone, "Celular", this::openPair));
        actions.addView(action(R.drawable.ic_input, "Entradas", this::openInputs));
        LinearLayout wifi = action(R.drawable.ic_wifi, "Wi-Fi",
                () -> safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        wifiIcon = (ImageView) wifi.getChildAt(0);
        wifiLabel = (TextView) wifi.getChildAt(1);
        actions.addView(wifi);
        actions.addView(action(R.drawable.ic_settings, "Ajustes",
                () -> safeStart(new Intent(Settings.ACTION_SETTINGS))));
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(WRAP, dp(48), Gravity.TOP | Gravity.END);
        alp.setMargins(0, dp(34), dp(40), 0);
        top.addView(actions, alp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(WRAP, dp(56), Gravity.TOP | Gravity.END);
        plp.setMargins(0, dp(34 + 48 + 12), dp(40), 0);
        top.addView(buildPlayer(), plp);
        return top;
    }

    private View buildPlayer() {
        player = new LinearLayout(this);
        player.setOrientation(LinearLayout.HORIZONTAL);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setPadding(dp(6), 0, dp(6), 0);
        player.setBackground(new GlassDrawable(player, wallpaper, dp(28), 0x1AFFFFFF, dp(1)));
        player.setVisibility(View.GONE);

        playerArt = new ImageView(this);
        playerArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        playerArt.setBackgroundColor(0x33000000);
        rounded(playerArt, dp(22));
        player.addView(playerArt, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(12), 0, dp(10), 0);
        playerTitle = text(14, "sans-serif-medium");
        playerTitle.setSingleLine(true);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        playerTitle.setMaxWidth(dp(220));
        txt.addView(playerTitle);
        playerArtist = text(12, "sans-serif");
        playerArtist.setTextColor(0xB3FFFFFF);
        playerArtist.setSingleLine(true);
        playerArtist.setEllipsize(TextUtils.TruncateAt.END);
        playerArtist.setMaxWidth(dp(220));
        txt.addView(playerArtist);
        player.addView(txt, new LinearLayout.LayoutParams(WRAP, WRAP));

        playerButtons = new LinearLayout(this);
        playerButtons.setOrientation(LinearLayout.HORIZONTAL);
        playerButtons.addView(mediaButton(R.drawable.ic_skip_previous, c -> c.getTransportControls().skipToPrevious()));
        playButton = mediaButton(R.drawable.ic_play, c -> {
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
            else c.getTransportControls().play();
        });
        playerButtons.addView(playButton);
        playerButtons.addView(mediaButton(R.drawable.ic_skip_next, c -> c.getTransportControls().skipToNext()));
        player.addView(playerButtons);
        return player;
    }

    private interface MediaAction { void run(MediaController c); }

    private ImageView mediaButton(int icon, final MediaAction action) {
        final ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(Color.WHITE);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x00FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : 0x00FFFFFF);
            b.setColorFilter(has ? 0xFF111111 : Color.WHITE);
        });
        b.setOnClickListener(v -> {
            if (playerSession != null) action.run(playerSession);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.leftMargin = dp(2);
        b.setLayoutParams(lp);
        return b;
    }

    private View buildHero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setClipChildren(false);
        hero.setClipToPadding(false);

        titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        appName = text(34, "sans-serif-medium");
        appName.setLetterSpacing(-0.01f);
        appName.setShadowLayer(dp(8), 0, dp(1), 0x66000000);
        titleBlock.addView(appName);
        appMeta = text(14, "sans-serif");
        appMeta.setAlpha(0.75f);
        appMeta.setPadding(dp(1), dp(4), 0, dp(14));
        titleBlock.addView(appMeta);
        titleBlock.setTranslationY(CARD_H_DP * getResources().getDisplayMetrics().density);
        hero.addView(titleBlock);

        // Tarjeta "Seguías viendo". Al seleccionarla no crece (se cortaría y el vidrio se desfasaría):
        // se ilumina en blanco con texto oscuro, como los botones de arriba.
        card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        cardGlass = new GlassDrawable(card, wallpaper, dp(20), 0x26FFFFFF, dp(1));
        card.setBackground(cardGlass);
        card.setPadding(dp(10), dp(10), dp(24), dp(10));
        card.setAlpha(0f);
        card.setVisibility(View.INVISIBLE);
        card.setOnFocusChangeListener((v, has) -> styleCard(has));
        card.setOnClickListener(v -> {
            App a = focusedPkg == null ? null : apps.get(focusedPkg);
            if (a != null) launch(a);
        });

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout artBox = cardArtBox = new FrameLayout(this);
        rounded(artBox, dp(12));
        artBox.setBackgroundColor(0x33000000);
        cardArt = new ImageView(this);
        cardArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBox.addView(cardArt, new FrameLayout.LayoutParams(MATCH, MATCH));
        playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        ImageView play = new ImageView(this);
        play.setImageResource(R.drawable.ic_play);
        play.setBackground(playBg);
        play.setPadding(dp(7), dp(7), dp(6), dp(7));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM | Gravity.START);
        plp.setMargins(dp(8), 0, 0, dp(8));
        artBox.addView(play, plp);
        inner.addView(artBox, new LinearLayout.LayoutParams(dp(176), dp(99)));

        LinearLayout txt = cardText = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(18), 0, 0, 0);
        cardKicker = text(11, "sans-serif-medium");
        cardKicker.setLetterSpacing(0.16f);
        cardKicker.setText("SEGUÍAS VIENDO");
        txt.addView(cardKicker);
        cardTitle = text(20, "sans-serif-medium");
        cardTitle.setMaxLines(2);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        cardTitle.setPadding(0, dp(5), 0, dp(2));
        txt.addView(cardTitle);
        cardSub = text(13, "sans-serif");
        cardSub.setSingleLine(true);
        cardSub.setEllipsize(TextUtils.TruncateAt.END);
        txt.addView(cardSub);

        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        prog.setPadding(0, dp(12), 0, 0);
        cardTrack = new FrameLayout(this);
        cardTrackBg = pill(0x33FFFFFF);
        cardTrack.setBackground(cardTrackBg);
        cardProgress = new View(this);
        cardFill = pill(Color.WHITE);
        cardProgress.setBackground(cardFill);
        cardTrack.addView(cardProgress, new FrameLayout.LayoutParams(0, MATCH));
        prog.addView(cardTrack, new LinearLayout.LayoutParams(dp(TRACK_W_DP), dp(4)));
        cardLeft = text(12, "sans-serif-medium");
        cardLeft.setPadding(dp(10), 0, 0, 0);
        prog.addView(cardLeft);
        txt.addView(prog);
        inner.addView(txt, new LinearLayout.LayoutParams(dp(300), WRAP));
        card.addView(inner);
        styleCard(false);

        hero.addView(card, new LinearLayout.LayoutParams(WRAP, dp(CARD_H_DP)));
        return hero;
    }

    private FrameLayout.LayoutParams heroParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(48), 0, 0, dp(24 + 132 + 26));
        return lp;
    }

    private View buildShelf() {
        FrameLayout shelf = new FrameLayout(this);
        shelfGlass = new GlassDrawable(shelf, wallpaper, dp(28), 0x14FFFFFF, dp(1));
        shelf.setBackground(shelfGlass);
        shelf.setClipChildren(false);

        rowScroll = new Shelf(this, dp(26));
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rowScroll.setSmoothScrollingEnabled(true);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(6), 0); // 6 + margen derecho del último mosaico (14) = 20
        row.setClipChildren(false);
        row.setClipToPadding(false);
        rowScroll.addView(row, new FrameLayout.LayoutParams(WRAP, MATCH));
        shelf.addView(rowScroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        return shelf;
    }

    private FrameLayout.LayoutParams shelfParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, dp(132), Gravity.BOTTOM);
        lp.setMargins(dp(32), 0, dp(32), dp(24));
        return lp;
    }

    private View buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setBackground(new GlassDrawable(overlay, wallpaper, 0, 0x8C0A0A12, 0));
        overlay.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(26, "sans-serif-light");
        title.setText("Todas las apps");
        title.setPadding(dp(48), dp(36), 0, dp(16));
        col.addView(title);

        gridScroll = new ScrollView(this);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid = new GridLayout(this);
        grid.setColumnCount(gridColumns);
        grid.setPadding(dp(40), dp(8), dp(40), dp(40));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        gridScroll.addView(grid, new ScrollView.LayoutParams(MATCH, WRAP));
        col.addView(gridScroll, new LinearLayout.LayoutParams(MATCH, MATCH));
        overlay.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));
        return overlay;
    }

    // ---------- Ciclo de vida ----------

    @Override protected void onResume() {
        super.onResume();
        if (appsDirty) loadApps();
        else reorder();
        refreshPrograms();
        handler.removeCallbacks(tick);
        tick.run();
        updateWifi();
        wallpaper.start();
        startMusic();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        wallpaper.stop();
        stopMusic();
        closePair();
        if (menu.getVisibility() != View.GONE) {
            menu.setVisibility(View.GONE);
            heroView.setAlpha(1f);
            menuAnchor = null;
        }
    }

    @Override protected void onDestroy() {
        NowPlaying.onChange = null;
        MediaWatcher.onConnected = null;
        Remote.onPaired = null;
        layout.unregisterOnSharedPreferenceChangeListener(layoutListener);
        unregisterReceiver(packageReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeOverlay(false);
        focusRow(0);
    }

    @Override public void onBackPressed() {
        // Somos el inicio: "Atrás" no cierra nada.
    }

    // ---------- Navegación con el control ----------

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        View f = getCurrentFocus();

        if (pairPanel.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) closePair();
            return true;
        }

        if (menu.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) { closeMenu(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            int i = f == null ? -1 : menuItems.indexOfChild(f);
            if (k == KeyEvent.KEYCODE_DPAD_UP && i == 0) return true;
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && i == menuItems.getChildCount() - 1) return true;
            return super.dispatchKeyEvent(e);
        }

        if (overlay.getVisibility() == View.VISIBLE) {
            boolean firstRow = f != null && f.getParent() == grid && grid.indexOfChild(f) < gridColumns;
            if (k == KeyEvent.KEYCODE_BACK || (k == KeyEvent.KEYCODE_DPAD_UP && firstRow)) {
                closeOverlay(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        // Niveles de arriba a abajo: botones (0), reproductor (1), tarjeta (2), barra de apps (3).
        int level = f == null ? -1 : f.getParent() == actions ? 0 : f.getParent() == playerButtons ? 1
                : f == card ? 2 : f.getParent() == row ? 3 : -1;
        if (level >= 0) {
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (level == 3 && k == KeyEvent.KEYCODE_DPAD_DOWN) { openOverlay(); return true; }
                int step = k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                for (int l = level + step; l >= 0 && l <= 3; l += step) if (focusLevel(l)) break;
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK && level != 3) { focusLevel(3); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (level == 2) return true;
                ViewGroup g = (ViewGroup) f.getParent();
                int i = g.indexOfChild(f);
                if (k == KeyEvent.KEYCODE_DPAD_LEFT && i == 0) return true;
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT && i == g.getChildCount() - 1) return true;
            }
        }
        if (k == KeyEvent.KEYCODE_BACK) { focusRow(0); return true; }
        return super.dispatchKeyEvent(e);
    }

    private boolean focusLevel(int l) {
        switch (l) {
            case 0: return actions.getChildAt(actions.getChildCount() - 1).requestFocus();
            case 1: return player.getVisibility() == View.VISIBLE && playButton.requestFocus();
            case 2: return card.getVisibility() == View.VISIBLE && card.requestFocus();
            default: focusPkg(focusedPkg); return true;
        }
    }

    private void focusRow(int i) {
        View t = row.getChildAt(i);
        if (t == null) return;
        t.requestFocus();
        rowScroll.smoothScrollTo(0, 0);
    }

    private void focusPkg(String pkg) {
        View t = pkg == null ? null : rowTiles.get(pkg);
        if (t != null && t.getParent() == row) t.requestFocus();
        else focusRow(0);
    }

    private void openOverlay() {
        if (grid.getChildCount() == 0) return;
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.setTranslationY(dp(28));
        overlay.animate().alpha(1f).translationY(0).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.6f)).withLayer().start();
        gridScroll.scrollTo(0, 0);
        grid.getChildAt(0).requestFocus();
    }

    private void closeOverlay(boolean animate) {
        if (overlay.getVisibility() != View.VISIBLE) return;
        if (!animate) {
            overlay.setVisibility(View.GONE);
            return;
        }
        overlay.animate().alpha(0f).translationY(dp(20)).setDuration(180).withLayer()
                .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
        focusPkg(focusedPkg);
    }

    // ---------- Vincular un celular ----------

    private View buildPair() {
        pairPanel = new FrameLayout(this);
        pairPanel.setBackground(new GlassDrawable(pairPanel, wallpaper, 0, 0xA60A0A12, 0));
        pairPanel.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(28), dp(28), dp(40), dp(28));
        card.setBackground(new GlassDrawable(card, wallpaper, dp(28), 0x1FFFFFFF, dp(1)));

        // El QR va sobre blanco con margen: las cámaras lo leen mejor que sobre el vidrio.
        FrameLayout qrBox = new FrameLayout(this);
        GradientDrawable white = new GradientDrawable();
        white.setColor(Color.WHITE);
        white.setCornerRadius(dp(18));
        qrBox.setBackground(white);
        qrBox.setPadding(dp(14), dp(14), dp(14), dp(14));
        pairQr = new ImageView(this);
        pairQr.setScaleType(ImageView.ScaleType.CENTER);
        qrBox.addView(pairQr, new FrameLayout.LayoutParams(dp(196), dp(196)));
        card.addView(qrBox, new LinearLayout.LayoutParams(WRAP, WRAP));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(32), 0, 0, 0);
        pairTitle = text(26, "sans-serif-medium");
        pairTitle.setPadding(0, 0, 0, dp(14));
        txt.addView(pairTitle);
        pairStep = text(15, "sans-serif");
        pairStep.setTextColor(0xD9FFFFFF);
        pairStep.setLineSpacing(dp(4), 1f);
        txt.addView(pairStep);
        pairUrl = text(15, "sans-serif-medium");
        pairUrl.setPadding(0, dp(4), 0, 0);
        txt.addView(pairUrl);
        pairCode = text(44, "sans-serif-light");
        pairCode.setLetterSpacing(0.12f);
        pairCode.setPadding(0, dp(10), 0, dp(6));
        txt.addView(pairCode);
        pairFoot = text(12, "sans-serif");
        pairFoot.setTextColor(0x99FFFFFF);
        txt.addView(pairFoot);
        card.addView(txt, new LinearLayout.LayoutParams(dp(420), WRAP));

        pairPanel.addView(card, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        return pairPanel;
    }

    private void openPair() {
        if (remote.address() == null) {
            Toast.makeText(this, "Conecta la TV a la red para usar el celular como control", Toast.LENGTH_LONG).show();
            return;
        }
        pairTitle.setText("Usa tu celular como control");
        pairStep.setText("Apunta la cámara del iPhone a este código.\n¿Prefieres escribirlo? Entra a");
        pairCode.setVisibility(View.VISIBLE);
        pairUrl.setVisibility(View.VISIBLE);
        pairQr.setAlpha(1f);
        newPairCode();
        Remote.onPaired = name -> handler.post(() -> onPaired(name));
        pairPanel.setVisibility(View.VISIBLE);
        pairPanel.setAlpha(0f);
        pairPanel.animate().alpha(1f).setDuration(200).withLayer().start();
        pairPanel.invalidate();
        handler.removeCallbacks(pairTick);
        pairTick.run();
    }

    private void newPairCode() {
        String code = remote.startPairing();
        String url = remote.address();
        if (url == null) { closePair(); return; }
        pairUntil = SystemClock.elapsedRealtime() + 3 * 60_000 - 2_000;
        pairUrl.setText(url.replace("http://", ""));
        pairCode.setText(code.substring(0, 3) + " " + code.substring(3));
        // El código va después de "#": el navegador no lo manda por la red ni lo guarda en el servidor.
        pairQr.setImageBitmap(qrBitmap(url + "/#c=" + code));
    }

    private void onPaired(String name) {
        if (pairPanel.getVisibility() != View.VISIBLE) return;
        handler.removeCallbacks(pairTick);
        pairTitle.setText(name + " ya es tu control");
        pairStep.setText("Si la TV pregunta «¿Permitir la depuración?», marca\n«Permitir siempre» y acepta: así funcionan las\nflechas y el teclado desde el celular.");
        pairCode.setVisibility(View.GONE);
        pairUrl.setVisibility(View.GONE);
        pairFoot.setText("");
        pairQr.animate().alpha(0.15f).setDuration(300).start();
        handler.postDelayed(this::closePair, 6000);
    }

    private void closePair() {
        if (pairPanel == null || pairPanel.getVisibility() != View.VISIBLE) return;
        handler.removeCallbacks(pairTick);
        Remote.onPaired = null;
        remote.cancelPairing();
        pairPanel.animate().alpha(0f).setDuration(160).withLayer()
                .withEndAction(() -> pairPanel.setVisibility(View.GONE)).start();
    }

    /** Módulos de tamaño entero en píxeles y sin escalar después: bordes nítidos para la cámara. */
    private Bitmap qrBitmap(String text) {
        boolean[][] m = Qr.encode(text);
        int n = m.length, scale = Math.max(1, dp(196) / n), size = n * scale;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFF0B0B10);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) if (m[y][x]) c.drawRect(x * scale, y * scale, (x + 1) * scale, (y + 1) * scale, p);
        }
        return b;
    }

    // ---------- Apps y orden ----------

    private void loadApps() {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        apps.clear();
        rowTiles.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || apps.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.banner = ai.loadBanner(pm);
            if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
            if (a.banner == null) a.icon = ri.loadIcon(pm);
            a.component = new ComponentName(ai.packageName, ai.name);
            apps.put(a.pkg, a);
        }

        List<App> all = new ArrayList<>(apps.values());
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
        grid.removeAllViews();
        for (App a : all) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(132);
            lp.height = dp(74);
            lp.setMargins(dp(7), dp(7), dp(7), dp(7));
            grid.addView(tile(a, false), lp);
        }
        rowOrder = Collections.emptyList();
        reorder();
    }

    /** Ordena la fila por uso reciente; solo la reconstruye si el orden cambió. */
    private void reorder() {
        Map<String, Long> used = recentUse();
        List<App> list = new ArrayList<>(apps.values());
        for (App a : list) {
            Long t = used.get(a.pkg);
            a.lastUsed = t == null ? 0 : t;
        }
        final List<String> fav = favorites;
        final List<String> pinned = pinned();
        final Set<String> excluded = layout.getStringSet("excluded", Collections.emptySet());
        list.removeIf(a -> excluded.contains(a.pkg));
        Collections.sort(list, (x, y) -> {
            int px = pinned.indexOf(x.pkg), py = pinned.indexOf(y.pkg);
            if (px != py) return px < 0 ? 1 : py < 0 ? -1 : Integer.compare(px, py);
            if (x.lastUsed != y.lastUsed) return Long.compare(y.lastUsed, x.lastUsed);
            int fx = fav.indexOf(x.pkg), fy = fav.indexOf(y.pkg);
            if (fx != fy) return fx < 0 ? 1 : fy < 0 ? -1 : Integer.compare(fx, fy);
            return x.label.compareToIgnoreCase(y.label);
        });
        List<String> order = new ArrayList<>();
        for (int i = 0; i < Math.min(rowMax, list.size()); i++) order.add(list.get(i).pkg);
        if (order.equals(rowOrder) && row.getChildCount() > 0) {
            if (getCurrentFocus() == null) focusRow(0);
            bindHero();
            return;
        }
        rowOrder = order;

        row.removeAllViews();
        for (String pkg : order) {
            View t = rowTiles.get(pkg);
            if (t == null) {
                t = tile(apps.get(pkg), true);
                rowTiles.put(pkg, t);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), dp(95));
            lp.rightMargin = dp(14);
            row.addView(t, lp);
        }
        if (apps.size() > order.size()) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), dp(95));
            lp.rightMargin = dp(14);
            row.addView(allAppsTile(), lp);
        }
        row.post(() -> {
            if (overlay.getVisibility() != View.VISIBLE) focusRow(0);
        });
    }

    /** Último uso de cada app: estadísticas de Android (si hay permiso) + lo que se abrió desde aquí. */
    private Map<String, Long> recentUse() {
        Map<String, Long> out = new HashMap<>();
        long now = System.currentTimeMillis();
        try {
            UsageStatsManager usm = getSystemService(UsageStatsManager.class);
            Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - USAGE_WINDOW_MS, now);
            for (UsageStats s : stats.values()) {
                if (s.getTotalTimeInForeground() > 0) out.put(s.getPackageName(), s.getLastTimeUsed());
            }
        } catch (Exception ignored) {
            // Sin permiso GET_USAGE_STATS: queda solo el registro propio.
        }
        for (Map.Entry<String, ?> e : launches.getAll().entrySet()) {
            if (!(e.getValue() instanceof Long)) continue;
            long t = (Long) e.getValue();
            Long prev = out.get(e.getKey());
            if (prev == null || t > prev) out.put(e.getKey(), t);
        }
        return out;
    }

    private void refreshPrograms() {
        bg.execute(() -> {
            Map<String, NowPlaying.Program> p = NowPlaying.load(this);
            handler.post(() -> {
                programs = p;
                bindHero();
            });
        });
    }

    // ---------- Menú de cada app ----------

    private View buildMenu() {
        menu = new FrameLayout(this);
        menu.setBackground(new GlassDrawable(menu, wallpaper, dp(20), 0xB3141420, dp(1)));
        menu.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), dp(14), dp(8), dp(8));
        menuTitle = text(13, "sans-serif-medium");
        menuTitle.setLetterSpacing(0.12f);
        menuTitle.setTextColor(0x99FFFFFF);
        menuTitle.setSingleLine(true);
        menuTitle.setEllipsize(TextUtils.TruncateAt.END);
        menuTitle.setPadding(dp(14), 0, dp(14), dp(8));
        col.addView(menuTitle);
        menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        col.addView(menuItems);
        menu.addView(col, new FrameLayout.LayoutParams(MATCH, WRAP));
        return menu;
    }

    private void showMenu(final App a, View anchor, boolean fromGrid) {
        menuAnchor = anchor;
        menuTitle.setText(a.label.toUpperCase(ES));
        menuItems.removeAllViews();
        final boolean isPinned = pinned().contains(a.pkg);
        final boolean isExcluded = layout.getStringSet("excluded", Collections.emptySet()).contains(a.pkg);

        menuItem("Abrir", () -> launch(a));
        menuItem(isPinned ? "Quitar de fijadas" : "Fijar al principio", () -> togglePin(a.pkg, !isPinned));
        menuItem(isExcluded ? "Mostrar en la barra" : "Quitar de la barra", () -> toggleExcluded(a.pkg, !isExcluded));
        if (programs.containsKey(a.pkg)) {
            menuItem(music.contains(a.pkg) ? "Olvidar lo último escuchado" : "Olvidar lo último visto", () -> {
                NowPlaying.forget(this, a.pkg);
                refreshPrograms();
            });
        }
        menuItem("Liberar memoria", () -> {
            getSystemService(ActivityManager.class).killBackgroundProcesses(a.pkg);
            Toast.makeText(this, a.label + " se cerró si estaba en segundo plano", Toast.LENGTH_SHORT).show();
        });
        menuItem("Información de la app", () -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.pkg))));
        if (!isSystemApp(a.pkg)) {
            menuItem("Desinstalar", () -> safeStart(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))));
        }

        // Se mide invisible y luego se coloca junto al mosaico (arriba en la barra; al lado en la cuadrícula).
        menu.setVisibility(View.INVISIBLE);
        menu.setAlpha(0f);
        menu.post(() -> {
            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            View root = (View) menu.getParent();
            int w = menu.getWidth(), h = menu.getHeight(), m = dp(24);
            float x, y;
            if (fromGrid) {
                // En la cuadrícula va al lado del mosaico (a la derecha; si no cabe, a la izquierda).
                x = loc[0] + anchor.getWidth() + dp(14);
                if (x + w > root.getWidth() - m) x = loc[0] - w - dp(14);
                y = Math.min(loc[1], root.getHeight() - h - m);
            } else {
                x = Math.max(m, Math.min(loc[0], root.getWidth() - w - m));
                y = loc[1] - h - dp(18);
            }
            menu.setTranslationX(x);
            menu.setTranslationY(Math.max(m, y));
            menu.setVisibility(View.VISIBLE);
            menu.invalidate(); // el vidrio toma el fondo de su posición final
            menu.animate().alpha(1f).setDuration(160).start();
            if (!fromGrid) heroView.animate().alpha(0.25f).setDuration(160).start();
            menuItems.getChildAt(0).requestFocus();
        });
    }

    private void menuItem(String label, final Runnable action) {
        final TextView t = text(15, "sans-serif-medium");
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = pill(0x00FFFFFF);
        bg.setCornerRadius(dp(12));
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : 0x00FFFFFF);
            t.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        t.setOnClickListener(v -> {
            closeMenu();
            action.run();
        });
        menuItems.addView(t, new LinearLayout.LayoutParams(MATCH, dp(42)));
    }

    private void closeMenu() {
        if (menu.getVisibility() == View.GONE) return;
        menu.animate().cancel();
        menu.setVisibility(View.GONE);
        heroView.animate().alpha(1f).setDuration(160).start();
        if (menuAnchor != null && menuAnchor.isAttachedToWindow()) menuAnchor.requestFocus();
        else if (overlay.getVisibility() != View.VISIBLE) focusRow(0);
        menuAnchor = null;
    }

    private List<String> pinned() {
        String v = layout.getString("pinned", "");
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void togglePin(String pkg, boolean pin) {
        List<String> list = pinned();
        list.remove(pkg);
        if (pin) list.add(pkg);
        layout.edit().putString("pinned", TextUtils.join(",", list)).apply();
        if (pin) toggleExcludedQuiet(pkg, false);
        reorder();
    }

    private void toggleExcluded(String pkg, boolean exclude) {
        toggleExcludedQuiet(pkg, exclude);
        if (exclude) {
            List<String> list = pinned();
            if (list.remove(pkg)) layout.edit().putString("pinned", TextUtils.join(",", list)).apply();
        }
        reorder();
    }

    private void toggleExcludedQuiet(String pkg, boolean exclude) {
        Set<String> set = new HashSet<>(layout.getStringSet("excluded", Collections.emptySet()));
        if (exclude ? set.add(pkg) : set.remove(pkg)) layout.edit().putStringSet("excluded", set).apply();
    }

    private boolean isSystemApp(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    // ---------- Mini reproductor ----------

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::trackMusic;

    private final MediaController.Callback musicCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { bindPlayer(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { bindPlayer(); }
        @Override public void onSessionDestroyed() { handler.post(MainActivity.this::bindPlayer); }
    };

    /** Solo mientras el inicio está visible: con otra app en pantalla no se escucha nada. */
    private void startMusic() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            trackMusic(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            msm = null; // MediaWatcher sin permiso: no hay reproductor.
        }
    }

    private void stopMusic() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        msm = null;
        trackMusic(null);
    }

    private void trackMusic(List<MediaController> list) {
        for (MediaController c : musicSessions) c.unregisterCallback(musicCallback);
        musicSessions.clear();
        if (list != null) {
            for (MediaController c : list) {
                if (!music.contains(c.getPackageName())) continue;
                c.registerCallback(musicCallback, handler);
                musicSessions.add(c);
            }
        }
        bindPlayer();
    }

    /** Elige la sesión de música (la que suena; si no, una en pausa) y la muestra. */
    private void bindPlayer() {
        MediaController pick = null;
        for (MediaController c : musicSessions) {
            PlaybackState st = c.getPlaybackState();
            int state = st == null ? PlaybackState.STATE_NONE : st.getState();
            if (c.getMetadata() == null || state == PlaybackState.STATE_NONE
                    || state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_ERROR) continue;
            if (pick == null || state == PlaybackState.STATE_PLAYING) pick = c;
        }
        playerSession = pick;
        if (pick == null) {
            if (player.getVisibility() == View.VISIBLE) {
                if (player.hasFocus()) focusLevel(3);
                player.animate().alpha(0f).setDuration(180).withEndAction(() -> player.setVisibility(View.GONE)).start();
            }
            return;
        }
        MediaMetadata m = pick.getMetadata();
        String title = metaText(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String artist = metaText(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM);
        playerTitle.setText(title != null ? title : "");
        playerArtist.setText(artist != null ? artist : "");
        playerArtist.setVisibility(TextUtils.isEmpty(artist) ? View.GONE : View.VISIBLE);
        boolean playing = pick.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
        playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        bindPlayerArt(pick.getPackageName(), m, title);

        if (player.getVisibility() != View.VISIBLE || player.getAlpha() < 1f) {
            player.animate().cancel();
            player.setVisibility(View.VISIBLE);
            player.animate().alpha(1f).setDuration(220).start();
        }
    }

    private void bindPlayerArt(String pkg, MediaMetadata m, String title) {
        Bitmap b = null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            if ((b = m.getBitmap(k)) != null) break;
        }
        // La clave incluye la imagen: Spotify manda la carátula nueva después del título nuevo.
        final String key = pkg + "|" + title + "|" + (b != null ? NowPlaying.fingerprint(b) : "");
        if (key.equals(playerArtKey)) return;
        playerArtKey = key;
        if (b != null) {
            // Copia pequeña: no retener la carátula grande de la otra app.
            int w = dp(44);
            playerArt.setImageBitmap(Bitmap.createScaledBitmap(b, w, Math.max(1, w * b.getHeight() / Math.max(1, b.getWidth())), true));
            return;
        }
        App a = apps.get(pkg);
        playerArt.setImageDrawable(a == null ? null : copy(a.icon != null ? a.icon : a.banner));
        final String uri = metaText(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (uri == null) return;
        final int w = dp(44);
        bg.execute(() -> {
            Bitmap art = NowPlaying.loadPoster(this, uri, w);
            if (art != null) handler.post(() -> { if (key.equals(playerArtKey)) playerArt.setImageBitmap(art); });
        });
    }

    private static String metaText(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString().trim();
        }
        return null;
    }

    // ---------- Información de la app seleccionada ----------

    private void onTileFocused(App a) {
        focusedPkg = a.pkg;
        appName.setText(a.label);
        moveGlow(a);
        handler.removeCallbacks(showFocused);
        handler.postDelayed(showFocused, 140);
    }

    private void bindHero() {
        App a = focusedPkg == null ? null : apps.get(focusedPkg);
        if (a == null) return;
        appName.setText(a.label);
        NowPlaying.Program p = programs.get(a.pkg);
        if (p == null) {
            appMeta.setText(a.lastUsed > 0 ? "Abierta " + ago(a.lastUsed) : "Pulsa OK para abrir");
            hideCard();
            return;
        }
        appMeta.setText(p.engaged > 0 ? "Visto " + ago(p.engaged) : a.lastUsed > 0 ? "Abierta " + ago(a.lastUsed) : "");
        if (a.glow == null) a.glow = glowColor(a.banner != null ? a.banner : a.icon);
        cardAccent = a.glow;
        styleCard(card.hasFocus());
        boolean isMusic = music.contains(p.pkg);
        cardKicker.setText(isMusic ? "LO ÚLTIMO QUE ESCUCHASTE" : "SEGUÍAS VIENDO");
        // Música: portada cuadrada de álbum; video: 16:9. El ancho total de la tarjeta no cambia.
        int artW = dp(isMusic ? 99 : 176);
        if (cardArtBox.getLayoutParams().width != artW) {
            cardArtBox.getLayoutParams().width = artW;
            cardText.getLayoutParams().width = dp(300 + 176) - artW;
            cardArtBox.requestLayout();
        }
        cardTitle.setText(p.title);
        cardSub.setText(p.subtitle);
        cardSub.setVisibility(p.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasProgress = p.duration > 0 && p.position > 0;
        ((View) cardTrack.getParent()).setVisibility(hasProgress || p.position >= 60_000 ? View.VISIBLE : View.GONE);
        cardTrack.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
        cardLeft.setPadding(hasProgress ? dp(10) : 0, 0, 0, 0);
        if (!hasProgress) cardLeft.setText("Ibas en el minuto " + p.position / 60_000);
        if (hasProgress) {
            float f = Math.min(1f, p.position / (float) p.duration);
            cardProgress.getLayoutParams().width = Math.max(dp(4), Math.round(dp(TRACK_W_DP) * f));
            cardProgress.requestLayout();
            long left = Math.max(1, (p.duration - p.position) / 60_000);
            cardLeft.setText(left >= 60 ? "Quedan " + left / 60 + " h " + left % 60 + " min" : "Quedan " + left + " min");
        }
        bindArt(a, p);
        titleBlock.animate().translationY(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        if (card.getVisibility() != View.VISIBLE || card.getAlpha() < 1f) {
            card.animate().cancel(); // por si se estaba ocultando
            if (card.getVisibility() != View.VISIBLE) card.setTranslationY(dp(10));
            card.setVisibility(View.VISIBLE);
            card.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void bindArt(App a, NowPlaying.Program p) {
        Bitmap cached = NowPlaying.cachedPoster(p.art);
        if (cached != null) { cardArt.setImageBitmap(cached); return; }
        Drawable fallback = a.banner != null ? copy(a.banner) : copy(a.icon);
        cardArt.setImageDrawable(fallback);
        if (p.art == null) return;
        final String pkg = a.pkg, art = p.art;
        final int w = dp(176);
        bg.execute(() -> {
            Bitmap b = NowPlaying.loadPoster(this, art, w);
            if (b == null) return;
            handler.post(() -> {
                NowPlaying.Program now = programs.get(pkg);
                if (pkg.equals(focusedPkg) && now != null && art.equals(now.art)) cardArt.setImageBitmap(b);
            });
        });
    }

    /** Colores de la tarjeta: vidrio oscuro normal, blanco al seleccionarla. Se dibuja una vez, no por cuadro. */
    private void styleCard(boolean focused) {
        int ink = focused ? 0xFF111111 : Color.WHITE;
        int accent = accent(cardAccent, focused);
        cardGlass.setTint(focused ? 0xF2FFFFFF : 0x26FFFFFF);
        cardKicker.setTextColor(focused ? accent : 0x99FFFFFF);
        cardTitle.setTextColor(ink);
        cardSub.setTextColor(focused ? 0xA6000000 : 0xBFFFFFFF);
        cardLeft.setTextColor(focused ? 0x99000000 : 0x99FFFFFF);
        cardTrackBg.setColor(focused ? 0x1F000000 : 0x33FFFFFF);
        cardFill.setColor(accent);
        playBg.setColor(focused ? 0xFF111111 : 0x8C000000);
    }

    /** Color de la app ajustado para leerse sobre fondo oscuro o claro (grises → blanco/gris oscuro). */
    private static int accent(int c, boolean onLight) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.2f) return onLight ? 0xFF333333 : Color.WHITE;
        hsv[1] = Math.max(hsv[1], 0.6f);
        hsv[2] = onLight ? Math.min(hsv[2], 0.8f) : Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }

    private void hideCard() {
        titleBlock.animate().translationY(dp(CARD_H_DP)).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        if (card.getVisibility() != View.VISIBLE) return;
        if (card.hasFocus()) focusPkg(focusedPkg);
        card.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> card.setVisibility(View.INVISIBLE)).start();
    }

    private static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60_000);
        if (m < 1) return "hace un momento";
        if (m < 60) return "hace " + m + " min";
        long h = m / 60;
        if (h < 24) return "hace " + h + " h";
        long d = h / 24;
        return d == 1 ? "ayer" : "hace " + d + " días";
    }

    private void moveGlow(App a) {
        if (a.glow == null) a.glow = glowColor(a.banner != null ? a.banner : a.icon);
        shelfGlass.setTint((a.glow & 0x00FFFFFF) | 0x2E000000);
    }

    /**
     * Color de marca del banner: promedio de los píxeles con color (los blancos, negros y grises del
     * fondo no cuentan), así YouTube y Netflix dan rojo y no gris. Muestrea 16×9 px, una vez por app.
     */
    static int glowColor(Drawable d) {
        if (d == null) return Color.WHITE;
        Drawable copy = copy(d);
        Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, 16, 9);
        copy.draw(new Canvas(b));
        long[] all = new long[4], vivid = new long[4];
        float[] hsv = new float[3];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 9; y++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                add(all, p);
                Color.colorToHSV(p, hsv);
                if (hsv[1] > 0.35f && hsv[2] > 0.25f) add(vivid, p);
            }
        }
        b.recycle();
        long[] use = vivid[3] >= 4 ? vivid : all;
        if (use[3] == 0) return Color.WHITE;
        Color.colorToHSV(Color.rgb((int) (use[0] / use[3]), (int) (use[1] / use[3]), (int) (use[2] / use[3])), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.4f);
        hsv[2] = Math.max(0.75f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static void add(long[] acc, int p) {
        acc[0] += Color.red(p); acc[1] += Color.green(p); acc[2] += Color.blue(p); acc[3]++;
    }

    // ---------- Mosaicos ----------

    private View tile(final App a, final boolean inRow) {
        final FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        final float radius = dp(inRow ? 14 : 12);
        rounded(tile, radius);

        if (a.banner != null) {
            tile.setBackgroundColor(0xFF1E1E24);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(copy(a.banner));
            tile.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            tile.setBackgroundColor(fallbackColor(a.pkg));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(copy(a.icon));
            col.addView(iv, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView tv = text(12, "sans-serif-medium");
            tv.setText(a.label);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(6), dp(4), dp(6), 0);
            col.addView(tv);
            tile.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));
        }

        // Borde blanco + línea interior oscura: se distingue también en banners blancos.
        GradientDrawable outer = new GradientDrawable();
        outer.setCornerRadius(radius);
        outer.setStroke(dp(2.5f), Color.WHITE);
        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(radius - dp(2.5f));
        inner.setStroke(dp(1), 0x59000000);
        final LayerDrawable ring = new LayerDrawable(new Drawable[]{outer, inner});
        ring.setLayerInset(1, dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f));

        tile.setOnClickListener(v -> launch(a));
        tile.setLongClickable(true);
        tile.setOnLongClickListener(v -> {
            showMenu(a, v, !inRow);
            return true;
        });
        tile.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            tile.setForeground(has ? ring : null);
            if (has && inRow) onTileFocused(a);
        });
        return tile;
    }

    private View allAppsTile() {
        final LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x1AFFFFFF);
        bg.setStroke(dp(1), 0x29FFFFFF);
        t.setBackground(bg);
        final ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_apps);
        iv.setColorFilter(Color.WHITE);
        t.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        final TextView tv = text(14, "sans-serif-medium");
        tv.setText("Todas las apps");
        tv.setPadding(dp(10), 0, 0, 0);
        t.addView(tv);
        t.setOnClickListener(v -> openOverlay());
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : 0x1AFFFFFF);
            bg.setStroke(dp(1), has ? Color.WHITE : 0x29FFFFFF);
            iv.setColorFilter(has ? 0xFF111111 : Color.WHITE);
            tv.setTextColor(has ? 0xFF111111 : Color.WHITE);
            float sc = has ? 1.08f : 1f;
            v.animate().scaleX(sc).scaleY(sc).setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            if (has) {
                shelfGlass.setTint(0x14FFFFFF);
                focusedPkg = null;
                appName.setText("Todas las apps");
                appMeta.setText(apps.size() + " apps instaladas");
                hideCard();
            }
        });
        return t;
    }

    /** Barra que al desplazarse deja un margen: el mosaico agrandado nunca queda pegado al borde. */
    private static class Shelf extends HorizontalScrollView {
        private final int margin;
        private final Rect tmp = new Rect();

        Shelf(Context c, int margin) {
            super(c);
            this.margin = margin;
        }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            tmp.set(r);
            tmp.inset(-margin, 0);
            return super.computeScrollDeltaToGetChildRectOnScreen(tmp);
        }
    }

    private static int fallbackColor(String pkg) {
        float[] hsv = {Math.abs(pkg.hashCode()) % 360, 0.45f, 0.45f};
        return Color.HSVToColor(hsv);
    }

    // ---------- Abrir cosas ----------

    private void launch(App a) {
        launches.edit().putLong(a.pkg, System.currentTimeMillis()).apply();
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        safeStart(i);
    }

    private void openInputs() {
        if (safeStartQuiet(new Intent("com.android.tv.action.VIEW_INPUTS"))) return;
        Intent tv = getPackageManager().getLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
        if (tv != null) safeStart(tv);
    }

    private void safeStart(Intent i) {
        if (!safeStartQuiet(i)) Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show();
    }

    private boolean safeStartQuiet(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Reloj y red ----------

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = h < 6 ? "Buenas noches" : h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches";
        String name = layout.getString("greet", "");
        if (!name.isEmpty()) greet += ", " + name;
        String d = new SimpleDateFormat("EEEE d 'de' MMMM", ES).format(now);
        date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1) + "  ·  " + greet);
    }

    private void updateWifi() {
        if (wifiIcon == null) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc == null) {
            wifiIcon.setImageResource(R.drawable.ic_wifi_off);
            wifiLabel.setText("Sin red");
        } else {
            wifiIcon.setImageResource(R.drawable.ic_wifi);
            wifiLabel.setText(nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Wi-Fi");
        }
    }

    // ---------- Helpers de UI ----------

    private LinearLayout action(int icon, String label, final Runnable run) {
        final LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setFocusable(true);
        p.setClickable(true);
        p.setPadding(dp(14), 0, dp(16), 0);
        final GradientDrawable bg = pill(0x00FFFFFF);
        p.setBackground(bg);

        final ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(Color.WHITE);
        p.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        final TextView tv = text(14, "sans-serif-medium");
        tv.setText(label);
        tv.setPadding(dp(8), 0, 0, 0);
        p.addView(tv);

        p.setLayoutParams(new LinearLayout.LayoutParams(WRAP, MATCH));
        p.setOnClickListener(v -> run.run());
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? Color.WHITE : 0x00FFFFFF);
            int c = has ? 0xFF111111 : Color.WHITE;
            iv.setColorFilter(c);
            tv.setTextColor(c);
        });
        return p;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(999));
        return g;
    }

    private static void rounded(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    private static Drawable copy(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d;
    }

    private TextView text(float sp, String family) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
