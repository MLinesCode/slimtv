package io.github.mlinescode.slimtv;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Observa las sesiones de reproducción de las apps y guarda en NowPlaying lo que cada una reproduce.
 * Es un "lector de notificaciones" solo porque Android exige ese permiso para ver las sesiones;
 * no lee ninguna notificación. Se activa con:
 *   adb shell cmd notification allow_listener io.github.mlinescode.slimtv/io.github.mlinescode.slimtv.MediaWatcher
 * Corre dentro del proceso del launcher (sin proceso ni RAM extra significativa).
 */
public class MediaWatcher extends NotificationListenerService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSessionToken, Watch> watches = new HashMap<>();
    private MediaSessionManager msm;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::track;

    /** Lo pone MainActivity: si arrancó antes de tener el permiso, se reintenta al conectar. */
    static Runnable onConnected;

    @Override public void onListenerConnected() {
        Runnable r = onConnected;
        if (r != null) handler.post(r);
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            track(msm.getActiveSessions(me));
        } catch (SecurityException ignored) {
            // El permiso se retiró.
        }
    }

    @Override public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        for (Watch w : watches.values()) w.controller.unregisterCallback(w);
        watches.clear();
    }

    private void track(List<MediaController> controllers) {
        if (controllers == null) return;
        Map<MediaSessionToken, Watch> keep = new HashMap<>();
        for (MediaController c : controllers) {
            if (getPackageName().equals(c.getPackageName())) continue;
            MediaSessionToken key = new MediaSessionToken(c);
            Watch w = watches.remove(key);
            if (w == null) {
                w = new Watch(c);
                c.registerCallback(w, handler);
                w.onMetadataChanged(c.getMetadata());
            }
            keep.put(key, w);
        }
        // Sesiones que terminaron: se guarda su último estado y se dejan de observar.
        for (Watch w : watches.values()) {
            w.save();
            w.controller.unregisterCallback(w);
        }
        watches.clear();
        watches.putAll(keep);
    }

    private final class Watch extends MediaController.Callback {
        final MediaController controller;
        NowPlaying.Program program;

        Watch(MediaController c) { controller = c; }

        @Override public void onMetadataChanged(MediaMetadata m) {
            if (m == null) return;
            String title = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) return; // Netflix: sesión sin título, no hay nada útil
            String sub = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                    MediaMetadata.METADATA_KEY_ALBUM);
            sub = sub == null || sub.equals(title) ? "" : sub;
            Bitmap art = bitmap(m, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            String artUri = text(m, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            // Spotify manda primero el título nuevo (a veces con la imagen anterior) y la imagen buena
            // después, con el mismo título: hay que comparar también la imagen.
            String artId = art != null ? "bmp:" + NowPlaying.fingerprint(art) : artUri;
            boolean sameText = program != null && title.equals(program.title) && sub.equals(program.subtitle);
            if (sameText && TextUtils.equals(artId, artFingerprint)) return;

            NowPlaying.Program p = sameText ? program : new NowPlaying.Program();
            p.pkg = controller.getPackageName();
            p.title = title;
            p.subtitle = sub;
            p.duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            if (!TextUtils.equals(artId, artFingerprint)) {
                p.art = art != null ? NowPlaying.saveArt(MediaWatcher.this, p.pkg, art) : artUri;
                artFingerprint = artId;
            }
            program = p;
            save();
        }

        private String artFingerprint;

        @Override public void onPlaybackStateChanged(PlaybackState s) {
            save();
        }

        @Override public void onSessionDestroyed() {
            save();
        }

        void save() {
            if (program == null) return;
            PlaybackState s = controller.getPlaybackState();
            if (s != null && s.getPosition() >= 0) {
                long pos = s.getPosition();
                if (s.getState() == PlaybackState.STATE_PLAYING) {
                    pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
                }
                if (pos > 0) program.position = pos;
            }
            if (program.duration <= 0) {
                MediaMetadata m = controller.getMetadata();
                if (m != null) program.duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            }
            program.engaged = System.currentTimeMillis();
            NowPlaying.save(MediaWatcher.this, program);
            Runnable r = NowPlaying.onChange;
            if (r != null) r.run();
        }
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString().trim();
        }
        return null;
    }

    private static Bitmap bitmap(MediaMetadata m, String... keys) {
        for (String k : keys) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    /** Identifica una sesión (MediaController no implementa equals). */
    private static final class MediaSessionToken {
        final Object token;
        MediaSessionToken(MediaController c) { token = c.getSessionToken(); }
        @Override public boolean equals(Object o) { return o instanceof MediaSessionToken && token.equals(((MediaSessionToken) o).token); }
        @Override public int hashCode() { return token.hashCode(); }
    }
}
