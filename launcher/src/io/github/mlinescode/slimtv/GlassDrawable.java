package io.github.mlinescode.slimtv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Vidrio esmerilado barato. Android 11 no puede desenfocar en vivo (eso llegó en Android 12),
 * así que dibuja el recorte de una copia del fondo ya desenfocada (160×90 px) que prepara
 * WallpaperView. Encima: tinte, brillo superior y borde fino. Solo para paneles que no se mueven.
 */
public class GlassDrawable extends Drawable {
    private final View host;
    private final WallpaperView wallpaper;
    private final float radius;
    private int tintAlpha;
    private final Paint blurA = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint blurB = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private Bitmap shaderA, shaderB;
    private int alpha = 255;

    public GlassDrawable(View host, WallpaperView wallpaper, float radius, int tint, float strokeWidth) {
        this.host = host;
        this.wallpaper = wallpaper;
        this.radius = radius;
        this.tintAlpha = tint >>> 24;
        fill.setColor(tint);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeWidth);
        stroke.setColor(Color.WHITE_EDGE);
        wallpaper.addGlass(this);
    }

    @Override public void draw(Canvas c) {
        if (rect.isEmpty()) return;
        host.getLocationInWindow(loc);
        shaderA = layer(blurA, wallpaper.blurCurrent(), shaderA, 255);
        if (shaderA != null) c.drawRoundRect(rect, radius, radius, blurA);
        Bitmap next = wallpaper.blurNext();
        if (next != null) {
            shaderB = layer(blurB, next, shaderB, Math.round(wallpaper.fade() * 255));
            c.drawRoundRect(rect, radius, radius, blurB);
        }

        fill.setAlpha(tintAlpha * alpha / 255);
        c.drawRoundRect(rect, radius, radius, fill);
        sheen.setAlpha(alpha);
        c.drawRoundRect(rect, radius, radius, sheen);

        if (stroke.getStrokeWidth() <= 0) return; // ancho 0 en Android = línea de 1 px
        float h = stroke.getStrokeWidth() / 2f;
        stroke.setAlpha((Color.WHITE_EDGE >>> 24) * alpha / 255);
        c.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, radius, radius, stroke);
    }

    /** Prepara el shader que estira el bitmap pequeño a pantalla completa, desplazado a este panel. */
    private Bitmap layer(Paint p, Bitmap b, Bitmap prev, int a) {
        if (b == null) return null;
        if (b != prev) p.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        matrix.setScale(wallpaper.getWidth() / (float) b.getWidth(), wallpaper.getHeight() / (float) b.getHeight());
        matrix.postTranslate(-loc[0], -loc[1]);
        p.getShader().setLocalMatrix(matrix);
        p.setAlpha(a * alpha / 255);
        return b;
    }

    @Override protected void onBoundsChange(Rect b) {
        rect.set(b);
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + Math.min(b.height(), radius * 3),
                0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    /** Cambia el tinte (color con alfa). Se redibuja una sola vez, no por cuadro. */
    public void setTint(int tint) {
        tintAlpha = tint >>> 24;
        fill.setColor(tint);
        host.invalidate();
    }

    /** Lo llama WallpaperView cuando cambia el fondo o durante el fundido. */
    void refresh() {
        invalidateSelf();
        host.invalidate();
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    private static final class Color {
        static final int WHITE_EDGE = 0x33FFFFFF;
    }
}
