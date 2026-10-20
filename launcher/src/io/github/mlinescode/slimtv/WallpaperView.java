package io.github.mlinescode.slimtv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;

import java.util.Calendar;
import java.util.Random;

/**
 * Paisajes generados por código (cielo + sol/luna + tres capas de montañas).
 * Se renderizan a media resolución y se escalan, así solo ocupan ~1 MB de RAM
 * en vez de cargar fotos. Cambian con un fundido cada N minutos (config.xml).
 */
public class WallpaperView extends View {
    private final long rotateMs;
    private static final long FADE_MS = 2500;
    private static final int W = 640, H = 360;

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
    private int index;
    private Bitmap current, next;
    private float fade;
    private boolean running;

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
    }

    public void start() {
        if (running) return;
        running = true;
        if (current == null) current = render(index);
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
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        anim.addUpdateListener(a -> { fade = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (current != null) current.recycle();
                current = next;
                next = null;
                fade = 0f;
                invalidate();
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
            p.setColor(s[3 + layer]);
            c.drawPath(path, p);
        }
        return b;
    }
}
