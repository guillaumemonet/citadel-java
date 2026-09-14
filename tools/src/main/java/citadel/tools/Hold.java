package citadel.tools;

/**
 * Hold-and-Modify decoding, the Amiga display mode the intro artwork and animations use.
 *
 * A HAM6 pixel is six bits. The top two select what to do: 00 takes a colour straight from the
 * first 16 CMAP entries, and 01, 10 and 11 <em>hold</em> the previous pixel's colour and
 * <em>modify</em> one component of it — blue, red and green respectively — with the low four bits.
 * Each row restarts from palette entry 0.
 *
 * That is why decoding one of these as a plain 64-colour image produces dense red and green
 * speckle: every modify pixel is read as an index into palette entries that were never meant to
 * be used as colours. `CAMG` bit 0x800 is what announces it; the game's own wall and sprite
 * artwork has `CAMG` 0, which is why that decoded correctly from the start.
 */
public final class Hold {

    /** CAMG flag marking a Hold-and-Modify viewport. */
    public static final int HAM = 0x800;

    private Hold() {
    }

    public static boolean isHam(int camg, int planes) {
        return (camg & HAM) != 0 && (planes == 6 || planes == 8);
    }

    /**
     * Converts a row of HAM pixel values into ARGB.
     *
     * @param indices  one byte per pixel, the raw 6 or 8 bit values
     * @param offset   start of the row within indices
     * @param width    pixels in the row
     * @param palette  the CMAP, as ARGB
     * @param planes   6 or 8
     * @param out      receives the row's ARGB pixels
     */
    public static void decodeRow(byte[] indices, int offset, int width,
                                 int[] palette, int planes, int[] out) {
        // HAM8 keeps six bits of each component and selects from 64 base colours.
        int dataBits = (planes == 8) ? 6 : 4;
        int mask = (1 << dataBits) - 1;
        int r = (palette[0] >> 16) & 0xff;
        int g = (palette[0] >> 8) & 0xff;
        int b = palette[0] & 0xff;

        for (int x = 0; x < width; x++) {
            int v = indices[offset + x] & 0xff;
            int mode = (v >> dataBits) & 3;
            int data = v & mask;
            switch (mode) {
                case 0: {
                    int c = palette[data];
                    r = (c >> 16) & 0xff;
                    g = (c >> 8) & 0xff;
                    b = c & 0xff;
                    break;
                }
                case 1:
                    b = expand(data, dataBits);
                    break;
                case 2:
                    r = expand(data, dataBits);
                    break;
                default:
                    g = expand(data, dataBits);
                    break;
            }
            out[x] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
    }

    /** Widens a 4 or 6 bit component to 8 bits by repeating its high bits. */
    private static int expand(int value, int bits) {
        return (bits == 4) ? (value << 4) | value : (value << 2) | (value >> 4);
    }
}
