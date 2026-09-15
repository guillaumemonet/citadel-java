package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Store/Bomba.rawb and the warhead counter DrawBomb reads. */
class BombTest {

    private static File dataRoot() {
        File root = new File("../Data").isDirectory() ? new File("../Data") : new File("Data");
        Assumptions.assumeTrue(root.isDirectory(), "original Data folder not present");
        return root;
    }

    private static BombGfx load() throws Exception {
        File f = new File(new File(dataRoot(), "GAME/Store"), "Bomba.rawb");
        Assumptions.assumeTrue(f.isFile(), "Bomba.rawb not present");
        return BombGfx.load(f);
    }

    @Test
    @DisplayName("the file is exactly the 155 rows of 36 bytes the blit walks")
    void theFileIsTheBlit() throws Exception {
        File f = new File(new File(dataRoot(), "GAME/Store"), "Bomba.rawb");
        Assumptions.assumeTrue(f.isFile(), "Bomba.rawb not present");
        // move #[31*5]-1,d1 with lea 36(a1),a1, and sv_Bomba is declared as $15cc bytes
        assertEquals(31 * 5 * 36, f.length());
        assertEquals(0x15cc, f.length());
    }

    @Test
    @DisplayName("sv_Glowica steps in twos from nothing to the finished bomb")
    void theCounterRunsInTwos() {
        assertEquals(-1, BombGfx.stageFor(0), "beq.s db_1 - nothing is drawn yet");
        assertEquals(0, BombGfx.stageFor(-2), "the first section");
        assertEquals(1, BombGfx.stageFor(-4));
        assertEquals(5, BombGfx.stageFor(-12), "cmpi #13,d0 lets 12 through");
        assertEquals(-1, BombGfx.stageFor(-14), "and stops there");
    }

    @Test
    @DisplayName("every stage decodes to palette indices, and they are not all the same picture")
    void thePicturesDiffer() throws Exception {
        BombGfx bomb = load();
        int[] first = bomb.pixels(0);
        int[] last = bomb.pixels(BombGfx.STAGES - 1);
        assertEquals(BombGfx.WIDTH * BombGfx.HEIGHT, first.length);
        for (int v : first) {
            assertTrue(v >= 0 && v < 32, "five bitplanes give 0..31, got " + v);
        }
        assertNotEquals(java.util.Arrays.hashCode(first), java.util.Arrays.hashCode(last),
                "the gauge has to actually fill up");

        int drawnFirst = 0, drawnLast = 0;
        for (int i = 0; i < first.length; i++) {
            if (first[i] != 0) {
                drawnFirst++;
            }
            if (last[i] != 0) {
                drawnLast++;
            }
        }
        assertTrue(drawnLast > drawnFirst, "later stages carry more of the bomb");
    }

    @Test
    @DisplayName("each section gets its own message, from FIRST to LAST")
    void eachSectionHasItsOwnLine() throws Exception {
        TextTable texts;
        try {
            texts = TextTable.loadFrom(dataRoot());
        } catch (Exception e) {
            Assumptions.abort("no TEXT file present");
            return;
        }
        // DrawBomb: addi #77,d0, so the six stages run 77..82
        String first = texts.get(GameState.BOMB_TEXT);
        String last = texts.get(GameState.BOMB_TEXT + BombGfx.STAGES - 1);
        assertTrue(first.toUpperCase().contains("FIRST") || first.toUpperCase().contains("PIERW"),
                "expected the first-part line, got: " + first);
        assertTrue(last.toUpperCase().contains("LAST") || last.toUpperCase().contains("OSTAT"),
                "expected the last-part line, got: " + last);
        assertNotEquals(first, last);
    }
}
