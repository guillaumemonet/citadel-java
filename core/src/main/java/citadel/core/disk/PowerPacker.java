package citadel.core.disk;

import java.io.IOException;

/**
 * PowerPacker 2.0 decrunching, which is how everything on the Citadel disks is stored.
 *
 * The algorithm is the one in SERVER's {@code Decrunch} ("PowerPacker 2.0 FAST decrunch routine
 * (v1.5)"). The stream is read backwards from its end: the last long word holds the unpacked
 * length in its top 24 bits and the number of bits to skip in the bottom 8, and the four
 * efficiency bytes before the data give the offset widths of the four match lengths.
 *
 * The disks use two framings of the same stream:
 * <ul>
 * <li>an ordinary PowerPacker file, {@code "PP20"} then the efficiency bytes — the files on the
 *     last disk, which SERVER loads with {@code FileLoader} and unpacks with {@code Decrunch2};</li>
 * <li>a block whose first long word is its own packed length in place of the magic — the
 *     track-loaded blocks, which SERVER unpacks with {@code Decrunch}, which reads that length
 *     from {@code (a0)}.</li>
 * </ul>
 */
public final class PowerPacker {

    private static final int MAGIC = 0x50503230; // "PP20"

    private PowerPacker() {
    }

    /** True when the bytes are a {@code PP20} file. */
    public static boolean isFile(byte[] d) {
        return d.length >= 12 && u32(d, 0) == MAGIC;
    }

    /**
     * True when a length-prefixed block plausibly starts at {@code off}: a length that fits, and
     * efficiency bytes in the range PowerPacker writes.
     */
    public static boolean isBlock(byte[] d, int off) {
        if (off < 0 || off + 12 > d.length) {
            return false;
        }
        long len = u32(d, off) & 0xffffffffL;
        if (len < 12 || off + len > d.length) {
            return false;
        }
        for (int i = 4; i < 8; i++) {
            int e = d[off + i] & 0xff;
            if (e < 9 || e > 15) {
                return false;
            }
        }
        return true;
    }

    /** Unpacks a whole {@code PP20} file. */
    public static byte[] file(byte[] d) throws IOException {
        if (!isFile(d)) {
            throw new IOException("not a PowerPacker file");
        }
        return decode(d, 4, d.length);
    }

    /** Unpacks a length-prefixed block starting at {@code off}. */
    public static byte[] block(byte[] d, int off) throws IOException {
        if (!isBlock(d, off)) {
            throw new IOException("no PowerPacker block at " + off);
        }
        return decode(d, off + 4, off + u32(d, off));
    }

    /**
     * @param eff offset of the four efficiency bytes, the packed bits follow them
     * @param end one past the trailing length word
     */
    private static byte[] decode(byte[] src, int eff, int end) throws IOException {
        int trailer = u32(src, end - 4);
        int outLen = trailer >>> 8;
        int skip = trailer & 0xff;
        byte[] out = new byte[outLen];
        Bits in = new Bits(src, eff + 4, end - 4);
        try {
            in.read(skip);
            int o = outLen;
            while (o > 0) {
                if (in.read(1) == 0) {
                    int run = 1;
                    int x;
                    do {
                        x = in.read(2);
                        run += x;
                    } while (x == 3);
                    for (int i = 0; i < run; i++) {
                        out[--o] = (byte) in.read(8);
                    }
                    if (o == 0) {
                        break;
                    }
                }
                int x = in.read(2);
                int bits = src[eff + x] & 0xff;
                int run = x + 2;
                int offset;
                if (x == 3) {
                    if (in.read(1) == 0) {
                        bits = 7;
                    }
                    offset = in.read(bits);
                    int y;
                    do {
                        y = in.read(3);
                        run += y;
                    } while (y == 7);
                } else {
                    offset = in.read(bits);
                }
                for (int i = 0; i < run; i++) {
                    out[o - 1] = out[o + offset];
                    o--;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IOException("corrupt PowerPacker data", e);
        }
        return out;
    }

    /** Reads bits backwards from the end of the stream, low bit of each byte first. */
    private static final class Bits {
        private final byte[] d;
        private final int start;
        private int pos;
        private int buf;
        private int left;

        Bits(byte[] d, int start, int end) {
            this.d = d;
            this.start = start;
            this.pos = end;
        }

        int read(int n) throws IOException {
            int x = 0;
            for (int i = 0; i < n; i++) {
                if (left == 0) {
                    if (pos <= start) {
                        throw new IOException("PowerPacker stream ran out");
                    }
                    buf = d[--pos] & 0xff;
                    left = 8;
                }
                x = (x << 1) | (buf & 1);
                buf >>= 1;
                left--;
            }
            return x;
        }
    }

    static int u32(byte[] d, int off) {
        return ((d[off] & 0xff) << 24) | ((d[off + 1] & 0xff) << 16)
                | ((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff);
    }
}
