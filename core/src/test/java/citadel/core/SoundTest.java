package citadel.core;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks the rules queue the sound the engine would have played. */
class SoundTest {

    /** One pass of MAIN_LOOP, the clock movement and the enemies run on. */
    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;

    private static Level load(String name) throws Exception {
        File dir = new File("../Data/GAME/MAPS").isDirectory()
                ? new File("../Data/GAME/MAPS") : new File("Data/GAME/MAPS");
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");
        File f = new File(dir, name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    private static boolean played(List<GameState.SoundEvent> events, int index) {
        for (GameState.SoundEvent e : events) {
            if (e.index == index) {
                return true;
            }
        }
        return false;
    }

    /** The sample bank and the table must stay in step, whatever else changes. */
    @Test
    void theSoundTableCoversTheWholeSampleBank() {
        SoundTable.Sfx last = SoundTable.ALL[SoundTable.ALL.length - 1];
        assertEquals(25, SoundTable.ALL.length);
        assertEquals(102108, last.offset + last.length,
                "the table should end exactly at the size sv_SAMPLES is documented as");
        for (SoundTable.Sfx s : SoundTable.ALL) {
            assertTrue(s.sampleRate() > 2000 && s.sampleRate() < 32000,
                    s.name + " has an implausible rate: " + s.sampleRate());
        }
    }

    @Test
    void everyWeaponHasItsOwnFireSound() {
        boolean[] seen = new boolean[SoundTable.ALL.length];
        for (int slot = 1; slot <= 6; slot++) {
            int sound = SoundTable.fireSound(slot);
            assertTrue(sound >= 0, "weapon slot " + slot + " has no fire sound");
            assertTrue(!seen[sound], "two weapons share fire sound " + sound);
            seen[sound] = true;
        }
        assertEquals(-1, SoundTable.fireSound(Inventory.HAND), "the bare hand should be silent");
    }

    @Test
    void firingQueuesTheWeaponSound() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        game.inventory.owned[1] = true;
        game.inventory.condition[1] = 500;
        game.inventory.ammo[1] = -10;
        game.inventory.current = 1;
        game.drainSounds();

        assertTrue(game.fire());
        assertTrue(played(game.drainSounds(), SoundTable.FIRE_HANDGUN),
                "the handgun shot was silent");
    }

    @Test
    void anEmptyWeaponQueuesTheEmptyClickInstead() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        game.inventory.owned[1] = true;
        game.inventory.condition[1] = 500;
        game.inventory.ammo[1] = 0;
        game.inventory.current = 1;
        game.drainSounds();

        game.fire();
        List<GameState.SoundEvent> events = game.drainSounds();
        assertTrue(played(events, SoundTable.NO_AMMO));
        assertTrue(!played(events, SoundTable.FIRE_HANDGUN), "an empty weapon still went bang");
    }

    @Test
    void openingADoorQueuesADoorSoundAtTheDoor() throws Exception {
        Level level = load("LEVEL01A.MAP");
        int[] spot = null;
        outer:
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (level.isBlocked(x, y)) {
                    continue;
                }
                for (int d = 0; d < 4; d++) {
                    if (level.doorState(x, y, d) == Level.DoorState.CLOSED) {
                        spot = new int[]{x, y, d};
                        break outer;
                    }
                }
            }
        }
        Assumptions.assumeTrue(spot != null, "no shut door in this level");

        GameState game = new GameState(level);
        game.posX = spot[0] * Level.CELL_UNITS + 512;
        game.posY = spot[1] * Level.CELL_UNITS + 512;
        game.drainSounds();
        game.update(0, 0, 0, TICK);

        List<GameState.SoundEvent> events = game.drainSounds();
        boolean door = played(events, SoundTable.DOOR1) || played(events, SoundTable.DOOR2);
        assertTrue(door, "the door opened silently");
        for (GameState.SoundEvent e : events) {
            if (e.index == SoundTable.DOOR1 || e.index == SoundTable.DOOR2) {
                assertTrue(e.positional, "a door sound should come from the door, not the player");
                assertEquals(spot[0], e.worldX / Level.CELL_UNITS);
                assertEquals(spot[1], e.worldY / Level.CELL_UNITS);
            }
        }
    }

    @Test
    void pickingSomethingUpQueuesTheRightSound() throws Exception {
        Level level = load("LEVEL01A.MAP");
        int[] spot = null;
        int kind = 0;
        outer:
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                int v = level.item(x, y);
                if (v >= Inventory.FIRST_AMMO && v < Inventory.FIRST_CARD) {
                    spot = new int[]{x, y};
                    kind = SoundTable.TAKE_AMMO;
                    break outer;
                }
            }
        }
        Assumptions.assumeTrue(spot != null, "no ammunition lying around");

        GameState game = new GameState(level);
        game.posX = spot[0] * Level.CELL_UNITS + 512;
        game.posY = spot[1] * Level.CELL_UNITS + 512;
        game.drainSounds();
        game.update(0, 0, 0, TICK);

        assertTrue(played(game.drainSounds(), kind), "the pickup was silent");
    }
}
