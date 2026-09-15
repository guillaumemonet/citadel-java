package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * {@code Store/KANE.fnt}, the font the server prints its menus in.
 *
 * Bigger than the in-game one and stored two glyphs to a block, which the index arithmetic in
 * DrukTekst gives away:
 *
 * <pre>
 *   subi #32,d0
 *   cmpi #32,d0
 *   bmi.s .m_d3
 *   subq #1,d0          ;there is no glyph for '@'
 * .m_d3:
 *   move d0,d1
 *   andi #1,d1          ;which of the pair
 *   lsr  d0             ;which pair
 *   lsl  #5,d0          ;32 bytes to a pair
 *   or   d1,d0
 * </pre>
 *
 * So a glyph sits at {@code (index >> 1) * 32 + (index & 1)} and its 13 rows are two bytes apart,
 * the odd bytes belonging to its neighbour. 3072 bytes is 96 pairs.
 *
 * The {@code subq #1} is the part worth keeping in mind: the font has no '@', so every code above
 * it is shifted down one and 'A' lands at index 32.
 */
public final class KaneFont {

    public static final int GLYPH_W = 8;
    /** {@code moveq #12,d2}, so thirteen rows. */
    public static final int GLYPH_H = 13;
    /** {@code lsl #5,d0}: two glyphs to a 32 byte block. */
    private static final int PAIR_STRIDE = 32;
    public static final char FIRST_GLYPH = ' ';
    /** The character the font skips, which shifts everything after it down one. */
    public static final char MISSING_GLYPH = '@';

    private final byte[] raw;

    private KaneFont(byte[] raw) {
        this.raw = raw;
    }

    public static KaneFont load(File f) throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length < PAIR_STRIDE) {
            throw new IOException(f + ": only " + raw.length + " bytes");
        }
        return new KaneFont(raw);
    }

    public static KaneFont loadFrom(File dataRoot) throws IOException {
        return load(new File(new File(dataRoot, "INTRO/STORE"), "KANE.fnt"));
    }

    /** How many glyph slots the file holds. */
    public int glyphs() {
        return (raw.length / PAIR_STRIDE) * 2;
    }

    /** DrukTekst's index for a character, or -1 when the font does not hold it. */
    public int indexOf(char c) {
        int index = c - FIRST_GLYPH;
        if (index < 0) {
            return -1;
        }
        if (index >= MISSING_GLYPH - FIRST_GLYPH) {
            index--;
        }
        return (index < glyphs()) ? index : -1;
    }

    /** One glyph, true where a pixel is set. Characters the font does not hold come back blank. */
    public boolean[] glyph(char c) {
        boolean[] out = new boolean[GLYPH_W * GLYPH_H];
        int index = indexOf(c);
        if (index < 0) {
            return out;
        }
        int base = (index >> 1) * PAIR_STRIDE + (index & 1);
        for (int y = 0; y < GLYPH_H; y++) {
            int at = base + y * 2;
            if (at >= raw.length) {
                break;
            }
            int b = raw[at] & 0xff;
            for (int x = 0; x < GLYPH_W; x++) {
                out[y * GLYPH_W + x] = ((b >> (7 - x)) & 1) != 0;
            }
        }
        return out;
    }
}
