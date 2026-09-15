package citadel.core;

import java.util.Random;

/**
 * sv_Death: blood runs down the screen.
 *
 * 71 passes (`moveq #70,d7`), three VBLANK frames apart, over 320 columns. Each column has its own
 * running height in DeathTab, a 320 word table, and each pass grows it by a fresh {@code 1..4}
 * taken from two bits of Amiga ROM:
 *
 * <pre>
 *   andi #3,d0
 *   addq #1,d0          ;new Y
 *   move (a1),d2        ;old Y
 *   add  d0,(a1)+
 * </pre>
 *
 * The rows it then fills use {@code and d4} on planes 0, 2 and 4 and {@code or d3} on planes 1 and
 * 3, which spells out index 2 + 8 = 10 — `#BB0000`, one of the fixed engine colours.
 *
 * EndLevel only reaches this every fourth call ({@code subi #1,sv_EndLevel+2} against a reload of
 * 4), which is what paces the whole sequence at about four and a half seconds.
 */
public final class DeathWipe {

    /** {@code move #319,d6}. */
    public static final int COLUMNS = 320;
    /** {@code moveq #70,d7}, so 71 passes. */
    public static final int PASSES = 71;
    /** VBLANK frames between passes, from the three VBLANKs at the end of .loopX. */
    public static final int FRAMES_PER_PASS = 3;
    /** The colour the bitplane pattern spells out. */
    public static final int COLOUR = 0xffbb0000;
    /** Rows on the screen the original fills, so heights are meaningful against something. */
    public static final int ROWS = 200;

    private final int[] height = new int[COLUMNS];
    private final Random rnd = new Random();
    private int pass;

    /** Height of one column, in rows out of {@link #ROWS}. */
    public int height(int column) {
        return height[column];
    }

    public int pass() {
        return pass;
    }

    public boolean isDone() {
        return pass >= PASSES;
    }

    /** One pass. Grows every column by 1 to 4 rows and reports whether anything is left to do. */
    public boolean step() {
        if (isDone()) {
            return false;
        }
        for (int x = 0; x < COLUMNS; x++) {
            height[x] = Math.min(ROWS, height[x] + 1 + rnd.nextInt(4));
        }
        pass++;
        return true;
    }
}
