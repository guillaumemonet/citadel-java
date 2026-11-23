package citadel.core;

import java.io.File;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ".pro" modules and the player that renders them. */
class MusicTest {

    private static byte[] module(String path) throws Exception {
        File f = new File(TestData.root(), path);
        Assumptions.assumeTrue(f.isFile(), path + " not present");
        return Io.readAll(f);
    }

    @Test
    @DisplayName("mt_init's layout accounts for every byte of the server's main music")
    void theLayoutAddsUp() throws Exception {
        ProModule m = ProModule.read(module("INTRO/MODS/mod.aconcagua.pro"));
        assertEquals(43, m.length());
        assertEquals(73, m.trackCount(), "the largest entry of the position tables, plus one");
        int samples = 0;
        for (int i = 0; i < ProModule.SAMPLES; i++) {
            samples += m.sample(i).lengthWords() * 2;
        }
        // 762 + tracks + the note table's size word and table + the samples = the file
        assertEquals(149174, samples);
        int[] cell = m.cell(0, 0, 0);
        assertTrue(cell[0] == 0 || (cell[0] >= 113 && cell[0] <= 907), "a period: " + cell[0]);
    }

    @Test
    @DisplayName("each module renders to sound, and loops back on itself")
    void itRenders() throws Exception {
        for (String p : new String[]{"INTRO/MODS/mod.aconcagua.pro", "INTRO/MODS/mod.dirranbandi.pro",
                "INTRO/MODS/mod.brief1.pro", "INTRO/STORE/mod.death.pro"}) {
            ProModule m = ProModule.read(module(p));
            short[] pcm = ProTrackerPlayer.render(m, 11025, 20);
            assertTrue(pcm.length > 11025, p + " is " + pcm.length + " samples");
            long energy = 0;
            for (short s : pcm) {
                energy += Math.abs(s);
            }
            assertTrue(energy > pcm.length * 50L, p + " is close to silent");
        }
    }

    @Test
    @DisplayName("anything too short to hold the tables is refused")
    void rejectsGarbage() {
        assertThrows(java.io.IOException.class, () -> ProModule.read(new byte[100]));
    }
}
