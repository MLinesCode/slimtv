package io.github.mlinescode.slimtv;

import android.content.Context;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Mantiene vivo {@link Injector} a través de {@link Adb} y le pasa teclas y texto.
 * Si la TV no tiene la depuración por red activa, el control remoto sigue sirviendo para volumen,
 * música, abrir apps y personalizar; solo las flechas, OK, Atrás y el teclado dependen de esto.
 */
final class Keys {
    enum State { OFF, CONNECTING, WAITING_APPROVAL, READY, UNAVAILABLE }

    private final Context ctx;
    private volatile Adb adb;
    private volatile State state = State.OFF;
    private volatile String detail = "";
    private Thread worker;

    Keys(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    State state() { return state; }
    String detail() { return detail; }

    /** Arranca la conexión si no está lista. No bloquea. */
    synchronized void ensure() {
        if (state == State.READY || state == State.CONNECTING || state == State.WAITING_APPROVAL) return;
        state = State.CONNECTING;
        worker = new Thread(this::run, "slimtv-keys");
        worker.setDaemon(true);
        worker.start();
    }

    boolean tap(int code, boolean longPress) {
        return send((longPress ? "l " : "k ") + code + "\n");
    }

    boolean type(String text) {
        if (text.isEmpty()) return true;
        return send("t " + Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\n");
    }

    private boolean send(String line) {
        Adb a = adb;
        if (state != State.READY || a == null) { ensure(); return false; }
        try {
            a.write(line);
            return true;
        } catch (IOException e) {
            a.close();
            state = State.OFF;
            ensure();
            return false;
        }
    }

    private void run() {
        Adb a = new Adb(ctx);
        try {
            String apk = ctx.getPackageCodePath();
            // Mientras el aviso "¿Permitir la depuración?" está en pantalla, read() espera.
            state = State.WAITING_APPROVAL;
            detail = "Acepta el aviso en la TV y marca \"Permitir siempre\"";
            a.open("CLASSPATH=" + apk + " app_process / " + Injector.class.getName(), 120_000);
            adb = a;
            a.pump(line -> {
                if (line.equals("ready")) {
                    state = State.READY;
                    detail = "";
                }
            });
        } catch (java.net.ConnectException e) {
            state = State.UNAVAILABLE;
            detail = "Activa la depuración por red en la TV";
        } catch (IOException e) {
            state = State.UNAVAILABLE;
            detail = e.getMessage() == null ? "Sin conexión" : e.getMessage();
        } finally {
            a.close();
            adb = null;
            if (state == State.READY || state == State.WAITING_APPROVAL || state == State.CONNECTING) state = State.OFF;
        }
    }
}
