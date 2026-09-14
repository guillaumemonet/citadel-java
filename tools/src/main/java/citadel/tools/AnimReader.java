package citadel.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import citadel.core.Io;

/**
 * Reader for IFF ANIM files, the format the intro sequences under Data/INTRO/ANIM2 are stored in.
 *
 * The repository README says the intro animations are gone; they are not. `start`, `kabina` and
 * `explozycja` are ordinary `FORM ANIM` files, 320x200 in 6 bitplanes.
 *
 * Structure is a container of ILBM forms: the first carries BMHD, CMAP and a full BODY, and each
 * one after that carries an ANHD describing how to apply its DLTA to the frame before it. The
 * only compression in use here is ANHD operation 5, "byte vertical delta", which is what every
 * Amiga animation package of the era produced.
 *
 * Op 5 stores, per bitplane, 16 columns worth of opcode lists (one per byte of the row stride).
 * Each list walks down its column: a positive opcode copies that many bytes, a negative one
 * repeats the next byte, and 0 introduces a skip count.
 */
public final class AnimReader {

    public final int width;
    public final int height;
    public final int planes;
    public final int[] palette;
    private final List<byte[]> frames = new ArrayList<byte[]>();

    private AnimReader(int width, int height, int planes, int[] palette) {
        this.width = width;
        this.height = height;
        this.planes = planes;
        this.palette = palette;
    }

    public int frameCount() {
        return frames.size();
    }

    public BufferedImage frame(int i) {
        byte[] idx = frames.get(i);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] line = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                line[x] = palette[idx[y * width + x] & 0xff];
            }
            img.setRGB(0, y, width, 1, line, 0, width);
        }
        return img;
    }

    public static AnimReader load(File f) throws IOException {
        byte[] d = Io.readAll(f);
        if (d.length < 12 || !ascii(d, 0, "FORM") || !ascii(d, 8, "ANIM")) {
            throw new IOException(f + ": not an IFF ANIM");
        }

        int w = 0, h = 0, planes = 0, compression = 0, masking = 0;
        int cmapEntries = 0;
        int camg = 0;
        int[] pal = new int[256];
        AnimReader anim = null;
        byte[] current = null;   // planar bitmap of the frame being built
        int rowBytes = 0;
        int totalPlanes = 0;
        int lastOperation = -1;

        int p = 12;
        while (p + 8 <= d.length) {
            String id = new String(d, p, 4, "ISO-8859-1");
            int len = readS32(d, p + 4);
            int payload = p + 8;
            if (len < 0) {
                break;
            }
            if (System.getProperty("iff.verbose") != null) {
                System.out.printf("  @%08x %s len=%d%n", p, id, len);
            }
            if ("FORM".equals(id)) {
                // a nested FORM ILBM: step inside it rather than over it
                p = payload + 4;
                continue;
            }
            if (payload + len > d.length) {
                break;
            }

            if ("BMHD".equals(id)) {
                w = Io.u16(d, payload);
                h = Io.u16(d, payload + 2);
                planes = Io.u8(d, payload + 8);
                masking = Io.u8(d, payload + 9);
                compression = Io.u8(d, payload + 10);
                rowBytes = ((w + 15) / 16) * 2;
                totalPlanes = planes + (masking == 1 ? 1 : 0);
                current = new byte[rowBytes * totalPlanes * h];
            } else if ("CMAP".equals(id)) {
                int count = 0;
                for (int i = 0; i * 3 + 2 < len && i < 256; i++) {
                    pal[i] = 0xff000000
                            | (Io.u8(d, payload + i * 3) << 16)
                            | (Io.u8(d, payload + i * 3 + 1) << 8)
                            | Io.u8(d, payload + i * 3 + 2);
                    count = i + 1;
                }
                cmapEntries = count;
            } else if ("CAMG".equals(id)) {
                camg = readS32(d, payload);
            } else if ("ANHD".equals(id)) {
                lastOperation = Io.u8(d, payload);
            } else if ("BODY".equals(id)) {
                expandHalfBrite(pal, cmapEntries, planes, camg);
                int used = readBody(d, payload, len, current, rowBytes, totalPlanes, h, compression);
                if (System.getProperty("iff.verbose") != null) {
                    System.out.printf("  BODY consumed %d of %d bytes, buffer %d%n",
                            used, len, current.length);
                }
                anim = ensure(anim, w, h, planes, pal);
                anim.frames.add(toChunky(current, w, h, rowBytes, planes, totalPlanes));
            } else if ("DLTA".equals(id)) {
                if (lastOperation != 5) {
                    throw new IOException(f + ": unsupported ANIM operation " + lastOperation
                            + " (only 5, byte vertical delta, is implemented)");
                }
                applyDelta5(d, payload, len, current, rowBytes, planes, h);
                anim = ensure(anim, w, h, planes, pal);
                anim.frames.add(toChunky(current, w, h, rowBytes, planes, totalPlanes));
            }
            p = payload + len + (len & 1);
        }
        if (anim == null) {
            throw new IOException(f + ": no frames found");
        }
        return anim;
    }

    private static AnimReader ensure(AnimReader anim, int w, int h, int planes, int[] pal) {
        return (anim != null) ? anim : new AnimReader(w, h, planes, pal.clone());
    }

    /**
     * Fills in the Extra Half-Brite half of the palette.
     *
     * These files carry six bitplanes but only 32 CMAP entries, because EHB derives the top 32
     * colours from the bottom 32 at half brightness. Without this the whole upper half of the
     * palette reads as black, which is exactly what a first decode looks like.
     *
     * The CAMG bit (0x80) states it, but a short CMAP against six planes is decisive on its own,
     * so accept either.
     */
    static void expandHalfBrite(int[] pal, int cmapEntries, int planes, int camg) {
        boolean ehb = (camg & 0x80) != 0
                || (planes == 6 && cmapEntries > 0 && cmapEntries <= 32);
        if (!ehb || cmapEntries <= 0) {
            return;
        }
        for (int i = 0; i < cmapEntries && cmapEntries + i < 256; i++) {
            int c = pal[i];
            pal[cmapEntries + i] = 0xff000000
                    | (((c >> 16) & 0xff) >> 1) << 16
                    | (((c >> 8) & 0xff) >> 1) << 8
                    | ((c & 0xff) >> 1);
        }
    }

    /** Decodes a BODY into the planar buffer, honouring ByteRun1 when the header asks for it. */
    private static int readBody(byte[] d, int off, int len, byte[] dst,
                                 int rowBytes, int totalPlanes, int h, int compression) {
        int src = off;
        byte[] row = new byte[rowBytes];
        int out = 0;
        for (int y = 0; y < h; y++) {
            for (int pl = 0; pl < totalPlanes; pl++) {
                if (compression == 1) {
                    src = unpackRow(d, src, row);
                } else {
                    System.arraycopy(d, src, row, 0, rowBytes);
                    src += rowBytes;
                }
                System.arraycopy(row, 0, dst, out, rowBytes);
                out += rowBytes;
            }
        }
        return src - off;
    }

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

    /**
     * ANHD operation 5. The DLTA opens with 16 longword offsets, one per bitplane, each pointing
     * at that plane's opcode lists. A plane has one list per byte of the row stride, and each list
     * walks down its column of the bitmap.
     */
    private static void applyDelta5(byte[] d, int off, int len, byte[] bitmap,
                                    int rowBytes, int planes, int h) {
        for (int plane = 0; plane < planes && plane < 16; plane++) {
            int planeOff = readS32(d, off + plane * 4);
            if (planeOff == 0) {
                continue;
            }
            int p = off + planeOff;
            for (int column = 0; column < rowBytes; column++) {
                if (p >= off + len) {
                    return;
                }
                int opCount = Io.u8(d, p++);
                // destination walks down the column, one row stride at a time
                int dst = plane * rowBytes + column;
                for (int op = 0; op < opCount && p < off + len; op++) {
                    int code = Io.u8(d, p++);
                    if (code == 0) {                       // run of one repeated byte
                        int count = Io.u8(d, p++);
                        byte value = d[p++];
                        for (int i = 0; i < count; i++) {
                            if (dst < bitmap.length) {
                                bitmap[dst] = value;
                            }
                            dst += rowBytes * planes;
                        }
                    } else if (code < 0x80) {              // skip that many rows
                        dst += code * rowBytes * planes;
                    } else {                               // copy (code & 0x7f) bytes
                        int count = code & 0x7f;
                        for (int i = 0; i < count && p < off + len; i++) {
                            if (dst < bitmap.length) {
                                bitmap[dst] = d[p];
                            }
                            p++;
                            dst += rowBytes * planes;
                        }
                    }
                }
            }
        }
    }

    /** Flattens the interleaved planar bitmap into one byte per pixel. */
    private static byte[] toChunky(byte[] bitmap, int w, int h, int rowBytes,
                                   int planes, int totalPlanes) {
        byte[] idx = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int rowBase = y * rowBytes * totalPlanes;
            for (int pl = 0; pl < planes; pl++) {
                int base = rowBase + pl * rowBytes;
                for (int x = 0; x < w; x++) {
                    if ((bitmap[base + (x >> 3)] & (0x80 >> (x & 7))) != 0) {
                        idx[y * w + x] |= (byte) (1 << pl);
                    }
                }
            }
        }
        return idx;
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
            AnimReader anim = load(f);
            System.out.printf("%s: %dx%d, %d planes, %d frames%n",
                    f.getName(), anim.width, anim.height, anim.planes, anim.frameCount());
            File dir = new File(f.getParentFile(), f.getName() + "_frames");
            citadel.core.Io.mkdirs(dir);
            for (int i = 0; i < anim.frameCount(); i++) {
                javax.imageio.ImageIO.write(anim.frame(i), "png",
                        new File(dir, String.format("%03d.png", i)));
            }
            System.out.println("  -> " + dir);
        }
    }
}
