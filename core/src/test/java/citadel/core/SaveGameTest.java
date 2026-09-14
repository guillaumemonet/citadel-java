package citadel.core;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the save format against the file the original game ships. */
class SaveGameTest {

    private static File dataRoot() {
        File a = new File("../Data");
        return a.isDirectory() ? a : new File("Data");
    }

    private static File shippedSave() {
        return new File(dataRoot(), "GAME/Store/Savegame.DAT");
    }

    private static Level level() throws Exception {
        File f = new File(dataRoot(), "GAME/MAPS/LEVEL01A.MAP");
        Assumptions.assumeTrue(f.isFile(), "LEVEL01A not present");
        return Level.load(f);
    }

    /**
     * The shipped file is the fixed point: if the offsets are right, its five slots read as empty
     * and its weapon fields hold the per-pickup ammunition the engine initialises them with.
     */
    @Test
    void theShippedSaveFileReadsAsFiveEmptySlots() throws Exception {
        Assumptions.assumeTrue(shippedSave().isFile(), "Savegame.DAT not present");
        SaveGame save = SaveGame.load(shippedSave());
        for (int i = 0; i < SaveGame.SLOT_COUNT; i++) {
            assertTrue(save.isEmpty(i), "slot " + i + " should be free, named " + save.name(i));
        }
        assertEquals(768, shippedSave().length(), "the file should be 6 blocks of 128");
    }

    @Test
    void aRoundTripKeepsTheInventory() throws Exception {
        GameState game = new GameState(level());
        game.inventory.owned[2] = true;
        game.inventory.condition[2] = 123;
        game.inventory.ammo[2] = -42;
        game.inventory.energy = -300;
        game.inventory.warhead = -4;

        Campaign campaign = new Campaign();
        SaveGame save = SaveGame.empty();
        save.store(0, game, campaign, campaign.indexOf("LEVEL03B.MAP"), "TEST");

        assertEquals("TEST", save.name(0));
        assertFalse(save.isEmpty(0));
        assertEquals(3, save.complex(0));
        assertEquals(-300, save.energy(0));

        GameState fresh = new GameState(level());
        Campaign.Stage stage = save.restore(0, fresh, campaign);
        assertEquals("LEVEL03B.MAP", stage.mapName());
        assertEquals(-300, fresh.inventory.energy);
        assertEquals(-4, fresh.inventory.warhead);
        assertTrue(fresh.inventory.owned[2]);
        assertEquals(123, fresh.inventory.condition[2]);
        assertEquals(-42, fresh.inventory.ammo[2]);
    }

    /** Writing then reading through a real file must survive the trip, at the original size. */
    @Test
    void itWritesAFileTheOriginalSizeAndReadsItBack(@TempDir File tmp) throws Exception {
        GameState game = new GameState(level());
        game.inventory.energy = -222;
        Campaign campaign = new Campaign();

        SaveGame save = SaveGame.empty();
        save.store(2, game, campaign, 0, "SLOT THREE");
        File f = new File(tmp, "Savegame.DAT");
        save.save(f);

        assertEquals(SaveGame.FILE_SIZE, Files.size(f.toPath()));
        SaveGame back = SaveGame.load(f);
        assertEquals("SLOT THREE", back.name(2));
        assertEquals(-222, back.energy(2));
        assertTrue(back.isEmpty(0), "untouched slots should stay free");
        assertTrue(back.isEmpty(4));
    }

    /** A freshly made file must match the shipped one's defaults where both are untouched. */
    @Test
    void defaultsMatchTheShippedFile() throws Exception {
        Assumptions.assumeTrue(shippedSave().isFile(), "Savegame.DAT not present");
        SaveGame shipped = SaveGame.load(shippedSave());
        SaveGame fresh = SaveGame.empty();
        for (int i = 0; i < SaveGame.SLOT_COUNT; i++) {
            assertEquals(shipped.isEmpty(i), fresh.isEmpty(i), "slot " + i);
        }
        // the live block at the head carries the engine's starting energy
        assertEquals(-150, shipped.energy(-1), "the head block should start at -150 energy");
        assertEquals(-150, fresh.energy(-1));
    }
}
