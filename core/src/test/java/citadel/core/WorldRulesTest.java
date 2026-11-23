package citadel.core;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement and world rules pinned to the values the engine computes: sv_DoMMove,
 * sv_Border, OpenCloseDoors with the priority doors, UpdateMap, the pause and the map.
 * Every map is built byte by byte, so the numbers are exact rather than whatever a shipped
 * level happens to give.
 */
class WorldRulesTest {

    /** One pass of MAIN_LOOP per update at the default pace. */
    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;
    private static final int NORTH = 128;

    private static void at(GameState game, int cx, int cy, int inX, int inY, int angle) {
        game.posX = cx * Level.CELL_UNITS + inX;
        game.posY = cy * Level.CELL_UNITS + inY;
        game.angle = angle;
    }

    private static int inX(GameState game) {
        return game.posX & 1023;
    }

    private static int inY(GameState game) {
        return game.posY & 1023;
    }

    private static void still(GameState game, int passes) {
        for (int i = 0; i < passes; i++) {
            game.update(0, 0, 0, TICK);
        }
    }

    private static int sounds(GameState game, int index) {
        int n = 0;
        for (GameState.SoundEvent e : game.drainSounds()) {
            if (e.index == index) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------- moving

    @Test
    @DisplayName("sv_rotate floors: 250 north is 249 units, and a diagonal 176 on each axis")
    void stepsAreFloored() {
        GameState game = new GameState(new SyntheticMap().level());
        at(game, 10, 10, 512, 200, NORTH);
        game.update(1, 0, 0, TICK);
        assertEquals(10 * 1024 + 512, game.posX);
        assertEquals(200 + 249, inY(game), "(250 * 32767 * 2) >> 16");

        at(game, 20, 20, 300, 300, NORTH);
        game.update(1, 1, 0, TICK);   // forward-right: d3 = 5, 64 off the view
        assertEquals(300 + 176, inX(game));
        assertEquals(300 + 176, inY(game), "a diagonal is as long as a straight line");
    }

    @Test
    @DisplayName("br_BumpWall: a rebound of 190, 130, 70 and 10 off the wall, one bump in six passes")
    void bumpRebound() {
        Level level = new SyntheticMap().wall(10, 10, Level.NORTH_DIR, 2).level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 717, NORTH);
        int energy = game.inventory.energyReading();

        game.update(1, 0, 0, TICK);
        assertEquals(717, inY(game), "clamped to 1024 - 307");
        assertEquals(energy - 1, game.inventory.energyReading());
        int[] expect = {717 - 190, 717 - 320, 717 - 390, 717 - 400};
        for (int y : expect) {
            game.update(1, 0, 0, TICK);
            assertEquals(y, inY(game), "the rebound replaces the player's own step");
            assertEquals(512, inX(game));
        }
        assertEquals(energy - 1, game.inventory.energyReading(), "no bump while thrown back");
        game.update(1, 0, 0, TICK);
        assertTrue(inY(game) < 717, "the sixth pass walks back but does not reach the wall");
        game.update(1, 0, 0, TICK);
        assertEquals(energy - 2, game.inventory.energyReading(), "the seventh bumps again");

        // pushing north for 32 passes costs a handful of points, not 32
        GameState again = new GameState(new SyntheticMap().wall(10, 10, Level.NORTH_DIR, 2).level());
        at(again, 10, 10, 512, 717, NORTH);
        int start = again.inventory.energyReading();
        for (int i = 0; i < 32; i++) {
            again.update(1, 0, 0, TICK);
        }
        int lost = start - again.inventory.energyReading();
        // a bump every six passes at full speed; EXCITE slows him, and a shorter stride gives a
        // shorter rebound, so they come a little closer together as he tires
        assertTrue(lost >= 5 && lost <= 8, "lost " + lost);
    }

    @Test
    @DisplayName("LEVEL01A (14,5): the corner gives back the 23 units of Y, not the 107 of X")
    void cornerOnAShippedLevel() throws Exception {
        java.io.File f = new java.io.File(TestData.maps(), "LEVEL01A.MAP");
        org.junit.jupiter.api.Assumptions.assumeTrue(f.isFile(), "LEVEL01A.MAP not present");
        Level level = Level.load(f);
        GameState game = new GameState(level);
        at(game, 14, 5, 200, 740 - 249, NORTH);
        game.update(1, 0, 0, TICK);
        assertEquals(14, game.cellX());
        assertEquals(5, game.cellY());
        assertEquals(200, inX(game));
        assertEquals(717, inY(game));
    }

    @Test
    @DisplayName("sv_AddMove replaces the next step and keeps its sign: a westward shove goes west")
    void addMoveKeepsItsSign() {
        GameState game = new GameState(new SyntheticMap().level());
        int bx = SyntheticMap.at(30), by = SyntheticMap.at(30);
        game.posX = bx - 600;
        game.posY = by;
        game.angle = 0;
        game.detonate(bx, by, 0, 0);   // westward probes reach him, four times their vector each
        int x = game.posX;
        game.update(0, 1, 0, TICK);    // strafing: on his own he would not move west at all
        int dx = game.posX - x;
        assertTrue(dx < 0 && dx >= -255, "the shove replaced the step, westwards: " + dx);
        int x2 = game.posX;
        game.update(0, 0, 0, TICK);
        assertEquals(x2, game.posX, "spent in one pass");
    }

    @Test
    @DisplayName("vodka: a drunk player drifts, his view swings back, and it wears off a pass at a time")
    void vodka() {
        GameState game = new GameState(new SyntheticMap().level());
        at(game, 30, 30, 512, 512, NORTH);
        game.inventory.vodka = 10;
        boolean drifted = false;
        boolean turned = false;
        for (int i = 0; i < 10; i++) {
            int a = game.angle, x = game.posX, y = game.posY;
            game.update(0, 0, 0, TICK);
            int da = ((game.angle - a + 256) & 511) - 256;
            assertTrue(da >= -8 && da <= 8, "asr #3 of -64..63, got " + da);
            assertEquals(0, game.angle & 1, "andi #$1fe");
            assertTrue(Math.abs(game.posX - x) <= 64 + 64 && Math.abs(game.posY - y) <= 64 + 64);
            drifted |= game.posX != x || game.posY != y;
            turned |= da != 0;
        }
        assertEquals(0, game.inventory.vodka);
        assertTrue(drifted, "standing still, the jitter alone moves him");
        assertTrue(turned);
        int a = game.angle, x = game.posX, y = game.posY;
        game.update(0, 0, 0, TICK);
        assertEquals(a, game.angle, "sober again");
        assertEquals(x, game.posX);
        assertEquals(y, game.posY);
    }

    // ------------------------------------------------------------ sv_Border

    @Test
    @DisplayName("br2_COLUMN: an enemy within 300 on both axes puts the player back on sv_LastPos")
    void enemyBlocksThePlayer() {
        int ex = SyntheticMap.at(10), ey = SyntheticMap.at(10);
        GameState game = new GameState(new SyntheticMap().enemy(1, 1, 100, ex, ey, 0, 0).level());
        at(game, 10, 10, 512, 512 - 362, NORTH);
        int y = game.posY;
        int energy = game.inventory.energyReading();
        game.update(1, 0, 0, TICK);
        assertEquals(y, game.posY, "249 north would leave him 113 off; the move is undone");
        assertEquals(energy, game.inventory.energyReading());

        game.enemies().get(0).state = Enemy.BURNING;
        game.drainLog();
        game.drainSounds();
        game.update(1, 0, 0, TICK);
        assertEquals(y, game.posY);
        assertEquals(energy - 1, game.inventory.energyReading(), "a burning one costs a point");
        assertEquals(1, sounds(game, SoundTable.PLAYER_HURT), "SOUND 13");
    }

    @Test
    @DisplayName("br2_UP: a corner pushes back along the axis that went in less")
    void cornerPushesTheSmallerPenetration() {
        // the corner north-west of (5,5) is closed by the north face of (4,5)
        Level level = new SyntheticMap().wall(4, 5, Level.NORTH_DIR, 2).level();
        GameState game = new GameState(level);
        at(game, 5, 5, 200, 740 - 249, NORTH);
        game.update(1, 0, 0, TICK);
        assertEquals(200, inX(game), "X went in 107, Y only 23");
        assertEquals(717, inY(game));

        // an open door closes a corner just the same (tst.b)
        Level open = new SyntheticMap().wall(4, 5, Level.NORTH_DIR, 32).level();
        GameState g2 = new GameState(open);
        at(g2, 5, 5, 290, 800 - 249, NORTH);
        g2.update(1, 0, 0, TICK);
        assertEquals(307, inX(g2), "Y went in 83, X 17: X goes back");
        assertEquals(800, inY(g2));

        // equal: X
        GameState g3 = new GameState(level);
        at(g3, 5, 5, 257, 767 - 249, NORTH);
        g3.update(1, 0, 0, TICK);
        assertEquals(307, inX(g3), "50 and 50: bpl takes X");
        assertEquals(767, inY(g3));
    }

    @Test
    @DisplayName(".br2_COLUMN2: column boxes are 304..719, both axes pushed, the barrel's open to the west")
    void columnBoxes() {
        Level level = new SyntheticMap().column(10, 10, 1).column(20, 20, 18).level();
        GameState game = new GameState(level);
        at(game, 10, 10, 250, 250, 192);   // facing north-east
        still(game, 1);
        game.update(1, 0, 0, TICK);        // 176 on each axis, into the box
        assertEquals(302, inX(game), "br2_c1 then br2_c2: X and Y both go back");
        assertEquals(302, inY(game));

        // the same approach to a column, from the south-west corner's west side
        GameState col = new GameState(level);
        at(col, 10, 10, 100, 200, NORTH);
        still(col, 1);
        col.update(1, 0, 0, TICK);
        assertEquals(449, inY(col), "west of 304 is outside a column's box");

        GameState barrel = new GameState(level);
        at(barrel, 20, 20, 100, 200, NORTH);
        still(barrel, 1);
        barrel.update(1, 0, 0, TICK);
        assertEquals(302, inY(barrel), "but inside the barrel's, which has no west edge");
        assertEquals(302, inX(barrel), "and coming from west of 303 he is put at 302, east of it");
    }

    // --------------------------------------------------------------- doors

    private static boolean passable(Level level) {
        return level.isPassable(10, 10, Level.NORTH_DIR);
    }

    @Test
    @DisplayName("OpenCloseDoors: door 1 passes on the sixth pass and shuts in six")
    void doorOneTiming() {
        Level level = new SyntheticMap().wall(10, 10, Level.NORTH_DIR, 30).level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        for (int i = 1; i <= 5; i++) {
            still(game, 1);
            assertFalse(passable(level), "pass " + i);
            assertEquals(34, level.wallValue(10, 10, Level.NORTH_DIR));
            assertEquals(34, level.wallValue(10, 11, Level.SOUTH_DIR), "and its far side");
        }
        assertEquals(1, sounds(game, SoundTable.DOOR1), "the sound when it starts to open");
        still(game, 1);
        assertTrue(passable(level), "pass 6");
        assertEquals(32, level.wallValue(10, 10, Level.NORTH_DIR));
        still(game, 3);
        assertEquals(0, sounds(game, SoundTable.DOOR1), "open is open: no sound");

        at(game, 30, 30, 512, 512, 0);
        for (int i = 1; i <= 5; i++) {
            still(game, 1);
            assertEquals(34, level.wallValue(10, 10, Level.NORTH_DIR), "closing, pass " + i);
        }
        assertEquals(1, sounds(game, SoundTable.DOOR1), "and again when it starts to close");
        still(game, 1);
        assertEquals(30, level.wallValue(10, 10, Level.NORTH_DIR), "shut on pass 6");
        assertFalse(game.doorSlots()[0].isActive());
    }

    @Test
    @DisplayName("OpenCloseDoors: door 2 passes on the seventh pass and shuts in seven")
    void doorTwoTiming() {
        Level level = new SyntheticMap().wall(10, 10, Level.NORTH_DIR, 36).level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        still(game, 6);
        assertEquals(40, level.wallValue(10, 10, Level.NORTH_DIR));
        still(game, 1);
        assertEquals(38, level.wallValue(10, 10, Level.NORTH_DIR), "pass 7");
        assertEquals(1, sounds(game, SoundTable.DOOR2));

        at(game, 30, 30, 512, 512, 0);
        still(game, 6);
        assertEquals(40, level.wallValue(10, 10, Level.NORTH_DIR));
        still(game, 1);
        assertEquals(36, level.wallValue(10, 10, Level.NORTH_DIR), "shut on pass 7");
    }

    @Test
    @DisplayName("door 1 plays SOUND2 16 on a level whose header word 26 is set")
    void doorSoundSixteen() {
        Level level = new SyntheticMap().header(26, 1).wall(10, 10, Level.NORTH_DIR, 30).level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        still(game, 1);
        List<GameState.SoundEvent> heard = game.drainSounds();
        assertEquals(1, heard.size());
        assertEquals(SoundTable.DOOR3, heard.get(0).index);
    }

    @Test
    @DisplayName("br_ChkDoorStatus: a door caught moving keeps its counter")
    void reenteringKeepsTheCounter() {
        Level level = new SyntheticMap().wall(10, 10, Level.NORTH_DIR, 30).level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        still(game, 3);                       // 6 -> 5 -> 4, 3, 2
        assertEquals(2, game.doorSlots()[0].counter());
        at(game, 30, 30, 512, 512, 0);
        still(game, 1);                       // closing: 3
        assertEquals(3, game.doorSlots()[0].counter());
        at(game, 10, 10, 512, 512, 0);
        still(game, 3);                       // 2, 1, 0
        assertFalse(passable(level));
        still(game, 1);
        assertTrue(passable(level), "four passes from where it was, not six from shut");
    }

    @Test
    @DisplayName("rm_FixDoor queues a switch's door; ServePriorDoor serves it; the switch waits")
    void priorityDoors() {
        int door = SyntheticMap.offset(20, 20, Level.NORTH_DIR);
        Level level = new SyntheticMap()
                .wall(10, 10, Level.WEST_DIR, 44)
                .wall(20, 20, Level.NORTH_DIR, 30)
                .trigger(10, 10, Level.WEST_DIR, Level.Command.OPEN_DOOR, door,
                        -2, Level.Command.CLOSE_DOOR, door)
                .level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);   // facing west, at the switch
        game.drainSounds();
        assertTrue(game.use());
        assertEquals(30, level.wallValue(20, 20, Level.NORTH_DIR), "queued, not set");
        assertFalse(game.use(), "SCROLL 54: a priority door is queued");
        assertEquals(46, level.wallValue(10, 10, Level.WEST_DIR) & 62, "and the switch stays in");

        still(game, 1);                   // ServePriorDoor hands it to channel 1
        assertTrue(game.doorSlots()[0].isPriority());
        assertEquals(30, level.wallValue(20, 20, Level.NORTH_DIR));
        still(game, 5);
        assertEquals(34, level.wallValue(20, 20, Level.NORTH_DIR));
        assertEquals(1, sounds(game, SoundTable.DOOR1), "the sound flag set to 50 makes it heard");
        assertFalse(game.use(), "still refused while it moves");
        still(game, 1);
        assertEquals(32, level.wallValue(20, 20, Level.NORTH_DIR), "open on the seventh pass");
        assertFalse(game.doorSlots()[0].isActive(), "and the channel given back at once");

        assertTrue(game.use(), "free again: the switch comes out and runs the second list");
        still(game, 7);
        assertEquals(30, level.wallValue(20, 20, Level.NORTH_DIR));
    }

    @Test
    @DisplayName("a priority door waits while the player keeps an automatic door of its type open")
    void priorityDoorWaitsForTheChannel() {
        int door = SyntheticMap.offset(20, 20, Level.NORTH_DIR);
        Level level = new SyntheticMap()
                .wall(10, 10, Level.WEST_DIR, 44)
                .wall(10, 10, Level.SOUTH_DIR, 30)
                .wall(20, 20, Level.NORTH_DIR, 30)
                .trigger(10, 10, Level.WEST_DIR, Level.Command.OPEN_DOOR, door)
                .level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        still(game, 8);
        assertTrue(level.isPassable(10, 10, Level.SOUTH_DIR), "the automatic door is open");
        assertTrue(game.use());
        still(game, 20);
        assertEquals(30, level.wallValue(20, 20, Level.NORTH_DIR),
                "channel 1 stays in use as long as he stands by its door");

        at(game, 40, 40, 512, 512, 0);
        still(game, 6);                   // the automatic door shuts, and the channel is free
        assertEquals(30, level.wallValue(10, 10, Level.SOUTH_DIR));
        // ServePriorDoor ran in the pass that freed the channel, so six more open it
        still(game, 5);
        assertFalse(level.isPassable(20, 20, Level.NORTH_DIR));
        still(game, 1);
        assertTrue(level.isPassable(20, 20, Level.NORTH_DIR));
    }

    @Test
    @DisplayName("a priority door suspends automatic doors of its type")
    void priorityDoorSuspendsTheAutomaticOnes() {
        int door = SyntheticMap.offset(20, 20, Level.NORTH_DIR);
        Level level = new SyntheticMap()
                .wall(10, 10, Level.WEST_DIR, 44)
                .wall(12, 10, Level.EAST_DIR, 30)
                .wall(20, 20, Level.NORTH_DIR, 30)
                .trigger(10, 10, Level.WEST_DIR, Level.Command.OPEN_DOOR, door)
                .level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        assertTrue(game.use());
        still(game, 1);                   // served
        assertTrue(game.doorSlots()[0].isPriority());
        at(game, 12, 10, 512, 512, 0);    // next to a door of the same type
        still(game, 5);
        assertEquals(30, level.wallValue(12, 10, Level.EAST_DIR),
                "br_Chk_OPEN is skipped while the channel serves a priority door");
        still(game, 1);
        assertEquals(32, level.wallValue(20, 20, Level.NORTH_DIR));
        assertFalse(game.doorSlots()[0].isActive());
        still(game, 6);
        assertEquals(32, level.wallValue(12, 10, Level.EAST_DIR), "then it opens as usual");
    }

    @Test
    @DisplayName("the renderer is told how far open every door face is")
    void openFractions() {
        Level level = new SyntheticMap().wall(10, 10, Level.NORTH_DIR, 30).level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        assertEquals(0f, game.doorOpenFraction(10, 10, Level.NORTH_DIR));
        float last = 0f;
        for (int i = 0; i < 5; i++) {
            still(game, 1);
            float f = game.doorOpenFraction(10, 11, Level.SOUTH_DIR);
            assertTrue(f >= last && f < 1f);
            last = f;
        }
        still(game, 1);
        assertEquals(1f, game.doorOpenFraction(10, 10, Level.NORTH_DIR));
    }

    // ------------------------------------------------------------- the map

    @Test
    @DisplayName("UpdateMap reveals up to 3 x 3: each open side, and that neighbour's own sides")
    void automapThreeByThree() {
        Level level = new SyntheticMap()
                .wall(10, 10, Level.NORTH_DIR, 2)
                .wall(11, 10, Level.EAST_DIR, 32)    // an open door still hides what is behind
                .level();
        GameState game = new GameState(level);
        at(game, 10, 10, 512, 512, 0);
        still(game, 1);
        assertTrue(game.isVisited(10, 10));
        assertFalse(game.isVisited(10, 11), "behind the north wall");
        assertTrue(game.isVisited(11, 11), "seen through the east neighbour");
        assertTrue(game.isVisited(9, 11), "and through the west one");
        assertTrue(game.isVisited(9, 9));
        assertTrue(game.isVisited(11, 9));
        assertTrue(game.isVisited(10, 9));
        assertFalse(game.isVisited(12, 10), "the reveal stops at one square, and at the door");
        assertFalse(game.isVisited(10, 8));
    }

    // ------------------------------------------------------- pause and map

    @Test
    @DisplayName("P: SCROLL 37 and 38, the loop held, SCROLL 41 every 750 VBLANKs, the clock running")
    void pause() {
        GameState game = new GameState(new SyntheticMap().level());
        at(game, 10, 10, 512, 512, NORTH);
        game.drainLog();
        assertTrue(game.togglePause());
        assertEquals(1, game.drainLog().size(), "SCROLL 37");
        int y = game.posY;
        int seconds = game.levelSeconds();
        game.heart.excite();
        int beat = game.heart.beat();
        for (int i = 0; i < 16 * 16; i++) {      // 16 seconds
            game.update(1, 0, 0, TICK);
        }
        assertEquals(y, game.posY, "MAIN_LOOP is held");
        assertTrue(game.levelSeconds() >= seconds + 15, "NewLev3 still keeps time");
        assertEquals(beat, game.heart.beat(), "but skips Draw_Heart");
        assertEquals(1, game.drainLog().size(), "one SCROLL 41 in 16 seconds");
        assertFalse(game.togglePause());
        game.update(1, 0, 0, TICK);
        assertNotEquals(y, game.posY);
    }

    @Test
    @DisplayName("M: the map holds the game, prints the location, and swallows P")
    void map() {
        GameState game = new GameState(new SyntheticMap().level());
        at(game, 7, 42, 512, 512, NORTH);
        game.drainLog();
        assertTrue(game.toggleMap());
        assertTrue(game.isHalted());
        List<String> said = game.drainLog();
        assertEquals(1, said.size());
        assertTrue(said.get(0).endsWith("07-42"), said.get(0));
        assertTrue(game.togglePause(), "eori #1,sv_Pause: the map itself holds sv_Pause");
        assertTrue(game.drainLog().isEmpty(), "cc_IsMap swallows P");
        int y = game.posY;
        game.update(1, 0, 0, TICK);
        assertEquals(y, game.posY);
        assertFalse(game.toggleMap());
        assertFalse(game.isHalted());
        game.update(1, 0, 0, TICK);
        assertNotEquals(y, game.posY);
    }

    // --------------------------------------------------------------- small

    @Test
    @DisplayName("sv_WalkState gains 6 a pass: the walk frames go 0, 3, 2, 1")
    void walkState() {
        GameState game = new GameState(new SyntheticMap().level());
        int[] seen = new int[4];
        for (int i = 0; i < 4; i++) {
            seen[i] = game.walkPhase();
            still(game, 1);
        }
        assertEquals(0, seen[0]);
        assertEquals(3, seen[1]);
        assertEquals(2, seen[2]);
        assertEquals(1, seen[3]);
    }

    @Test
    @DisplayName("ti_power: two potions in a row leave the base speed at 300")
    void twoPotions() {
        Inventory inv = new Inventory();
        inv.take(Inventory.POWER);
        for (int i = 0; i < 300; i++) {
            inv.tickSpeed();
        }
        assertEquals(250, inv.walkSpeed(), "one potion wears off");
        inv.take(Inventory.POWER);
        inv.tickSpeed();
        inv.take(Inventory.POWER);
        for (int i = 0; i < 400; i++) {
            inv.tickSpeed();
        }
        assertFalse(inv.isSpeedBoosted());
        assertEquals(300, inv.walkSpeed(), "the second saved the first's 300");
        inv.resetLevelState();
        assertEquals(250, inv.walkSpeed(), "until the next level");
    }

    @Test
    @DisplayName("TEST_COUNTERS while immortal puts back even the energy cheat")
    void energyCheatWhileImmortal() {
        GameState game = new GameState(new SyntheticMap().level());
        still(game, 1);
        int before = game.inventory.energy;
        for (char c : Cheats.Cheat.IMMORTAL.code.toCharArray()) {
            game.typeLetter(c);
        }
        assertTrue(game.isImmortal());
        for (char c : Cheats.Cheat.ENERGY.code.toCharArray()) {
            game.typeLetter(c);
        }
        assertEquals(-666, game.inventory.energy, "CheckCodes writes sv_Energy");
        still(game, 1);
        assertEquals(before, game.inventory.energy, "and TEST_COUNTERS writes it back");
    }

    @Test
    @DisplayName("do_pikaj: the first beep waits out the countdown")
    void firstBeepWaits() {
        GameState game = new GameState(new SyntheticMap().level());
        game.inventory.energy = -9;
        game.drainSounds();
        game.update(0, 0, 0, TICK);    // TEST_COUNTERS sets do_pikaj
        // 50 VBLANKs to the first beep: 16 updates of 3.125 frames are exactly 50
        for (int i = 0; i < 15; i++) {
            game.update(0, 0, 0, TICK);
        }
        assertEquals(0, sounds(game, SoundTable.BEEP), "not before 50 frames");
        game.update(0, 0, 0, TICK);
        assertEquals(1, sounds(game, SoundTable.BEEP));
    }
}
