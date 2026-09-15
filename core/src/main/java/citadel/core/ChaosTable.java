package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * {@code Store/CHAOSTAC.DAT}, the order TELEPORT reveals the new view in.
 *
 * 2048 bytes, 1024 big endian words, and they are a **permutation of 0..1023** — every value once.
 * That is what makes the dissolve complete rather than merely dense.
 *
 * TELEPORT walks the table 32 times over 32 VBLANK frames, 32 entries a frame, and for each entry
 * adds 1024 a further four times ({@code move #5-1,a4} then {@code addi #1024,d4}). So it visits
 * 32 * 32 * 5 = 5120 offsets. Each offset is split with {@code divu d0,d5} where d0 is 80: the
 * quotient is a row pair, the remainder a nibble column. 80 nibble columns of 4 pixels is 320
 * pixels, and 5120 / 80 = 64 row pairs is 128 rows — the whole view, each cell exactly once.
 *
 * The narrow view setting uses d0 = 48 and three repeats, which works out the same way: 3072
 * offsets over 48 columns and 64 row pairs, or 192 by 128.
 */
public final class ChaosTable {

    /** Entries in the file. */
    public static final int SIZE = 1024;
    /** VBLANK frames the dissolve takes, from {@code moveq #31,d7}. */
    public static final int FRAMES = 32;
    /** Entries consumed per frame, from {@code moveq #31,d2}. */
    public static final int PER_FRAME = 32;

    /** Nibble columns across the view, and pixels in each: d0 = 40*2, four pixels to a nibble. */
    public static final int COLUMNS = 80;
    public static final int PIXELS_PER_COLUMN = 4;
    /** {@code move #5-1,a4}: each entry is used at this many offsets, 1024 apart. */
    public static final int REPEATS = 5;
    /** Row pairs down the view; each covers two rows, since the inner loop serves 5 planes twice. */
    public static final int ROW_PAIRS = (SIZE * REPEATS) / COLUMNS;
    public static final int ROWS_PER_PAIR = 2;

    private final int[] order = new int[SIZE];

    private ChaosTable() {
    }

    public static ChaosTable load(File f) throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length < SIZE * 2) {
            throw new IOException(f + ": expected " + (SIZE * 2) + " bytes, got " + raw.length);
        }
        ChaosTable t = new ChaosTable();
        for (int i = 0; i < SIZE; i++) {
            t.order[i] = ((raw[i * 2] & 0xff) << 8) | (raw[i * 2 + 1] & 0xff);
        }
        return t;
    }

    public static ChaosTable loadFrom(File dataRoot) throws IOException {
        return load(new File(new File(dataRoot, "GAME/Store"), "CHAOSTAC.DAT"));
    }

    public int entry(int i) {
        return order[i];
    }

    /** True when the file really is a permutation, which the dissolve relies on. */
    public boolean isPermutation() {
        boolean[] seen = new boolean[SIZE];
        for (int v : order) {
            if (v < 0 || v >= SIZE || seen[v]) {
                return false;
            }
            seen[v] = true;
        }
        return true;
    }

    /**
     * The cells revealed on one frame of the dissolve, as {@code {column, rowPair}} pairs.
     * Frame runs 0..{@link #FRAMES}-1.
     */
    public int[][] frame(int frame) {
        int[][] out = new int[PER_FRAME * REPEATS][2];
        int n = 0;
        for (int i = 0; i < PER_FRAME; i++) {
            int d4 = order[frame * PER_FRAME + i];
            for (int r = 0; r < REPEATS; r++) {
                out[n][0] = d4 % COLUMNS;     // divu d0,d5 - remainder is the nibble column
                out[n][1] = d4 / COLUMNS;     // quotient is the row pair
                n++;
                d4 += SIZE;                   // addi #1024,d4
            }
        }
        return out;
    }
}
