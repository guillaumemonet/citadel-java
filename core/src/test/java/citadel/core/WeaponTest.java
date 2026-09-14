package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers firing: ammunition, wear, line of fire and the per-species vulnerability mask. */
class WeaponTest {

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

    /** Arms the player with a full weapon, bypassing having to find one on the map. */
    private static void arm(GameState game, int slot) {
        game.inventory.owned[slot] = true;
        game.inventory.condition[slot] = 500;
        game.inventory.ammo[slot] = -50;
        game.inventory.current = slot;
    }

    @Test
    void theBareHandFallsThroughToTheUseAction() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        assertEquals(Inventory.HAND, game.inventory.current);
        // nothing to use where the player spawns, so it reports a miss rather than firing
        assertFalse(game.fire());
        assertTrue(game.drainLog().toString().contains("nothing to use"));
    }

    @Test
    void firingSpendsAmmunitionAndWearsTheWeapon() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        arm(game, 1);
        int ammo = game.inventory.ammo[1];
        int condition = game.inventory.condition[1];

        for (int i = 0; i < 20; i++) {
            assertTrue(game.fire(), "shot " + i + " refused");
        }
        assertEquals(ammo + 20, game.inventory.ammo[1], "ammunition did not go down");
        assertTrue(game.inventory.condition[1] <= condition, "the weapon did not wear");
    }

    @Test
    void anEmptyWeaponRefusesToFire() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        arm(game, 1);
        game.inventory.ammo[1] = 0;
        assertFalse(game.fire());
        assertTrue(game.drainLog().toString().contains("no ammunition"));
    }

    @Test
    void aBrokenWeaponRefusesToFire() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        arm(game, 1);
        game.inventory.condition[1] = 0;
        assertFalse(game.fire());
        assertTrue(game.drainLog().toString().contains("broken"));
    }

    /** A shot must not travel through a wall to reach an enemy standing behind it. */
    @Test
    void shotsDoNotPassThroughWalls() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        arm(game, 1);

        // find a cell with a shut face, put the player in it and an enemy just beyond
        int[] wall = null;
        outer:
        for (int y = 1; y < Level.GRID - 1; y++) {
            for (int x = 1; x < Level.GRID - 1; x++) {
                for (int d = 0; d < 4; d++) {
                    if (level.hasWall(x, y, d) && !level.isPassable(x, y, d)) {
                        wall = new int[]{x, y, d};
                        break outer;
                    }
                }
            }
        }
        assertNotNull(wall);
        int dir = wall[2];
        game.posX = wall[0] * Level.CELL_UNITS + 512;
        game.posY = wall[1] * Level.CELL_UNITS + 512;
        game.angle = angleFacing(dir);
        assertEquals(dir, game.facingDir());

        Enemy victim = game.enemies().get(0);
        victim.x = (wall[0] + Level.DX[dir]) * Level.CELL_UNITS + 512;
        victim.y = (wall[1] + Level.DY[dir]) * Level.CELL_UNITS + 512;
        victim.state = Enemy.WALKING;
        int energy = victim.energy;

        game.fire();
        assertEquals(energy, victim.energy, "the shot went through a wall");
    }

    @Test
    void aShotDownAClearLineHurtsTheEnemy() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        arm(game, 1);

        Enemy victim = game.enemies().get(0);
        victim.state = Enemy.WALKING;
        // stand the enemy one step in front of the player, in the same cell
        game.posX = victim.x - (int) Math.round(GameState.forwardX(game.angle) * 400);
        game.posY = victim.y - (int) Math.round(GameState.forwardY(game.angle) * 400);
        int energy = victim.energy;

        assertTrue(game.fire());
        assertTrue(victim.energy < energy, "a point blank shot did nothing");
    }

    /** A species whose vulnerability mask excludes a damage type shrugs it off. */
    @Test
    void anInvulnerableSpeciesTakesNoDamage() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        Enemy victim = game.enemies().get(0);
        Assumptions.assumeTrue(
                level.enemyTemplates[victim.species].vulnerableTo(GameState.SHOT_BULLET),
                "this species is already immune to bullets, nothing to compare");

        // the bolter delivers electricity; check the mask actually gates it
        boolean shocks = level.enemyTemplates[victim.species]
                .vulnerableTo(GameState.SHOT_ELECTRIC);
        arm(game, 5);
        victim.state = Enemy.WALKING;
        game.posX = victim.x - (int) Math.round(GameState.forwardX(game.angle) * 400);
        game.posY = victim.y - (int) Math.round(GameState.forwardY(game.angle) * 400);
        int energy = victim.energy;
        game.fire();
        // the bolter fires a travelling object now, so let it reach the target
        for (int i = 0; i < 5; i++) {
            game.update(0, 0, 0, TICK);
        }

        if (shocks) {
            assertTrue(victim.energy < energy, "the bolt never landed");
        } else {
            assertEquals(energy, victim.energy, "damage landed despite the vulnerability mask");
        }
    }

    @Test
    void enoughShotsKillAnEnemy() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        arm(game, 1);
        game.inventory.ammo[1] = -999;

        Enemy victim = game.enemies().get(0);
        Assumptions.assumeTrue(
                level.enemyTemplates[victim.species].vulnerableTo(GameState.SHOT_BULLET),
                "this species is immune to bullets");
        victim.state = Enemy.WALKING;
        game.posX = victim.x - (int) Math.round(GameState.forwardX(game.angle) * 400);
        game.posY = victim.y - (int) Math.round(GameState.forwardY(game.angle) * 400);

        for (int i = 0; i < 200 && victim.isAlive(); i++) {
            game.fire();
        }
        assertFalse(victim.isAlive(), "the enemy survived 200 point blank shots");
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
