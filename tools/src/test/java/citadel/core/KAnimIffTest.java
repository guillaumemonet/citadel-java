package citadel.core;

import java.awt.image.BufferedImage;
import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import citadel.tools.AnimReader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every frame of the .k end sequence animations against {@link AnimReader}'s frames of the IFF
 * ANIMs they were converted from.
 *
 * It sits in the tools module because that is where AnimReader is, and in package citadel.core
 * so it can reach {@code KAnim.load(raw, false)}. Two things keep it from being a bare equality:
 * <ul>
 * <li>Colours. The .k keeps 12 bit copper words; the IFF CMAP has the same values in the high
 * nibbles with zero low nibbles, and AnimReader uses them as they are while widening HAM modify
 * nibbles by repetition. The test asserts the low nibbles are zero, so nothing is lost, and
 * widens AnimReader's palette the same way before comparing.</li>
 * <li>Buffering. AnimReader applies each delta to the previous frame; the player, and
 * {@link KAnim#load(byte[])}, apply it two frames back as the IFF's interleave asks. The two
 * agree on frames 0 and 1, which are compared directly; for the rest the .k is decoded the
 * AnimReader way, which proves its deltas are the IFF's deltas, byte for byte in effect.
 * {@code KAnimTest} covers why double buffering is the right reading.</li>
 * </ul>
 * The IFFs are 320x200 canvases with the picture in the top left quarter; the rest is checked to
 * be colour 0 everywhere and the quarter compared.
 */
class KAnimIffTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({"start, 1", "kabina, 2", "explozycja, 1"})
    void framesMatchTheIff(String name, int droppedLoopFrames) throws Exception {
        String data = System.getProperty("citadel.data");
        Assumptions.assumeTrue(data != null, "citadel.data not set");
        File dir = new File(data, "INTRO/ANIM2");
        File iffFile = new File(dir, name);
        File kFile = new File(dir, name + ".k");
        Assumptions.assumeTrue(iffFile.isFile() && kFile.isFile(), name + " not present");

        AnimReader iff = AnimReader.load(iffFile);
        byte[] raw = Io.readAll(kFile);
        KAnim played = KAnim.load(raw);
        KAnim sequential = KAnim.load(raw, false);

        assertEquals(320, iff.width);
        assertEquals(200, iff.height);
        assertEquals(iff.frameCount() - droppedLoopFrames, played.frameCount());
        assertEquals(played.frameCount(), sequential.frameCount());

        int[] kPalette = played.palette();
        for (int i = 0; i < iff.palette.length; i++) {
            int c = iff.palette[i];
            if (i < 16) {
                assertEquals(0, c & 0x0f0f0f, name + " CMAP " + i + " has 12 bit precision only");
            }
            iff.palette[i] = (c & 0xfff0f0f0) | ((c >> 4) & 0x0f0f0f);
            if (i < 16) {
                assertEquals(iff.palette[i], kPalette[i], name + " colour " + i);
            }
        }

        int w = KAnim.WIDTH;
        int h = KAnim.HEIGHT;
        for (int f = 0; f < played.frameCount(); f++) {
            BufferedImage img = iff.frame(f);
            int background = iff.palette[0];
            for (int y = 0; y < iff.height; y++) {
                for (int x = (y < h ? w : 0); x < iff.width; x++) {
                    assertEquals(background, img.getRGB(x, y),
                            name + " frame " + f + " outside the picture at " + x + "," + y);
                }
            }
            int[] expected = img.getRGB(0, 0, w, h, null, 0, w);
            assertArrayEquals(expected, sequential.frame(f), name + " frame " + f);
            if (f < 2) {
                assertArrayEquals(expected, played.frame(f), name + " frame " + f);
            }
        }
    }
}
