package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store/CHAOSTAC.DAT, the order TELEPORT reveals the new view in.
 *
 * The claim worth testing is that the dissolve is exhaustive: the table is a permutation, and the
 * way TELEPORT walks it visits every cell of the view exactly once. If that were not so the
 * transition would leave patches of the old view behind.
 */
class ChaosTableTest {

    private static ChaosTable load() throws Exception {
        File root = TestData.root();
        Assumptions.assumeTrue(root.isDirectory(), "original Data folder not present");
        File f = new File(new File(root, "GAME/Store"), "CHAOSTAC.DAT");
        Assumptions.assumeTrue(f.isFile(), "CHAOSTAC.DAT not present");
        return ChaosTable.load(f);
    }

    @Test
    @DisplayName("the file is a permutation of 0..1023")
    void isAPermutation() throws Exception {
        assertTrue(load().isPermutation(),
                "the dissolve only finishes clean because every value appears once");
    }

    @Test
    @DisplayName("the 32 frames between them cover every cell of the view exactly once")
    void coversTheViewExactlyOnce() throws Exception {
        ChaosTable t = load();
        // divu d0,d5 with d0 = 80 splits an offset into a nibble column and a row pair, and
        // 32 frames * 32 entries * 5 repeats is 5120, which is 80 * 64
        assertEquals(ChaosTable.COLUMNS * ChaosTable.ROW_PAIRS,
                ChaosTable.FRAMES * ChaosTable.PER_FRAME * ChaosTable.REPEATS,
                "the walk does not match the grid");

        boolean[] seen = new boolean[ChaosTable.COLUMNS * ChaosTable.ROW_PAIRS];
        int count = 0;
        for (int f = 0; f < ChaosTable.FRAMES; f++) {
            int[][] cells = t.frame(f);
            assertEquals(ChaosTable.PER_FRAME * ChaosTable.REPEATS, cells.length);
            for (int[] c : cells) {
                assertTrue(c[0] >= 0 && c[0] < ChaosTable.COLUMNS, "column " + c[0]);
                assertTrue(c[1] >= 0 && c[1] < ChaosTable.ROW_PAIRS, "row pair " + c[1]);
                int at = c[1] * ChaosTable.COLUMNS + c[0];
                assertTrue(!seen[at], "cell " + c[0] + "," + c[1] + " is revealed twice");
                seen[at] = true;
                count++;
            }
        }
        assertEquals(seen.length, count, "not every cell is reached");
    }

    @Test
    @DisplayName("the view it covers is the 320 by 128 the engine draws")
    void theGridIsTheView() throws Exception {
        load();
        assertEquals(320, ChaosTable.COLUMNS * ChaosTable.PIXELS_PER_COLUMN);
        assertEquals(128, ChaosTable.ROW_PAIRS * ChaosTable.ROWS_PER_PAIR,
                "sv_ViewHeigth is 128");
    }
}
