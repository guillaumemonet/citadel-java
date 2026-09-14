package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the level clock, running out of energy, and what the automap reveals. */
class CountersAndMapTest {

    private static Level load(String name) throws Exception {
        File dir = new File("../Data/GAME/MAPS").isDirectory()
                ? new File("../Data/GAME/MAPS") : new File("Data/GAME/MAPS");
        Assumptions.assumeTrue(dir.isDirectory(), "original Data folder not present");
        File f = new File(dir, name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    /** NewLev3 ticks sv_Time once every 50 frames, so the clock runs in real seconds. */
    @Test
    void theLevelClockCountsSeconds() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        assertEquals(0, game.levelSeconds());
        for (int i = 0; i < 300; i++) {
            game.update(0, 0, 0, 1f / 50f);   // 300 engine frames = 6 seconds
        }
        assertFalse(game.isPlayerDead(),
                "the player died during the test, energy " + game.inventory.energyReading());
        assertEquals(6, game.levelSeconds(), "the clock drifted");
    }

    @Test
    void runningOutOfEnergyKillsThePlayerAndStopsTheWorld() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        assertFalse(game.isPlayerDead());

        game.inventory.energy = -1;
        assertTrue(game.inventory.isAlive());
        game.inventory.hurt(5);
        game.update(0, 0, 0, 1f / 50f);

        assertTrue(game.isPlayerDead(), "zero energy should be fatal");
        int x = game.posX;
        for (int i = 0; i < 50; i++) {
            game.update(1, 0, 0, 1f / 50f);
        }
        assertEquals(x, game.posX, "a dead player should not be able to walk");
    }

    /** Test_Counters flags the counter red and beeps below 10. */
    @Test
    void lowEnergyIsFlaggedCritical() throws Exception {
        GameState game = new GameState(load("LEVEL01A.MAP"));
        assertFalse(game.isEnergyCritical(), "225 energy is not critical");
        game.inventory.energy = -9;
        assertTrue(game.isEnergyCritical());
        game.drainSounds();
        game.update(0, 0, 0, 1f / 50f);
        boolean beeped = false;
        for (GameState.SoundEvent e : game.drainSounds()) {
            beeped |= e.index == SoundTable.BEEP;
        }
        assertTrue(beeped, "the low-energy warning did not sound");
    }

    /**
     * UpdateMap reveals the cell underfoot plus every neighbour with no wall between, so standing
     * still already shows a little, and walking reveals more.
     */
    @Test
    void theAutomapRevealsWhereYouHaveBeen() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);

        assertFalse(game.isVisited(0, 0), "nothing should be revealed before the first update");
        game.update(0, 0, 0, 1f / 50f);
        assertTrue(game.isVisited(game.cellX(), game.cellY()), "the cell underfoot is revealed");

        // an open neighbour is revealed too; a walled one is not
        int cx = game.cellX(), cy = game.cellY();
        for (int d = 0; d < 4; d++) {
            int nx = cx + Level.DX[d], ny = cy + Level.DY[d];
            if (!Level.inside(nx, ny)) {
                continue;
            }
            if (!level.hasWall(cx, cy, d)) {
                assertTrue(game.isVisited(nx, ny),
                        "an open neighbour should be revealed: " + nx + "," + ny);
            }
        }

        int before = countVisited(game);
        for (int i = 0; i < 400; i++) {
            game.update(1, 0, 0.05f, 1f / 50f);
        }
        assertTrue(countVisited(game) > before, "walking around revealed nothing new");
    }

    private static int countVisited(GameState game) {
        int n = 0;
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (game.isVisited(x, y)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** br_BumpWall: a full stride into a wall costs energy; edging along it does not. */
    @Test
    void walkingIntoAWallAtSpeedCostsEnergy() throws Exception {
        Level level = load("LEVEL01A.MAP");
        GameState game = new GameState(level);

        // face a shut wall from the cell beside it
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
        int energy = game.inventory.energyReading();

        for (int i = 0; i < 30; i++) {
            game.update(1, 0, 0, 1f / 50f);
        }
        assertTrue(game.inventory.energyReading() < energy,
                "running into a wall should hurt");
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
