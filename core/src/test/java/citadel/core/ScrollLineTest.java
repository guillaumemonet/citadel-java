package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sc_DoScroll: the message line, and the two speeds that make it readable. */
class ScrollLineTest {

    private static ScrollLine line() throws Exception {
        File root = TestData.root();
        Assumptions.assumeTrue(root.isDirectory(), "original Data folder not present");
        File dir = new File(root, "GAME/Gfx_VIR");
        Assumptions.assumeTrue(new File(dir, "FONTS01.FNT").isFile(), "FONTS01.FNT not present");
        return new ScrollLine(HudGfx.loadFrom(dir));
    }

    @Test
    @DisplayName("the window is the 34 bytes by seven rows the routine walks")
    void theWindowIsTheOneTheRoutineWalks() {
        // REPT 33 over bytes, starting at sv_ScrollArea-34, and moveq #6,d7 rows
        assertEquals(34 * 8, ScrollLine.WIDTH);
        assertEquals(7, ScrollLine.HEIGHT);
        // sv_ScrollArea is screen+$7d00+$1770+40: row 30 of the panel, bitplane 1, less 34 bytes
        assertEquals(190, ScrollLine.ROW);
        assertEquals(48, ScrollLine.X);
    }

    @Test
    @DisplayName("a message goes in a character a frame, at the right hand end")
    void textEntersFromTheRight() throws Exception {
        ScrollLine s = line();
        assertTrue(s.isEmpty());
        s.show("AB");
        assertTrue(s.isPrinting());

        s.tick();
        assertFalse(s.isEmpty(), "the first letter should be on the line");
        assertTrue(litInLastByte(s), "and at the right hand end");
        s.tick();
        assertFalse(s.isPrinting(), "two letters, two frames");
    }

    @Test
    @DisplayName("once it has all been printed the line drifts out a pixel a frame")
    void itDriftsOutAPixelAtATime() throws Exception {
        ScrollLine s = line();
        s.show("X");
        s.tick();
        int before = leftmostLit(s);
        assertTrue(before >= 0, "something is on the line");

        s.tick();
        assertEquals(before - 1, leftmostLit(s), "roxl moves it exactly one pixel");

        // 272 pixels at one a frame is five and a half seconds to clear
        for (int i = 0; i < ScrollLine.WIDTH; i++) {
            s.tick();
        }
        assertTrue(s.isEmpty(), "the window should have emptied");
    }

    @Test
    @DisplayName("printing shifts eight pixels, not one")
    void printingShiftsAWholeCharacter() throws Exception {
        ScrollLine s = line();
        s.show("XX");
        s.tick();
        int first = leftmostLit(s);
        s.tick();
        // the first glyph moved a whole byte left, so the leftmost lit pixel did too
        assertEquals(first - 8, leftmostLit(s));
    }

    private static boolean litInLastByte(ScrollLine s) {
        boolean[] px = s.pixels();
        for (int y = 0; y < ScrollLine.HEIGHT; y++) {
            for (int x = ScrollLine.WIDTH - 8; x < ScrollLine.WIDTH; x++) {
                if (px[y * ScrollLine.WIDTH + x]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int leftmostLit(ScrollLine s) {
        boolean[] px = s.pixels();
        for (int x = 0; x < ScrollLine.WIDTH; x++) {
            for (int y = 0; y < ScrollLine.HEIGHT; y++) {
                if (px[y * ScrollLine.WIDTH + x]) {
                    return x;
                }
            }
        }
        return -1;
    }
}
