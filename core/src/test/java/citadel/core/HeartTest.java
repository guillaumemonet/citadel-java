package citadel.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DRAW_HEART, EXCITE and sc_NormWalk: the heart monitor is the readout for a stamina system, and
 * the thing worth pinning down is the walk speed it drives.
 */
class HeartTest {

    @Test
    @DisplayName("a rested player walks at full speed and a winded one at 65%")
    void exhaustionScalesTheWalk() {
        Heart h = new Heart();
        assertEquals(GameState.WALK_SPEED, h.walkSpeed(GameState.WALK_SPEED),
                "at rest the beat is 56*4 = 224, and 224 + 288 is exactly 512");

        for (int i = 0; i < 20; i++) {
            h.excite();
        }
        assertEquals(Heart.MIN_BEAT, h.beat(), "EXCITE stops at 11*4");
        // (250 * (44 + 288)) >> 9
        assertEquals((250 * (Heart.MIN_BEAT + 288)) >> 9, h.walkSpeed(250));
        assertTrue(h.walkSpeed(250) < 250 * 0.67f && h.walkSpeed(250) > 250 * 0.64f,
                "the worst case is about two thirds speed, got " + h.walkSpeed(250));
    }

    @Test
    @DisplayName("EXCITE costs eleven regeneration steps")
    void exciteCostsElevenSteps() {
        Heart h = new Heart();
        h.excite();
        int lost = Heart.MAX_BEAT - h.beat();
        assertEquals(Heart.EXCITE_STEP, lost);
        assertEquals(11, lost / Heart.REGEN_STEP, "subi #11*4 against addi #4");
    }

    @Test
    @DisplayName("standing still gets the breath back twice as fast as moving does")
    void movingRegeneratesSlower() {
        assertEquals(Heart.REGEN_STILL * 2, Heart.REGEN_MOVING);
        assertEquals(Heart.MAX_BEAT, recover(false), "22 seconds of standing still is enough");
        assertTrue(recover(true) < Heart.MAX_BEAT, "moving should not have got there yet");
    }

    /** Fully winded, then 25 seconds on the VBLANK clock. */
    private static int recover(boolean moving) {
        Heart h = new Heart();
        for (int i = 0; i < 20; i++) {
            h.excite();
        }
        for (int frame = 0; frame < 25 * 50; frame++) {
            h.tick(moving);
        }
        return h.beat();
    }

    @Test
    @DisplayName("the trace is 29 columns and stays inside the strip DRAW_HEART restores")
    void theTraceFitsTheStrip() {
        Heart h = new Heart();
        for (int frame = 0; frame < 500; frame++) {
            h.tick(false);
            int[][] trace = h.trace();
            assertEquals(Heart.COLUMNS, trace.length);
            for (int i = 0; i < trace.length; i++) {
                int[] c = trace[i];
                assertEquals(Heart.FIRST_COLUMN + i, c[0], "columns run left to right");
                assertTrue(c[0] >= 0 && c[0] < Heart.WIDTH, "x " + c[0]);
                assertTrue(c[1] >= 0 && c[1] + c[2] <= Heart.HEIGHT,
                        "run from row " + c[1] + " for " + c[2] + " overflows 12 rows");
            }
        }
    }

    @Test
    @DisplayName("a faster heart sweeps the trace round sooner")
    void aFasterHeartRepeatsSooner() {
        assertTrue(spikesShown(new Heart()) < spikesShown(winded()),
                "the whole point of the readout is that exhaustion is visible");
    }

    private static Heart winded() {
        Heart h = new Heart();
        for (int i = 0; i < 20; i++) {
            h.excite();
        }
        return h;
    }

    /** How many of the 29 columns are off the flat line, once the ring has filled. */
    private static int spikesShown(Heart h) {
        for (int frame = 0; frame < 200; frame++) {
            h.tick(false);
        }
        int n = 0;
        for (int[] c : h.trace()) {
            if (c[1] != 6 || c[2] != 1) {
                n++;
            }
        }
        return n;
    }
}
