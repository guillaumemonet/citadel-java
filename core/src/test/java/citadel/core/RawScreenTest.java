package citadel.core;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headerless screens under Data/INTRO. They say nothing about themselves, so what stands in
 * for a header is arithmetic: the palette length fixes the plane count and the height then has to
 * come out whole, which is the check that the reading is right at all.
 */
class RawScreenTest {

    private static File intro() {
        File root = TestData.root();
        Assumptions.assumeTrue(root.isDirectory(), "original Data folder not present");
        File dir = new File(root, "INTRO");
        Assumptions.assumeTrue(dir.isDirectory(), "Data/INTRO not present");
        return dir;
    }

    private static RawScreen load(String path, int w, int planes, int pal) throws IOException {
        File f = new File(intro(), path);
        Assumptions.assumeTrue(f.isFile(), path + " not present");
        return RawScreen.load(f, w, planes, pal);
    }

    @Test
    @DisplayName("the death picture is 320 by 200 in five planes with a 32 entry palette")
    void theDeathPictureFits() throws Exception {
        RawScreen s = load("STORE/zabity.rawb", 320, 5, 64);
        assertEquals(320, s.width);
        assertEquals(200, s.height, "40064 bytes less 64 of palette is exactly 320 by 200 by 5");
        assertEquals(32, s.palette.length);
        for (int c : s.palette) {
            assertEquals(0xff000000, c & 0xff000000, "palette entries are opaque");
        }
        // 0RGB nibbles are replicated to 8 bits, so every component is a multiple of 17
        for (int c : s.palette) {
            assertEquals(0, ((c >> 16) & 0xff) % 17, "red");
            assertEquals(0, ((c >> 8) & 0xff) % 17, "green");
            assertEquals(0, (c & 0xff) % 17, "blue");
        }
        assertEquals(320 * 200, s.pixels().length);
    }

    @Test
    @DisplayName("the hangar and the map are six plane screens of different heights")
    void theServerScreensFit() throws Exception {
        assertEquals(200, load("GFX/hangar.rawb", 320, 6, 128).height);
        assertEquals(192, load("GFX/MAPKA.rawb", 320, 6, 128).height);
        assertEquals(200, load("GFX/MT.raw", 320, 4, 32).height);
        assertEquals(200, load("GFX/Panel_Hires.rawb", 640, 4, 32).height, "hires, 640 across");
    }

    @Test
    @DisplayName("a shape that does not divide is rejected, which is how a bad guess shows up")
    void aWrongShapeIsRejected() throws Exception {
        File f = new File(intro(), "GFX/hangar.rawb");
        Assumptions.assumeTrue(f.isFile(), "hangar.rawb not present");
        assertThrows(IOException.class, () -> RawScreen.load(f, 320, 7, 128));
        // five planes happens to divide 48000 as well, and gives a 240 row screen - so the
        // arithmetic narrows the shape down but does not pin it; the picture has to be looked at
        assertEquals(240, RawScreen.load(f, 320, 5, 128).height);
    }

    @Test
    @DisplayName("the cover art is Hold-and-Modify, so it carries more colours than its palette")
    void theCoverArtIsHam() throws Exception {
        RawScreen s = load("GFX/cytadela.rawb", 320, 6, 128);
        assertEquals(252, s.height);
        java.util.Set<Integer> plain = new java.util.HashSet<Integer>();
        for (int c : s.pixels()) {
            plain.add(c);
        }
        java.util.Set<Integer> ham = new java.util.HashSet<Integer>();
        for (int c : s.hamPixels()) {
            ham.add(c);
        }
        assertTrue(plain.size() <= s.palette.length, "a plain decode cannot exceed its palette");
        assertTrue(ham.size() > s.palette.length,
                "holding and modifying is the whole point: got " + ham.size() + " colours");
    }
}
