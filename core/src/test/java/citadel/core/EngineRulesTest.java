package citadel.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules checked against the assembly after the port was audited: what a level starts with,
 * when an enemy dies and what it sounds like, how a weapon wears out, the order of message
 * variants, and Extra Half-Brite.
 */
class EngineRulesTest {

    private static Level load(String name) throws Exception {
        File f = new File(TestData.maps(), name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    @Test
    @DisplayName("a new game starts at 150 energy, and machinegun ammunition comes in tens")
    void startingValues() {
        Inventory inv = new Inventory();
        assertEquals(150, inv.energyReading());
        inv.take(Inventory.FIRST_AMMO + 2);
        assertEquals(10, -inv.ammo[3]);
    }

    @Test
    @DisplayName("a level starts with no cards and at most 500 energy")
    void levelStart() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        game.inventory.cards[0] = -2;
        game.inventory.cards[2] = -1;
        game.inventory.energy = -900;
        game.beginLevel();
        assertEquals(0, game.inventory.cards[0]);
        assertEquals(0, game.inventory.cards[2]);
        assertEquals(500, game.inventory.energyReading());
    }

    @Test
    @DisplayName("easy: low energy is topped up, every fourth enemy slot is emptied, aggression is 4/5")
    void easyLevelStart() throws Exception {
        Level level = load("LEVEL01A.MAP");
        int aggression = level.enemyTemplates[0].aggression;
        GameState game = new GameState(level);
        int before = game.enemies().size();
        long quarter = game.enemies().stream().filter(e -> e.slot % 4 == 0).count();
        game.inventory.energy = -10;
        game.setEasy(true);
        game.beginLevel();
        int e = game.inventory.energyReading();
        assertTrue(e >= 90 && e <= 152 && e % 2 == 0, "energy " + e);
        assertEquals(before - quarter, game.enemies().size());
        assertTrue(game.enemies().stream().noneMatch(en -> en.slot % 4 == 0));
        assertEquals(aggression * 4 / 5, level.enemyTemplates[0].aggression);
    }

    @Test
    @DisplayName("an enemy left at exactly 0 energy is still standing; it dies below 0")
    void enemyDiesBelowZero() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        Assumptions.assumeFalse(game.enemies().isEmpty());
        Enemy e = game.enemies().get(0);
        e.energy = 10;
        game.drainSounds();
        game.damageEnemy(e, 10);
        assertEquals(Enemy.HIT, e.state);
        for (GameState.SoundEvent s : game.drainSounds()) {
            assertTrue(s.index >= 17 && s.index <= 19, "hit grunt " + s.index);
        }
        game.damageEnemy(e, 1);
        assertEquals(Enemy.KILLED, e.state);
        List<GameState.SoundEvent> death = game.drainSounds();
        assertEquals(1, death.size(), "one death scream");
        assertTrue(death.get(0).index >= 16 && death.get(0).index <= 19);
        assertNotEquals(SoundTable.CARD, death.get(0).index);
    }

    @Test
    @DisplayName("DamageWeapon wears first: a weapon that wears down to 0 does not fire")
    void weaponWear() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        game.inventory.owned[1] = true;
        game.inventory.ammo[1] = -500;
        game.inventory.current = 1;
        boolean refused = false;
        for (int i = 0; i < 200 && !refused; i++) {
            game.inventory.condition[1] = 1;
            int ammo = game.inventory.ammo[1];
            if (game.fire()) {
                assertEquals(1, game.inventory.condition[1]);
                assertEquals(ammo + 1, game.inventory.ammo[1]);
            } else {
                refused = true;
                assertEquals(0, game.inventory.condition[1]);
                assertEquals(ammo, game.inventory.ammo[1], "a refused shot spent ammunition");
            }
        }
        assertTrue(refused, "a weapon at 1 never wore out");
        assertFalse(game.inventory.select(2), "a weapon not picked up cannot be selected");
    }

    @Test
    @DisplayName("message variants come in turn, not at random")
    void variantsCycle() throws Exception {
        TextTable t = TextTable.load("@^^^\nFIRST\nSECOND\nTHIRD\n@ONLY\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("FIRST", t.get(0));
        assertEquals("SECOND", t.get(0));
        assertEquals("THIRD", t.get(0));
        assertEquals("FIRST", t.get(0));
        assertEquals("ONLY", t.get(1));
    }

    @Test
    @DisplayName("Extra Half-Brite: colour 32 + n is colour n at half brightness")
    void extraHalfBrite() throws Exception {
        // one row of 16 pixels in six interleaved planes, then 32 colours
        byte[] raw = new byte[2 * 6 + 128];
        raw[0] = (byte) 0x80;          // pixel 0, plane 0
        raw[5 * 2] = (byte) 0x80;      // pixel 0, plane 5: index 33
        raw[12 + 2] = 0x0f;            // colour 1 = $fff
        raw[12 + 3] = (byte) 0xff;
        RawScreen s = RawScreen.load("ehb", raw, 16, 6, 128);
        assertEquals(33, s.indices()[0]);
        assertEquals(0xff777777, s.ehbPixels()[0]);
        assertEquals(0xffffffff, s.ehbPalette()[1]);
    }
}
