package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rm_ServeSwitch, rm_CardUsed, br_Chk_OPEN and Take_Items, held against the shipped maps.
 */
class SwitchCardDoorTest {

    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;

    /** sv_Angle for each compass direction: 0 faces west, 128 north, 256 east, 384 south. */
    private static final int[] ANGLE_FOR_DIR = {128, 256, 384, 0};

    private static Level load(String name) throws Exception {
        File f = new File(TestData.maps(), name);
        Assumptions.assumeTrue(f.isFile(), name + " not present");
        return Level.load(f);
    }

    private static void standIn(GameState game, int cx, int cy, int dir) {
        game.posX = cx * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.posY = cy * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        game.angle = ANGLE_FOR_DIR[dir];
        assertEquals(dir, game.facingDir());
    }

    private static void passes(GameState game, int n) {
        for (int i = 0; i < n; i++) {
            game.update(0, 0, 0, TICK);
        }
    }

    private static boolean isOpen(Level level, Level.Command c) {
        int v = level.wallValue(c.cellX, c.cellY, c.dir) & 0x3e;
        return v == 32 || v == 38;
    }

    @Test
    @DisplayName("a switch runs its first list going in and its second list coming out")
    void switchesGoBothWays() throws Exception {
        Level level = load("LEVEL04C.MAP");
        Level.Trigger pick = null;
        Level.Command in = null;
        Level.Command out = null;
        for (Level.Trigger t : level.triggers()) {
            if (t.isCardReader()) {
                continue;
            }
            Level.Command first = null;
            Level.Command second = null;
            for (Level.Command c : t.commands) {
                boolean door = c.code == Level.Command.OPEN_DOOR || c.code == Level.Command.CLOSE_DOOR;
                if (door && !c.onRelease && first == null) {
                    first = c;
                }
                if (door && c.onRelease && second == null) {
                    second = c;
                }
            }
            if (first != null && second != null && first.code != second.code
                    && first.cellX == second.cellX && first.cellY == second.cellY
                    && first.dir == second.dir) {
                pick = t;
                in = first;
                out = second;
                break;
            }
        }
        assertNotNull(pick, "no switch with a door command in both lists");

        GameState game = new GameState(level);
        standIn(game, pick.cellX, pick.cellY, pick.dir);
        assertEquals(44, level.wallValue(pick.cellX, pick.cellY, pick.dir) & 0x3e);

        assertTrue(game.use());
        assertEquals(46, level.wallValue(pick.cellX, pick.cellY, pick.dir) & 0x3e);
        // rm_ServeSwitch refuses while the priority door it queued is still to finish
        assertFalse(game.use(), "SCROLL 54 while a priority door is queued or moving");
        assertEquals(46, level.wallValue(pick.cellX, pick.cellY, pick.dir) & 0x3e);
        passes(game, 10);
        assertEquals(in.code == Level.Command.OPEN_DOOR, isOpen(level, in));

        assertTrue(game.use());
        assertEquals(44, level.wallValue(pick.cellX, pick.cellY, pick.dir) & 0x3e);
        passes(game, 10);
        assertEquals(out.code == Level.Command.OPEN_DOOR, isOpen(level, out));
    }

    @Test
    @DisplayName("a card of the right colour goes into an empty reader and runs its commands")
    void cardReaders() throws Exception {
        Level level = load("LEVEL01B.MAP");
        Level.Trigger reader = null;
        int dir = -1;
        for (Level.Trigger t : level.triggers()) {
            if (!t.isCardReader() || t.cardColour < 1 || t.cardColour > 3) {
                continue;
            }
            for (int d = 0; d < 4; d++) {
                if (level.wallPlaque(t.cellX, t.cellY, d) == 1) {
                    reader = t;
                    dir = d;
                }
            }
            if (reader != null) {
                break;
            }
        }
        assertNotNull(reader, "no card reader with an empty slot");
        int colour = reader.cardColour - 1;

        GameState game = new GameState(level);
        standIn(game, reader.cellX, reader.cellY, dir);
        assertTrue(game.inventory.select(Inventory.FIRST_CARD_SLOT + colour),
                "a card can always be taken in hand");

        // no card: refused
        assertTrue(!game.fire());
        assertEquals(1, level.wallPlaque(reader.cellX, reader.cellY, dir));

        // a card of another colour: refused, and the card is kept
        int other = (colour + 1) % 3;
        game.inventory.cards[other] = -1;
        game.inventory.select(Inventory.FIRST_CARD_SLOT + other);
        boolean otherRecord = false;
        for (Level.Trigger t : level.triggers()) {
            otherRecord |= t.isCardReader() && t.cellX == reader.cellX && t.cellY == reader.cellY
                    && t.cardColour == other + 1;
        }
        if (!otherRecord) {
            assertTrue(!game.fire());
            assertEquals(-1, game.inventory.cards[other]);
        }

        game.inventory.cards[colour] = -1;
        game.inventory.select(Inventory.FIRST_CARD_SLOT + colour);
        assertEquals(1, game.inventory.ammoReading(), "the counter shows the cards in hand");
        assertTrue(game.fire(), "the card was refused");
        assertEquals(0, game.inventory.cards[colour], "the card was not used up");
        assertEquals(2, level.wallPlaque(reader.cellX, reader.cellY, dir), "the slot did not fill");

        // a full slot takes nothing more
        game.inventory.cards[colour] = -1;
        assertTrue(!game.fire());
        assertEquals(-1, game.inventory.cards[colour]);
    }

    @Test
    @DisplayName("every door of a family in the player's cell opens, not just the first")
    void twoDoorsInOneCell() throws Exception {
        Level level = load("LEVEL01B.MAP");
        int[] cell = null;
        int closed = 0;
        search:
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (level.isBlocked(x, y)) {
                    continue;
                }
                for (int family : new int[]{30, 36}) {
                    int n = 0;
                    for (int d = 0; d < 4; d++) {
                        if ((level.wallValue(x, y, d) & 0x3e) == family) {
                            n++;
                        }
                    }
                    if (n >= 2) {
                        cell = new int[]{x, y};
                        closed = family;
                        break search;
                    }
                }
            }
        }
        assertNotNull(cell, "no cell with two shut doors of one family");

        GameState game = new GameState(level);
        standIn(game, cell[0], cell[1], 0);
        for (int i = 0; i < 20; i++) {
            game.update(0, 0, 0, TICK);
        }
        int open = closed + 2;
        int opened = 0;
        for (int d = 0; d < 4; d++) {
            if ((level.wallValue(cell[0], cell[1], d) & 0x3e) == open) {
                opened++;
            }
        }
        assertTrue(opened >= 2, "only " + opened + " door(s) opened at " + cell[0] + "," + cell[1]);
    }

    @Test
    @DisplayName("an item standing on a table is still an item: bit 5 is its height, not its kind")
    void raisedItems() throws Exception {
        Level level = load("LEVEL04A.MAP");
        int[] spot = null;
        for (int y = 0; y < Level.GRID && spot == null; y++) {
            for (int x = 0; x < Level.GRID && spot == null; x++) {
                if (level.itemRaised(x, y) && level.item(x, y) != 0
                        && level.item(x, y) <= Inventory.WARHEAD) {
                    spot = new int[]{x, y};
                }
            }
        }
        assertNotNull(spot, "no raised item");
        GameState game = new GameState(level);
        standIn(game, spot[0], spot[1], 0);
        game.update(0, 0, 0, TICK);
        assertEquals(0, level.item(spot[0], spot[1]), "the item was not taken");
        assertTrue(!level.itemRaised(spot[0], spot[1]));
    }
}
