package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CheckCodes.
 *
 * The decode is worth asserting because it is two layers deep: the stored bytes are negated, and
 * what they are compared against is the raw byte the Amiga keyboard hands over — the key code
 * rotated left one and inverted — not ASCII.
 */
class CheatsTest {

    /** {@code stored = -( ~ROL(rawkey, 1) )}, which is what cco_check undoes with neg.b. */
    private static int encode(int rawkey) {
        int rol = ((rawkey << 1) | (rawkey >> 7)) & 0xff;
        return (~rol) & 0xff;
    }

    @Test
    @DisplayName("the stored bytes of AmmoCode really do spell HOTKISS")
    void theObfuscationUndoes() {
        // Amiga raw key codes
        int[] hotkiss = {0x25, 0x18, 0x14, 0x27, 0x17, 0x21, 0x21};
        // dc.b -$b5,-$cf,-$d7,-$b1,-$d1,-$bd,-$bd,0
        int[] stored = {0xb5, 0xcf, 0xd7, 0xb1, 0xd1, 0xbd, 0xbd};
        assertEquals(stored.length, hotkiss.length);
        for (int i = 0; i < stored.length; i++) {
            assertEquals(stored[i], encode(hotkiss[i]), "byte " + i);
        }
        assertEquals("HOTKISS", Cheats.Cheat.ARSENAL.code);
    }

    @Test
    @DisplayName("a code is recognised wherever it sits in the last sixteen keys")
    void codesAreFoundInTheRing() {
        Cheats c = new Cheats();
        for (char ch : "xyz".toCharArray()) {
            assertNull(c.type(ch));
        }
        Cheats.Cheat got = null;
        for (char ch : "idaho".toCharArray()) {
            got = c.type(ch);
        }
        assertSame(Cheats.Cheat.HOP, got, "lower case counts, the engine sees key codes");
    }

    @Test
    @DisplayName("matching clears the ring, so a code does not fire twice")
    void matchingClearsTheRing() {
        Cheats c = new Cheats();
        for (char ch : "lorien".toCharArray()) {
            c.type(ch);
        }
        // cco_Found zeroes all sixteen bytes; one more letter cannot complete it again
        assertNull(c.type('n'));
    }

    @Test
    @DisplayName("every cheat does what its branch of CheckCodes does")
    void theEffectsLand() throws Exception {
        assertNotNull(run(Cheats.Cheat.ENERGY));
        assertEquals(666, run(Cheats.Cheat.ENERGY).inventory.energyReading());

        GameState armed = run(Cheats.Cheat.ARSENAL);
        for (int i = 0; i < Inventory.WEAPON_COUNT; i++) {
            assertTrue(armed.inventory.owned[i], "weapon " + i);
            assertEquals(666, -armed.inventory.ammo[i], "ammo " + i);
        }
        for (int colour = 0; colour < 3; colour++) {
            assertTrue(armed.inventory.hasCard(colour), "card " + colour);
        }

        assertTrue(run(Cheats.Cheat.EXIT).isLevelComplete());
        assertEquals(-12, run(Cheats.Cheat.BOMB).inventory.warhead);
        assertTrue(run(Cheats.Cheat.IMMORTAL).isImmortal());
        assertTrue(run(Cheats.Cheat.MAP).isVisited(0, 0));

        GameState hopped = load();
        int before = hopped.posX + hopped.posY;
        type(hopped, Cheats.Cheat.HOP.code);
        assertTrue(hopped.posX + hopped.posY != before, "IDAHO did not move the player");
    }

    @Test
    @DisplayName("HOTKIWI stops energy draining")
    void immortalityHolds() throws Exception {
        GameState game = load();
        int start = game.inventory.energyReading();
        game.update(0f, 0f, 0f, 0.1f);
        game.inventory.hurt(50);
        game.update(0f, 0f, 0f, 0.1f);
        assertTrue(game.inventory.energyReading() < start, "the hit should have landed");

        GameState safe = run(Cheats.Cheat.IMMORTAL);
        safe.update(0f, 0f, 0f, 0.1f);
        int held = safe.inventory.energyReading();
        safe.inventory.hurt(50);
        safe.update(0f, 0f, 0f, 0.1f);
        assertEquals(held, safe.inventory.energyReading(), "sv_NieUmieraj puts the reading back");
    }

    private static GameState load() throws Exception {
        File dir = TestData.maps();
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");
        File f = new File(dir, "LEVEL01A.MAP");
        Assumptions.assumeTrue(f.isFile(), "LEVEL01A.MAP not present");
        return new GameState(Level.load(f));
    }

    private static GameState run(Cheats.Cheat cheat) throws Exception {
        GameState game = load();
        type(game, cheat.code);
        return game;
    }

    private static void type(GameState game, String code) {
        for (char ch : code.toCharArray()) {
            game.typeLetter(ch);
        }
    }
}
