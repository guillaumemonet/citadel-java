package citadel.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Small file helpers, kept dependency free so the tool builds with a bare JDK. */
public final class Io {

    private Io() {
    }

    public static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, (int) f.length()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    public static void write(File f, byte[] data) throws IOException {
        mkdirs(f.getParentFile());
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    public static void writeText(File f, String text) throws IOException {
        write(f, text.getBytes("UTF-8"));
    }

    public static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create directory " + dir);
        }
    }

    /** Unsigned big endian word, the 68000 native order used by every Citadel data file. */
    public static int u16(byte[] d, int off) {
        return ((d[off] & 0xff) << 8) | (d[off + 1] & 0xff);
    }

    /** Signed big endian word. */
    public static int s16(byte[] d, int off) {
        return (short) u16(d, off);
    }

    public static int u8(byte[] d, int off) {
        return d[off] & 0xff;
    }
}
