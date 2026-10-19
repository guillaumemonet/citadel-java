package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WINDOW1.RAW, NUMBERS.RAW and FONTS01.FNT: the status panel and its two fonts. */
class HudGfxTest {

    private static File gfxDir() {
        File root = TestData.root();
        Assumptions.assumeTrue(root.isDirectory(), "original Data folder not present");
        File dir = new File(root, "GAME/Gfx_VIR");
        Assumptions.assumeTrue(new File(dir, "WINDOW1.RAW").isFile(), "WINDOW1.RAW not present");
        return dir;
    }

    private static HudGfx load() throws Exception {
        return HudGfx.loadFrom(gfxDir());
    }

    @Test
    @DisplayName("each file is exactly the size its equ declares")
    void theSizesMatchTheDeclarations() {
        File dir = gfxDir();
        // screen: equ ... ;($9c40 razem), and 320/8 * 5 * 200 is the same number
        assertEquals(0x9c40, new File(dir, "WINDOW1.RAW").length());
        assertEquals(HudGfx.SCREEN_W / 8 * HudGfx.PLANES * HudGfx.SCREEN_H,
                new File(dir, "WINDOW1.RAW").length());
        assertEquals(0x1ae, new File(dir, "NUMBERS.RAW").length(), "sv_Numbers");
        assertEquals(0x300, new File(dir, "FONTS01.FNT").length(), "sv_Fonts");
    }

    @Test
    @DisplayName("the panel has a hole where the view goes, and the bevel is two pixels")
    void theViewHoleIsWhereTheEngineSaysItIs() throws Exception {
        int[] px = load().panel();
        assertEquals(HudGfx.SCREEN_W * HudGfx.SCREEN_H, px.length);
        for (int v : px) {
            assertTrue(v >= 0 && v < 32, "five bitplanes give 0..31, got " + v);
        }
        // sv_Leftoffset * 8 and sv_Upoffset, 192 by 128
        for (int y = HudGfx.VIEW_Y; y < HudGfx.VIEW_Y + HudGfx.VIEW_H; y++) {
            for (int x = HudGfx.VIEW_X; x < HudGfx.VIEW_X + HudGfx.VIEW_W; x++) {
                assertEquals(0, px[y * HudGfx.SCREEN_W + x],
                        "the view window has to be clear at " + x + "," + y);
            }
        }
        // the artwork's hole runs two pixels wider all round, which is its bevel
        assertEquals(0, px[(HudGfx.VIEW_Y - 2) * HudGfx.SCREEN_W + HudGfx.VIEW_X - 2]);
        assertTrue(px[(HudGfx.VIEW_Y - 3) * HudGfx.SCREEN_W + 160] != 0
                        || px[(HudGfx.VIEW_Y + HudGfx.VIEW_H + 2) * HudGfx.SCREEN_W + 160] != 0,
                "and there is panel outside it");
        // the status strip along the bottom is not empty
        int lit = 0;
        for (int y = 160; y < HudGfx.SCREEN_H; y++) {
            for (int x = 0; x < HudGfx.SCREEN_W; x++) {
                if (px[y * HudGfx.SCREEN_W + x] != 0) {
                    lit++;
                }
            }
        }
        assertTrue(lit > 5000, "the status strip should be mostly drawn, got " + lit);
    }

    @Test
    @DisplayName("ten digits, all different, on an 18 row grid of 20 byte rows")
    void theDigitsDecode() throws Exception {
        HudGfx hud = load();
        java.util.Set<String> shapes = new java.util.HashSet<String>();
        for (int d = 0; d < HudGfx.DIGITS; d++) {
            boolean[] g = hud.digit(d);
            assertEquals(HudGfx.DIGIT_W * HudGfx.DIGIT_H, g.length);
            int set = 0;
            for (boolean b : g) {
                if (b) {
                    set++;
                }
            }
            assertTrue(set > 20, "digit " + d + " is nearly blank");
            shapes.add(java.util.Arrays.toString(g));
        }
        assertEquals(HudGfx.DIGITS, shapes.size(), "no two digits should look the same");
    }

    @Test
    @DisplayName("the font is ASCII from space, 96 glyphs of 8 by 8")
    void theFontIsAscii() throws Exception {
        HudGfx hud = load();
        for (boolean b : hud.glyph(' ')) {
            assertFalse(b, "space is blank");
        }
        assertTrue(anySet(hud.glyph('A')));
        assertTrue(anySet(hud.glyph('z')));
        assertTrue(anySet(hud.glyph('0')));
        for (boolean b : hud.glyph((char) 0x1f)) {
            assertFalse(b, "below the first glyph");
        }
        for (boolean b : hud.glyph((char) 0x81)) {
            assertFalse(b, "past the last glyph");
        }
        // the letters are distinct, which is what says the offset is right
        assertFalse(java.util.Arrays.equals(hud.glyph('A'), hud.glyph('B')));
    }

    @Test
    @DisplayName("the bytes after the digits are a second, smaller set of digits")
    void theSmallDigitsDecode() throws Exception {
        HudGfx hud = load();
        java.util.Set<String> shapes = new java.util.HashSet<String>();
        for (int d = 0; d < HudGfx.SMALL_DIGITS; d++) {
            boolean[] g = hud.smallDigit(d);
            assertTrue(anySet(g), "small digit " + d + " is blank");
            shapes.add(java.util.Arrays.toString(g));
        }
        assertEquals(HudGfx.SMALL_DIGITS, shapes.size(), "ten distinct glyphs");
        // a one is the thinnest of them, which is what says these are digits and not a bar
        assertTrue(count(hud.smallDigit(1)) < count(hud.smallDigit(8)));
    }

    private static int count(boolean[] bits) {
        int n = 0;
        for (boolean b : bits) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    private static boolean anySet(boolean[] bits) {
        for (boolean b : bits) {
            if (b) {
                return true;
            }
        }
        return false;
    }
}
