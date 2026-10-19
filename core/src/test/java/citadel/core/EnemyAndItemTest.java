package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers item pickup, line of sight and the enemy movement rules. */
class EnemyAndItemTest {

    /** One pass of MAIN_LOOP, the clock movement and the enemies run on. */
    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;

    private static Level load(String name) throws Exception {
        File dir = TestData.maps();
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");
        File f = new File(dir, name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    @Test
    void standingOnAnItemCollectsItAndClearsTheCell() throws Exception {
        Level level = load("LEVEL01A.MAP");
        int[] spot = findItem(level, Inventory.MEDIKIT);
        Assumptions.assumeTrue(spot != null, "no medikit in this level");

        GameState game = new GameState(level);
        int before = game.inventory.energyReading();
        game.posX = spot[0] * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.posY = spot[1] * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.update(0, 0, 0, TICK);

        assertEquals(0, level.item(spot[0], spot[1]), "the item was not removed from the map");
        assertEquals(before + 30, game.inventory.energyReading(), "first aid did not heal");
        assertTrue(game.consumeGeometryDirty(), "the renderer was not told to drop the sprite");
    }

    @Test
    void aWeaponPickupArmsThePlayer() throws Exception {
        Level level = load("LEVEL01A.MAP");
        int[] spot = null;
        for (int v = Inventory.FIRST_WEAPON; v < Inventory.FIRST_AMMO && spot == null; v++) {
            spot = findItem(level, v);
        }
        Assumptions.assumeTrue(spot != null, "no weapon lying around in this level");

        GameState game = new GameState(level);
        assertEquals(Inventory.HAND, game.inventory.current);
        game.posX = spot[0] * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.posY = spot[1] * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.update(0, 0, 0, TICK);

        assertTrue(game.inventory.current != Inventory.HAND, "the weapon was not selected");
        assertTrue(game.inventory.owned[game.inventory.current]);
    }

    /** An item further than 220 units from the cell centre is out of reach. */
    @Test
    void anItemIsNotCollectedFromTheEdgeOfItsCell() throws Exception {
        Level level = load("LEVEL01A.MAP");
        int[] spot = findAnyItem(level);
        Assumptions.assumeTrue(spot != null, "no item in this level");

        GameState game = new GameState(level);
        game.posX = spot[0] * Level.CELL_UNITS + 512 - 300;
        game.posY = spot[1] * Level.CELL_UNITS + 512;
        game.update(0, 0, 0, TICK);
        assertTrue(level.item(spot[0], spot[1]) != 0, "the item was collected from too far away");
    }

    @Test
    void lineOfSightIsBlockedByAWall() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);

        // a cell and its neighbour across a shut face
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
        int ax = wall[0] * Level.CELL_UNITS + 512;
        int ay = wall[1] * Level.CELL_UNITS + 512;
        int bx = (wall[0] + Level.DX[wall[2]]) * Level.CELL_UNITS + 512;
        int by = (wall[1] + Level.DY[wall[2]]) * Level.CELL_UNITS + 512;

        assertFalse(game.canShoot(ax, ay, bx, by), "shot straight through a wall");
        assertTrue(game.canShoot(ax, ay, ax, ay), "cannot see its own cell");
    }

    /**
     * The same invariant as for the player, applied to every enemy: simulate a while and check
     * none of them has walked into a wall or onto a column.
     */
    @Test
    void enemiesStayInsideTheLevel() throws Exception {
        for (String name : new String[]{"LEVEL01A.MAP", "LEVEL03C.MAP", "LEVEL05B.MAP"}) {
            Level level = load(name);
            GameState game = new GameState(level);
            for (int i = 0; i < 600; i++) {
                game.update(0, 0, 0, TICK);
            }
            for (Enemy e : game.enemies()) {
                if (e.isDead()) {
                    continue;
                }
                int cx = e.cellX(), cy = e.cellY();
                assertTrue(Level.inside(cx, cy),
                        name + ": enemy " + e.slot + " left the grid");
                int inX = e.x % Level.CELL_UNITS;
                int inY = e.y % Level.CELL_UNITS;
                final int r = Enemy.RADIUS;
                final int far = Level.CELL_UNITS - r;
                if (inX < r) {
                    assertTrue(level.isPassable(cx, cy, Level.WEST_DIR),
                            name + ": enemy " + e.slot + " is inside the west wall of ("
                                    + cx + "," + cy + ")");
                }
                if (inX > far) {
                    assertTrue(level.isPassable(cx, cy, Level.EAST_DIR),
                            name + ": enemy " + e.slot + " is inside the east wall of ("
                                    + cx + "," + cy + ")");
                }
                if (inY < r) {
                    assertTrue(level.isPassable(cx, cy, Level.SOUTH_DIR),
                            name + ": enemy " + e.slot + " is inside the south wall of ("
                                    + cx + "," + cy + ")");
                }
                if (inY > far) {
                    assertTrue(level.isPassable(cx, cy, Level.NORTH_DIR),
                            name + ": enemy " + e.slot + " is inside the north wall of ("
                                    + cx + "," + cy + ")");
                }
            }
        }
    }

    /** Enemies should actually be doing something: over 600 frames, some of them move. */
    @Test
    void enemiesNearThePlayerAreSimulated() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        int[] startX = new int[game.enemies().size()];
        int[] startY = new int[game.enemies().size()];
        for (int i = 0; i < startX.length; i++) {
            startX[i] = game.enemies().get(i).x;
            startY[i] = game.enemies().get(i).y;
        }
        for (int i = 0; i < 600; i++) {
            game.update(0, 0, 0, TICK);
        }
        int moved = 0;
        for (int i = 0; i < startX.length; i++) {
            Enemy e = game.enemies().get(i);
            if (e.x != startX[i] || e.y != startY[i]) {
                moved++;
            }
        }
        assertTrue(moved > 0, "no enemy moved at all in 600 frames");
    }

    private static int[] findItem(Level level, int value) {
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (level.item(x, y) == value) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private static int[] findAnyItem(Level level) {
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (level.item(x, y) != 0) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }
}
