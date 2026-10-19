package citadel.core;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * br_BumpWall, and the two tests at the top of it that decide whether a collision counts.
 *
 * The one that matters is the angle. Every caller hands the routine the direction of the move less
 * the angle that would take you straight into that face, plus 62, and it throws the hit away
 * unless the result lands inside 124. So walking along a wall is free and only walking into it
 * costs anything — without which the static, the insults and the energy drain never stop.
 */
class BumpTest {

    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;
    /** forwardY is sin(a), so this heads north; forwardX is -cos(a), so 256 heads east. */
    private static final int NORTH = 128;
    private static final int EAST = 256;

    /** The first shipped level that has a cell {@link #northWall} accepts; LEVEL01A has none. */
    private static Level level() throws Exception {
        File dir = TestData.maps();
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");
        File[] maps = dir.listFiles((d, n) -> n.toUpperCase().endsWith(".MAP"));
        Assumptions.assumeTrue(maps != null, "no level maps present");
        Arrays.sort(maps);
        for (File f : maps) {
            Level level = Level.load(f);
            if (northWall(level) != null) {
                return level;
            }
        }
        return null;
    }

    /**
     * A cell walled to the north and open east and west, so both moves can be tried in it, and
     * well clear of any enemy - Move_Enemy simulates everything within 24 cells and a shot landing
     * would look exactly like a bump.
     */
    private static int[] northWall(Level level) {
        for (int y = 1; y < Level.GRID - 1; y++) {
            for (int x = 1; x < Level.GRID - 1; x++) {
                if (level.hasWall(x, y, Level.NORTH_DIR)
                        && !level.hasWall(x, y, Level.EAST_DIR)
                        && !level.hasWall(x, y, Level.WEST_DIR)
                        && level.column(x, y) == 0
                        && level.item(x, y) == 0
                        && farFromEnemies(level, x, y)) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private static boolean farFromEnemies(Level level, int x, int y) {
        for (Level.EnemySpawn e : level.enemySpawns()) {
            if (Math.abs(e.cellX() - x) < 16 && Math.abs(e.cellY() - y) < 16) {
                return false;
            }
        }
        return true;
    }

    private static GameState atWall(Level level, int[] cell, int angle) {
        GameState game = new GameState(level);
        game.posX = cell[0] * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        // right up against the north face, so any northward step hits it
        game.posY = cell[1] * Level.CELL_UNITS + Level.CELL_UNITS - GameState.PLAYER_RADIUS - 1;
        game.angle = angle;
        return game;
    }

    @Test
    @DisplayName("walking into a wall costs energy and sets the static off")
    void walkingIntoAWallCounts() throws Exception {
        Level level = level();
        Assumptions.assumeTrue(level != null, "no cell walled only to the north on any level");
        int[] cell = northWall(level);

        GameState game = atWall(level, cell, NORTH);
        int before = game.inventory.energyReading();
        game.update(1f, 0f, 0f, TICK);

        assertTrue(game.inventory.energyReading() < before, "br_BumpWall takes a point");
        assertTrue(game.szumActive(), "and sets sv_SzumTime");
    }

    @Test
    @DisplayName("sliding along the same wall costs nothing at all")
    void slidingAlongItDoesNot() throws Exception {
        Level level = level();
        Assumptions.assumeTrue(level != null, "no cell walled only to the north on any level");
        int[] cell = northWall(level);

        GameState game = atWall(level, cell, EAST);
        int before = game.inventory.energyReading();
        // one pass only: any further and the player leaves the cell and meets other geometry
        game.update(1f, 0f, 0f, TICK);

        assertEquals(before, game.inventory.energyReading(),
                "a move 90 degrees off the face is outside the 124 unit window");
        assertFalse(game.szumActive(), "and must not set the static off either");
    }

    @Test
    @DisplayName("Z turns the burst off, as cc_z does, and the counter keeps running underneath")
    void theZKeyTurnsItOff() throws Exception {
        Level level = level();
        Assumptions.assumeTrue(level != null, "no cell walled only to the north on any level");
        int[] cell = northWall(level);

        GameState game = atWall(level, cell, NORTH);
        assertFalse(game.toggleSzum(), "eori #1,cc_RequestTab2 - first press turns it off");
        game.update(1f, 0f, 0f, TICK);
        assertFalse(game.szumActive(), "MAIN_LOOP skips sv_MAKE_SZUM while the flag is set");

        assertTrue(game.toggleSzum(), "and the second press turns it back on");
        GameState again = atWall(level, cell, NORTH);
        again.update(1f, 0f, 0f, TICK);
        assertTrue(again.szumActive());
    }
}
