package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Store/CHAOSTAB.DAT, the order a newly drawn weapon appears in. */
class WeaponChangeTest {

    private static WeaponChange load() throws Exception {
        File root = new File("../Data").isDirectory() ? new File("../Data") : new File("Data");
        Assumptions.assumeTrue(root.isDirectory(), "original Data folder not present");
        File f = new File(new File(root, "GAME/Store"), "CHAOSTAB.DAT");
        Assumptions.assumeTrue(f.isFile(), "CHAOSTAB.DAT not present");
        return WeaponChange.load(f);
    }

    @Test
    @DisplayName("the file is a permutation of the 32 by 27 weapon window")
    void isAPermutation() throws Exception {
        assertEquals(32 * 27, WeaponChange.SIZE);
        assertTrue(load().isPermutation());
    }

    @Test
    @DisplayName("27 frames of 32 pixels cover every pixel exactly once")
    void coversEveryPixelOnce() throws Exception {
        WeaponChange t = load();
        assertEquals(27, WeaponChange.FRAMES, "32 entries a call out of 864");

        boolean[] seen = new boolean[WeaponChange.SIZE];
        int count = 0;
        for (int f = 0; f < WeaponChange.FRAMES; f++) {
            for (int[] p : t.frame(f)) {
                assertTrue(p[0] >= 0 && p[0] < WeaponChange.WIDTH, "column " + p[0]);
                assertTrue(p[1] >= 0 && p[1] < WeaponChange.HEIGHT, "row " + p[1]);
                int at = p[1] * WeaponChange.WIDTH + p[0];
                assertTrue(!seen[at], "pixel " + p[0] + "," + p[1] + " is drawn twice");
                seen[at] = true;
                count++;
            }
        }
        assertEquals(WeaponChange.SIZE, count);
    }

    @Test
    @DisplayName("the walk runs backwards, as sv_ChaosAddr counts down from the last entry")
    void theWalkRunsBackwards() throws Exception {
        WeaponChange t = load();
        // sv_ChaosAddr starts at [32*27*2]-2, the last entry, and subq #2,d0 takes it down
        int last = t.entry(WeaponChange.SIZE - 1);
        int[] firstDrawn = t.frame(0)[0];
        assertEquals(last & 31, firstDrawn[0], "first column drawn");
        assertEquals(last >> 5, firstDrawn[1], "first row drawn");

        int first = t.entry(0);
        int[] lastDrawn = t.frame(WeaponChange.FRAMES - 1)[WeaponChange.PER_FRAME - 1];
        assertEquals(first & 31, lastDrawn[0], "last column drawn");
        assertEquals(first >> 5, lastDrawn[1], "last row drawn");
    }
}
