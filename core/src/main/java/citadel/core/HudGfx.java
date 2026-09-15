package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * The status panel and its two fonts, which the repository has had all along under
 * {@code Data/GAME/Gfx_VIR}.
 *
 * Three files, and each one's size is the proof it is what it looks like:
 *
 * <ul>
 *   <li>{@code WINDOW1.RAW}, 40000 bytes — a whole 320 by 200 screen in five interleaved
 *       bitplanes. The {@code screen} equ carries the comment {@code ($9c40 razem)}, and $9c40 is
 *       40000.</li>
 *   <li>{@code NUMBERS.RAW}, 430 bytes — {@code sv_Numbers}, declared as $1ae.</li>
 *   <li>{@code FONTS01.FNT}, 768 bytes — {@code sv_Fonts}, declared as $300.</li>
 * </ul>
 *
 * The view window sits inside the panel at {@code sv_Leftoffset * 8 = 64} across and
 * {@code sv_Upoffset = 16} down, 192 by 128; the black hole in the artwork is two pixels larger
 * all round, which is its bevel.
 */
public final class HudGfx {

    // ------------------------------------------------------------- the panel

    public static final int SCREEN_W = 320;
    public static final int SCREEN_H = 200;
    public static final int PLANES = 5;
    /** sv_Leftoffset is 8 bytes, sv_Upoffset 16 rows. */
    public static final int VIEW_X = 64;
    public static final int VIEW_Y = 16;
    public static final int VIEW_W = 192;
    public static final int VIEW_H = 128;

    // ------------------------------------------------------------ the digits

    /** {@code moveq #17,d4}, so eighteen rows. */
    public static final int DIGIT_H = 18;
    /** {@code move (a5),d0} reads a word. */
    public static final int DIGIT_W = 16;
    /** {@code lea 20(a5),a5}: ten digits of two bytes to a row. */
    public static final int DIGIT_STRIDE = 20;
    public static final int DIGITS = 10;

    /**
     * A second, smaller set of digits follows at {@code sv_Numbers + 20*18}, which
     * tc_DrawCardCnt uses for the three card counts. It indexes them with the count itself
     * ({@code move.b (a2,d0.w),d2}) and steps a row with {@code addi #10,d0}, so it is ten
     * glyphs of one byte on a seven row grid, and the routine adds a blank eighth row.
     */
    public static final int SMALL_DIGIT_W = 8;
    public static final int SMALL_DIGIT_H = 7;
    public static final int SMALL_DIGIT_STRIDE = 10;
    public static final int SMALL_DIGITS = 10;

    // -------------------------------------------------------------- the font

    public static final int GLYPH_W = 8;
    public static final int GLYPH_H = 8;
    public static final int GLYPHS = 96;
    /** The first character the font holds; 96 glyphs of 8 bytes covers space to DEL. */
    public static final char FIRST_GLYPH = ' ';

    private final byte[] window;
    private final byte[] numbers;
    private final byte[] font;

    private HudGfx(byte[] window, byte[] numbers, byte[] font) {
        this.window = window;
        this.numbers = numbers;
        this.font = font;
    }

    public static HudGfx loadFrom(File gfxDir) throws IOException {
        byte[] w = read(new File(gfxDir, "WINDOW1.RAW"), SCREEN_W / 8 * PLANES * SCREEN_H);
        byte[] n = read(new File(gfxDir, "NUMBERS.RAW"),
                DIGIT_STRIDE * DIGIT_H + SMALL_DIGIT_STRIDE * SMALL_DIGIT_H);
        byte[] f = read(new File(gfxDir, "FONTS01.FNT"), GLYPHS * GLYPH_H);
        return new HudGfx(w, n, f);
    }

    private static byte[] read(File f, int want) throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length < want) {
            throw new IOException(f + ": expected " + want + " bytes, got " + raw.length);
        }
        return raw;
    }

    /**
     * The panel as palette indices, row major.
     *
     * Five bitplanes interleaved a row at a time, which is the same arrangement DrawBomb blits
     * into: row r holds plane p at {@code r * 200 + p * 40}.
     */
    public int[] panel() {
        int bytesPerRow = SCREEN_W / 8;
        int[] out = new int[SCREEN_W * SCREEN_H];
        for (int y = 0; y < SCREEN_H; y++) {
            for (int p = 0; p < PLANES; p++) {
                int at = (y * PLANES + p) * bytesPerRow;
                for (int x = 0; x < SCREEN_W; x++) {
                    int bit = (window[at + (x >> 3)] >> (7 - (x & 7))) & 1;
                    out[y * SCREEN_W + x] |= bit << p;
                }
            }
        }
        return out;
    }

    /**
     * One counter digit as a bitmap, true where the glyph is set.
     *
     * tc_DrawCounter ORs the set bits in with the counter's colour and leaves the cleared ones
     * showing the panel behind, so the set bits are the plate and the digit itself is the hole.
     */
    public boolean[] digit(int d) {
        boolean[] out = new boolean[DIGIT_W * DIGIT_H];
        for (int y = 0; y < DIGIT_H; y++) {
            int at = y * DIGIT_STRIDE + d * 2;
            for (int x = 0; x < DIGIT_W; x++) {
                out[y * DIGIT_W + x] = ((numbers[at + (x >> 3)] >> (7 - (x & 7))) & 1) != 0;
            }
        }
        return out;
    }

    /** One of the small digits the card counters use, 0..9. */
    public boolean[] smallDigit(int d) {
        int base = DIGIT_STRIDE * DIGIT_H;
        boolean[] out = new boolean[SMALL_DIGIT_W * SMALL_DIGIT_H];
        for (int y = 0; y < SMALL_DIGIT_H; y++) {
            int b = numbers[base + d + y * SMALL_DIGIT_STRIDE] & 0xff;
            for (int x = 0; x < SMALL_DIGIT_W; x++) {
                out[y * SMALL_DIGIT_W + x] = ((b >> (7 - x)) & 1) != 0;
            }
        }
        return out;
    }

    /** One 8 by 8 glyph, true where a pixel is set. Characters outside the font come back blank. */
    public boolean[] glyph(char c) {
        boolean[] out = new boolean[GLYPH_W * GLYPH_H];
        int index = c - FIRST_GLYPH;
        if (index < 0 || index >= GLYPHS) {
            return out;
        }
        for (int y = 0; y < GLYPH_H; y++) {
            int b = font[index * GLYPH_H + y] & 0xff;
            for (int x = 0; x < GLYPH_W; x++) {
                out[y * GLYPH_W + x] = ((b >> (7 - x)) & 1) != 0;
            }
        }
        return out;
    }
}
