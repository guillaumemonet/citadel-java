package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sv_DoAnims and the sprite picking that depends on it.
 *
 * Every expectation here is read straight off sv_AnimOffsets in Code/MAIN1/CytadelaHD_134.ss,
 * whose entries are tile offsets: dividing one by 65*64 gives the tile, and the offset tables put
 * sprite n of a group at a known tile, so an offset pins a sprite index.
 */
class AnimationTest {

    private static Level load(String name) throws Exception {
        File dir = new File("../Data/GAME/MAPS").isDirectory()
                ? new File("../Data/GAME/MAPS") : new File("Data/GAME/MAPS");
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");
        File f = new File(dir, name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    private static Enemy anEnemy() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        Assumptions.assumeTrue(!game.enemies().isEmpty(), "no enemies on this level");
        return game.enemies().get(0);
    }

    @Test
    @DisplayName("the animated wall alternates between sprites 23 and 24")
    void wallAlternates() {
        // dc.l 0,23*65*64,24*65*64 written to sv_WallOffsets+23*4
        assertEquals(23, GfxLayout.wallSprite(23, 0));
        assertEquals(24, GfxLayout.wallSprite(23, 1));
        assertEquals(23, GfxLayout.wallSprite(23, 2));
        // every other wall is still
        for (int s = 0; s < 27; s++) {
            if (s != GfxLayout.ANIMATED_WALL_SPRITE) {
                assertEquals(s, GfxLayout.wallSprite(s, 1), "wall " + s + " must not animate");
            }
        }
    }

    @Test
    @DisplayName("each animated column cycles through its own four sprites")
    void columnsCycle() {
        // the three sv_DAdo calls target sv_CollumnOffsets entries 4, 12 and 20
        for (int base : new int[]{4, 12, 20}) {
            assertTrue(GfxLayout.isAnimatedColumnSprite(base));
            for (int phase = 0; phase < 8; phase++) {
                int s = GfxLayout.columnSprite(base, phase);
                assertEquals(base + (phase & 3), s);
                assertTrue(s >= base && s < base + 4, "stays inside its group of four");
            }
        }
        assertFalse(GfxLayout.isAnimatedColumnSprite(0));
        assertEquals(7, GfxLayout.columnSprite(7, 3), "a still column ignores the phase");
    }

    @Test
    @DisplayName("the phase advances once per pass of the main loop, not per VBLANK")
    void phaseFollowsTheLoopClock() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        int before = game.animPhase();
        // one second at the default loop rate is DEFAULT_LOOP_HZ passes
        for (int i = 0; i < 50; i++) {
            game.update(0f, 0f, 0f, 1f / GameState.FRAMES_PER_SECOND);
        }
        int advanced = game.animPhase() - before;
        assertEquals(Math.round(GameState.DEFAULT_LOOP_HZ), advanced,
                "one pass of MAIN_LOOP is one call to sv_DoAnims");
    }

    @Test
    @DisplayName("view sectors follow sv_EDirSub: front and back are wider than the sides")
    void viewSectorsAreUneven() throws Exception {
        Enemy e = anEnemy();
        e.angle = 0;
        // dc.w 128,64+32,64,64,64,64+32, after a +64 bias, so sector 0 spans -64..+63
        assertEquals(0, e.viewDirection(0));
        assertEquals(0, e.viewDirection(63));
        assertEquals(0, e.viewDirection(512 - 63));
        assertEquals(1, e.viewDirection(512 - 64), "the boundary belongs to the next sector");
        // the six sectors between them cover the whole turn exactly once
        boolean[] seen = new boolean[6];
        for (int a = 0; a < 512; a++) {
            int d = e.viewDirection(a);
            assertTrue(d >= 0 && d < 6, "sector " + d + " at angle " + a);
            seen[d] = true;
        }
        for (int d = 0; d < 6; d++) {
            assertTrue(seen[d], "sector " + d + " is never reached");
        }
    }

    @Test
    @DisplayName("all enemies share one walk phase, as sv_WalkState is a single global")
    void walkPhaseIsShared() throws Exception {
        Enemy e = anEnemy();
        e.angle = 0;
        int a = e.frame(0, 0);
        int b = e.frame(0, 1);
        assertNotEquals(a, b, "sv_EnemyDirTab row 0 is 52,56,52,56, so phases 0 and 1 differ");
        assertEquals(a, e.frame(0, 4), "the phase is masked to two bits");
    }

    @Test
    @DisplayName("flags bit 0 set means the first species, which is the inverse of the index")
    void speciesBitIsInverted() throws Exception {
        // EnemyHitServe: btst.b #0,1(a4) / beq.s .me01 -> the SET bit takes sv_LevelData+4
        Level level = load("LEVEL01A.MAP");
        Assumptions.assumeTrue(!level.enemySpawns().isEmpty(), "no enemies on this level");
        for (Level.EnemySpawn s : level.enemySpawns()) {
            assertEquals((s.raw[1] & 1) ^ 1, s.species, "slot " + s.slot + " on " + level.name);
            assertTrue(s.species == 0 || s.species == 1);
        }
    }
}
