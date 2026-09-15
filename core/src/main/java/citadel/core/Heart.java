package citadel.core;

/**
 * The heart monitor from DRAW_HEART, and the exhaustion it is the readout for.
 *
 * It looks like a decoration and is not. The word at sc_PosTab+2 is the length of the trace cycle,
 * so a smaller value repeats the spike sooner and the heart races. EXCITE knocks 11*4 off it every
 * time the player is hurt, walks into a wall, or sets off their own shot at point blank range;
 * DRAW_HEART adds 4 back on a timer. And sc_NormWalk reads that same word to scale the walk speed:
 *
 * <pre>
 *   d0 = beat + 288          ; 332 at the worst, 512 at rest
 *   d1 = base * d0 >> 9      ; lsr.l #8 then lsr, so a divide by 512
 * </pre>
 *
 * A winded player therefore walks at 65% speed and takes the better part of half a minute to get
 * it back — longer if they keep moving, since sv_Flag+2 doubles the timer while they do.
 *
 * The trace itself is a 29 column window onto a 32 entry ring buffer, each column a vertical run
 * of pixels in colour 19, the green the bset/bclr pattern over five bitplanes picks out.
 */
public final class Heart {

    /** {@code cmpi #11*4} in EXCITE: the fastest the heart is allowed to beat. */
    public static final int MIN_BEAT = 11 * 4;
    /** {@code move #56*4} in DRAW_HEART: at rest. */
    public static final int MAX_BEAT = 56 * 4;
    /** {@code subi #11*4} per EXCITE. */
    public static final int EXCITE_STEP = 11 * 4;
    /** {@code addi #4} per regeneration step. */
    public static final int REGEN_STEP = 4;
    /** VBLANK frames between steps, doubled while the player is moving. */
    public static final int REGEN_STILL = 25;
    public static final int REGEN_MOVING = 50;

    /** Entries in sc_PosTab, and in the sc_PosTab2 ring. */
    public static final int TRACE_LENGTH = 56;
    public static final int RING = 32;
    /** Columns of the ring the readout shows, from {@code moveq #28,d2}. */
    public static final int COLUMNS = 29;
    /** How far ahead of the cursor a new sample is written: {@code addi #29*4,d1}. */
    private static final int WRITE_AHEAD = 29;
    /** Pixel width and height of the readout, from the 5 byte by 12 row restore in sc_RetHt. */
    public static final int WIDTH = 40;
    public static final int HEIGHT = 12;
    /** {@code moveq #2,d0} counted down before the first column, so bit 1 of byte 0. */
    public static final int FIRST_COLUMN = 6;

    /**
     * sc_PosTab from the fourth byte on, as {@code {row, length}}. The stored words are a row
     * offset of 200 bytes — five bitplanes of 40 — and a repeat count one less than the length.
     * Ten entries of spike, then a flat line the rest of the way round.
     */
    private static final int[][] SHAPE = new int[TRACE_LENGTH][];

    static {
        int[][] spike = {
                {6, 1}, {5, 1}, {4, 1}, {2, 2}, {0, 2},
                {2, 4}, {6, 4}, {10, 2}, {8, 2}, {7, 1},
        };
        System.arraycopy(spike, 0, SHAPE, 0, spike.length);
        for (int i = spike.length; i < TRACE_LENGTH; i++) {
            SHAPE[i] = new int[]{6, 1};   // blk.l 46,$04b00000
        }
    }

    /** sc_PosTab+2: the cycle length, and so the inverse of the pulse rate. */
    private int beat = MAX_BEAT;
    /** sc_PosTab+0: how far through the cycle the trace is. Counted in longwords, like the table. */
    private int cursor;
    /** sv_Flag: VBLANK frames until the next regeneration step. */
    private int regen = 1;
    /** sc_PosTab2: the ring the readout is drawn from, and its write cursor. */
    private final int[][] ring = new int[RING][];
    private int ringCursor;
    private boolean flatline;

    public Heart() {
        for (int i = 0; i < RING; i++) {
            ring[i] = new int[]{6, 1};
        }
    }

    /** EXCITE: something jolted the player, so the heart beats faster. */
    public void excite() {
        beat = Math.max(MIN_BEAT, beat - EXCITE_STEP);
    }

    /** True once the level has ended badly; the trace goes flat. */
    public void setFlatline(boolean flat) {
        flatline = flat;
    }

    public int beat() {
        return beat;
    }

    /** 0 at rest, 1 when as winded as the engine allows. */
    public float exhaustion() {
        return (MAX_BEAT - beat) / (float) (MAX_BEAT - MIN_BEAT);
    }

    /**
     * One pass of DRAW_HEART, which NewLev3 calls every VBLANK unless the game is paused.
     *
     * @param moving whether the player moved this pass, which is sv_Flag+2
     */
    public void tick(boolean moving) {
        if (--regen <= 0) {
            regen = moving ? REGEN_MOVING : REGEN_STILL;
            beat = Math.min(MAX_BEAT, beat + REGEN_STEP);
        }

        cursor += 4;
        if (cursor >= beat) {
            cursor = 0;
        }
        int[] sample = flatline ? new int[]{6, 1} : SHAPE[(cursor / 4) % TRACE_LENGTH];
        ring[(ringCursor + WRITE_AHEAD) % RING] = sample;
        ringCursor = (ringCursor + 1) % RING;
    }

    /**
     * The readout, left to right: {@code {x, row, length}} per column, in pixels within a
     * {@link #WIDTH} by {@link #HEIGHT} strip.
     */
    public int[][] trace() {
        int[][] out = new int[COLUMNS][3];
        for (int i = 0; i < COLUMNS; i++) {
            int[] s = ring[(ringCursor + i) % RING];
            out[i][0] = FIRST_COLUMN + i;
            out[i][1] = s[0];
            out[i][2] = s[1];
        }
        return out;
    }

    /** sc_NormWalk: scales the walk speed by how winded the player is. */
    public int walkSpeed(int base) {
        return (int) (((long) base * (beat + 288)) >> 9);
    }
}
