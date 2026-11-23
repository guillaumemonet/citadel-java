package citadel.game;

import citadel.core.HangarMenu;
import citadel.core.KaneFont;

/**
 * MainServer's screen: the hangar, with the menus printed into the panel the artwork leaves for
 * them, the way DrukTekst, PostawKropa and NewLev3 do it.
 *
 * <ul>
 * <li>The panel is {@code SERoff = iff_screen+[53*40*6]+12}: x 96 to 223, 102 rows from row 53.
 *     MainServer keeps a clean copy ({@code SERbuf}); DrukTekst restores it into a work buffer
 *     ({@code SERscr}), prints the six lines there, and only then copies the work buffer to the
 *     screen a byte column at a time in the order {@code Store/HangarTab.dat} gives — 816 bytes of
 *     eight pixels, each with its twin 51 rows further down, 32 a VBLANK. That is the panel
 *     dissolving into the new menu.</li>
 * <li>Text goes in where a glyph bit is set, planes 1 and 4 set and the others cleared: colour
 *     18. A flagged line ({@code m_CopFont2}) is rotated four bits and written across two bytes,
 *     four pixels further right.</li>
 * <li>The cursor dots are planes 0 set and 1 to 5 cleared, colour 1, at bytes 11 and 28.
 *     CzyscBoki puts those two columns back from {@code SERlepr} first.</li>
 * <li>NewLev3, while {@code do_flash} is on, moves the cursor and, every other VBLANK, writes
 *     the next FlashTab green into colour 1 ({@code move 2(a1,d0.w),Maincopper+6}). Colour 1 is
 *     a register, so whatever else in the picture is colour 1 flashes with the dots.</li>
 * </ul>
 */
final class HangarView {

    private static final int W = Display.W;
    /** SERoff's row and pixel column, and the panel's size: 612 plane rows of 16 bytes. */
    private static final int PANEL_ROW = 53;
    private static final int PANEL_X = 12 * 8;
    private static final int PANEL_W = 16 * 8;
    private static final int PANEL_H = 612 / 6;
    /** Half the panel: each HangTab entry reveals a byte and the one 51 rows below it. */
    private static final int HALF = 51;
    /** SERlepr: bytes 11 and 28 of each panel row, where the dots go. */
    private static final int LEFT_X = 11 * 8;
    private static final int RIGHT_X = 28 * 8;
    /** DrukTekst's colour: planes 1 and 4. */
    static final int INK = 2 + 16;
    /** PostawKropa's: plane 0 alone. */
    static final int DOT = 1;
    /** FlashTab, the sixteen greens colour 1 cycles through. */
    static final int[] FLASH = {
        0x0f0, 0x0e0, 0x0d0, 0x0c0, 0x0b0, 0x0a0, 0x090, 0x080,
        0x070, 0x080, 0x090, 0x0a0, 0x0b0, 0x0c0, 0x0d0, 0x0e0,
    };
    /** KropaDat. */
    private static final int[] KROPA = {
        0b00111100, 0b01111110, 0b11111111, 0b11111111,
        0b11111111, 0b11111111, 0b01111110, 0b00111100,
    };

    private final Display display;
    private final KaneFont font;
    private final int[] hangar;
    final int[] palette;
    private final int[] hangTab;

    private final int[] serBuf = new int[PANEL_W * PANEL_H];
    private final int[] serScr = new int[PANEL_W * PANEL_H];
    private final int[] serLepr = new int[2 * 8 * PANEL_H];

    /** Kropa, the line the cursor is on. */
    int kropa;
    /** do_flash: the interrupt moves the cursor and flashes colour 1 only while this is on. */
    boolean doFlash;
    private boolean flashToggle;
    private int flashCounter;

    /**
     * @param hangar  the picture's indices, six planes, Extra Half-Brite
     * @param palette its 32 colours, 12 bit
     * @param hangTab Store/HangarTab.dat
     */
    HangarView(Display display, KaneFont font, int[] hangar, int[] palette, byte[] hangTab) {
        this.display = display;
        this.font = font;
        this.hangar = hangar;
        this.palette = palette;
        this.hangTab = new int[hangTab.length / 2];
        for (int i = 0; i < this.hangTab.length; i++) {
            this.hangTab[i] = ((hangTab[i * 2] & 0xff) << 8) | (hangTab[i * 2 + 1] & 0xff);
        }
    }

    /** {@code s}: the picture freshly unpacked, all its colours black until MainServer fades in. */
    void load() {
        System.arraycopy(hangar, 0, display.index, 0, hangar.length);
        java.util.Arrays.fill(display.colours, 0);
        display.ehb = true;
        display.argb = null;
        doFlash = false;
        display.touch();
    }

    /** MainServer: the clean panel into SERscr and SERbuf, and the dot columns into SERlepr. */
    void keepPanel() {
        for (int y = 0; y < PANEL_H; y++) {
            System.arraycopy(display.index, (PANEL_ROW + y) * W + PANEL_X, serBuf, y * PANEL_W,
                    PANEL_W);
            System.arraycopy(display.index, (PANEL_ROW + y) * W + LEFT_X, serLepr, y * 16, 8);
            System.arraycopy(display.index, (PANEL_ROW + y) * W + RIGHT_X, serLepr, y * 16 + 8, 8);
        }
        System.arraycopy(serBuf, 0, serScr, 0, serBuf.length);
    }

    /** CzyscBoki: the two dot columns back as they were. */
    void czyscBoki() {
        for (int y = 0; y < PANEL_H; y++) {
            System.arraycopy(serLepr, y * 16, display.index, (PANEL_ROW + y) * W + LEFT_X, 8);
            System.arraycopy(serLepr, y * 16 + 8, display.index, (PANEL_ROW + y) * W + RIGHT_X, 8);
        }
        display.touch();
    }

    /** wwww: the clean panel back on the screen. */
    void restorePanel() {
        for (int y = 0; y < PANEL_H; y++) {
            System.arraycopy(serBuf, y * PANEL_W, display.index, (PANEL_ROW + y) * W + PANEL_X,
                    PANEL_W);
        }
        czyscBoki();
    }

    /** PostawKropa: CzyscBoki, then a dot either side of line {@link #kropa}. */
    void postawKropa() {
        czyscBoki();
        int row = HangarMenu.CURSOR_ROW + kropa * HangarMenu.LINE_STEP;
        for (int y = 0; y < KROPA.length; y++) {
            for (int x = 0; x < 8; x++) {
                if (((KROPA[y] >> (7 - x)) & 1) != 0) {
                    display.index[(row + y) * W + LEFT_X + x] = DOT;
                    display.index[(row + y) * W + RIGHT_X + x] = DOT;
                }
            }
        }
        display.touch();
    }

    /**
     * DrukTekst: the six lines into SERscr, then revealed through HangTab, 32 bytes a VBLANK,
     * then the cursor and the flashing back on.
     */
    void drukTekst(FrontScript script, String[] lines, boolean[] shifted) {
        script.vblank();
        doFlash = false;
        czyscBoki();
        System.arraycopy(serBuf, 0, serScr, 0, serBuf.length);
        for (int i = 0; i < lines.length; i++) {
            int row = 8 + i * HangarMenu.LINE_STEP;
            int shift = shifted[i] ? HangarMenu.FLAG_SHIFT : 0;
            for (int c = 0; c < lines[i].length(); c++) {
                boolean[] g = font.glyph(lines[i].charAt(c));
                for (int y = 0; y < KaneFont.GLYPH_H; y++) {
                    for (int x = 0; x < KaneFont.GLYPH_W; x++) {
                        int px = c * 8 + x + shift;
                        if (g[y * KaneFont.GLYPH_W + x] && px < PANEL_W && row + y < PANEL_H) {
                            serScr[(row + y) * PANEL_W + px] = INK;
                        }
                    }
                }
            }
        }
        // move #-1,(a3): sixteen pixels of plane 0 on the panel's first row, until revealed
        for (int x = 0; x < 16; x++) {
            display.index[PANEL_ROW * W + PANEL_X + x] |= 1;
        }
        int entry = 0;
        for (int frame = 0; frame < hangTab.length / 32; frame++) {
            script.vblank();
            for (int k = 0; k < 32; k++) {
                reveal(hangTab[entry++]);
            }
        }
        while (entry < hangTab.length) {
            reveal(hangTab[entry++]);
        }
        script.input.up = false;
        script.input.down = false;
        postawKropa();
        doFlash = true;
    }

    /** prz2: one byte of SERscr, all six planes, and its twin 51 rows down. */
    private void reveal(int v) {
        int row = (v >> 4) & 0xfff;
        int col = v & 15;
        for (int half = 0; half < 2; half++) {
            int r = row + half * HALF;
            if (r >= PANEL_H) {
                continue;
            }
            System.arraycopy(serScr, r * PANEL_W + col * 8, display.index,
                    (PANEL_ROW + r) * W + PANEL_X + col * 8, 8);
        }
        display.touch();
    }

    /** NewLev3's part for the main server, once a VBLANK. */
    void interrupt(FrontScript.Input in) {
        if (!doFlash) {
            return;
        }
        if (in.up) {
            in.up = false;
            kropa = kropa == 0 ? 5 : kropa - 1;
            postawKropa();
        } else if (in.down) {
            in.down = false;
            kropa = kropa == 5 ? 0 : kropa + 1;
            postawKropa();
        }
        flashToggle = !flashToggle;
        if (!flashToggle) {
            return;
        }
        flashCounter = (flashCounter + 2) & 31;
        display.colours[1] = FLASH[flashCounter / 2];
        display.touch();
    }
}
