package io.github.mlinescode.slimtv;

import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Proceso aparte que corre como usuario "shell" (lo arranca {@link Adb} con app_process) y es el
 * único que puede inyectar teclas en otras apps. Se queda vivo leyendo órdenes de su entrada:
 * así cada tecla tarda milisegundos, no el medio segundo que cuesta lanzar "input keyevent".
 * <pre>
 *   k 19        pulsar y soltar una tecla
 *   l 23        pulsación larga (OK sostenido abre el menú de la app)
 *   t SG9sYQ==  escribir texto (UTF-8 en base64)
 * </pre>
 * No usa Context ni nada de la app: solo InputManager (API oculta, la misma que usa scrcpy).
 */
public final class Injector {
    private static Object inputManager;
    private static Method inject;

    public static void main(String[] args) throws Exception {
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        inputManager = im.getDeclaredMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

        System.out.println("ready");
        System.out.flush();
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = r.readLine()) != null; ) {
            line = line.trim();
            if (line.length() < 3 || line.charAt(1) != ' ') continue;
            String arg = line.substring(2);
            try {
                switch (line.charAt(0)) {
                    case 'k': tap(Integer.parseInt(arg), false); break;
                    case 'l': tap(Integer.parseInt(arg), true); break;
                    case 't': type(kcm, new String(Base64.decode(arg, Base64.DEFAULT), StandardCharsets.UTF_8)); break;
                    default: break;
                }
                System.out.println("ok");
            } catch (Exception e) {
                System.out.println("error " + e.getClass().getSimpleName());
            }
            System.out.flush();
        }
    }

    private static void tap(int code, boolean longPress) throws Exception {
        if (code <= 0 || code > KeyEvent.getMaxKeyCode()) return;
        long t = SystemClock.uptimeMillis();
        send(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD));
        if (longPress) {
            Thread.sleep(550);
            long now = SystemClock.uptimeMillis();
            send(new KeyEvent(t, now, KeyEvent.ACTION_DOWN, code, 1, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD));
        }
        long up = SystemClock.uptimeMillis();
        send(new KeyEvent(t, up, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD));
    }

    /** Escribe con el teclado virtual. Lo que no tiene tecla (á, ñ…) se manda sin acento. */
    private static void type(KeyCharacterMap kcm, String text) throws Exception {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            KeyEvent[] ev = kcm.getEvents(new char[]{c});
            if (ev == null) {
                String plain = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                ev = plain.isEmpty() ? null : kcm.getEvents(new char[]{plain.charAt(0)});
            }
            if (ev == null) continue;
            for (KeyEvent e : ev) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0));
        }
    }

    private static void send(KeyEvent e) throws Exception {
        inject.invoke(inputManager, e, 0); // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
    }
}
