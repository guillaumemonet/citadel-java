package citadel.core;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The .k end sequence animations. The pixel for pixel comparison with the IFF originals lives in
 * the tools module ({@code KAnimIffTest}), which is the one that can see {@code AnimReader}; this
 * checks what the .k files show on their own, chiefly that the IFF loop frames come back exactly,
 * which only happens when deltas are applied two frames back as {@code iff_ChangeScreen} does.
 */
class KAnimTest {

    private static KAnim load(String name) throws Exception {
        return load(name, true);
    }

    private static KAnim load(String name, boolean doubleBuffered) throws Exception {
        File f = new File(TestData.root(), "INTRO/ANIM2/" + name);
        Assumptions.assumeTrue(f.isFile(), f + " not present");
        return KAnim.load(Io.readAll(f), doubleBuffered);
    }

    @Test
    @DisplayName("all three are 160 by 100 with one frame per DLTA plus the BODY")
    void sizesAndCounts() throws Exception {
        int[] expected = {26, 18, 26};
        String[] names = {"start.k", "kabina.k", "explozycja.k"};
        for (int i = 0; i < names.length; i++) {
            KAnim a = load(names[i]);
            assertEquals(160, a.width(), names[i]);
            assertEquals(100, a.height(), names[i]);
            assertEquals(expected[i], a.frameCount(), names[i]);
            for (int f = 0; f < a.frameCount(); f++) {
                int[] px = a.frame(f);
                assertEquals(160 * 100, px.length);
                for (int p : px) {
                    assertEquals(0xff, p >>> 24, "opaque");
                }
            }
        }
    }

    @Test
    @DisplayName("the colour table is the IFF CMAP cut to 12 bits, nibbles repeated")
    void palette() throws Exception {
        int[] pal = load("start.k").palette();
        // start's CMAP opens 000000 000010 000030 001000 201000
        assertEquals(0xff000000, pal[0]);
        assertEquals(0xff000011, pal[1]);
        assertEquals(0xff000033, pal[2]);
        assertEquals(0xff001100, pal[3]);
        assertEquals(0xff221100, pal[4]);
        assertEquals(0xffeeeeee, pal[15]);
    }

    @Test
    @DisplayName("double buffered, start and explozycja end on an exact copy of frame 0")
    void loopFrameComesBack() throws Exception {
        for (String name : new String[] {"start.k", "explozycja.k"}) {
            KAnim a = load(name);
            assertArrayEquals(a.frame(0), a.frame(a.frameCount() - 1), name);
            KAnim wrong = load(name, false);
            assertFalse(Arrays.equals(wrong.frame(0), wrong.frame(wrong.frameCount() - 1)),
                    name + ": applying deltas to the previous frame does not close the loop");
        }
        KAnim kabina = load("kabina.k");
        assertArrayEquals(kabina.frame(16), kabina.frame(17), "kabina holds its last frame");
    }

    @Test
    @DisplayName("frame durations follow SERVER's anims table, row by row in the order played")
    void timing() {
        // END_PART2 plays kabina first, and ReplayPart takes the table's first row for it
        int[] kabina = KAnim.frameVblanks("kabina", 18);
        assertEquals(60 + 7, kabina[0]);
        assertEquals(7, kabina[15]);
        assertEquals(80, kabina[16]);
        assertEquals(1, kabina[17]);
        int[] start = KAnim.frameVblanks("start.k", 26);
        assertEquals(86, start[0]);
        assertEquals(6, start[16]);
        assertEquals(1, start[25]);
        int[] explo = KAnim.frameVblanks("explozycja", 26);
        assertEquals(20, explo[0]);
        assertEquals(5, explo[23]);
        assertEquals(50, explo[24]);
        assertNull(KAnim.frameVblanks("other", 3));
    }

    @Test
    @DisplayName("anything that is not ANIM, colours, BODY is refused")
    void rejectsGarbage() {
        assertThrows(java.io.IOException.class, () -> KAnim.load(new byte[64]));
        byte[] truncated = new byte[44];
        System.arraycopy("ANIM".getBytes(), 0, truncated, 0, 4);
        System.arraycopy("BODY".getBytes(), 0, truncated, 36, 4);
        assertThrows(java.io.IOException.class, () -> KAnim.load(truncated));
    }
}
