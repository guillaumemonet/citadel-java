package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * The headerless screens under {@code Data/INTRO}: the hangar, the map, the cover art, the death
 * picture and the disk prompts.
 *
 * They carry no header at all, but the shape is consistent and the sizes give it away: interleaved
 * bitplanes exactly as {@code WINDOW1.RAW} has them, followed by the palette as big endian 0RGB
 * words. Two colours to a bitplane, so the palette length says how many planes there are, and the
 * height then falls out of what is left.
 *
 * {@code cytadela.rawb} is the exception worth knowing about: six planes and a 64 entry palette,
 * but it is Hold-and-Modify, so only the first 16 entries are colours and decoding it as plain
 * indices gives the same red and green speckle the intro animations did.
 */
public final class RawScreen {

    public final int width;
    public final int height;
    public final int planes;
    /** ARGB for every palette entry; for a HAM screen only the first 16 are meaningful. */
    public final int[] palette;
    private final byte[] body;

    private RawScreen(int width, int height, int planes, int[] palette, byte[] body) {
        this.width = width;
        this.height = height;
        this.planes = planes;
        this.palette = palette;
        this.body = body;
    }

    /**
     * @param paletteBytes trailing palette size, two bytes an entry, or 0 when the file has none
     */
    public static RawScreen load(File f, int width, int planes, int paletteBytes)
            throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length <= paletteBytes) {
            throw new IOException(f + ": only " + raw.length + " bytes");
        }
        int bodyLength = raw.length - paletteBytes;
        int bytesPerRow = width / 8;
        if (bodyLength % (bytesPerRow * planes) != 0) {
            throw new IOException(f + ": " + bodyLength + " bytes is not a whole number of "
                    + width + " pixel rows in " + planes + " planes");
        }
        int height = bodyLength / (bytesPerRow * planes);

        int entries = 1 << planes;
        int[] palette = new int[entries];
        for (int i = 0; i < entries; i++) {
            int at = bodyLength + i * 2;
            if (at + 1 < raw.length) {
                int v = ((raw[at] & 0xff) << 8) | (raw[at + 1] & 0xff);
                int r = (v >> 8) & 0xf;
                int g = (v >> 4) & 0xf;
                int b = v & 0xf;
                palette[i] = 0xff000000 | (r << 20) | (r << 16) | (g << 12) | (g << 8)
                        | (b << 4) | b;
            } else {
                palette[i] = 0xff000000;
            }
        }

        byte[] body = new byte[bodyLength];
        System.arraycopy(raw, 0, body, 0, bodyLength);
        return new RawScreen(width, height, planes, palette, body);
    }

    /** Palette indices, row major. */
    public int[] indices() {
        int bytesPerRow = width / 8;
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int p = 0; p < planes; p++) {
                int at = (y * planes + p) * bytesPerRow;
                for (int x = 0; x < width; x++) {
                    int bit = (body[at + (x >> 3)] >> (7 - (x & 7))) & 1;
                    out[y * width + x] |= bit << p;
                }
            }
        }
        return out;
    }

    /** ARGB pixels, row major, reading the indices straight out of the palette. */
    public int[] pixels() {
        int[] idx = indices();
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            out[i] = palette[idx[i] & (palette.length - 1)];
        }
        return out;
    }

    /**
     * ARGB pixels decoded as Hold-and-Modify: the top two bits of a six bit value say whether to
     * take a colour from the first 16 palette entries or to hold the pixel to the left and replace
     * its blue, red or green with the low four bits.
     */
    public int[] hamPixels() {
        int[] idx = indices();
        int[] out = new int[idx.length];
        for (int y = 0; y < height; y++) {
            int r = 0;
            int g = 0;
            int b = 0;
            for (int x = 0; x < width; x++) {
                int v = idx[y * width + x];
                int val = v & 0xf;
                switch (v >> 4) {
                    case 0:
                        int c = palette[val];
                        r = (c >> 16) & 0xff;
                        g = (c >> 8) & 0xff;
                        b = c & 0xff;
                        break;
                    case 1:
                        b = val * 17;
                        break;
                    case 2:
                        r = val * 17;
                        break;
                    default:
                        g = val * 17;
                        break;
                }
                out[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }
}
