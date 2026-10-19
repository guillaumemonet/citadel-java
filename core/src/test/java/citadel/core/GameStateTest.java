package citadel.core;

import java.io.File;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Exercises the level model and the movement rules against the shipped map data. */
class GameStateTest {

    /** One pass of MAIN_LOOP, the clock movement and the enemies run on. */
    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;

    private static File mapsDir() {
        File f = TestData.maps();
        return f.isDirectory() ? f : null;
    }

    private static Level load(String name) throws Exception {
        File dir = mapsDir();
        Assumptions.assumeTrue(dir != null, "original Data folder not present");
        File f = new File(dir, name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    @Test
    void spawnIsInsideTheLevelAndNotInAWall() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);

        assertEquals(level.spawnCellX, game.cellX());
        assertEquals(level.spawnCellY, game.cellY());
        assertFalse(level.isBlocked(game.cellX(), game.cellY()),
                "the player would start inside a blocked cell");
        assertFalse(game.isLevelComplete());
    }

    @Test
    void everyShippedLevelParses() throws Exception {
        File dir = mapsDir();
        Assumptions.assumeTrue(dir != null, "original Data folder not present");
        File[] files = dir.listFiles();
        assertNotNull(files);
        int n = 0;
        for (File f : files) {
            if (!f.isFile() || f.length() < MapFile.EVENTS_OFF) {
                continue;
            }
            Level level = Level.load(f);
            assertTrue(Level.inside(level.spawnCellX, level.spawnCellY),
                    f.getName() + ": spawn outside the grid");
            assertTrue(Level.inside(level.exitCellX, level.exitCellY),
                    f.getName() + ": exit outside the grid");
            for (Level.Teleport t : level.teleports()) {
                assertTrue(Level.inside(t.toX, t.toY),
                        f.getName() + ": teleport target outside the grid");
            }
            n++;
        }
        assertTrue(n >= 20, "expected the full set of levels, found " + n);
    }

    /**
     * The invariant that matters: however the player is pushed around, they never end up on the
     * far side of a face that was shut. Random walks explore far more corner cases than a
     * hand-written path, and the failure message names the exact cell and face.
     */
    @Test
    void randomWalkNeverCrossesAClosedFace() throws Exception {
        for (String name : new String[]{
                "LEVEL01A.MAP", "LEVEL02C.MAP", "LEVEL04C.MAP", "LEVEL05D.MAP", "LEVEL08A.MAP"}) {
            randomWalk(load(name));
        }
    }

    private void randomWalk(Level level) {
        GameState game = new GameState(level);
        Random rnd = new Random(20260914L);

        int prevX = game.cellX(), prevY = game.cellY();
        for (int i = 0; i < 20000; i++) {
            boolean[] passable = new boolean[4];
            for (int d = 0; d < 4; d++) {
                passable[d] = level.isPassable(prevX, prevY, d);
            }

            game.update(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1,
                    rnd.nextFloat() * 2 - 1, TICK);

            int cx = game.cellX(), cy = game.cellY();
            int dx = cx - prevX, dy = cy - prevY;
            if (dx == 0 && dy == 0) {
                continue;
            }
            if (steppedOnTeleport(level, prevX, prevY)) {
                prevX = cx;
                prevY = cy;
                continue;
            }
            if (Math.abs(dx) == 1 && Math.abs(dy) == 1) {
                // Cutting a corner is legal as long as one of the two L-shaped routes is open;
                // br2_UP and br2_DOWN are what stop it when both are walled off.
                assertTrue(cornerIsOpen(level, prevX, prevY, dx, dy, passable),
                        String.format("step %d: cut a sealed corner from (%d,%d) to (%d,%d)",
                                i, prevX, prevY, cx, cy));
            } else {
                assertEquals(1, Math.abs(dx) + Math.abs(dy),
                        String.format("step %d: jumped from (%d,%d) to (%d,%d)",
                                i, prevX, prevY, cx, cy));
                int dir = directionOf(dx, dy);
                assertTrue(passable[dir],
                        String.format("step %d: walked through the %s face of (%d,%d)",
                                i, MapFile.DIR_NAMES[dir], prevX, prevY));
            }
            prevX = cx;
            prevY = cy;
        }
    }

    /** True when at least one of the two L-shaped routes around a corner is walkable. */
    private static boolean cornerIsOpen(Level level, int x, int y, int dx, int dy,
                                        boolean[] passableFromStart) {
        int dirX = directionOf(dx, 0);
        int dirY = directionOf(0, dy);
        boolean viaX = passableFromStart[dirX]
                && level.isPassable(x + dx, y, dirY);
        boolean viaY = passableFromStart[dirY]
                && level.isPassable(x, y + dy, dirX);
        return viaX || viaY;
    }

    private static boolean steppedOnTeleport(Level level, int x, int y) {
        for (Level.Teleport t : level.teleports()) {
            if (t.fromX == x && t.fromY == y) {
                return true;
            }
        }
        return false;
    }

    private static int directionOf(int dx, int dy) {
        for (int d = 0; d < 4; d++) {
            if (Level.DX[d] == dx && Level.DY[d] == dy) {
                return d;
            }
        }
        return fail("not a cardinal step: " + dx + "," + dy);
    }

    /** br_Chk_OPEN: standing in a cell with a door on one of its faces opens that door. */
    @Test
    void steppingNextToADoorOpensIt() throws Exception {
        Level level = load("LEVEL01A.MAP");
        int[] spot = findDoorCell(level);
        Assumptions.assumeTrue(spot != null, "no unblocked door in this level");

        GameState game = new GameState(level);
        game.posX = spot[0] * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.posY = spot[1] * Level.CELL_UNITS + Level.CELL_UNITS / 2;

        assertFalse(level.isPassable(spot[0], spot[1], spot[2]),
                "the door should start shut");
        for (int i = 0; i < 10; i++) {
            game.update(0, 0, 0, TICK);
        }
        assertTrue(level.isPassable(spot[0], spot[1], spot[2]),
                "the door next to the player never opened");

        // and it shuts again once the player is elsewhere
        game.posX = 1 * Level.CELL_UNITS + 512;
        game.posY = 1 * Level.CELL_UNITS + 512;
        for (int i = 0; i < 20; i++) {
            game.update(0, 0, 0, TICK);
        }
        assertFalse(level.isPassable(spot[0], spot[1], spot[2]),
                "the door stayed open after the player left");
    }

    private static int[] findDoorCell(Level level) {
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (level.isBlocked(x, y)) {
                    continue;
                }
                for (int d = 0; d < 4; d++) {
                    if (level.doorState(x, y, d) == Level.DoorState.CLOSED) {
                        return new int[]{x, y, d};
                    }
                }
            }
        }
        return null;
    }

    /** Pressing a wired switch runs its commands; here, one that opens a door somewhere else. */
    @Test
    void usingASwitchRunsItsCommands() throws Exception {
        Level level = load("LEVEL01A.MAP");
        Level.Trigger opener = null;
        for (Level.Trigger t : level.triggers()) {
            if (t.isCardReader() || t.commands.isEmpty()) {
                continue;
            }
            for (Level.Command c : t.commands) {
                if (c.code == Level.Command.OPEN_DOOR && !c.onRelease) {
                    opener = t;
                    break;
                }
            }
            if (opener != null) {
                break;
            }
        }
        Assumptions.assumeTrue(opener != null, "no door-opening switch in this level");

        Level.Command target = null;
        for (Level.Command c : opener.commands) {
            if (c.code == Level.Command.OPEN_DOOR && !c.onRelease) {
                target = c;
                break;
            }
        }
        assertNotNull(target);
        assertFalse(level.isPassable(target.cellX, target.cellY, target.dir),
                "the remote door should start shut");

        GameState game = new GameState(level);
        game.posX = opener.cellX * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.posY = opener.cellY * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.angle = angleFacing(opener.dir);
        assertEquals(opener.dir, game.facingDir(), "the player is not facing the switch");

        assertTrue(game.use(), "the switch did not fire");
        assertTrue(level.isPassable(target.cellX, target.cellY, target.dir),
                "the remote door did not open");
    }

    private static int angleFacing(int dir) {
        switch (dir) {
            case Level.WEST_DIR:  return 0;
            case Level.NORTH_DIR: return 128;
            case Level.EAST_DIR:  return 256;
            default:              return 384;
        }
    }

    /**
     * Pins down the angle convention, which is easy to get backwards and expensive when you do:
     * mirroring east and west sends every "use" at the wall behind the player.
     *
     * The cardinal values come from rm_HandUsed, whose own comment reads "W for 0 degrees"; the
     * loop then checks the movement vector agrees with the face the player is deemed to face.
     */
    @Test
    void facingAndForwardAgreeWithTheEngine() {
        GameState game = new GameState(dummyLevel());
        for (int dir : new int[]{
                Level.WEST_DIR, Level.NORTH_DIR, Level.EAST_DIR, Level.SOUTH_DIR}) {
            game.angle = angleFacing(dir);
            assertEquals(dir, game.facingDir(),
                    "angle " + game.angle + " should face " + MapFile.DIR_NAMES[dir]);
        }

        for (int a = 0; a < 512; a += 2) {
            game.angle = a;
            int dir = game.facingDir();
            double dot = GameState.forwardX(a) * Level.DX[dir]
                    + GameState.forwardY(a) * Level.DY[dir];
            assertTrue(dot >= 0.7,
                    "at angle " + a + " the player faces " + MapFile.DIR_NAMES[dir]
                            + " but moves the other way (dot " + dot + ")");
        }
    }

    private static Level dummyLevel() {
        byte[] raw = new byte[MapFile.EVENTS_OFF + 4];
        raw[MapFile.EVENTS_OFF] = (byte) 0xff;
        raw[MapFile.EVENTS_OFF + 1] = (byte) 0xff;
        return new Level(new MapFile("dummy", raw));
    }

    /** Reaching the cell the header names as the exit ends the level. */
    @Test
    void reachingTheExitCompletesTheLevel() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);
        game.posX = level.exitCellX * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.posY = level.exitCellY * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.update(0, 0, 0, TICK);
        assertTrue(game.isLevelComplete());
    }
}
