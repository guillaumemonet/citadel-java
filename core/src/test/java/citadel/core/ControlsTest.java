package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Controls, checked against the compass rather than against themselves.
 *
 * The angle convention was already pinned down by facingAndForwardAgreeWithTheEngine, but that
 * says nothing about which way a control turns you, and the two are independent: the port shipped
 * once with a correct forward vector and every control mirrored.
 */
class ControlsTest {

    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;

    /** An empty grid, so nothing gets in the way of a measurement. */
    private static Level openLevel() {
        byte[] raw = new byte[MapFile.EVENTS_OFF + 4];
        raw[MapFile.EVENTS_OFF] = (byte) 0xff;
        raw[MapFile.EVENTS_OFF + 1] = (byte) 0xff;
        raw[MapFile.HEADER_OFF + 17] = 32;
        raw[MapFile.HEADER_OFF + 19] = 32;
        return new Level(new MapFile("open", raw));
    }

    /**
     * Turning right has to sweep the facing clockwise: north to east to south to west. The engine
     * numbers those 128, 256, 384 and 0, so a right turn from north must reach east.
     */
    @Test
    void turningRightSweepsClockwise() {
        GameState game = new GameState(openLevel());
        game.angle = 128;                                   // north
        for (int i = 0; i < 11; i++) {                      // 11 passes * 12 units = 132
            game.update(0, 0, 1, TICK);                     // turn = +1 is right
        }
        assertTrue(game.facingDir() == Level.EAST_DIR,
                "turning right from north should end up facing east, got "
                        + MapFile.DIR_NAMES[game.facingDir()] + " at angle " + game.angle);

        game.angle = 128;
        for (int i = 0; i < 11; i++) {
            game.update(0, 0, -1, TICK);
        }
        assertTrue(game.facingDir() == Level.WEST_DIR,
                "turning left from north should end up facing west, got "
                        + MapFile.DIR_NAMES[game.facingDir()]);
    }

    /** Strafing right must move the player towards where a right turn would have pointed. */
    @Test
    void strafingRightMovesRight() {
        GameState game = new GameState(openLevel());
        game.angle = 128;                                   // north, so right is east, +X
        int x0 = game.posX, y0 = game.posY;
        for (int i = 0; i < 3; i++) {
            game.update(0, 1, 0, TICK);
        }
        assertTrue(game.posX > x0,
                "strafing right while facing north should increase X, moved "
                        + (game.posX - x0) + "," + (game.posY - y0));

        game.posX = x0;
        game.posY = y0;
        for (int i = 0; i < 3; i++) {
            game.update(0, -1, 0, TICK);
        }
        assertTrue(game.posX < x0, "strafing left while facing north should decrease X");
    }

    /** Walking forward while facing north must increase Y. */
    @Test
    void walkingForwardGoesWhereYouLook() {
        GameState game = new GameState(openLevel());
        game.angle = 128;
        int y0 = game.posY;
        for (int i = 0; i < 3; i++) {
            game.update(1, 0, 0, TICK);
        }
        assertTrue(game.posY > y0, "facing north, forward should increase Y");
    }

    /**
     * The invariant the player already has, applied to enemies: over a long simulation no enemy
     * may end up on the far side of a face that was shut when it stepped.
     */
    @Test
    void enemiesNeverCrossAClosedFace() throws Exception {
        File dir = new File("../Data/GAME/MAPS").isDirectory()
                ? new File("../Data/GAME/MAPS") : new File("Data/GAME/MAPS");
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");

        for (String name : new String[]{"LEVEL01A.MAP", "LEVEL02C.MAP", "LEVEL05B.MAP"}) {
            File f = new File(dir, name);
            Assumptions.assumeTrue(f.isFile(), name + " not present");
            Level level = Level.load(f);
            GameState game = new GameState(level);

            int n = game.enemies().size();
            int[] lastX = new int[n];
            int[] lastY = new int[n];
            for (int i = 0; i < n; i++) {
                lastX[i] = game.enemies().get(i).cellX();
                lastY[i] = game.enemies().get(i).cellY();
            }

            for (int step = 0; step < 400; step++) {
                boolean[][] passable = new boolean[n][4];
                for (int i = 0; i < n; i++) {
                    for (int d = 0; d < 4; d++) {
                        passable[i][d] = level.isPassable(lastX[i], lastY[i], d);
                    }
                }
                game.update(0, 0, 0, TICK);

                for (int i = 0; i < n; i++) {
                    Enemy e = game.enemies().get(i);
                    int cx = e.cellX(), cy = e.cellY();
                    int dx = cx - lastX[i], dy = cy - lastY[i];
                    if (dx != 0 || dy != 0) {
                        assertTrue(Math.abs(dx) <= 1 && Math.abs(dy) <= 1,
                                name + ": enemy " + e.slot + " jumped from ("
                                        + lastX[i] + "," + lastY[i] + ") to (" + cx + "," + cy + ")");
                        if (dx != 0 && dy == 0) {
                            int crossed = dx > 0 ? Level.EAST_DIR : Level.WEST_DIR;
                            assertTrue(passable[i][crossed],
                                    name + ": enemy " + e.slot + " walked through the "
                                            + MapFile.DIR_NAMES[crossed] + " face of ("
                                            + lastX[i] + "," + lastY[i] + ")");
                        } else if (dy != 0 && dx == 0) {
                            int crossed = dy > 0 ? Level.NORTH_DIR : Level.SOUTH_DIR;
                            assertTrue(passable[i][crossed],
                                    name + ": enemy " + e.slot + " walked through the "
                                            + MapFile.DIR_NAMES[crossed] + " face of ("
                                            + lastX[i] + "," + lastY[i] + ")");
                        } else {
                            // A diagonal step is only legal if one of the two L routes is open.
                            int dirX = dx > 0 ? Level.EAST_DIR : Level.WEST_DIR;
                            int dirY = dy > 0 ? Level.NORTH_DIR : Level.SOUTH_DIR;
                            boolean viaX = passable[i][dirX]
                                    && level.isPassable(lastX[i] + dx, lastY[i], dirY);
                            boolean viaY = passable[i][dirY]
                                    && level.isPassable(lastX[i], lastY[i] + dy, dirX);
                            assertTrue(viaX || viaY,
                                    name + ": enemy " + e.slot + " cut a sealed corner from ("
                                            + lastX[i] + "," + lastY[i] + ") to ("
                                            + cx + "," + cy + ")");
                        }
                        lastX[i] = cx;
                        lastY[i] = cy;
                    }
                }
            }
        }
    }
}
