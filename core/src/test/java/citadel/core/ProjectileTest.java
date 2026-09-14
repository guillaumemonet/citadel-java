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
        File dir = new File("../Data/GAME/MAPS").isDirectory()
                ? new File("../Data/GAME/MAPS") : new File("Data/GAME/MAPS");
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

    /** The bullet weapons resolve at once; the other three put an object into the table. */
    @Test
    void onlyTheSlowWeaponsMakeObjects() throws Exception {
        for (int slot : new int[]{1, 2, 3}) {
            GameState game = new GameState(load("LEVEL01A.MAP"));
            arm(game, slot);
            game.fire();
            assertTrue(game.projectiles().isEmpty(),
                    Inventory.WEAPON_NAMES[slot] + " should be hitscan");
        }
        for (int slot : new int[]{4, 5, 6}) {
            GameState game = new GameState(load("LEVEL01A.MAP"));
            arm(game, slot);
            game.fire();
            assertEquals(1, game.projectiles().size(),
                    Inventory.WEAPON_NAMES[slot] + " should launch an object");
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

    /** ro_SetExplode replaces a rocket with an explosion mark plus eight fireballs. */
    @Test
    void aRocketDetonatesIntoFireballs() throws Exception {
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

        game.fire();
        boolean sawExplosion = false;
        for (int i = 0; i < 10 && !sawExplosion; i++) {
            game.update(0, 0, 0, TICK);
            for (Projectile p : game.projectiles()) {
                sawExplosion |= p.type == Projectile.EXPLOSION;
            }
        }
        assertTrue(sawExplosion, "the rocket did not detonate against the wall");

        int fireballs = 0;
        for (Projectile p : game.projectiles()) {
            if (p.type == Projectile.FIREBALL) {
                fireballs++;
            }
        }
        assertTrue(fireballs > 0, "the blast threw no fireballs");
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

    /** A shot must not detonate on the enemy that fired it. */
    @Test
    void anEnemyShotDoesNotHitItsOwnShooter() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        Enemy shooter = game.enemies().get(0);
        shooter.state = Enemy.WALKING;
        int energy = shooter.energy;

        // a fireball launched from the shooter's own position
        game.projectiles().add(new Projectile(
                Projectile.FIREBALL, shooter.x, shooter.y, shooter.angle, shooter.slot, false));
        for (int i = 0; i < 3; i++) {
            game.update(0, 0, 0, TICK);
        }
        assertEquals(energy, shooter.energy, "the enemy shot itself");
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
