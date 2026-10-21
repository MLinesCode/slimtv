package io.github.mlinescode.slimtv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * "Lo último que estabas viendo" en cada app. Lo guarda MediaWatcher a partir de las sesiones de
 * reproducción (MediaSession), porque Android 11 solo deja leer Watch Next al launcher de Google.
 * Netflix no publica título en su sesión, así que para Netflix no hay tarjeta.
 */
final class NowPlaying {
    static final class Program {
        String pkg, title, subtitle, art;
        long position, duration, engaged;
    }

    private static final String PREFS = "now_playing";
    /** Lo pone MainActivity para enterarse cuando algo cambia (se llama en el hilo principal). */
    static Runnable onChange;

    private NowPlaying() { }

    static Map<String, Program> load(Context ctx) {
        Map<String, Program> out = new HashMap<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String key : sp.getAll().keySet()) {
            if (!key.endsWith("|title")) continue;
            String pkg = key.substring(0, key.length() - 6);
            Program p = new Program();
            p.pkg = pkg;
            p.title = sp.getString(pkg + "|title", null);
            p.subtitle = sp.getString(pkg + "|sub", "");
            p.art = sp.getString(pkg + "|art", null);
            p.position = sp.getLong(pkg + "|pos", 0);
            p.duration = sp.getLong(pkg + "|dur", 0);
            p.engaged = sp.getLong(pkg + "|time", 0);
            if (p.title != null) out.put(pkg, p);
        }
        return out;
    }

    static void save(Context ctx, Program p) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(p.pkg + "|title", p.title)
                .putString(p.pkg + "|sub", p.subtitle)
                .putString(p.pkg + "|art", p.art)
                .putLong(p.pkg + "|pos", p.position)
                .putLong(p.pkg + "|dur", p.duration)
                .putLong(p.pkg + "|time", p.engaged)
                .apply();
    }

    /** Borra lo guardado de una app (opción "Olvidar" del menú). */
    static void forget(Context ctx, String pkg) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (String k : new String[]{"title", "sub", "art", "pos", "dur", "time"}) e.remove(pkg + "|" + k);
        e.apply();
        File[] old = new File(ctx.getFilesDir(), "art").listFiles((d, n) -> n.startsWith(pkg + "_"));
        if (old != null) for (File o : old) o.delete();
    }

    /**
     * Guarda la portada reducida (JPEG ~350 px) y devuelve su URI; borra la anterior de esa app.
     * Nombre único por imagen: así la caché de portadas nunca devuelve la de antes.
     */
    static String saveArt(Context ctx, String pkg, Bitmap b) {
        if (b == null) return null;
        File dir = new File(ctx.getFilesDir(), "art");
        dir.mkdirs();
        File[] old = dir.listFiles((d, n) -> n.startsWith(pkg + "_"));
        if (old != null) for (File o : old) o.delete();
        File f = new File(dir, pkg + "_" + Long.toHexString(System.currentTimeMillis()) + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            int w = Math.min(352, b.getWidth());
            Bitmap small = Bitmap.createScaledBitmap(b, w, Math.max(1, b.getHeight() * w / b.getWidth()), true);
            small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            if (small != b) small.recycle();
            return Uri.fromFile(f).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Huella barata de una imagen: tamaño + 25 píxeles muestreados. Distingue una carátula de otra. */
    static String fingerprint(Bitmap b) {
        int h = b.getWidth() * 31 + b.getHeight();
        for (int i = 1; i <= 5; i++) {
            for (int j = 1; j <= 5; j++) {
                h = h * 31 + b.getPixel(b.getWidth() * i / 6, b.getHeight() * j / 6);
            }
        }
        return Integer.toHexString(h);
    }

    // ---------- Portadas ----------

    private static final LruCache<String, Bitmap> POSTERS = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static Bitmap cachedPoster(String uri) { return uri == null ? null : POSTERS.get(uri); }

    /** Carga y reduce la portada local a ~targetW px de ancho (RGB_565: la mitad de memoria). */
    static Bitmap loadPoster(Context ctx, String uri, int targetW) {
        if (uri == null) return null;
        Bitmap hit = POSTERS.get(uri);
        if (hit != null) return hit;
        try {
            byte[] data = read(ctx.getContentResolver(), uri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= targetW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null) POSTERS.put(uri, b);
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Solo portadas locales (content:// o file://): SlimTV no usa internet. */
    private static byte[] read(ContentResolver cr, String uri) throws Exception {
        if (!uri.startsWith("content:") && !uri.startsWith("file:") && !uri.startsWith("android.resource:")) {
            throw new IllegalArgumentException("solo portadas locales");
        }
        try (InputStream s = cr.openInputStream(Uri.parse(uri)); ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = s.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > 4 * 1024 * 1024) throw new IllegalStateException("portada demasiado grande");
            }
            return out.toByteArray();
        }
    }
}
