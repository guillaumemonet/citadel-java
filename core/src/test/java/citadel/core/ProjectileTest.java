package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers objects in flight: which weapons make them, how far they go, and what they do on impact. */
class ProjectileTest {

    /** One pass of MAIN_LOOP, the clock movement and the enemies run on. */
    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;

    private static Level load(String name) throws Exception {
        File dir = TestData.maps();
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");
        File f = new File(dir, name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    private static void arm(GameState game, int slot) {
        game.inventory.owned[slot] = true;
        game.inventory.condition[slot] = 500;
        game.inventory.ammo[slot] = -99;
        game.inventory.current = slot;
    }

    /** Every projectile type ends up with the same reach, which is how the speeds were pinned. */
    @Test
    void everyProjectileTypeHasTheSameRange() {
        for (int type : new int[]{
                Projectile.FIREBALL, Projectile.ELECTRIC, Projectile.ROCKET}) {
            int range = Projectile.speedOf(type) * Projectile.lifetimeOf(type);
            assertEquals(12000, range,
                    "type " + type + " should reach 12 cells, got " + range);
        }
    }

    /**
     * The bullet weapons resolve at once and leave at most impact marks; the other three put an
     * object into the table, unless it lands within its first collision call.
     */
    @Test
    void onlyTheSlowWeaponsMakeObjects() throws Exception {
        for (int slot : new int[]{1, 2, 3}) {
            GameState game = new GameState(load("LEVEL01A.MAP"));
            arm(game, slot);
            game.fire();
            for (Projectile p : game.projectiles()) {
                assertTrue(p.isEffect(), Inventory.WEAPON_NAMES[slot] + " left " + p.type
                        + " in flight");
            }
        }
        for (int slot : new int[]{4, 5, 6}) {
            GameState game = new GameState(new SyntheticMap().level());
            game.posX = SyntheticMap.at(10);
            game.posY = SyntheticMap.at(10);
            arm(game, slot);
            game.fire();
            assertEquals(1, game.projectiles().size(),
                    Inventory.WEAPON_NAMES[slot] + " should launch an object");
            assertTrue(game.projectiles().get(0).inFlight());
        }
    }

    @Test
    void aProjectileExpiresAfterItsLifetime() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        arm(game, 4);   // flamer, 30 frames
        game.fire();
        assertFalse(game.projectiles().isEmpty());
        for (int i = 0; i < 200; i++) {
            game.update(0, 0, 0, TICK);
        }
        assertTrue(game.projectiles().isEmpty(), "an object outlived its counter");
    }

    /**
     * ro_SetExplode replaces a rocket with an explosion mark and resolves its fireball probes on
     * the spot: nothing is left flying, and a point-blank rocket costs the player 5 energy.
     */
    @Test
    void aRocketDetonatesAtOnce() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        arm(game, 6);   // launcher

        // stand facing a shut wall so the rocket has something to hit
        int[] spot = null;
        outer:
        for (int y = 1; y < Level.GRID - 1; y++) {
            for (int x = 1; x < Level.GRID - 1; x++) {
                for (int d = 0; d < 4; d++) {
                    if (level.hasWall(x, y, d) && !level.isPassable(x, y, d)) {
                        spot = new int[]{x, y, d};
                        break outer;
                    }
                }
            }
        }
        Assumptions.assumeTrue(spot != null, "no shut wall found");
        game.posX = spot[0] * Level.CELL_UNITS + 512;
        game.posY = spot[1] * Level.CELL_UNITS + 512;
        game.angle = angleFacing(spot[2]);
        int before = game.inventory.energyReading();

        game.fire();
        boolean sawExplosion = false;
        for (Projectile p : game.projectiles()) {
            sawExplosion |= p.type == Projectile.EXPLOSION;
            assertFalse(p.inFlight(), "the blast left something flying");
        }
        assertTrue(sawExplosion, "the rocket did not detonate against the wall");
        assertTrue(game.projectiles().size() <= 13, "one mark and at most twelve probes");
        assertEquals(before - 5, game.inventory.energyReading(), "point blank, and only that");
    }

    /** An object must not pass through a wall, however fast it travels. */
    @Test
    void projectilesDoNotPassThroughWalls() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        arm(game, 5);   // bolter, the fastest

        int[] spot = null;
        outer:
        for (int y = 1; y < Level.GRID - 1; y++) {
            for (int x = 1; x < Level.GRID - 1; x++) {
                for (int d = 0; d < 4; d++) {
                    if (level.hasWall(x, y, d) && !level.isPassable(x, y, d)) {
                        spot = new int[]{x, y, d};
                        break outer;
                    }
                }
            }
        }
        Assumptions.assumeTrue(spot != null, "no shut wall found");
        int cx = spot[0], cy = spot[1], dir = spot[2];
        game.posX = cx * Level.CELL_UNITS + 512;
        game.posY = cy * Level.CELL_UNITS + 512;
        game.angle = angleFacing(dir);

        game.fire();
        int beyondX = cx + Level.DX[dir];
        int beyondY = cy + Level.DY[dir];
        for (int i = 0; i < 30; i++) {
            game.update(0, 0, 0, TICK);
            for (Projectile p : game.projectiles()) {
                assertFalse(p.cellX() == beyondX && p.cellY() == beyondY,
                        "an object crossed a shut wall into (" + beyondX + "," + beyondY + ")");
            }
        }
    }

    /**
     * es_PrepStruct starts an enemy's shot four or six vectors ahead, 300 units, which is what
     * keeps it off its own shooter: Object_Collision has no notion of who fired what.
     */
    @Test
    void anEnemyShotDoesNotHitItsOwnShooter() throws Exception {
        for (int weapon = 1; weapon <= 6; weapon++) {
            int sx = SyntheticMap.at(20), sy = SyntheticMap.at(20);
            GameState game = new GameState(new SyntheticMap().species(0, 0xf, weapon, 0, 0)
                    .enemy(1, 1, 100, sx, sy, 256, 0).level());
            game.posX = SyntheticMap.at(10);
            game.posY = SyntheticMap.at(10);
            Enemy shooter = game.enemies().get(0);
            shooter.state = Enemy.SHOOTING;
            shooter.counter = 64;
            for (int i = 0; i < 3; i++) {
                game.update(0, 0, 0, TICK);
            }
            assertEquals(100, shooter.energy, "weapon " + weapon + ": the enemy shot itself");
        }
    }

    private static int angleFacing(int dir) {
        switch (dir) {
            case Level.WEST_DIR:  return 0;
            case Level.NORTH_DIR: return 128;
            case Level.EAST_DIR:  return 256;
            default:              return 384;
        }
    }
}
