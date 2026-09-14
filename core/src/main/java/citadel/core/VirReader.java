package citadel.core;

import java.awt.image.BufferedImage;

/**
 * Decoder for the .VIR graphics format.
 *
 * A .VIR file is raw 8 bit paletted pixel data with no header. The engine loads several files
 * back to back into one contiguous block based at (MEMORY + co_Walls) and then addresses every
 * graphic through the sv_WallOffsets / sv_CollumnOffsets / sv_ItemOffsets / sv_BloodOffsets /
 * sv_Enemy1Offsets / sv_Enemy2Offsets tables, which hold byte offsets into that block.
 *
 * Storage is column major with a stride of {@link #STRIDE} bytes per column: 64 pixels of picture
 * plus one guard byte the engine forces to 0 (see mk_coll1 / mk_fixblood in the main source).
 * A full tile is therefore 64 columns * 65 bytes = {@link #TILE} bytes.
 *
 * Reading a column of texture is then a contiguous byte run, which is exactly what the wall
 * rasteriser does (shZ_DownPix: move.b (a1)+,d0).
 */
public final class VirReader {

    /** Bytes per stored column: 64 visible pixels + 1 guard byte. */
    public static final int STRIDE = 65;
    /** Bytes of one 64x64 tile. */
    public static final int TILE = STRIDE * 64;
    /** Bytes to step 32 columns to the right, the "half tile" constant used all over the tables. */
    public static final int HALF = STRIDE * 32;

    private final byte[] block;
    private final boolean columnMajor;

    public VirReader(byte[] block, boolean columnMajor) {
        this.block = block;
        this.columnMajor = columnMajor;
    }

    public int size() {
        return block.length;
    }

    /**
     * Maps a stored .VIR byte onto a palette index in 0..31.
     *
     * The five significant bits live in bits 3..7 of the byte and they are stored in reverse
     * order. That falls straight out of the chunky to planar converters: sv2_copy and friends run
     * each byte through the ConvBits macro, which does add.b d0,d0 and rolls the bit that just
     * fell out of bit 7 into the current bitplane. So bit 7 of the byte feeds bitplane 0, bit 6
     * feeds bitplane 1, and so on. Storing the index bit-reversed and pre-shifted means the
     * converter never has to shuffle anything.
     *
     * Verified at 100% over 20480 pixels against the authoring source SCIANY_01A_podziemia.iff
     * (see {@link ProbeMapping}).
     */
    private static final int[] BYTE_TO_INDEX = new int[256];

    static {
        for (int b = 0; b < 256; b++) {
            int v = (b >> 3) & 31;
            int r = 0;
            for (int i = 0; i < 5; i++) {
                r = (r << 1) | ((v >> i) & 1);
            }
            BYTE_TO_INDEX[b] = r;
        }
    }

    /** Colour index of one pixel, in 0..31. */
    public int pixel(int base, int x, int y) {
        int off = columnMajor ? base + x * STRIDE + y : base + y * STRIDE + x;
        if (off < 0 || off >= block.length) {
            return 0;
        }
        return BYTE_TO_INDEX[block[off] & 0xff];
    }

    /**
     * Extracts one graphic.
     *
     * @param base byte offset of the graphic inside the block
     * @param w    width in pixels
     * @param h    height in pixels
     * @param lut  32 entry ARGB lookup table, see {@link Palette#lut(boolean)}
     */
    public BufferedImage extract(int base, int w, int h, int[] lut) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                row[x] = lut[pixel(base, x, y) & 31];
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }

    /** True when every pixel of the graphic is the transparent key, i.e. the slot is unused. */
    public boolean isBlank(int base, int w, int h) {
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (pixel(base, x, y) != 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
