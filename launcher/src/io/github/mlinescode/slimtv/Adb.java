package io.github.mlinescode.slimtv;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Cliente ADB mínimo hacia la propia TV (127.0.0.1:5555). Solo sirve para una cosa: arrancar
 * {@link Injector} como usuario "shell", que es quien puede inyectar teclas en cualquier app.
 * La primera vez Android muestra "¿Permitir la depuración?": hay que marcar "Permitir siempre".
 * La llave RSA vive en los archivos privados de la app; el panel web nunca puede mandar comandos
 * de shell, solo teclas y texto que {@link Injector} valida.
 */
final class Adb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    /** Prefijo DigestInfo de SHA-1: adbd verifica el token como si ya fuera un hash SHA-1. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    interface Lines { void onLine(String line); }

    private final Context ctx;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int localId = 1, remoteId;
    private final StringBuilder pending = new StringBuilder();

    Adb(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /**
     * Conecta y abre {@code shell:<command>}. Bloquea hasta que adbd acepta, incluso mientras la TV
     * espera que alguien pulse "Permitir" (hasta {@code authWaitMs}).
     */
    void open(String command, int authWaitMs) throws IOException {
        close();
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
        KeyPair keys = keys();

        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean sentSignature = false;
        socket.setSoTimeout(5000);
        while (true) {
            Message m = read();
            if (m.cmd == CNXN) break;
            if (m.cmd != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("adb: respuesta inesperada");
            if (!sentSignature) {
                sentSignature = true;
                send(AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
            } else {
                // Llave nueva: la TV muestra el aviso y esperamos a que alguien lo acepte.
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKey((RSAPublicKey) keys.getPublic()));
                socket.setSoTimeout(authWaitMs);
            }
        }
        socket.setSoTimeout(0);

        int id = localId++;
        send(OPEN, id, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        socket.setSoTimeout(5000);
        while (true) {
            Message m = read();
            if (m.cmd == OKAY) { remoteId = m.arg0; localId = id; break; }
            if (m.cmd == CLSE) throw new IOException("adb: no se pudo abrir el shell");
        }
        socket.setSoTimeout(0);
    }

    /** Escribe en la entrada estándar del comando abierto. */
    synchronized void write(String s) throws IOException {
        if (out == null) throw new IOException("adb: sin conexión");
        send(WRTE, localId, remoteId, s.getBytes(StandardCharsets.UTF_8));
    }

    /** Lee la salida del comando línea por línea hasta que se cierra. Bloquea: úsese en su propio hilo. */
    void pump(Lines lines) throws IOException {
        while (true) {
            Message m = read();
            if (m.cmd == WRTE) {
                synchronized (this) { send(OKAY, localId, remoteId, null); }
                pending.append(new String(m.data, StandardCharsets.UTF_8));
                for (int nl; (nl = pending.indexOf("\n")) >= 0; ) {
                    lines.onLine(pending.substring(0, nl).trim());
                    pending.delete(0, nl + 1);
                }
            } else if (m.cmd == CLSE) {
                throw new IOException("adb: el comando terminó");
            }
        }
    }

    void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null;
        in = null;
        out = null;
    }

    // ---------- Protocolo ----------

    private static final class Message {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length, sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        out.write(h.array());
        if (len > 0) out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("adb: mensaje inválido");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---------- Llave RSA ----------

    private KeyPair keys() throws IOException {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            write(priv, kp.getPrivate().getEncoded());
            write(pub, kp.getPublic().getEncoded());
            return kp;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("adb: llave", e);
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            c.update(SHA1_PREFIX);
            return c.doFinal(token);
        } catch (Exception e) {
            throw new IOException("adb: firma", e);
        }
    }

    /** Formato de llave pública de Android (struct RSAPublicKey de mincrypt) en base64 + nombre. */
    private static byte[] publicKey(RSAPublicKey k) {
        final int words = 64; // 2048 bits
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = k.getModulus();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        BigInteger n0inv = r32.subtract(n.remainder(r32).modInverse(r32));
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) {
            b.putInt(n.remainder(r32).intValue());
            n = n.shiftRight(32);
        }
        for (int i = 0; i < words; i++) {
            b.putInt(rr.remainder(r32).intValue());
            rr = rr.shiftRight(32);
        }
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " SlimTV-control\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
