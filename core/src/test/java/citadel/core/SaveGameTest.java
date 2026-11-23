package citadel.core;

import java.io.File;
import java.nio.file.Files;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the save format against the file the original game ships, and SERVER's use of it. */
class SaveGameTest {

    private static File shippedSave() {
        return new File(TestData.root(), "GAME/Store/Savegame.DAT");
    }

    private static Level level() throws Exception {
        File f = new File(TestData.root(), "GAME/MAPS/LEVEL01A.MAP");
        Assumptions.assumeTrue(f.isFile(), "LEVEL01A not present");
        return Level.load(f);
    }

    @Test
    @DisplayName("the shipped file has five free slots and an occupied head block")
    void theShippedSaveFileReadsAsFiveEmptySlots() throws Exception {
        Assumptions.assumeTrue(shippedSave().isFile(), "Savegame.DAT not present");
        SaveGame save = SaveGame.load(shippedSave());
        for (int i = 0; i < SaveGame.SLOT_COUNT; i++) {
            assertTrue(save.isEmpty(i), "slot " + i + " should be free, named " + save.name(i));
            assertEquals("WOLNE", save.name(i));
        }
        assertTrue(save.head().isOccupied(), "+88 is 1 in the head, so a saved STRUCTURE is too");
        assertEquals(1, save.head().complex());
        assertEquals(0, save.head().level());
        assertEquals(768, shippedSave().length(), "the file should be 6 blocks of 128");
    }

    @Test
    @DisplayName("a fresh file is the shipped one, byte for byte")
    void defaultsMatchTheShippedFile() throws Exception {
        Assumptions.assumeTrue(shippedSave().isFile(), "Savegame.DAT not present");
        assertArrayEquals(Io.readAll(shippedSave()), SaveGame.empty().bytes());
    }

    @Test
    @DisplayName("STRUCTURE carries the inventory between the engine and the server")
    void theStructureCarriesTheInventory() throws Exception {
        GameState game = new GameState(level());
        game.inventory.owned[2] = true;
        game.inventory.condition[2] = 123;
        game.inventory.ammo[2] = -42;
        game.inventory.energy = -300;
        game.inventory.warhead = -4;

        Structure s = Structure.fresh();
        s.fromGame(game);
        assertEquals(-300, s.energy());
        assertEquals(-4, s.glowica());

        GameState fresh = new GameState(level());
        s.toGame(fresh);
        assertEquals(-300, fresh.inventory.energy);
        assertEquals(-4, fresh.inventory.warhead);
        assertTrue(fresh.inventory.owned[2]);
        assertFalse(fresh.inventory.owned[3]);
        assertEquals(123, fresh.inventory.condition[2]);
        assertEquals(-42, fresh.inventory.ammo[2]);
    }

    @Test
    @DisplayName("ENDLEVEL_PART's save: STRUCTURE, its name, +88, and the pristine head")
    void endLevelSave(@TempDir File tmp) throws Exception {
        SaveGame save = SaveGame.empty();
        Structure pristine = save.head();

        Structure s = Structure.fresh();
        s.setComplex(3);
        s.setLevel(2);
        s.put(Structure.OFF_DIFFICULTY, 1);
        s.put(Structure.OFF_OCCUPIED, 0);
        String name = SaveGame.slotName(s.complex(), s.level());
        assertEquals("STORES 2", name);
        save.store(2, s, name, pristine);

        File f = new File(tmp, "Savegame.DAT");
        save.save(f);
        assertEquals(SaveGame.FILE_SIZE, Files.size(f.toPath()));

        SaveGame back = SaveGame.load(f);
        assertTrue(back.isOccupied(2));
        assertEquals("STORES 2", back.name(2));
        assertTrue(back.slot(2).easy(), "the difficulty travels with the slot");
        assertEquals(3, back.slot(2).complex());
        assertTrue(back.isEmpty(0), "untouched slots stay free");
        assertArrayEquals(pristine.bytes(), back.head().bytes());

        assertEquals("DUNGEONS  1", SaveGame.slotName(1, 1));
        assertEquals("POWER PLANT 3", SaveGame.slotName(2, 3));
    }

    @Test
    @DisplayName("a slot saved at the end of a complex loads into MAPA, any other into the next level")
    void loadingResumesAfterTheLevelSaved() {
        Structure mid = Structure.fresh();
        mid.setComplex(3);
        mid.setLevel(1);
        Campaign.Step step = Campaign.levelLoop(mid);
        assertSame(Campaign.Next.PLAY, step.next());
        assertEquals("LEVEL03B.MAP", step.mapName());
        assertEquals(2, mid.level());

        Structure end = Structure.fresh();
        end.setComplex(3);
        end.setLevel(3);
        assertSame(Campaign.Next.MAP, Campaign.levelLoop(end).next());

        Structure first = Structure.fresh();
        first.setComplex(1);
        first.setLevel(2);
        assertSame(Campaign.Next.MAP, Campaign.levelLoop(first).next(),
                "the 2 after complex 1 is the protection check, then MAPA");

        Structure last = Structure.fresh();
        last.setComplex(8);
        Campaign.Step centre = Campaign.levelLoop(last);
        assertEquals("LEVEL08A.MAP", centre.mapName());
        assertEquals('K', last.bytes()[Structure.OFF_LAST_LEVEL], "the last level is marked KANE");
        assertSame(Campaign.Next.END, Campaign.levelLoop(last).next());

        Structure start = Structure.fresh();
        assertEquals("LEVEL01A.MAP", Campaign.levelLoop(start).mapName(), "a new game");
    }

    @Test
    @DisplayName("MAPA marks the complex left behind and starts the chosen one at level 0")
    void mapaUsesTheStructure() {
        Structure s = Structure.fresh();
        s.setComplex(1);
        s.setLevel(2);
        WorldMap map = new WorldMap();
        map.enter(s);
        assertTrue(s.isDone(1));
        assertEquals(1, s.taken());
        assertEquals(WorldMap.CURSOR_AFTER[0], map.choice());

        map.setChoice(2);   // POWER PLANT
        map.choose(s);
        assertEquals(2, s.complex());
        assertEquals(0, s.level());

        WorldMap other = new WorldMap();
        other.markDone(5);
        other.load(Structure.fresh());
        assertFalse(other.isDone(5), "loading forgets what the map held before");
        assertEquals(0, other.complexesTaken());
    }
}
