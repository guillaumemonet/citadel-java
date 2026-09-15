package citadel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** sv_Death: the blood that runs down the screen when the player runs out of energy. */
class DeathWipeTest {

    @Test
    @DisplayName("71 passes three VBLANK frames apart, which is about four and a half seconds")
    void theSequenceLastsAsLongAsTheOriginal() {
        // moveq #70,d7 and three VBLANKs at the end of .loopX
        assertEquals(71, DeathWipe.PASSES);
        assertEquals(3, DeathWipe.FRAMES_PER_PASS);
        float seconds = DeathWipe.PASSES * DeathWipe.FRAMES_PER_PASS / GameState.FRAMES_PER_SECOND;
        assertTrue(seconds > 4f && seconds < 5f, "got " + seconds + "s");
    }

    @Test
    @DisplayName("every column grows by one to four rows a pass, and they do not grow together")
    void columnsGrowIndependently() {
        DeathWipe w = new DeathWipe();
        int[] before = new int[DeathWipe.COLUMNS];
        for (int x = 0; x < DeathWipe.COLUMNS; x++) {
            before[x] = w.height(x);
            assertEquals(0, before[x], "nothing has run yet");
        }
        assertTrue(w.step());

        boolean ragged = false;
        for (int x = 0; x < DeathWipe.COLUMNS; x++) {
            int grew = w.height(x) - before[x];
            // andi #3,d0 / addq #1,d0
            assertTrue(grew >= 1 && grew <= 4, "column " + x + " grew by " + grew);
            ragged |= grew != w.height(0);
        }
        assertTrue(ragged, "the edge is meant to be uneven");
    }

    @Test
    @DisplayName("by the last pass the screen is covered, and the wipe stops")
    void itFinishesCovered() {
        DeathWipe w = new DeathWipe();
        while (w.step()) {
            // run it out
        }
        assertTrue(w.isDone());
        assertFalse(w.step(), "it must not keep going");
        assertEquals(DeathWipe.PASSES, w.pass());
        for (int x = 0; x < DeathWipe.COLUMNS; x++) {
            assertTrue(w.height(x) >= DeathWipe.PASSES, "column " + x + " at " + w.height(x));
            assertTrue(w.height(x) <= DeathWipe.ROWS, "and never past the bottom");
        }
    }

    @Test
    @DisplayName("the blood is colour 10, which the bitplane pattern spells out")
    void theColourIsTen() {
        // and d4 on planes 0, 2, 4 and or d3 on planes 1 and 3 gives 2 + 8
        assertEquals(0xffbb0000, DeathWipe.COLOUR);
    }
}
