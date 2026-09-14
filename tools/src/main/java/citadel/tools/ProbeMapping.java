package citadel.tools;

import citadel.core.GfxLayout;
import citadel.core.Io;
import citadel.core.MapFile;
import citadel.core.Palette;
import citadel.core.VirReader;

import java.io.File;

/**
 * Diagnostic: compares the first wall tile of WALLS1A.VIR against the same tile in the authoring
 * source SCIANY_01A_podziemia.iff, and prints how stored .VIR bytes map onto palette indices.
 *
 * Run with: java -cp out citadel.tools.ProbeMapping <WALLS1A.VIR> <SCIANY_01A...iff>
 */
public final class ProbeMapping {

    public static void main(String[] args) throws Exception {
        byte[] vir = Io.readAll(new File(args[0]));
        IffReader iff = IffReader.load(new File(args[1]));
        System.out.printf("iff %dx%d, %d planes%n", iff.width, iff.height, iff.planes);

        // byte value -> histogram of iff indices seen at the same pixel
        int[][] hist = new int[256][32];
        int tiles = Math.min(5, iff.width / 64);
        for (int t = 0; t < tiles; t++) {
            int base = t * VirReader.TILE;
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    int b = vir[base + x * VirReader.STRIDE + y] & 0xff;
                    int i = iff.indices[y * iff.width + t * 64 + x] & 31;
                    hist[b][i]++;
                }
            }
        }

        System.out.println("virByte  byte>>3  bestIffIndex  agreement  samples");
        int agreeShift = 0, agreeTotal = 0;
        for (int b = 0; b < 256; b++) {
            int total = 0, best = -1, bestN = 0;
            for (int i = 0; i < 32; i++) {
                total += hist[b][i];
                if (hist[b][i] > bestN) {
                    bestN = hist[b][i];
                    best = i;
                }
            }
            if (total == 0) {
                continue;
            }
            System.out.printf("%5d   %5d    %10d   %6.1f%%   %d%n",
                    b, b >> 3, best, 100.0 * bestN / total, total);
            agreeShift += hist[b][(b >> 3) & 31];
            agreeTotal += total;
        }
        System.out.printf("%nplain (byte>>3) agreement over %d pixels: %.2f%%%n",
                agreeTotal, 100.0 * agreeShift / agreeTotal);

        // try a few candidate transforms
        check("byte>>3", hist, new Transform() { public int f(int b) { return b >> 3; } });
        check("reverse5(byte>>3)", hist, new Transform() {
            public int f(int b) {
                int v = (b >> 3) & 31, r = 0;
                for (int i = 0; i < 5; i++) {
                    r = (r << 1) | ((v >> i) & 1);
                }
                return r;
            }
        });
        check("byte&31", hist, new Transform() { public int f(int b) { return b & 31; } });
        check("reverse8(byte)&31", hist, new Transform() {
            public int f(int b) {
                int r = 0;
                for (int i = 0; i < 8; i++) {
                    r = (r << 1) | ((b >> i) & 1);
                }
                return r & 31;
            }
        });
    }

    interface Transform {
        int f(int b);
    }

    private static void check(String name, int[][] hist, Transform t) {
        int hit = 0, total = 0;
        for (int b = 0; b < 256; b++) {
            for (int i = 0; i < 32; i++) {
                total += hist[b][i];
                if (hist[b][i] > 0 && t.f(b) == i) {
                    hit += hist[b][i];
                }
            }
        }
        System.out.printf("%-22s : %.2f%%%n", name, 100.0 * hit / Math.max(1, total));
    }
}
