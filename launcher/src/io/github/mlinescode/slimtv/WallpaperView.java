package io.github.mlinescode.slimtv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * Paisajes generados por código (cielo + sol/luna + tres capas de montañas con bruma).
 * Se renderizan a media resolución y se escalan, así solo ocupan ~1 MB de RAM
 * en vez de cargar fotos. Cambian con un fundido cada N minutos (config.xml).
 * De cada paisaje se guarda además una copia desenfocada de 160×90 para el efecto vidrio.
 */
public class WallpaperView extends View {
    private static final long FADE_MS = 2500;
    private static final int W = 640, H = 360;
    private static final int BW = 160, BH = 90;

    // cielo arriba, cielo abajo, sol/luna, montaña lejana, media, cercana, estrellas
    private static final int[][] SCENES = {
            {0xFF0B1026, 0xFF2B2F77, 0xFFF4F1DE, 0xFF1F2457, 0xFF151A40, 0xFF0B0D24, 1}, // noche
            {0xFF2E1F5E, 0xFFF08A5D, 0xFFFFD6A5, 0xFF6B3E75, 0xFF472D5C, 0xFF261A3A, 0}, // atardecer
            {0xFF1E3C72, 0xFF8CBEDD, 0xFFFFF5CC, 0xFF55819F, 0xFF2F5D7C, 0xFF1C3B4F, 0}, // día
            {0xFF0F2027, 0xFF2C5364, 0xFFB8F2E6, 0xFF24434D, 0xFF16303A, 0xFF0B1C22, 1}, // lago
            {0xFF3A1C71, 0xFFD76D77, 0xFFFFC48C, 0xFF5B2A6E, 0xFF3D1F52, 0xFF1F1030, 0}, // magenta
            {0xFF0D3B2E, 0xFFA7CBA0, 0xFFFFF1C1, 0xFF2D6A4F, 0xFF1B4332, 0xFF081C15, 0}, // bosque
    };

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect dst = new Rect();
    private final List<GlassDrawable> glass = new ArrayList<>();
    private int index;
    private Bitmap current, next, blurCurrent, blurNext;
    private float fade;
    private boolean running;
    private long rotateMs;

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            advance();
            postDelayed(this, rotateMs);
        }
    };

    public WallpaperView(Context context) {
        super(context);
        rotateMs = context.getResources().getInteger(R.integer.wallpaper_minutes) * 60_000L;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        index = hour < 6 || hour >= 20 ? 0 : hour < 9 ? 4 : hour < 17 ? 2 : 1;
        current = render(index);
        blurCurrent = blur(current);
    }

    void addGlass(GlassDrawable g) { glass.add(g); }
    void setRotateMs(long ms) { rotateMs = ms; }
    Bitmap blurCurrent() { return blurCurrent; }
    Bitmap blurNext() { return blurNext; }
    float fade() { return fade; }

    public void start() {
        if (running) return;
        running = true;
        postDelayed(rotate, rotateMs);
        invalidate();
    }

    public void stop() {
        running = false;
        removeCallbacks(rotate);
    }

    private void advance() {
        if (next != null) return;
        index = (index + 1) % SCENES.length;
        next = render(index);
        blurNext = blur(next);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        anim.addUpdateListener(a -> {
            fade = (float) a.getAnimatedValue();
            invalidate();
            for (GlassDrawable g : glass) g.refresh();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                current = next;
                blurCurrent = blurNext;
                next = null;
                blurNext = null;
                fade = 0f;
                invalidate();
                for (GlassDrawable g : glass) g.refresh();
            }
        });
        anim.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        dst.set(0, 0, getWidth(), getHeight());
        if (current != null) {
            bitmapPaint.setAlpha(255);
            canvas.drawBitmap(current, null, dst, bitmapPaint);
        }
        if (next != null) {
            bitmapPaint.setAlpha(Math.round(fade * 255));
            canvas.drawBitmap(next, null, dst, bitmapPaint);
        }
    }

    private static Bitmap render(int i) {
        int[] s = SCENES[i];
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

        p.setShader(new LinearGradient(0, 0, 0, H * 0.75f, s[0], s[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        Random rnd = new Random(i * 7919L + 17);
        if (s[6] == 1) {
            for (int k = 0; k < 140; k++) {
                p.setColor(0xFFFFFFFF);
                p.setAlpha(40 + rnd.nextInt(180));
                c.drawCircle(rnd.nextFloat() * W, rnd.nextFloat() * H * 0.6f, 0.4f + rnd.nextFloat() * 0.9f, p);
            }
            p.setAlpha(255);
        }

        float sx = W * (0.62f + rnd.nextFloat() * 0.2f), sy = H * (0.22f + rnd.nextFloat() * 0.15f);
        int glow = (s[2] & 0x00FFFFFF) | 0x66000000;
        p.setShader(new RadialGradient(sx, sy, 150, glow, s[2] & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(sx, sy, 150, p);
        p.setShader(null);
        p.setColor(s[2]);
        c.drawCircle(sx, sy, 22, p);

        float[] base = {0.58f, 0.70f, 0.82f};
        float[] amp = {46, 36, 26};
        for (int layer = 0; layer < 3; layer++) {
            float f1 = 0.006f + rnd.nextFloat() * 0.006f, f2 = 0.017f + rnd.nextFloat() * 0.01f;
            float p1 = rnd.nextFloat() * 6.28f, p2 = rnd.nextFloat() * 6.28f;
            Path path = new Path();
            path.moveTo(0, H);
            for (int x = 0; x <= W; x += 6) {
                double y = H * base[layer]
                        - amp[layer] * (0.65 * Math.sin(x * f1 + p1) + 0.35 * Math.sin(x * f2 + p2));
                path.lineTo(x, (float) y);
            }
            path.lineTo(W, H);
            path.close();
            // Perspectiva atmosférica: la cresta se funde con el cielo y oscurece hacia abajo.
            int col = s[3 + layer];
            float top = H * base[layer] - amp[layer];
            p.setShader(new LinearGradient(0, top, 0, top + amp[layer] * 2.4f,
                    mix(col, s[1], 0.38f - layer * 0.1f), col, Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
        }

        // Viñeta suave para que el contenido respire.
        p.setShader(new RadialGradient(W * 0.5f, H * 0.45f, W * 0.75f,
                new int[]{0x00000000, 0x00000000, 0x66000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        return b;
    }

    /** Copia de 160×90 con desenfoque (3 pasadas de caja ≈ gaussiano) y algo más de saturación. */
    private static Bitmap blur(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, BW, BH, true);
        int n = BW * BH;
        int[] a = new int[n], t = new int[n];
        small.getPixels(a, 0, BW, 0, 0, BW, BH);
        for (int pass = 0; pass < 3; pass++) {
            boxH(a, t, 3);
            boxV(t, a, 3);
        }
        float[] hsv = new float[3];
        for (int k = 0; k < n; k++) {
            Color.colorToHSV(a[k], hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.3f);
            hsv[2] = Math.min(1f, hsv[2] * 1.08f + 0.02f);
            a[k] = Color.HSVToColor(hsv);
        }
        small.setPixels(a, 0, BW, 0, 0, BW, BH);
        return small;
    }

    private static void boxH(int[] in, int[] out, int r) {
        int d = r * 2 + 1;
        for (int y = 0; y < BH; y++) {
            int row = y * BW, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, BW)];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int x = 0; x < BW; x++) {
                out[row + x] = 0xFF000000 | (sr / d) << 16 | (sg / d) << 8 | sb / d;
                int pa = in[row + clamp(x - r, BW)], pb = in[row + clamp(x + r + 1, BW)];
                sr += ((pb >> 16) & 255) - ((pa >> 16) & 255);
                sg += ((pb >> 8) & 255) - ((pa >> 8) & 255);
                sb += (pb & 255) - (pa & 255);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int r) {
        int d = r * 2 + 1;
        for (int x = 0; x < BW; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, BH) * BW + x];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int y = 0; y < BH; y++) {
                out[y * BW + x] = 0xFF000000 | (sr / d) << 16 | (sg / d) << 8 | sb / d;
                int pa = in[clamp(y - r, BH) * BW + x], pb = in[clamp(y + r + 1, BH) * BW + x];
                sr += ((pb >> 16) & 255) - ((pa >> 16) & 255);
                sg += ((pb >> 8) & 255) - ((pa >> 8) & 255);
                sb += (pb & 255) - (pa & 255);
            }
        }
    }

    private static int clamp(int v, int max) { return v < 0 ? 0 : v >= max ? max - 1 : v; }

    private static int mix(int a, int b, float f) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }
}
