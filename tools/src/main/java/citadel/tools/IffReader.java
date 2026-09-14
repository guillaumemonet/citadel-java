package citadel.tools;

import citadel.core.GfxLayout;
import citadel.core.Io;
import citadel.core.MapFile;
import citadel.core.Palette;
import citadel.core.VirReader;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Minimal IFF ILBM reader, enough for the artwork in Data/GAME/Gfx_IFF and Data/INTRO.
 * Supports uncompressed and ByteRun1 bodies, up to 8 bitplanes, no HAM or EHB.
 *
 * These files are the authoring sources the .VIR graphics were converted from, so they are the
 * reference to check a .VIR decode against.
 */
public final class IffReader {

    public final int width;
    public final int height;
    public final int planes;
    public final int[] palette;
    public final byte[] indices;

    private IffReader(int w, int h, int planes, int[] pal, byte[] idx) {
        this.width = w;
        this.height = h;
        this.planes = planes;
        this.palette = pal;
        this.indices = idx;
    }

    public static IffReader load(File f) throws IOException {
        byte[] d = Io.readAll(f);
        if (d.length < 12 || !ascii(d, 0, "FORM") || !ascii(d, 8, "ILBM")) {
            throw new IOException(f + ": not an ILBM file");
        }
        int w = 0, h = 0, planes = 0, compression = 0, masking = 0;
        int cmapEntries = 0, camg = 0;
        int[] pal = new int[256];
        byte[] body = null;

        int p = 12;
        while (p + 8 <= d.length) {
            String id = new String(d, p, 4, "ISO-8859-1");
            int len = readS32(d, p + 4);
            int payload = p + 8;
            if (len < 0 || payload + len > d.length) {
                break;
            }
            if ("BMHD".equals(id)) {
                w = Io.u16(d, payload);
                h = Io.u16(d, payload + 2);
                planes = Io.u8(d, payload + 8);
                masking = Io.u8(d, payload + 9);
                compression = Io.u8(d, payload + 10);
            } else if ("CMAP".equals(id)) {
                for (int i = 0; i * 3 + 2 < len && i < 256; i++) {
                    pal[i] = 0xff000000
                            | (Io.u8(d, payload + i * 3) << 16)
                            | (Io.u8(d, payload + i * 3 + 1) << 8)
                            | Io.u8(d, payload + i * 3 + 2);
                    cmapEntries = i + 1;
                }
            } else if ("CAMG".equals(id)) {
                camg = readS32(d, payload);
            } else if ("BODY".equals(id)) {
                body = new byte[len];
                System.arraycopy(d, payload, body, 0, len);
            }
            p = payload + len + (len & 1);
        }
        if (body == null || w == 0 || planes == 0) {
            throw new IOException(f + ": incomplete ILBM");
        }

        // Six planes against a 32-entry CMAP means Extra Half-Brite; fill in the derived half.
        AnimReader.expandHalfBrite(pal, cmapEntries, planes, camg);

        int rowBytes = ((w + 15) / 16) * 2;
        int totalPlanes = planes + (masking == 1 ? 1 : 0);
        byte[] idx = new byte[w * h];
        int src = 0;
        byte[] row = new byte[rowBytes];
        for (int y = 0; y < h; y++) {
            for (int pl = 0; pl < totalPlanes; pl++) {
                if (compression == 1) {
                    src = unpackRow(body, src, row);
                } else {
                    System.arraycopy(body, src, row, 0, Math.min(rowBytes, body.length - src));
                    src += rowBytes;
                }
                if (pl >= planes) {
                    continue; // mask plane, ignored
                }
                for (int x = 0; x < w; x++) {
                    if ((row[x >> 3] & (0x80 >> (x & 7))) != 0) {
                        idx[y * w + x] |= (byte) (1 << pl);
                    }
                }
            }
        }
        return new IffReader(w, h, planes, pal, idx);
    }

    /** ByteRun1 decoder, one scanline at a time. */
    private static int unpackRow(byte[] src, int p, byte[] dst) {
        int o = 0;
        while (o < dst.length && p < src.length) {
            int n = src[p++];
            if (n >= 0) {
                int count = n + 1;
                for (int i = 0; i < count && o < dst.length && p < src.length; i++) {
                    dst[o++] = src[p++];
                }
            } else if (n != -128) {
                int count = 1 - n;
                byte v = src[p++];
                for (int i = 0; i < count && o < dst.length; i++) {
                    dst[o++] = v;
                }
            }
        }
        return p;
    }

    public BufferedImage toImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] line = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                line[x] = palette[indices[y * width + x] & 0xff];
            }
            img.setRGB(0, y, width, 1, line, 0, width);
        }
        return img;
    }

    private static boolean ascii(byte[] d, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            if ((d[off + i] & 0xff) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int readS32(byte[] d, int off) {
        return ((d[off] & 0xff) << 24) | ((d[off + 1] & 0xff) << 16)
                | ((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff);
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            File f = new File(a);
            IffReader iff = load(f);
            File out = new File(f.getParentFile(), f.getName() + ".png");
            javax.imageio.ImageIO.write(iff.toImage(), "png", out);
            System.out.printf("%s : %dx%d, %d planes -> %s%n",
                    f.getName(), iff.width, iff.height, iff.planes, out);
        }
    }
}
