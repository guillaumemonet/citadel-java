package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the ETAPY level sequence and the message table. */
class CampaignAndTextTest {

    private static File dataRoot() {
        return TestData.root();
    }

    // ------------------------------------------------------------- campaign

    @Test
    void theSequenceMatchesEtapy() {
        Campaign c = new Campaign();
        // "AB" + "ABC" * 6 + "A"
        assertEquals(2 + 3 * 6 + 1, c.size());
        assertEquals("LEVEL01A.MAP", c.stage(0).mapName());
        assertEquals("LEVEL01B.MAP", c.stage(1).mapName());
        assertEquals("LEVEL02A.MAP", c.stage(2).mapName());
        assertEquals("LEVEL08A.MAP", c.stage(c.size() - 1).mapName());
    }

    @Test
    void everyStageHasItsMapOnDisk() {
        File maps = new File(dataRoot(), "GAME/MAPS");
        Assumptions.assumeTrue(maps.isDirectory(), "original Data folder not present");
        Campaign c = new Campaign();
        for (Campaign.Stage s : c.stages()) {
            assertNotNull(c.file(maps, s), "missing " + s.mapName());
        }
    }

    @Test
    void theLastStageEndsTheGame() {
        Campaign c = new Campaign();
        assertNull(c.next("LEVEL08A.MAP"), "the campaign should end after complex 8");
        assertEquals("LEVEL01B.MAP", c.next("LEVEL01A.MAP").mapName());
        assertEquals("LEVEL02A.MAP", c.next("LEVEL01B.MAP").mapName());
        assertEquals("LEVEL03A.MAP", c.next("LEVEL02C.MAP").mapName());
    }

    /** Several maps ship that the campaign never visits; they must not confuse the sequence. */
    @Test
    void extraMapsAreNotPartOfTheSequence() {
        Campaign c = new Campaign();
        for (String extra : new String[]{"LEVEL01T.MAP", "LEVEL03K.MAP", "LEVEL05D.MAP"}) {
            assertEquals(-1, c.indexOf(extra), extra + " should not be in the campaign");
            assertNull(c.next(extra));
        }
    }

    // ----------------------------------------------------------------- text

    private static TextTable texts() throws Exception {
        File maps = new File(dataRoot(), "GAME/Store/TEXT.ENG");
        Assumptions.assumeTrue(maps.isFile(), "TEXT.ENG not present");
        return TextTable.loadFrom(dataRoot());
    }

    /**
     * The message numbers are the SCROLL arguments, so a handful of known ones are checked
     * verbatim. If the parser ever drifts by one these fail immediately.
     */
    @Test
    void knownMessageNumbersLineUpWithTheSource() throws Exception {
        TextTable t = texts();
        assertTrue(t.size() > 80, "expected the full table, got " + t.size());
        assertTrue(t.get(TextTable.PAUSED).contains("PAUSED"),
                "41 should be the paused message, got: " + t.get(TextTable.PAUSED));
        assertTrue(t.get(TextTable.DOOR_ANIMATING).contains("OPENING"),
                "52 should be the door-moving message, got: " + t.get(TextTable.DOOR_ANIMATING));
        assertTrue(t.get(TextTable.WEAPON_DAMAGED).contains("WEAPON"),
                "76 should be the damaged-weapon message");
        for (String v : t.variants(TextTable.OUT_OF_AMMO)) {
            assertTrue(v.contains("AMMO") || v.contains("AMMUNITION"),
                    "65 should be about ammunition, got: " + v);
        }
        for (String v : t.variants(TextTable.SWITCH_USED)) {
            assertFalse(v.isEmpty());
        }
    }

    /** Blocks marked with '^' hold interchangeable lines; the marker itself must not survive. */
    @Test
    void variantBlocksAreSplitAndTheMarkerStripped() throws Exception {
        TextTable t = texts();
        String[] ammo = t.variants(TextTable.OUT_OF_AMMO);
        assertTrue(ammo.length > 1, "65 should have several variants, got " + ammo.length);
        for (String v : ammo) {
            assertFalse(v.contains("^"), "the variant marker leaked into: " + v);
            assertFalse(v.contains(";"), "an author's note leaked into: " + v);
        }
    }

    @Test
    void theRulesUseTheOriginalWordingWhenGivenTheTable() throws Exception {
        File maps = new File(dataRoot(), "GAME/MAPS/LEVEL01A.MAP");
        Assumptions.assumeTrue(maps.isFile(), "LEVEL01A not present");
        GameState game = new GameState(Level.load(maps));
        game.setTexts(texts());
        game.inventory.owned[1] = true;
        game.inventory.condition[1] = 500;
        game.inventory.ammo[1] = 0;
        game.inventory.current = 1;
        game.drainLog();

        game.fire();
        String msg = game.drainLog().toString();
        assertTrue(msg.contains("AMMO") || msg.contains("AMMUNITION"),
                "expected the original out-of-ammo wording, got: " + msg);
    }
}
