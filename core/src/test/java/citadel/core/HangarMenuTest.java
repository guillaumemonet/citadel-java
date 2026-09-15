package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server's menus and the font it prints them in. */
class HangarMenuTest {

    private static File dataRoot() {
        File root = new File("../Data").isDirectory() ? new File("../Data") : new File("Data");
        Assumptions.assumeTrue(root.isDirectory(), "original Data folder not present");
        return root;
    }

    @Test
    @DisplayName("every line is the sixteen characters DrukTekst prints")
    void everyLineIsSixteen() {
        HangarMenu menu = new HangarMenu();
        for (HangarMenu.Screen s : HangarMenu.Screen.values()) {
            menu.open(s);
            String[] lines = menu.lines();
            assertEquals(HangarMenu.LINES, lines.length, s + " should have six lines");
            for (String line : lines) {
                assertEquals(HangarMenu.COLUMNS, line.length(), s + ": '" + line + "'");
            }
        }
    }

    @Test
    @DisplayName("the cursor wraps both ways, as ma_odejmij and ma_dodaj do")
    void theCursorWraps() {
        HangarMenu menu = new HangarMenu();
        assertEquals(0, menu.line());
        menu.move(-1);
        assertEquals(HangarMenu.LINES - 1, menu.line());
        menu.move(1);
        assertEquals(0, menu.line());
        for (int i = 0; i < HangarMenu.LINES; i++) {
            menu.move(1);
        }
        assertEquals(0, menu.line(), "a full lap comes back to the top");
    }

    @Test
    @DisplayName("the main screen picks out START, LOAD, OPTIONS, TRAINING and INFO")
    void theMainScreenDispatches() {
        HangarMenu menu = new HangarMenu();
        assertSame(HangarMenu.Action.START, at(menu, 0));
        assertSame(HangarMenu.Action.LOAD, at(menu, 1));
        assertSame(HangarMenu.Action.OPTIONS, at(menu, 2));
        assertSame(HangarMenu.Action.TRAINING, at(menu, 3));
        assertSame(HangarMenu.Action.NONE, at(menu, 4), "the blank line does nothing");
        assertSame(HangarMenu.Action.INFO, at(menu, 5));
    }

    @Test
    @DisplayName("the training screen has five levels and a way back, and all five ship")
    void theTrainingLevelsExist() {
        HangarMenu menu = new HangarMenu();
        menu.open(HangarMenu.Screen.TRAINING);
        for (int i = 0; i < 5; i++) {
            assertSame(HangarMenu.Action.TRAIN_LEVEL, at(menu, i), "line " + i);
        }
        assertSame(HangarMenu.Action.BACK, at(menu, 5));

        // FileStructure2 lists M1T, M3T, M3K, M5T and M6T for the five entries
        assertEquals(5, HangarMenu.TRAINING_MAPS.length);
        File maps = new File(dataRoot(), "GAME/MAPS");
        Assumptions.assumeTrue(maps.isDirectory(), "maps folder not present");
        for (String m : HangarMenu.TRAINING_MAPS) {
            assertTrue(new File(maps, m).isFile(), m + " is missing");
        }
    }

    @Test
    @DisplayName("the difficulty line toggles sv_difficult and says which way it went")
    void difficultyToggles() {
        HangarMenu menu = new HangarMenu();
        menu.open(HangarMenu.Screen.OPTIONS);
        assertFalse(menu.easy(), "HARD to start with, as the original prints");
        assertTrue(menu.lines()[0].contains("HARD"));
        assertSame(HangarMenu.Action.DIFFICULTY, at(menu, 0));
        assertTrue(menu.easy());
        assertTrue(menu.lines()[0].contains("EASY"));
        assertSame(HangarMenu.Action.BACK, at(menu, 5));
    }

    @Test
    @DisplayName("KANE.fnt has no glyph for @, so A sits at index 32")
    void theFontSkipsTheAtSign() throws Exception {
        File f = new File(new File(dataRoot(), "INTRO/STORE"), "KANE.fnt");
        Assumptions.assumeTrue(f.isFile(), "KANE.fnt not present");
        KaneFont font = KaneFont.load(f);

        // subi #32,d0 / cmpi #32,d0 / bmi / subq #1,d0
        assertEquals(0, font.indexOf(' '));
        assertEquals(31, font.indexOf('?'), "the last one before the gap");
        assertEquals(32, font.indexOf('A'), "and the first one after it");
        assertEquals(33, font.indexOf('B'));

        assertFalse(anySet(font.glyph(' ')), "space is blank");
        assertTrue(anySet(font.glyph('A')));
        assertTrue(anySet(font.glyph('S')));
        assertNotEquals(java.util.Arrays.toString(font.glyph('A')),
                java.util.Arrays.toString(font.glyph('B')));
        assertEquals(192, font.glyphs(), "3072 bytes is 96 pairs");
    }

    private static HangarMenu.Action at(HangarMenu menu, int line) {
        while (menu.line() != line) {
            menu.move(1);
        }
        return menu.select();
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
