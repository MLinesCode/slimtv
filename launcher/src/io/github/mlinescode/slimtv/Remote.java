package io.github.mlinescode.slimtv;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Control remoto desde el celular: un servidor HTTP chico (puerto 8765, solo red local) que sirve el
 * panel web (res/raw/remote.html) y su API. Corre en el proceso del launcher, que siempre está vivo
 * porque el sistema mantiene enlazado a {@link MediaWatcher}.
 *
 * Seguridad:
 *  - Solo responde a direcciones de red local, y solo si el Host es una IP (evita "DNS rebinding").
 *  - Vincular un celular exige ver la TV: el código de 6 dígitos (o el QR) aparece al pulsar
 *    "Celular" en el inicio, dura 3 minutos y admite 5 intentos.
 *  - Al vincular, la TV entrega una llave de 256 bits que el celular guarda. Desde ahí cada petición va
 *    firmada (HMAC-SHA256 de método, ruta, hora, un número único y el cuerpo): la llave no vuelve a
 *    viajar por la red y una petición capturada no se puede repetir ni modificar.
 *  - La API no ejecuta comandos: solo teclas de una lista fija, texto, volumen, música y abrir apps.
 */
final class Remote {
    static final int PORT = 8765;
    private static final long PAIR_MS = 3 * 60_000, SKEW_MS = 90_000;
    private static final int MAX_BODY = 16 * 1024, KEEP_ALIVE_MS = 8_000;

    interface PairListener { void paired(String name); }

    /** Lo pone MainActivity para cerrar el código QR cuando un celular se vincula (se llama desde otro hilo). */
    static volatile PairListener onPaired;

    private static Remote instance;

    final Keys keys;
    private final Context ctx;
    private final SharedPreferences devices, layout, launches;
    private final SecureRandom random = new SecureRandom();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 6, 20, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final Map<String, Long> nonces = new LinkedHashMap<>();
    private final Map<String, long[]> failures = new HashMap<>();
    private final LruCache<String, byte[]> icons = new LruCache<String, byte[]>(768 * 1024) {
        @Override protected int sizeOf(String k, byte[] v) { return v.length; }
    };

    private String pairCode;
    private long pairUntil;
    private int pairTries;
    private byte[] page, pageGz, appIcon;
    private String pageTag;
    private String artKey;
    private byte[] art;
    private String fgPkg;
    private long fgAt;

    static synchronized Remote start(Context c) {
        if (instance == null) {
            instance = new Remote(c.getApplicationContext());
            Thread t = new Thread(instance::listen, "slimtv-remote");
            t.setDaemon(true);
            t.start();
        }
        return instance;
    }

    private Remote(Context ctx) {
        this.ctx = ctx;
        keys = new Keys(ctx);
        devices = ctx.getSharedPreferences("remote_devices", Context.MODE_PRIVATE);
        layout = ctx.getSharedPreferences("layout", Context.MODE_PRIVATE);
        launches = ctx.getSharedPreferences("launches", Context.MODE_PRIVATE);
        // Si ya hay celulares vinculados, se prepara el teclado remoto para que la primera tecla sea instantánea.
        if (!pairedIds().isEmpty()) keys.ensure();
    }

    // ---------- Vinculación (la llama MainActivity) ----------

    /** Nuevo código de 6 dígitos, válido 3 minutos. */
    synchronized String startPairing() {
        pairCode = String.format(Locale.US, "%06d", random.nextInt(1_000_000));
        pairUntil = SystemClock.elapsedRealtime() + PAIR_MS;
        pairTries = 0;
        return pairCode;
    }

    synchronized void cancelPairing() { pairCode = null; }

    /** Dirección del panel, p. ej. http://192.168.68.110:8765 (null sin red). */
    String address() {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) return "http://" + la.getAddress().getHostAddress() + ":" + PORT;
        }
        return null;
    }

    int pairedCount() { return pairedIds().size(); }

    // ---------- Servidor ----------

    private void listen() {
        while (true) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT), 16);
                while (true) {
                    final Socket s = ss.accept();
                    if (!isLocal(s.getInetAddress())) { s.close(); continue; }
                    try {
                        pool.execute(() -> serve(s));
                    } catch (Exception busy) {
                        s.close();
                    }
                }
            } catch (IOException e) {
                SystemClock.sleep(15_000); // puerto ocupado o red caída: se reintenta
            }
        }
    }

    private static boolean isLocal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC; // IPv6 privada (fc00::/7)
    }

    private void serve(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(KEEP_ALIVE_MS);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String ip = s.getInetAddress().getHostAddress();
            while (true) {
                Request r = Request.read(in);
                if (r == null) return;
                Response res;
                try {
                    res = handle(r, ip);
                } catch (JSONException | IllegalArgumentException e) {
                    res = error(400, "bad_request");
                } catch (Exception e) {
                    res = error(500, "internal");
                }
                boolean keep = !"close".equalsIgnoreCase(r.header("connection"));
                res.write(out, keep);
                out.flush();
                if (!keep) return;
            }
        } catch (IOException ignored) {
            // conexión cerrada o datos inválidos
        }
    }

    private Response handle(Request r, String ip) throws Exception {
        String host = r.header("host");
        if (host == null || !host.matches("(\\d{1,3}\\.){3}\\d{1,3}(:\\d+)?|localhost(:\\d+)?|\\[[0-9a-fA-F:.%]+\\](:\\d+)?")) {
            return error(421, "host");
        }
        String origin = r.header("origin");
        if (origin != null && !origin.equals("http://" + host)) return error(403, "origin");

        switch (r.path) {
            case "/":
            case "/index.html":
                return page(r);
            case "/icon.png":
                return new Response(200, "image/png", appIcon()).cache("public, max-age=86400");
            case "/api/hello":
                return json(new JSONObject().put("name", "SlimTV").put("tv", tvName())
                        .put("time", System.currentTimeMillis()));
            case "/api/pair":
                if (!r.method.equals("POST")) return error(405, "method");
                if (blocked(ip)) return error(429, "slow_down");
                return pair(r, ip);
            default:
                break;
        }
        if (!r.path.startsWith("/api/")) return error(404, "not_found");
        if (blocked(ip)) return error(429, "slow_down");
        String failure = authenticate(r);
        if (failure != null) {
            fail(ip);
            return failure.equals("clock")
                    ? json(401, new JSONObject().put("error", "clock").put("time", System.currentTimeMillis()))
                    : error(401, failure);
        }
        return api(r, r.auth[0]);
    }

    // ---------- Autenticación ----------

    private Response pair(Request r, String ip) throws Exception {
        JSONObject b = r.json();
        String code = b.optString("code", "").replaceAll("\\D", "");
        String name = clean(b.optString("name", ""), 40);
        if (name.isEmpty()) name = "Celular";
        synchronized (this) {
            if (pairCode == null || SystemClock.elapsedRealtime() > pairUntil) return error(410, "expired");
            if (++pairTries > 5) {
                pairCode = null;
                return error(429, "too_many");
            }
            if (!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), pairCode.getBytes(StandardCharsets.UTF_8))) {
                fail(ip);
                return error(403, "code");
            }
            pairCode = null;
        }
        byte[] idBytes = new byte[8], key = new byte[32];
        random.nextBytes(idBytes);
        random.nextBytes(key);
        String id = hex(idBytes);
        devices.edit()
                .putString(id + "|name", name)
                .putString(id + "|key", Base64.encodeToString(key, Base64.NO_WRAP))
                .putLong(id + "|seen", System.currentTimeMillis())
                .apply();
        PairListener l = onPaired;
        if (l != null) l.paired(name);
        keys.ensure(); // si es la primera vez, la TV pide permiso ahora, mientras la persona está frente a ella
        return json(new JSONObject().put("id", id).put("key", Base64.encodeToString(key, Base64.NO_WRAP))
                .put("tv", tvName()));
    }

    /** null si la firma es válida; si no, el motivo. Deja el id del celular en r.auth[0]. */
    private String authenticate(Request r) {
        String h = r.header("x-slim-auth");
        if (h == null) return "unauthorized";
        String[] p = h.split("\\.");
        if (p.length != 4 || !p[0].matches("[0-9a-f]{16}") || !p[2].matches("[0-9a-f]{16,64}")
                || !p[3].matches("[0-9a-f]{64}")) return "unauthorized";
        String key = devices.getString(p[0] + "|key", null);
        if (key == null) return "unpaired";
        long ts;
        try { ts = Long.parseLong(p[1]); } catch (NumberFormatException e) { return "unauthorized"; }
        long now = System.currentTimeMillis();
        byte[] expected = hmac(Base64.decode(key, Base64.NO_WRAP),
                r.method + "\n" + r.target + "\n" + p[1] + "\n" + p[2] + "\n" + r.bodyText());
        if (!MessageDigest.isEqual(hex(expected).getBytes(StandardCharsets.US_ASCII), p[3].getBytes(StandardCharsets.US_ASCII))) {
            return "unauthorized";
        }
        if (Math.abs(now - ts) > SKEW_MS) return "clock";
        synchronized (nonces) {
            for (Iterator<Long> it = nonces.values().iterator(); it.hasNext(); ) if (it.next() < now) it.remove();
            if (nonces.containsKey(p[2])) return "replay";
            if (nonces.size() > 4096) return "slow_down";
            nonces.put(p[2], now + 2 * SKEW_MS);
        }
        r.auth = new String[]{p[0]};
        if (now - devices.getLong(p[0] + "|seen", 0) > 10 * 60_000) devices.edit().putLong(p[0] + "|seen", now).apply();
        return null;
    }

    private boolean blocked(String ip) {
        synchronized (failures) {
            long[] f = failures.get(ip);
            return f != null && f[0] >= 10 && SystemClock.elapsedRealtime() - f[1] < 60_000;
        }
    }

    private void fail(String ip) {
        synchronized (failures) {
            long now = SystemClock.elapsedRealtime();
            long[] f = failures.get(ip);
            if (f == null || now - f[1] > 60_000) failures.put(ip, f = new long[]{0, now});
            f[0]++;
            if (failures.size() > 256) failures.clear();
        }
    }

    private static byte[] hmac(byte[] key, String msg) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Set<String> pairedIds() {
        Set<String> out = new HashSet<>();
        for (String k : devices.getAll().keySet()) if (k.endsWith("|key")) out.add(k.substring(0, k.length() - 4));
        return out;
    }

    // ---------- API ----------

    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        String[] names = {"up", "down", "left", "right", "ok", "back", "home", "menu", "playpause", "next", "prev",
                "rewind", "forward", "power", "volup", "voldown", "mute", "input", "settings", "del", "enter",
                "info", "guide", "chup", "chdown", "search"};
        int[] codes = {19, 20, 21, 22, 23, 4, 3, 82, 85, 87, 88, 89, 90, 26, 24, 25, 164, 178, 176, 67, 66,
                165, 172, 166, 167, 84};
        for (int i = 0; i < names.length; i++) KEYS.put(names[i], codes[i]);
    }

    private Response api(Request r, String me) throws Exception {
        boolean post = r.method.equals("POST");
        switch (r.path) {
            case "/api/state": return json(state());
            case "/api/apps": return json(new JSONObject().put("apps", appsJson()));
            case "/api/icon": return icon(r.query("pkg"));
            case "/api/art": return artResponse();
            case "/api/devices": return json(devicesJson(me));
            default: break;
        }
        if (!post) return error(405, "method");
        JSONObject b = r.json();
        switch (r.path) {
            case "/api/key": {
                Integer code = KEYS.get(b.optString("key"));
                if (code == null) return error(400, "key");
                if (code == 3 && !keys.tap(3, false)) return ok(goHome());
                return keys.tap(code, b.optBoolean("long")) ? ok(true) : keysUnavailable();
            }
            case "/api/text": {
                int del = Math.min(64, Math.max(0, b.optInt("del")));
                for (int i = 0; i < del; i++) if (!keys.tap(67, false)) return keysUnavailable();
                String text = b.optString("text", "");
                if (text.length() > 200) return error(413, "too_long");
                if (!keys.type(text)) return keysUnavailable();
                if (b.optBoolean("enter") && !keys.tap(66, false)) return keysUnavailable();
                return ok(true);
            }
            case "/api/volume": return ok(volume(b.optString("action"), b.optInt("value", -1)));
            case "/api/media": return ok(media(b.optString("action"), b.optLong("pos", -1)));
            case "/api/launch": return ok(launch(b.optString("pkg", ""), b.optString("target", "")));
            case "/api/layout": return ok(setLayout(b));
            case "/api/prefs": {
                SharedPreferences.Editor e = layout.edit();
                if (b.has("greet")) e.putString("greet", clean(b.optString("greet"), 24));
                if (b.has("tv")) e.putString("tv_name", clean(b.optString("tv"), 24));
                e.apply();
                return ok(true);
            }
            case "/api/devices/remove": {
                String id = b.optString("id", "");
                if (!id.matches("[0-9a-f]{16}")) return error(400, "id");
                devices.edit().remove(id + "|name").remove(id + "|key").remove(id + "|seen").apply();
                return ok(true);
            }
            case "/api/system": return system(b.optString("action"));
            default: return error(404, "not_found");
        }
    }

    private Response keysUnavailable() throws JSONException {
        return json(409, new JSONObject().put("error", "keys").put("keys", keysJson()));
    }

    private JSONObject keysJson() throws JSONException {
        return new JSONObject().put("state", keys.state().name().toLowerCase(Locale.US)).put("detail", keys.detail());
    }

    private JSONObject state() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("tv", tvName()).put("time", System.currentTimeMillis()).put("keys", keysJson());

        AudioManager am = ctx.getSystemService(AudioManager.class);
        o.put("volume", new JSONObject()
                .put("level", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC)));

        String fg = foreground();
        if (fg != null) {
            JSONObject app = new JSONObject().put("pkg", fg);
            if (fg.equals(ctx.getPackageName())) app.put("label", "Inicio").put("home", true);
            else app.put("label", label(fg)).put("color", hexColor(color(fg)));
            o.put("app", app);
        }

        MediaController c = session();
        if (c != null) {
            MediaMetadata m = c.getMetadata();
            PlaybackState st = c.getPlaybackState();
            JSONObject md = new JSONObject().put("pkg", c.getPackageName()).put("app", label(c.getPackageName()))
                    .put("color", hexColor(color(c.getPackageName())));
            if (m != null) {
                md.put("title", metaText(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE));
                md.put("sub", metaText(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                        MediaMetadata.METADATA_KEY_ALBUM));
                md.put("dur", Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION)));
                md.put("art", artId(c.getPackageName(), m));
            }
            if (st != null) {
                long pos = st.getPosition();
                if (st.getState() == PlaybackState.STATE_PLAYING && pos >= 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                }
                md.put("playing", st.getState() == PlaybackState.STATE_PLAYING).put("pos", Math.max(0, pos))
                        .put("seek", (st.getActions() & PlaybackState.ACTION_SEEK_TO) != 0);
            }
            o.put("media", md);
        }

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        o.put("ram", new JSONObject().put("free", mi.availMem / (1024 * 1024)).put("total", mi.totalMem / (1024 * 1024)));
        return o;
    }

    /** App en primer plano según las estadísticas de uso (permiso que install.ps1 ya concede). */
    private synchronized String foreground() {
        long now = System.currentTimeMillis();
        if (now - fgAt < 1500) return fgPkg;
        // Solo los eventos nuevos desde la última consulta (la primera vez, las últimas 6 h).
        long from = fgPkg == null ? now - 6 * 60 * 60_000L : fgAt - 2000;
        fgAt = now;
        try {
            UsageEvents ev = ctx.getSystemService(UsageStatsManager.class).queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && isApp(e.getPackageName())) last = e.getPackageName();
            }
            if (last != null) fgPkg = last;
        } catch (Exception ignored) {
            // sin permiso de uso
        }
        return fgPkg;
    }

    private final Map<String, Boolean> launchable = new HashMap<>();

    /** Diálogos del sistema (permisos, volumen…) no cuentan como "lo que se ve en la TV". */
    private boolean isApp(String pkg) {
        Boolean b = launchable.get(pkg);
        if (b == null) {
            PackageManager pm = ctx.getPackageManager();
            b = pkg.equals(ctx.getPackageName()) || pm.getLeanbackLaunchIntentForPackage(pkg) != null
                    || pm.getLaunchIntentForPackage(pkg) != null;
            launchable.put(pkg, b);
        }
        return b;
    }

    private MediaController session() {
        try {
            List<MediaController> list = ctx.getSystemService(MediaSessionManager.class)
                    .getActiveSessions(new ComponentName(ctx, MediaWatcher.class));
            MediaController pick = null;
            for (MediaController c : list) {
                if (c.getPackageName().equals(ctx.getPackageName())) continue;
                PlaybackState st = c.getPlaybackState();
                int s = st == null ? PlaybackState.STATE_NONE : st.getState();
                if (c.getMetadata() == null || s == PlaybackState.STATE_NONE || s == PlaybackState.STATE_STOPPED
                        || s == PlaybackState.STATE_ERROR) continue;
                if (pick == null || s == PlaybackState.STATE_PLAYING) pick = c;
                if (s == PlaybackState.STATE_PLAYING) break;
            }
            return pick;
        } catch (SecurityException e) {
            return null; // MediaWatcher sin permiso
        }
    }

    private boolean media(String action, long pos) {
        MediaController c = session();
        if (c == null) {
            Integer k = action.equals("toggle") ? Integer.valueOf(85) : action.equals("next") ? Integer.valueOf(87)
                    : action.equals("prev") ? Integer.valueOf(88) : null;
            return k != null && keys.tap(k, false);
        }
        MediaController.TransportControls t = c.getTransportControls();
        PlaybackState st = c.getPlaybackState();
        switch (action) {
            case "toggle":
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                return true;
            case "next": t.skipToNext(); return true;
            case "prev": t.skipToPrevious(); return true;
            case "seek":
                if (pos < 0) return false;
                t.seekTo(pos);
                return true;
            case "back10":
            case "fwd30": {
                if (st == null) return false;
                long p = st.getPosition() + (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                        * (st.getState() == PlaybackState.STATE_PLAYING ? st.getPlaybackSpeed() : 0));
                t.seekTo(Math.max(0, p + (action.equals("fwd30") ? 30_000 : -10_000)));
                return true;
            }
            default: return false;
        }
    }

    private boolean volume(String action, int value) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        int s = AudioManager.STREAM_MUSIC, f = AudioManager.FLAG_SHOW_UI;
        switch (action) {
            case "up": am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f); return true;
            case "down": am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f); return true;
            case "mute": am.adjustStreamVolume(s, AudioManager.ADJUST_TOGGLE_MUTE, f); return true;
            case "set":
                if (value < 0 || value > am.getStreamMaxVolume(s)) return false;
                am.setStreamVolume(s, value, f);
                return true;
            default: return false;
        }
    }

    private boolean launch(String pkg, String target) {
        Intent i = null;
        switch (target) {
            case "home": return goHome();
            case "settings": i = new Intent(Settings.ACTION_SETTINGS); break;
            case "wifi": i = new Intent(Settings.ACTION_WIFI_SETTINGS); break;
            case "inputs":
                i = new Intent("com.android.tv.action.VIEW_INPUTS");
                if (ctx.getPackageManager().resolveActivity(i, 0) == null) {
                    i = ctx.getPackageManager().getLaunchIntentForPackage("com.mediatek.wwtv.tvcenter");
                }
                break;
            default:
                if (!pkg.matches("[A-Za-z0-9_.]{1,200}")) return false;
                PackageManager pm = ctx.getPackageManager();
                i = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) launches.edit().putLong(pkg, System.currentTimeMillis()).apply();
                break;
        }
        return start(i);
    }

    private boolean goHome() {
        return start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.getPackageName()));
    }

    private boolean start(Intent i) {
        if (i == null) return false;
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setLayout(JSONObject b) throws JSONException {
        SharedPreferences.Editor e = layout.edit();
        if (b.has("pinned")) {
            List<String> list = packages(b.getJSONArray("pinned"));
            e.putString("pinned", TextUtils.join(",", list));
        }
        if (b.has("excluded")) e.putStringSet("excluded", new HashSet<>(packages(b.getJSONArray("excluded"))));
        e.apply();
        return true;
    }

    private static List<String> packages(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length() && out.size() < 60; i++) {
            String p = a.getString(i);
            if (p.matches("[A-Za-z0-9_.]{1,200}") && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private Response system(String action) throws JSONException {
        switch (action) {
            case "sleep":
                return keys.tap(26, false) ? ok(true) : keysUnavailable();
            case "clean": {
                ActivityManager am = ctx.getSystemService(ActivityManager.class);
                ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(before);
                String fg = foreground();
                Set<String> keep = new HashSet<>();
                Collections.addAll(keep, ctx.getResources().getStringArray(R.array.music_apps));
                MediaController c = session();
                if (c != null) keep.add(c.getPackageName());
                int n = 0;
                for (ResolveInfo ri : leanbackApps()) {
                    String p = ri.activityInfo.packageName;
                    if (p.equals(fg) || keep.contains(p) || p.equals(ctx.getPackageName())) continue;
                    am.killBackgroundProcesses(p);
                    n++;
                }
                SystemClock.sleep(600);
                ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(after);
                return json(new JSONObject().put("ok", true).put("apps", n)
                        .put("freed", Math.max(0, (after.availMem - before.availMem) / (1024 * 1024)))
                        .put("free", after.availMem / (1024 * 1024)));
            }
            default:
                return error(400, "action");
        }
    }

    private JSONObject devicesJson(String me) throws JSONException {
        JSONArray a = new JSONArray();
        for (String id : pairedIds()) {
            a.put(new JSONObject().put("id", id).put("name", devices.getString(id + "|name", "Celular"))
                    .put("seen", devices.getLong(id + "|seen", 0)).put("me", id.equals(me)));
        }
        return new JSONObject().put("devices", a).put("greet", layout.getString("greet", ""))
                .put("tv", tvName());
    }

    private String tvName() {
        String n = layout.getString("tv_name", "");
        return n.isEmpty() ? "Sala" : n;
    }

    // ---------- Apps e imágenes ----------

    private List<ResolveInfo> leanbackApps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        return ctx.getPackageManager().queryIntentActivities(q, 0);
    }

    private JSONArray appsJson() throws JSONException {
        PackageManager pm = ctx.getPackageManager();
        Set<String> hidden = new HashSet<>();
        Collections.addAll(hidden, ctx.getResources().getStringArray(R.array.hidden_apps));
        hidden.add(ctx.getPackageName());
        List<String> pinned = new ArrayList<>();
        for (String p : layout.getString("pinned", "").split(",")) if (!p.isEmpty()) pinned.add(p);
        Set<String> excluded = layout.getStringSet("excluded", Collections.emptySet());
        Map<String, JSONObject> out = new LinkedHashMap<>();
        for (ResolveInfo ri : leanbackApps()) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || out.containsKey(ai.packageName)) continue;
            out.put(ai.packageName, new JSONObject().put("pkg", ai.packageName).put("label", String.valueOf(ri.loadLabel(pm)))
                    .put("color", hexColor(color(ai.packageName)))
                    .put("pinned", pinned.indexOf(ai.packageName))
                    .put("excluded", excluded.contains(ai.packageName))
                    .put("used", launches.getLong(ai.packageName, 0))
                    .put("system", (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0));
        }
        List<JSONObject> list = new ArrayList<>(out.values());
        Collections.sort(list, (x, y) -> x.optString("label").compareToIgnoreCase(y.optString("label")));
        return new JSONArray(list);
    }

    private Drawable banner(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable d = null;
            if (i != null && i.getComponent() != null) d = pm.getActivityBanner(i.getComponent());
            if (d == null) d = pm.getApplicationBanner(pkg);
            return d;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Response icon(String pkg) throws Exception {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]{1,200}")) return error(400, "pkg");
        byte[] png = icons.get(pkg);
        if (png == null) {
            Drawable d = banner(pkg);
            boolean isBanner = d != null;
            if (d == null) {
                try { d = ctx.getPackageManager().getApplicationIcon(pkg); }
                catch (PackageManager.NameNotFoundException e) { return error(404, "not_found"); }
            }
            int w = isBanner ? 320 : 160, h = isBanner ? 180 : 160;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Drawable c = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            c.setBounds(0, 0, w, h);
            c.draw(new Canvas(b));
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            b.recycle();
            png = o.toByteArray();
            icons.put(pkg, png);
        }
        return new Response(200, "image/png", png).cache("private, max-age=86400");
    }

    private final Map<String, Integer> colors = new HashMap<>();

    private int color(String pkg) {
        synchronized (colors) {
            Integer c = colors.get(pkg);
            if (c != null) return c;
        }
        Drawable d = banner(pkg);
        if (d == null) {
            try { d = ctx.getPackageManager().getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
        }
        int c = MainActivity.glowColor(d);
        synchronized (colors) { colors.put(pkg, c); }
        return c;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Identificador de la carátula actual; el panel la pide de nuevo solo cuando cambia. */
    private synchronized String artId(String pkg, MediaMetadata m) {
        Bitmap b = null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            if ((b = m.getBitmap(k)) != null) break;
        }
        String uri = null;
        if (b == null) {
            uri = metaText(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (uri != null) b = NowPlaying.loadPoster(ctx, uri, 360);
        }
        if (b == null) return null;
        String key = pkg + ":" + (uri != null ? Integer.toHexString(uri.hashCode()) : NowPlaying.fingerprint(b));
        if (!key.equals(artKey)) {
            int w = Math.min(360, b.getWidth());
            Bitmap small = Bitmap.createScaledBitmap(b, w, Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth())), true);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 85, o);
            if (small != b) small.recycle();
            art = o.toByteArray();
            artKey = key;
        }
        return artKey;
    }

    private synchronized Response artResponse() {
        if (art == null) return error(404, "no_art");
        return new Response(200, "image/jpeg", art).cache("private, max-age=3600");
    }

    private static String metaText(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString().trim();
        }
        return null;
    }

    // ---------- Panel ----------

    private synchronized Response page(Request r) throws IOException {
        if (page == null) {
            try (InputStream s = ctx.getResources().openRawResource(R.raw.remote)) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = s.read(buf)) > 0; ) o.write(buf, 0, n);
                page = o.toByteArray();
            }
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (GZIPOutputStream z = new GZIPOutputStream(gz)) { z.write(page); }
            pageGz = gz.toByteArray();
            pageTag = "\"" + hex(sha256(page)).substring(0, 16) + "\"";
        }
        if (pageTag.equals(r.header("if-none-match"))) return new Response(304, null, new byte[0]).header("ETag", pageTag);
        String ae = r.header("accept-encoding");
        boolean gzip = ae != null && ae.contains("gzip");
        Response res = new Response(200, "text/html; charset=utf-8", gzip ? pageGz : page).cache("no-cache")
                .header("ETag", pageTag)
                .header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                        + "style-src 'unsafe-inline'; img-src 'self' blob: data:; connect-src 'self'; "
                        + "manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        if (gzip) res.header("Content-Encoding", "gzip");
        return res;
    }

    /** Ícono de la app web en el iPhone (180×180), dibujado aquí para no cargar otra imagen en el APK. */
    private synchronized byte[] appIcon() {
        if (appIcon != null) return appIcon;
        int s = 180;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, s, s, 0xFF2B3A55, 0xFF0B0F17, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(null);
        p.setColor(0x26FFFFFF);
        c.drawRoundRect(new RectF(58, 26, 122, 154), 32, 32, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(0x66FFFFFF);
        c.drawRoundRect(new RectF(58, 26, 122, 154), 32, 32, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawCircle(90, 64, 20, p);
        p.setColor(0xB3FFFFFF);
        c.drawCircle(76, 112, 6, p);
        c.drawCircle(104, 112, 6, p);
        c.drawRoundRect(new RectF(80, 130, 100, 136), 3, 3, p);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        b.recycle();
        return appIcon = o.toByteArray();
    }

    // ---------- Utilidades ----------

    private static String clean(String s, int max) {
        s = s == null ? "" : s.replaceAll("[\\p{Cntrl}<>]", "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String hexColor(int c) { return String.format(Locale.US, "#%06X", c & 0xFFFFFF); }

    private static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString();
    }

    private static Response json(JSONObject o) { return json(200, o); }

    private static Response json(int status, JSONObject o) {
        return new Response(status, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Response ok(boolean ok) throws JSONException {
        return ok ? json(new JSONObject().put("ok", true)) : error(422, "failed");
    }

    private static Response error(int status, String code) {
        try {
            return json(status, new JSONObject().put("error", code));
        } catch (JSONException e) {
            return new Response(status, "text/plain", new byte[0]);
        }
    }

    // ---------- HTTP ----------

    private static final class Request {
        String method, target, path;
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
        String[] auth;

        static Request read(InputStream in) throws IOException {
            String line = readLine(in);
            if (line == null) return null;
            if (line.isEmpty()) line = readLine(in); // CRLF suelto entre peticiones
            if (line == null) return null;
            String[] p = line.split(" ");
            if (p.length != 3 || !p[2].startsWith("HTTP/1.")) throw new IOException("petición inválida");
            Request r = new Request();
            r.method = p[0];
            r.target = p[1];
            int q = r.target.indexOf('?');
            r.path = q >= 0 ? r.target.substring(0, q) : r.target;
            int total = 0;
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                if ((total += h.length()) > 8192 || r.headers.size() > 64) throw new IOException("cabeceras demasiado grandes");
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.US), h.substring(c + 1).trim());
            }
            String cl = r.headers.get("content-length");
            if (cl != null) {
                int n = Integer.parseInt(cl.trim());
                if (n < 0 || n > MAX_BODY) throw new IOException("cuerpo demasiado grande");
                r.body = new byte[n];
                for (int off = 0; off < n; ) {
                    int k = in.read(r.body, off, n - off);
                    if (k < 0) throw new IOException("cuerpo incompleto");
                    off += k;
                }
            } else if (r.headers.containsKey("transfer-encoding")) {
                throw new IOException("sin soporte para chunked");
            }
            return r;
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder s = new StringBuilder();
            for (int c; (c = in.read()) >= 0; ) {
                if (c == '\n') {
                    int n = s.length();
                    if (n > 0 && s.charAt(n - 1) == '\r') s.setLength(n - 1);
                    return s.toString();
                }
                if (s.length() > 8192) throw new IOException("línea demasiado larga");
                s.append((char) c);
            }
            return s.length() == 0 ? null : s.toString();
        }

        String header(String name) { return headers.get(name); }

        String bodyText() { return new String(body, StandardCharsets.UTF_8); }

        JSONObject json() throws JSONException {
            String t = bodyText().trim();
            return t.isEmpty() ? new JSONObject() : new JSONObject(t);
        }

        String query(String name) {
            int q = target.indexOf('?');
            if (q < 0) return null;
            for (String kv : target.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0 && kv.substring(0, e).equals(name)) return android.net.Uri.decode(kv.substring(e + 1));
            }
            return null;
        }
    }

    private static final class Response {
        final int status;
        final String type;
        final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();

        Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
            headers.put("Cache-Control", "no-store");
        }

        Response cache(String v) { headers.put("Cache-Control", v); return this; }

        Response header(String k, String v) { headers.put(k, v); return this; }

        void write(OutputStream out, boolean keepAlive) throws IOException {
            StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
            h.append("Content-Length: ").append(body.length).append("\r\n");
            h.append("X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Frame-Options: DENY\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            h.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
        }

        private static String reason(int s) {
            switch (s) {
                case 200: return "OK";
                case 304: return "Not Modified";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 409: return "Conflict";
                case 410: return "Gone";
                case 413: return "Payload Too Large";
                case 421: return "Misdirected Request";
                case 422: return "Unprocessable Entity";
                case 429: return "Too Many Requests";
                default: return "Internal Server Error";
            }
        }
    }
}
