package io.github.mlinescode.slimtv;

import java.nio.charset.StandardCharsets;

/**
 * Generador de códigos QR mínimo (modo byte, corrección M, versiones 1 a 9: hasta 180 bytes).
 * Basta para la dirección del control remoto. Sigue el algoritmo de la norma ISO/IEC 18004 en la
 * forma compacta de Nayuki (MIT). Sin dependencias para que el launcher siga pesando ~60 KB.
 */
final class Qr {
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22};
    private static final int[] NUM_BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5};

    private final int size;
    private final boolean[][] modules, function;

    private Qr(int version) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
    }

    /** Devuelve la matriz [fila][columna]; true = módulo oscuro. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 1;
        while (version <= 9 && dataCodewords(version) < (4 + 8 + data.length * 8 + 7) / 8) version++;
        if (version > 9) throw new IllegalArgumentException("texto demasiado largo para el QR");

        int capacity = dataCodewords(version) * 8;
        Bits bits = new Bits(capacity);
        bits.add(4, 4); // modo byte
        bits.add(data.length, 8);
        for (byte b : data) bits.add(b & 0xFF, 8);
        bits.add(0, Math.min(4, capacity - bits.len));
        bits.add(0, (8 - bits.len % 8) % 8);
        for (int pad = 0xEC; bits.len < capacity; pad ^= 0xEC ^ 0x11) bits.add(pad, 8);

        Qr q = new Qr(version);
        q.drawFunctionPatterns(version);
        q.drawCodewords(q.withEcc(version, bits.bytes()));
        int best = 0, bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            q.applyMask(m);
            q.drawFormat(m);
            int p = q.penalty();
            if (p < bestPenalty) { best = m; bestPenalty = p; }
            q.applyMask(m); // deshacer (XOR)
        }
        q.applyMask(best);
        q.drawFormat(best);
        return q.modules;
    }

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int align = ver / 7 + 2;
            r -= (25 * align - 10) * align - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int dataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    // ---------- Patrones fijos ----------

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] pos = alignmentPositions(version);
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0);
                if (!corner) alignment(pos[i], pos[j]);
            }
        }
        drawFormat(0); // reserva las zonas; se reescribe con la máscara elegida
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(a, c, b);
                set(c, a, b);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy)), xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
        }
    }

    private void alignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, p = size - 7; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    private void drawFormat(int mask) {
        int data = mask; // corrección M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    // ---------- Datos ----------

    private byte[] withEcc(int ver, byte[] data) {
        int blocks = NUM_BLOCKS[ver], ecc = ECC_PER_BLOCK[ver], raw = rawModules(ver) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = rsDivisor(ecc);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] dat = new byte[len];
            System.arraycopy(data, k, dat, 0, len);
            k += len;
            byte[] rem = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, len);
            System.arraycopy(rem, 0, block, block.length - ecc, ecc);
            all[i] = block;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - ecc || j >= shortBlocks) out[n++] = all[j][i];
            }
        }
        return out;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (mask) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !function[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** Penalización simplificada (rachas, bloques 2×2 y balance): cualquier máscara es válida; esto solo elige una legible. */
    private int penalty() {
        int p = 0, dark = 0;
        for (int a = 0; a < size; a++) {
            int runRow = 1, runCol = 1;
            for (int b = 1; b < size; b++) {
                if (modules[a][b] == modules[a][b - 1]) { if (++runRow == 5) p += 3; else if (runRow > 5) p++; } else runRow = 1;
                if (modules[b][a] == modules[b - 1][a]) { if (++runCol == 5) p += 3; else if (runCol > 5) p++; } else runCol = 1;
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (x < size - 1 && y < size - 1) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) p += 3;
                }
            }
        }
        int total = size * size;
        p += (Math.abs(dark * 20 - total * 10) + total - 1) / total * 10;
        return p;
    }

    // ---------- Reed-Solomon en GF(256) ----------

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] r = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(divisor[i] & 0xFF, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static final class Bits {
        final byte[] buf;
        int len;
        Bits(int capacity) { buf = new byte[(capacity + 7) / 8]; }
        void add(int value, int n) {
            for (int i = n - 1; i >= 0; i--, len++) {
                if (((value >>> i) & 1) != 0) buf[len >>> 3] |= (byte) (0x80 >>> (len & 7));
            }
        }
        byte[] bytes() { return buf; }
    }
}
