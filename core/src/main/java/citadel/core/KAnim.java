package citadel.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The ".k" animations under {@code Data/INTRO/ANIM2}: {@code start.k}, {@code kabina.k} and
 * {@code explozycja.k}, the end sequence the game plays after the last level.
 *
 * They are Kane's repacking of the IFF ANIMs next to them, made by
 * {@code Code/IntroPlayer/AnimConvert02.ss} ("converts a ham anim for full screen to 160*100
 * screen ... Saves 16 copper colors, BODY, and DLTA's only") and played by the {@code iff_REPLAY}
 * routine that {@code IntroReplayer_125_ENG.ss} and {@code SERVER_031_ENG.ss} share. The IFF
 * originals are 320x200 HAM6 canvases with the picture drawn in the top left 160x100 only; the .k
 * keeps that quarter and the player doubles it back to full screen on the fly.
 *
 * <h2>Layout</h2>
 * <ul>
 * <li>{@code "ANIM"}, then sixteen big endian 0RGB words, poked straight into the copper list
 * ({@code iff_CMAP}: {@code move (a1)+,(a2)} sixteen times). These are the HAM base colours;
 * the words that look like a header ({@code 0000 0001 0003 0010}) are simply the first four
 * colours. There is no width, height, plane count, frame count or timing anywhere in the file:
 * 160x100, six planes and HAM ({@code BPLCON0 $6b00} in the copper list) are all hard-wired.</li>
 * <li>{@code "BODY"} and an unterminated ByteRun1 stream ({@code iff_convert}): a byte
 * {@code n >= 0} copies {@code n+1} literals, a negative one repeats the next byte {@code 1-n}
 * times ({@code iff_negval}; {@code -128} is not special-cased and gives 129). It stops once
 * 100 rows of six interleaved 20 byte plane rows are filled. The converter got this by clipping
 * each 40 byte IFF row at byte 20 ({@code iff_negloop}'s {@code cmpi #40,d2}).</li>
 * <li>Then any number of {@code "DLTA"} chunks, each word aligned ({@code addq #1 / andi #-2} in
 * {@code iff_ANIMLOOP}) and without a length. Anything else ends the animation; the converter
 * writes {@code "KANE"} ({@code iff_END}).</li>
 * </ul>
 *
 * <h2>DLTA</h2>
 * A re-encoding of IFF ANIM operation 5 (byte vertical delta), one opcode list per byte column:
 * <ul>
 * <li>A byte holding the column count minus one: 20 columns per plane, planes in order, so
 * 119 for all six ({@code iff_cntbtpl} in the converter; {@code iff_DLTA} gives up at 120).
 * Column {@code c} is byte {@code c % 20} of plane {@code c / 20}; the player just steps one
 * word along the interleaved row per column ({@code iff_NextRow}).</li>
 * <li>Per column, the IFF op count, then the IFF ops verbatim: {@code 0, n, v} sets {@code n}
 * rows to {@code v}; {@code 0x80|n} is followed by {@code n} bytes, one a row; anything else
 * skips that many rows. A count of 0 leaves the column alone.</li>
 * <li>The player runs only count-1 of those ops and then steps over one byte
 * ({@code subq #2,d0} and {@code lea 1(a1),a1} in {@code iff_FracLoop}). That works because the
 * IFF canvas is 200 rows and only the top 100 ever change, so DPaint always closed a changed
 * column with a one byte skip into the blank bottom half. The player has no use for it: its
 * buffers are 100 rows and {@code iff_Ytab} maps skips of 100 or more to nothing.</li>
 * </ul>
 *
 * <h2>Double buffering</h2>
 * The IFF deltas are interleave 2 (ANHD interleave 0): each describes the frame two back, not the
 * previous one. The player matches it: {@code iff_ANIM} copies the BODY into both screens, and
 * every tick {@code iff_ChangeScreen} swaps them before the delta goes into the one now hidden.
 * Frame {@code k} is therefore frame {@code k-2} plus delta {@code k}, with frame 0 standing in
 * for frame -1. This class decodes the same way. ({@code citadel.tools.AnimReader} applies IFF
 * deltas to the previous frame instead, so from frame 2 on its frames of these files carry
 * coloured streaks the real player never showed; frames 0 and 1 are the same either way.)
 *
 * <h2>Frames</h2>
 * One for the BODY and one per DLTA: 26 for {@code start.k}, 18 for {@code kabina.k}, 26 for
 * {@code explozycja.k}. The IFFs end, as DPaint's looping animations do, with two deltas that
 * rebuild frames 0 and 1. {@code iff_find1} in the converter overwrites the last {@code FORM} with
 * {@code "KANE"}, so the .k loses that one; {@code kabina.k} lost both (the commented out
 * {@code iff_find2} does exactly that, so it was presumably still live when kabina was made).
 * The last frame of {@code start.k} and {@code explozycja.k} is therefore a copy of frame 0.
 *
 * <h2>Pixels</h2>
 * HAM6 per row, starting each line from colour 0: the top two bits of a pixel pick base colour,
 * or hold the previous pixel and replace blue, red or green with the low four bits. Four bit
 * components widen by repeating the nibble ({@code 0xa} becomes {@code 0xaa}). Frames come out at
 * the stored 160x100; the player doubles them, horizontally through {@code iff_DoubleTab} (every
 * bit of a byte twice) and vertically with a copper list that alternates a -40 modulo, so scaling
 * by two in both directions reproduces what the Amiga showed.
 *
 * <h2>Timing</h2>
 * None in the file. END_PART2 (SERVER_032_HD_ENG.ss) loads the three back to back
 * ({@code Anim1} = {@code start.k}, {@code Anim2} = {@code kabina.k} at +135858, {@code Anim3} =
 * {@code explozycja.k} at +276782), plays kabina, then start, then explozycja, and paces them with
 * its {@code anims} table: per animation, a minimum length in vblanks, then pairs of (vblanks per
 * tick, ticks), consumed by {@code ko_NewLev3}. The rows are labelled anim1, anim2, anim3, but
 * END_PART2 writes the address of the animation it is about to play over the row
 * {@code AnimsAdr} points at, and ReplayPart steps to the next row afterwards: the rows go to the
 * animations in the order they are played, so kabina, played first, runs on the first row.
 * {@link #frameVblanks(String, int)} turns that into a duration per frame.
 */
public final class KAnim {

    /** Stored picture size. The player shows it doubled to 320x200. */
    public static final int WIDTH = 160;
    public static final int HEIGHT = 100;
    public static final int PLANES = 6;

    private static final int ROW_BYTES = WIDTH / 8;
    private static final int LINE = ROW_BYTES * PLANES;   // one interleaved row, all planes
    private static final int BITMAP = LINE * HEIGHT;

    private final int[] palette;
    private final List<int[]> frames;

    private KAnim(int[] palette, List<int[]> frames) {
        this.palette = palette;
        this.frames = frames;
    }

    public int width() {
        return WIDTH;
    }

    public int height() {
        return HEIGHT;
    }

    public int frameCount() {
        return frames.size();
    }

    /** Frame {@code i} as ARGB, row major, {@code width() * height()} pixels. */
    public int[] frame(int i) {
        return frames.get(i).clone();
    }

    /** The sixteen HAM base colours, ARGB. */
    public int[] palette() {
        return palette.clone();
    }

    /**
     * How long each frame stays up in the end sequence, in 50 Hz vblanks. The file has no timing;
     * this is {@code SERVER_031_ENG.ss}'s {@code anims} table worked through {@code ko_NewLev3}
     * and {@code iff_ANIMLOOP}.
     *
     * The table gives the vblanks before each player tick. Tick 1 only swaps to the second copy
     * of frame 0, and tick {@code k+1} puts up frame {@code k}, so frame 0 lasts the first two
     * intervals and frame {@code k} the interval before tick {@code k+2}. The last frame gets a
     * single vblank: the tick that shows it finds no DLTA after it, and {@code ReplayPart} swaps
     * in the blank {@code copper0} at the next vertical blank.
     *
     * @param name   {@code "start"}, {@code "kabina"} or {@code "explozycja"}, ".k" optional
     * @param frames the animation's {@link #frameCount()}
     * @return vblanks per frame, or null for a name the table does not cover
     */
    public static int[] frameVblanks(String name, int frames) {
        int[] pairs;
        switch (name.endsWith(".k") ? name.substring(0, name.length() - 2) : name) {
            case "kabina" -> pairs = END_ROWS[0];
            case "start" -> pairs = END_ROWS[1];
            case "explozycja" -> pairs = END_ROWS[2];
            default -> {
                return null;
            }
        }
        return frameVblanks(pairs, frames);
    }

    /**
     * The three rows of {@code anims} after the minimum length: (vblanks per tick, ticks)
     * pairs, the last one open ended, in the order END_PART2 plays them.
     */
    public static final int[][] END_ROWS = {
        {60, 1, 7, 16, 80, 300},
        {80, 1, 6, 300},
        {15, 1, 5, 24, 50, 300},
    };

    /** {@code dc.l anim,$21 / $28 / $2c}: the least each one runs, in vblanks ({@code licznik}). */
    public static final int[] END_MINIMUM = {0x21, 0x28, 0x2c};

    /** One row of the table worked into a duration per frame; see {@link #frameVblanks(String, int)}. */
    public static int[] frameVblanks(int[] pairs, int frames) {
        int[] interval = new int[frames + 1];   // interval[j]: vblanks before tick j + 1
        int pair = 0;
        int left = pairs[1];
        for (int j = 0; j < interval.length; j++) {
            interval[j] = pairs[pair];
            if (--left == 0 && pair + 2 < pairs.length) {
                pair += 2;
                left = pairs[pair + 1];
            }
        }
        int[] out = new int[frames];
        for (int k = 0; k < frames - 1; k++) {
            out[k] = (k == 0 ? interval[0] : 0) + interval[k + 1];
        }
        out[frames - 1] = 1;
        return out;
    }

    /** Decodes a whole .k file, every frame up front, double buffered as the player does. */
    public static KAnim load(byte[] raw) throws IOException {
        return load(raw, true);
    }

    /**
     * Decoding with {@code doubleBuffered} false applies each delta to the frame just before it,
     * which is wrong for these files but is what {@code citadel.tools.AnimReader} does with the
     * IFF originals. It exists so a test can show the two streams carry the same deltas.
     */
    static KAnim load(byte[] raw, boolean doubleBuffered) throws IOException {
        if (raw.length < 40 || !tag(raw, 0, "ANIM") || !tag(raw, 36, "BODY")) {
            throw new IOException("not a .k animation (ANIM, 16 colours, BODY)");
        }
        int[] palette = new int[16];
        for (int i = 0; i < 16; i++) {
            int c = Io.u16(raw, 4 + i * 2);
            palette[i] = 0xff000000
                    | ((c >> 8) & 0xf) * 0x110000
                    | ((c >> 4) & 0xf) * 0x1100
                    | (c & 0xf) * 0x11;
        }

        try {
            return decode(raw, palette, doubleBuffered);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("truncated .k animation", e);
        }
    }

    private static KAnim decode(byte[] raw, int[] palette, boolean doubleBuffered)
            throws IOException {
        byte[][] screens = {new byte[BITMAP], null};
        int p = unpackBody(raw, 40, screens[0]);
        screens[1] = screens[0].clone();

        List<int[]> frames = new ArrayList<>();
        frames.add(toArgb(screens[0], palette));
        for (int k = 1; ; k++) {
            p = (p + 1) & ~1;
            if (p + 5 > raw.length || !tag(raw, p, "DLTA")) {
                break;
            }
            byte[] back = screens[doubleBuffered ? k & 1 : 0];
            p = applyDelta(raw, p + 4, back, k);
            frames.add(toArgb(back, palette));
        }
        return new KAnim(palette, frames);
    }

    /** ByteRun1 until the 100 rows are full, as {@code iff_convert}. Returns where it stopped. */
    private static int unpackBody(byte[] d, int p, byte[] dst) throws IOException {
        int o = 0;
        while (o < dst.length) {
            if (p >= d.length) {
                throw new IOException("BODY ends after " + o + " of " + dst.length + " bytes");
            }
            int n = d[p++];
            if (n >= 0) {
                for (int i = 0; i <= n; i++) {
                    byte v = d[p++];
                    if (o < dst.length) {
                        dst[o++] = v;
                    }
                }
            } else {
                byte v = d[p++];
                for (int i = 0; i < 1 - n && o < dst.length; i++) {
                    dst[o++] = v;
                }
            }
        }
        return p;
    }

    /**
     * One DLTA body, as {@code iff_DLTA}. The player bails out of the whole animation where this
     * throws; none of the shipped files get there.
     */
    private static int applyDelta(byte[] d, int p, byte[] bmp, int k) throws IOException {
        int columns = (d[p++] & 0xff) + 1;
        if (columns > ROW_BYTES * PLANES) {
            throw new IOException("DLTA " + k + ": " + columns + " columns");
        }
        for (int c = 0; c < columns; c++) {
            int ops = d[p++] & 0xff;
            if (ops == 0) {
                continue;
            }
            if (ops == 1) {
                throw new IOException("DLTA " + k + ": column " + c + " has no closing skip");
            }
            int row = 0;
            for (int op = 0; op < ops - 1; op++) {
                int code = d[p++] & 0xff;
                if (code == 0) {                       // run of one value
                    int n = d[p++] & 0xff;
                    byte v = d[p++];
                    checkCount(n, k, c);
                    for (int i = 0; i < n; i++, row++) {
                        put(bmp, row, c, v);
                    }
                } else if (code >= 0x80) {             // literal bytes, one per row
                    int n = code & 0x7f;
                    checkCount(n, k, c);
                    for (int i = 0; i < n; i++, row++) {
                        put(bmp, row, c, d[p++]);
                    }
                } else if (code < HEIGHT) {            // iff_Ytab: 100 entries, zeros after
                    row += code;
                }
                if (row >= HEIGHT + 2) {
                    throw new IOException("DLTA " + k + ": column " + c + " runs off the screen");
                }
            }
            p++;                                       // the IFF's skip into the blank half
        }
        return p;
    }

    private static void checkCount(int n, int k, int c) throws IOException {
        if (n == 0 || n > HEIGHT + 1) {
            throw new IOException("DLTA " + k + ": column " + c + " run of " + n);
        }
    }

    /** Rows 100 and 101 land in the gap between the player's two screens; nobody sees them. */
    private static void put(byte[] bmp, int row, int column, byte v) {
        if (row < HEIGHT) {
            bmp[row * LINE + column] = v;
        }
    }

    private static int[] toArgb(byte[] bmp, int[] palette) {
        int[] out = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            int base = y * LINE;
            int rgb = palette[0];
            for (int x = 0; x < WIDTH; x++) {
                int bit = 0x80 >> (x & 7);
                int v = 0;
                for (int pl = 0; pl < PLANES; pl++) {
                    if ((bmp[base + pl * ROW_BYTES + (x >> 3)] & bit) != 0) {
                        v |= 1 << pl;
                    }
                }
                int nibble = (v & 0xf) * 0x11;
                rgb = switch (v >> 4) {
                    case 0 -> palette[v];
                    case 1 -> (rgb & 0xffffff00) | nibble;
                    case 2 -> (rgb & 0xff00ffff) | nibble << 16;
                    default -> (rgb & 0xffff00ff) | nibble << 8;
                };
                out[y * WIDTH + x] = rgb;
            }
        }
        return out;
    }

    private static boolean tag(byte[] d, int off, String s) {
        for (int i = 0; i < 4; i++) {
            if ((d[off + i] & 0xff) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
