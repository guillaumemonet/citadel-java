package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * Citadel palette: COLS0n.DAT, 32 colours, 2 bytes each, big endian, format 0RGB (4 bits per
 * component). Colour index 0 is the transparent key for every sprite (columns, items, enemies,
 * objects); wall textures are always fully opaque.
 *
 * Per Editor/edytor_notes.txt the first 20 entries are fixed engine colours, the last 12 are
 * free for the level designer.
 */
public final class Palette {

    public static final int SIZE = 32;
    public static final int FIXED_ENTRIES = 20;

    /** Opaque ARGB for every index. */
    public final int[] opaque = new int[SIZE];
    /** Same, but index 0 is fully transparent. */
    public final int[] keyed = new int[SIZE];
    public final String name;

    private Palette(String name) {
        this.name = name;
    }

    public static Palette load(File f) throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length < SIZE * 2) {
            throw new IOException(f + ": expected " + (SIZE * 2) + " bytes, got " + raw.length);
        }
        Palette p = new Palette(f.getName());
        for (int i = 0; i < SIZE; i++) {
            int v = ((raw[i * 2] & 0xff) << 8) | (raw[i * 2 + 1] & 0xff);
            int r = (v >> 8) & 0xf;
            int g = (v >> 4) & 0xf;
            int b = v & 0xf;
            // 4 bit -> 8 bit, replicating the nibble so 0xf maps to 0xff
            int argb = 0xff000000 | (r << 20) | (r << 16) | (g << 12) | (g << 8) | (b << 4) | b;
            p.opaque[i] = argb;
            p.keyed[i] = (i == 0) ? 0x00000000 : argb;
        }
        return p;
    }

    public int[] lut(boolean transparentIndex0) {
        return transparentIndex0 ? keyed : opaque;
    }

    /** Human readable dump, one line per entry, with the original 0RGB word. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append('\n');
        sb.append("# index  0RGB  #RRGGBB   note\n");
        for (int i = 0; i < SIZE; i++) {
            int a = opaque[i];
            int r = (a >> 16) & 0xff, g = (a >> 8) & 0xff, b = a & 0xff;
            String note = (i == 0) ? "transparent key on sprites"
                    : (i < FIXED_ENTRIES ? "fixed engine colour" : "level palette");
            sb.append(String.format("%7d  0%X%X%X  #%02X%02X%02X   %s%n",
                    i, r >> 4, g >> 4, b >> 4, r, g, b, note));
        }
        return sb.toString();
    }
}
