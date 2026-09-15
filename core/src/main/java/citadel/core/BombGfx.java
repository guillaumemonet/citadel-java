package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * {@code Store/Bomba.rawb}, the warhead assembly gauge DrawBomb paints.
 *
 * `sv_Glowica` starts at 0 and `ti_Head` takes 2 off it for every warhead section picked up, down
 * to -12. DrawBomb turns that into a stage with {@code subq #2,d0 / lsr d0 / mulu #6}, so there
 * are six stages and the sixth is the finished bomb.
 *
 * The blit is the giveaway for the layout:
 *
 * <pre>
 *   move #[31*5]-1,d1
 *   db_2:  move.l (a1),(a2)      ; 4 bytes
 *          move   4(a1),4(a2)    ; and 2 more, so 6 bytes = 48 pixels
 *          lea 36(a1),a1         ; source stride 36, which is six stages of six
 *          lea 40(a2),a2         ; destination stride 40, one bitplane row
 * </pre>
 *
 * 155 steps of 36 bytes is 5580, exactly the file size. The destination steps a bitplane row at a
 * time through a five plane interleaved screen, so step i is screen row i / 5, plane i % 5 — and
 * 155 steps is 31 rows. The six stages are interleaved six bytes at a time within each row.
 */
public final class BombGfx {

    public static final int WIDTH = 48;
    public static final int HEIGHT = 31;
    public static final int PLANES = 5;
    public static final int STAGES = 6;
    /** Bytes per stage per plane row: 48 pixels. */
    private static final int STRIDE = WIDTH / 8;
    /** {@code lea 36(a1),a1}. */
    private static final int ROW = STRIDE * STAGES;

    private final byte[] raw;

    private BombGfx(byte[] raw) {
        this.raw = raw;
    }

    public static BombGfx load(File f) throws IOException {
        byte[] raw = Io.readAll(f);
        int want = HEIGHT * PLANES * ROW;
        if (raw.length < want) {
            throw new IOException(f + ": expected " + want + " bytes, got " + raw.length);
        }
        return new BombGfx(raw);
    }

    public static BombGfx loadFrom(File dataRoot) throws IOException {
        return load(new File(new File(dataRoot, "GAME/Store"), "Bomba.rawb"));
    }

    /**
     * How many warhead sections have been collected, from the engine's counter.
     * DrawBomb draws nothing at all while this is zero.
     */
    public static int stageFor(int glowica) {
        int d0 = -glowica;
        if (d0 <= 0 || d0 >= 13) {
            return -1;      // beq.s db_1, and cmpi #13,d0 / bpl.s db_1
        }
        return (d0 - 2) / 2;
    }

    /** Palette indices for one stage, row major, {@link #WIDTH} by {@link #HEIGHT}. */
    public int[] pixels(int stage) {
        int[] out = new int[WIDTH * HEIGHT];
        for (int i = 0; i < HEIGHT * PLANES; i++) {
            int row = i / PLANES;
            int plane = i % PLANES;
            int at = i * ROW + stage * STRIDE;
            for (int x = 0; x < WIDTH; x++) {
                int bit = (raw[at + (x >> 3)] >> (7 - (x & 7))) & 1;
                out[row * WIDTH + x] |= bit << plane;
            }
        }
        return out;
    }
}
