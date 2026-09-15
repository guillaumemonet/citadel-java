package citadel.core;

/**
 * sc_DoScroll: the message line along the bottom of the status panel.
 *
 * It has two speeds, and that is what makes it readable. With a message still to print it shifts
 * a whole byte and pokes the next glyph into the rightmost column:
 *
 * <pre>
 *   .sc_MoveText:  lea sv_ScrollArea-34,a2
 *                  REPT 33
 *                  move.b 1(a2),(a2)+      ;eight pixels at a time
 *                  ENDR
 * </pre>
 *
 * With nothing left to say it shifts a single pixel instead, by rolling the row through the carry:
 *
 * <pre>
 *   .sc_DS1:  move #%00000,CCR
 *             REPT 17
 *             roxl -(a1)                   ;one pixel
 *             ENDR
 * </pre>
 *
 * So a message is pushed in at a character a frame and then drifts out at a pixel a frame, taking
 * five and a half seconds to clear the window.
 *
 * The window itself is 34 bytes wide and seven rows deep. {@code sv_ScrollArea} is the address one
 * past its right hand end — {@code screen+$7d00+$1770+40}, which is panel row 30 in bitplane 1, so
 * subtracting 34 lands at byte 6 of bitplane 0: x 48 to 319 on screen row 190, drawn in index 1.
 */
public final class ScrollLine {

    /** 34 bytes, from the {@code REPT 33} and the {@code -34} that starts the row. */
    public static final int WIDTH = 34 * 8;
    /** {@code moveq #6,d7}, so seven rows. */
    public static final int HEIGHT = 7;
    /** Where the window starts on the panel, from sv_ScrollArea less 34 bytes. */
    public static final int X = 48;
    public static final int ROW = 190;
    /** One bitplane, the first, so the text is palette index 1. */
    public static final int PLANE = 0;

    private final HudGfx font;
    private final boolean[] pixels = new boolean[WIDTH * HEIGHT];
    private String pending = "";
    private int at;

    public ScrollLine(HudGfx font) {
        this.font = font;
    }

    /** Queues a message. Whatever was still being printed is dropped, as sc_TextAddr+4 does. */
    public void show(String text) {
        pending = (text == null) ? "" : text.toUpperCase();
        at = 0;
    }

    public boolean isPrinting() {
        return at < pending.length();
    }

    /** True while anything at all is on the line, so the renderer can skip the idle case. */
    public boolean isEmpty() {
        if (isPrinting()) {
            return false;
        }
        for (boolean b : pixels) {
            if (b) {
                return false;
            }
        }
        return true;
    }

    public boolean[] pixels() {
        return pixels;
    }

    /** One call of sc_DoScroll, which NewLev3 makes every VBLANK frame. */
    public void tick() {
        if (isPrinting()) {
            shift(8);
            char c = pending.charAt(at++);
            boolean[] glyph = font.glyph(c);
            // REPT 7: the glyph's first seven rows go into the rightmost byte column
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < 8; x++) {
                    pixels[y * WIDTH + WIDTH - 8 + x] = glyph[y * HudGfx.GLYPH_W + x];
                }
            }
        } else {
            shift(1);
        }
    }

    private void shift(int by) {
        for (int y = 0; y < HEIGHT; y++) {
            int row = y * WIDTH;
            System.arraycopy(pixels, row + by, pixels, row, WIDTH - by);
            java.util.Arrays.fill(pixels, row + WIDTH - by, row + WIDTH, false);
        }
    }
}
