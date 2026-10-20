package io.github.mlinescode.slimtv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pantalla de inicio de SlimTV. Toda la UI se construye en código (sin layouts,
 * sin AndroidX) para mantener el APK en ~30 KB y el uso de RAM en ~20 MB.
 * Lo que se personaliza (apps de la barra, ocultas, columnas) vive en res/values/config.xml.
 */
public class MainActivity extends Activity {
    private String[] dockPackages;
    private Set<String> hidden;
    private int dockMax, gridColumns;
    private static final Locale ES = new Locale("es", "MX");
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static class App {
        String pkg, label;
        ComponentName component;
        Drawable banner, icon;
        Integer glow;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WallpaperView wallpaper;
    private View dim, glow;
    private GradientDrawable glowDrawable;
    private ScrollView scroll;
    private TextView clock, greeting, date, focusLabel, wifiLabel, allTitle;
    private ImageView wifiIcon;
    private LinearLayout pills, dock;
    private GridLayout grid;
    private int lastDock = 0;
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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dockPackages = getResources().getStringArray(R.array.dock_apps);
        hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        dockMax = getResources().getInteger(R.integer.dock_max);
        gridColumns = getResources().getInteger(R.integer.grid_columns);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        wallpaper = new WallpaperView(this);
        root.addView(wallpaper, new FrameLayout.LayoutParams(MATCH, MATCH));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x11000000, 0x22000000, 0xDD000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        dim = new View(this);
        dim.setBackgroundColor(0xC0000000);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(MATCH, MATCH));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(true);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));

        final int screenH = getResources().getDisplayMetrics().heightPixels;
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) ->
                dim.setAlpha(Math.min(1f, y / (screenH * 0.6f))));

        content.addView(buildHeader(), new LinearLayout.LayoutParams(MATCH, screenH));

        allTitle = text(22, "sans-serif-medium");
        allTitle.setText("Todas las apps");
        allTitle.setPadding(dp(48), dp(8), 0, dp(12));
        content.addView(allTitle);

        grid = new GridLayout(this);
        grid.setColumnCount(gridColumns);
        grid.setPadding(dp(40), 0, dp(40), dp(48));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));

        setContentView(root);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        clock = text(60, "sans-serif-light");
        greeting = text(22, "sans-serif");
        date = text(16, "sans-serif");
        date.setAlpha(0.75f);
        info.addView(clock);
        info.addView(greeting);
        info.addView(date);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        ilp.setMargins(dp(48), dp(28), 0, 0);
        header.addView(info, ilp);

        pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.addView(pill(R.drawable.ic_input, "Entradas", this::openInputs));
        LinearLayout wifi = pill(R.drawable.ic_wifi, "Wi-Fi",
                () -> safeStart(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        wifiIcon = (ImageView) wifi.getChildAt(0);
        wifiLabel = (TextView) wifi.getChildAt(1);
        pills.addView(wifi);
        pills.addView(pill(R.drawable.ic_settings, "Ajustes",
                () -> safeStart(new Intent(Settings.ACTION_SETTINGS))));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
        plp.setMargins(0, dp(36), dp(40), 0);
        header.addView(pills, plp);

        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(dp(230));
        glowDrawable.setColors(new int[]{0x55FFFFFF, 0x00FFFFFF});
        glow = new View(this);
        glow.setBackground(glowDrawable);
        glow.setAlpha(0f);
        // Vista cuadrada del doble del radio: el degradado nunca toca un borde.
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(460), dp(460), Gravity.BOTTOM | Gravity.START);
        header.addView(glow, glp);
        glow.setTranslationY(dp(200));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        focusLabel = text(30, "sans-serif-medium");
        focusLabel.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        bottom.addView(focusLabel);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(0, dp(14), 0, dp(10));
        bottom.addView(dock);

        TextView hint = text(14, "sans-serif");
        hint.setText("▾  Todas las apps");
        hint.setAlpha(0.6f);
        hint.setPadding(0, dp(10), 0, 0);
        bottom.addView(hint);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
        blp.setMargins(dp(48), 0, 0, dp(28));
        header.addView(bottom, blp);
        return header;
    }

    @Override protected void onResume() {
        super.onResume();
        if (appsDirty) loadApps();
        handler.removeCallbacks(tick);
        tick.run();
        updateWifi();
        wallpaper.start();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        wallpaper.stop();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusDock();
    }

    @Override public void onBackPressed() {
        // Somos el inicio: "Atrás" no cierra nada.
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            ViewGroup parent = f != null && f.getParent() instanceof ViewGroup ? (ViewGroup) f.getParent() : null;
            int k = e.getKeyCode();
            if (parent == dock) {
                if (k == KeyEvent.KEYCODE_DPAD_DOWN && grid.getChildCount() > 0) {
                    grid.getChildAt(0).requestFocus();
                    scroll.smoothScrollTo(0, allTitle.getTop() - dp(24));
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP) {
                    pills.getChildAt(pills.getChildCount() - 1).requestFocus();
                    return true;
                }
            } else if (parent == pills && k == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusDock();
                return true;
            } else if (parent == grid && k == KeyEvent.KEYCODE_DPAD_UP && grid.indexOfChild(f) < gridColumns) {
                focusDock();
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) {
                if (parent != dock) focusDock();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void focusDock() {
        scroll.smoothScrollTo(0, 0);
        View t = dock.getChildAt(lastDock);
        if (t != null) t.requestFocus();
    }

    // ---------- Apps ----------

    private void loadApps() {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.component = new ComponentName(ai.packageName, ai.name);
            a.label = String.valueOf(ri.loadLabel(pm));
            a.banner = ai.loadBanner(pm);
            if (a.banner == null) a.banner = ai.applicationInfo.loadBanner(pm);
            if (a.banner == null) a.icon = ri.loadIcon(pm);
            byPkg.put(a.pkg, a);
        }

        List<App> dockApps = new ArrayList<>();
        for (String p : dockPackages) {
            if (dockApps.size() >= dockMax) break;
            App a = byPkg.remove(p);
            if (a != null) dockApps.add(a);
        }
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dock.removeAllViews();
        for (int i = 0; i < dockApps.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(84));
            lp.rightMargin = dp(18);
            dock.addView(tile(dockApps.get(i), true, i), lp);
        }
        grid.removeAllViews();
        for (App a : rest) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(128);
            lp.height = dp(72);
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            grid.addView(tile(a, false, -1), lp);
        }
        allTitle.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        if (lastDock >= dock.getChildCount()) lastDock = 0;
        dock.post(this::focusDock);
    }

    private View tile(final App a, final boolean inDock, final int index) {
        final FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        final float radius = dp(10);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tile.setClipToOutline(true);

        if (a.banner != null) {
            tile.setBackgroundColor(0xFF1E1E24);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(a.banner);
            tile.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            tile.setBackgroundColor(fallbackColor(a.pkg));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            col.addView(iv, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView tv = text(12, "sans-serif-medium");
            tv.setText(a.label);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setPadding(dp(6), dp(4), dp(6), 0);
            col.addView(tv);
            tile.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));
        }

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radius);
        ring.setStroke(dp(3), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);

        tile.setOnClickListener(v -> launch(a));
        tile.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .translationZ(has ? dp(8) : 0).setDuration(150).start();
            tile.setForeground(has ? ring : null);
            if (has && inDock) {
                lastDock = index;
                focusLabel.setText(a.label);
                moveGlow(v, a);
            }
        });
        return tile;
    }

    private void moveGlow(View tile, App a) {
        if (a.glow == null) a.glow = glowColor(a.banner != null ? a.banner : a.icon);
        int c = a.glow & 0x00FFFFFF;
        glowDrawable.setColors(new int[]{c | 0x77000000, c});
        float cx = dp(48) + tile.getLeft() + tile.getWidth() / 2f;
        glow.animate().translationX(cx - dp(230)).alpha(1f).setDuration(250).start();
    }

    /** Color promedio (con más saturación) del banner, para el brillo bajo la barra. */
    private static int glowColor(Drawable d) {
        if (d == null) return Color.WHITE;
        Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        Bitmap b = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888);
        copy.setBounds(0, 0, 8, 4);
        copy.draw(new Canvas(b));
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 4; y++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
            }
        }
        b.recycle();
        if (n == 0) return Color.WHITE;
        float[] hsv = new float[3];
        Color.colorToHSV(Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n)), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.4f);
        hsv[2] = Math.max(0.75f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static int fallbackColor(String pkg) {
        float[] hsv = {Math.abs(pkg.hashCode()) % 360, 0.45f, 0.45f};
        return Color.HSVToColor(hsv);
    }

    private void launch(App a) {
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
        greeting.setText(h < 6 ? "Buenas noches" : h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches");
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", ES).format(now);
        date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
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

    private LinearLayout pill(int icon, String label, final Runnable action) {
        final LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setFocusable(true);
        p.setClickable(true);
        p.setPadding(dp(14), dp(8), dp(16), dp(8));

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(0xFFFFFFFF));
        bg.addState(new int[]{}, rounded(0x33FFFFFF));
        p.setBackground(bg);

        final ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(Color.WHITE);
        p.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        final TextView tv = text(15, "sans-serif-medium");
        tv.setText(label);
        tv.setPadding(dp(8), 0, 0, 0);
        p.addView(tv);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        p.setLayoutParams(lp);
        p.setOnClickListener(v -> action.run());
        p.setOnFocusChangeListener((v, has) -> {
            int c = has ? 0xFF111111 : Color.WHITE;
            iv.setColorFilter(c);
            tv.setTextColor(c);
        });
        return p;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(999));
        return g;
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
