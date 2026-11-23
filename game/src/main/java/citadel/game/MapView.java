package citadel.game;

import citadel.core.HudGfx;
import citadel.core.Structure;
import citadel.core.WorldMap;

/**
 * MAPA, the map between complexes, as SERVER runs it.
 *
 * <ul>
 * <li>The picture is 320 by 192, six planes, Extra Half-Brite. MAPAcopper turns the planes off
 *     at line $f6 and back on, one plane deep, at $f8 for the status line: {@code MAPAscr},
 *     seven rows of the in-game 8 by 8 font ({@code ma_draw}) in a colour of its own,
 *     {@code ma_col}, on black. $f8 is row 194, and the display ends six rows later.</li>
 * <li>Every change of the line fades that colour out in steps of $222 ({@code ma_out}), clears
 *     and redraws, and fades it back in ({@code ma_in}), a VBLANK a step.</li>
 * <li>{@code map_NEWLEV3} bobs the arrow through Asinus: it puts back the 20 rows it saved
 *     under the arrow and draws the 2 by 11 arrow, masked, ArrowWys rows down.</li>
 * </ul>
 */
final class MapView {

    private static final int W = Display.W;
    private static final int MAP_H = 192;
    /** Line $f8, where the one plane status line starts. */
    static final int TEXT_ROW = 0xf8 - 0x36;
    /** The registers the status line uses: black, and ma_col. */
    private static final int TEXT_BG = 64;
    private static final int TEXT_INK = 65;
    /** ClrArrow and Zachowaj keep 20 rows under each arrow position. */
    private static final int SAVED_ROWS = 20;
    private static final int ARROW_W = 16;

    private final Display display;
    private final HudGfx font;
    private final int[] map;
    final int[] palette;
    private final int[] arrow;
    private final int arrowH;

    /** CzyArrow: the interrupt leaves the arrow alone while this is set. */
    volatile boolean czyArrow = true;
    private int asinus;
    final WorldMap world = new WorldMap();

    /**
     * @param map     MAPKA.rawb's indices, 320 by 192
     * @param palette its 32 colours, 12 bit
     * @param arrow   strzalka.rawb's indices, 16 by 11, 0 where the mask lets the map through
     */
    MapView(Display display, HudGfx font, int[] map, int[] palette, int[] arrow, int arrowH) {
        this.display = display;
        this.font = font;
        this.map = map;
        this.palette = palette;
        this.arrow = arrow;
        this.arrowH = arrowH;
    }

    /** The picture in place, every colour black, the status line empty. */
    void load() {
        java.util.Arrays.fill(display.index, 0);
        System.arraycopy(map, 0, display.index, 0, Math.min(map.length, W * MAP_H));
        java.util.Arrays.fill(display.colours, 0);
        display.ehb = true;
        display.argb = null;
        display.colours[TEXT_BG] = 0;
        display.colours[TEXT_INK] = 0xfff;   // ma_col: dc.l $1820fff
        maClr();
    }

    /** ma_clr: MAPAscr cleared. */
    void maClr() {
        for (int y = TEXT_ROW; y < Display.H; y++) {
            java.util.Arrays.fill(display.index, y * W, (y + 1) * W, TEXT_BG);
        }
        display.touch();
    }

    /** ma_draw: Font8, seven rows a glyph, from the left edge. */
    void maDraw(String text) {
        for (int i = 0; i < text.length() && i < 40; i++) {
            boolean[] g = font.glyph(text.charAt(i));
            for (int y = 0; y < 7; y++) {
                int row = TEXT_ROW + y;
                if (row >= Display.H) {
                    break;
                }
                for (int x = 0; x < 8; x++) {
                    display.index[row * W + i * 8 + x] = g[y * 8 + x] ? TEXT_INK : TEXT_BG;
                }
            }
        }
        display.touch();
    }

    /** ma_out: ma_col from $eee down by $222 a VBLANK, to black. */
    void maOut(FrontScript s) {
        for (int c = 0xeee; c >= 0; c -= 0x222) {
            s.vblank();
            display.colours[TEXT_INK] = c;
            display.touch();
        }
    }

    /** ma_in: back up from $222 to $eee. */
    void maIn(FrontScript s) {
        int c = 0x222;
        do {
            s.vblank();
            display.colours[TEXT_INK] = c;
            display.touch();
            c += 0x222;
        } while (c != 0xeee);
        display.colours[TEXT_INK] = c;
    }

    /** Printnij: the status line for the current choice, faded out and in again. */
    void printnij(FrontScript s) {
        String line = world.statusLine();
        maOut(s);
        maDraw(line);
        maIn(s);
    }

    /** One of the refusals, or the choice: faded out, cleared, drawn, faded in. */
    void say(FrontScript s, String text) {
        maOut(s);
        maClr();
        maDraw(text);
        maIn(s);
    }

    /** ClrArrow: every position put back as Zachowaj found it. */
    void clrArrow() {
        for (int[] at : WorldMap.ARROW) {
            restore(at[0], at[1]);
        }
        display.touch();
    }

    private void restore(int x, int row) {
        for (int y = row; y < row + SAVED_ROWS && y < MAP_H; y++) {
            System.arraycopy(map, y * W + x, display.index, y * W + x, ARROW_W);
        }
    }

    /** map_NEWLEV3: the next Asinus height, and DrukArrow. */
    void interrupt() {
        if (czyArrow) {
            return;
        }
        asinus = (asinus + 1) & 31;
        int[] at = WorldMap.ARROW[world.choice()];
        restore(at[0], at[1]);
        int top = at[1] + WorldMap.BOB[asinus];
        for (int y = 0; y < arrowH; y++) {
            for (int x = 0; x < ARROW_W; x++) {
                int v = arrow[y * ARROW_W + x];
                if (v != 0 && top + y < MAP_H) {
                    display.index[(top + y) * W + at[0] + x] = v;
                }
            }
        }
        display.touch();
    }

    /** The structure MAPA works on, read in on the way in. */
    void enter(Structure s) {
        world.enter(s);
    }
}
