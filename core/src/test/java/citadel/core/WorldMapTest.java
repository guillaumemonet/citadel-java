package citadel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAPA's rules: which complexes can be entered, and what it takes to reach the centre.
 *
 * The interesting one is the budget. Printnij walks Kolejnosc skipping the complexes already
 * taken, so a finished one does not use up a slot, and the first three still standing are open.
 */
class WorldMapTest {

    @Test
    @DisplayName("the tables line up: seven targets, seven complexes, one order")
    void theTablesLineUp() {
        assertEquals(WorldMap.TARGETS, WorldMap.NAMES.length);
        assertEquals(WorldMap.TARGETS, WorldMap.COMPLEX.length);
        assertEquals(WorldMap.TARGETS, WorldMap.ORDER.length);
        assertEquals(WorldMap.TARGETS, WorldMap.ARROW.length);

        java.util.Set<Integer> fromTargets = new java.util.TreeSet<Integer>();
        for (int c : WorldMap.COMPLEX) {
            fromTargets.add(c);
        }
        java.util.Set<Integer> fromOrder = new java.util.TreeSet<Integer>();
        for (int c : WorldMap.ORDER) {
            fromOrder.add(c);
        }
        assertEquals(fromOrder, fromTargets, "Kon2Tab and Kolejnosc name the same complexes");
        assertEquals(1, WorldMap.COMPLEX.length - fromTargets.size() + 1,
                "and none of them twice");
    }

    @Test
    @DisplayName("three complexes are open at a time, in Kolejnosc order")
    void threeAreOpenAtATime() {
        WorldMap map = new WorldMap();
        // Kolejnosc is 2,5,6,3,4,7,8, so those first three are the ones to start with
        assertSame(WorldMap.Status.UNEXPLORED, statusOfComplex(map, 2));
        assertSame(WorldMap.Status.UNEXPLORED, statusOfComplex(map, 5));
        assertSame(WorldMap.Status.UNEXPLORED, statusOfComplex(map, 6));
        assertSame(WorldMap.Status.NO_ACCESS, statusOfComplex(map, 3), "fourth in the order");
        assertSame(WorldMap.Status.NO_ACCESS, statusOfComplex(map, 8), "and the centre is last");
    }

    @Test
    @DisplayName("taking one opens the next, since a finished complex does not use up a slot")
    void takingOneOpensTheNext() {
        WorldMap map = new WorldMap();
        map.markDone(2);
        assertSame(WorldMap.Status.EXPLORED, statusOfComplex(map, 2));
        assertSame(WorldMap.Status.UNEXPLORED, statusOfComplex(map, 5));
        assertSame(WorldMap.Status.UNEXPLORED, statusOfComplex(map, 6));
        assertSame(WorldMap.Status.UNEXPLORED, statusOfComplex(map, 3), "now within reach");
        assertSame(WorldMap.Status.NO_ACCESS, statusOfComplex(map, 4), "but not the fifth");
    }

    @Test
    @DisplayName("the centre needs every complex behind you and the bomb in hand")
    void theCentreNeedsEverything() {
        WorldMap map = new WorldMap();
        for (int c : WorldMap.ORDER) {
            if (c != 8) {
                map.markDone(c);
            }
        }
        map.markDone(1);
        map.setChoice(WorldMap.CENTRE);
        assertSame(WorldMap.Status.UNEXPLORED, map.statusOf(WorldMap.CENTRE),
                "it is the only one left, so it is open");

        // ma_wybrany wants seven complexes counted and sv_Glowica at -12
        assertEquals(7, map.complexesTaken());
        assertSame(WorldMap.Pick.NEED_THE_BOMB, map.pick(-10), "the bomb is a section short");
        assertSame(WorldMap.Pick.CHOSEN, map.pick(-12));
    }

    @Test
    @DisplayName("a finished complex and a locked one each say so rather than letting you in")
    void refusalsAreReported() {
        WorldMap map = new WorldMap();
        map.markDone(2);
        map.setChoice(target(2));
        assertSame(WorldMap.Pick.ALREADY_EXPLORED, map.pick(0));
        map.setChoice(target(4));
        assertSame(WorldMap.Pick.INACCESSIBLE, map.pick(0));
        map.setChoice(target(5));
        assertSame(WorldMap.Pick.CHOSEN, map.pick(0));
        assertEquals(5, map.chosenComplex());
    }

    @Test
    @DisplayName("the choice wraps both ways and the status line fills in both fields")
    void theStatusLineReads() {
        WorldMap map = new WorldMap();
        map.setChoice(0);
        map.move(-1);
        assertEquals(WorldMap.TARGETS - 1, map.choice());
        map.move(1);
        assertEquals(0, map.choice());

        String line = map.statusLine();
        assertEquals(40, line.length(), "KomplexTexts is forty characters");
        assertTrue(line.contains("PRISON"), line);
        assertTrue(line.contains("NO ACCESS"), line);
        assertTrue(line.startsWith(" COMPLEX:"), line);
    }

    private static int target(int complex) {
        for (int i = 0; i < WorldMap.COMPLEX.length; i++) {
            if (WorldMap.COMPLEX[i] == complex) {
                return i;
            }
        }
        throw new IllegalArgumentException("complex " + complex + " is not on the map");
    }

    private static WorldMap.Status statusOfComplex(WorldMap map, int complex) {
        return map.statusOf(target(complex));
    }
}
