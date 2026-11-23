package citadel.core.disk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An 880 KB Amiga floppy image.
 *
 * Citadel's disks mix two layouts. The first and the last are AmigaDOS volumes, read here through
 * the root directory; the three in between have no file system at all, SERVER reads them a track
 * at a time with its own loader ({@code dl_START_s}: start sector, sector count), and so does
 * {@link #sectors}.
 *
 * Only what the game needs from AmigaDOS is here: files in the root directory, OFS or FFS.
 */
public final class Adf {

    public static final int SECTOR = 512;
    public static final int SECTORS = 1760;
    public static final int SIZE = SECTOR * SECTORS;
    /** Sectors in one cylinder, both heads, which is the unit SERVER and the installer count in. */
    public static final int CYLINDER = 22;

    private static final int ROOT = SECTORS / 2;
    private static final int HASH_SIZE = 72;
    private static final int T_DATA = 8;
    private static final int ST_FILE = -3;

    private final String name;
    private final byte[] image;

    public Adf(String name, byte[] image) throws IOException {
        if (image.length != SIZE) {
            throw new IOException(name + ": " + image.length + " bytes is not an 880 KB disk");
        }
        this.name = name;
        this.image = image;
    }

    public static Adf load(Path p) throws IOException {
        return new Adf(p.getFileName().toString(), Files.readAllBytes(p));
    }

    public String name() {
        return name;
    }

    /** The first four bytes of the boot block: "DOS" and a flag byte, or Citadel's "CYTn". */
    public String bootId() {
        return new String(image, 0, 4, StandardCharsets.ISO_8859_1);
    }

    /** {@code count} sectors starting at {@code first}, as the track loader would read them. */
    public byte[] sectors(int first, int count) {
        byte[] out = new byte[count * SECTOR];
        System.arraycopy(image, first * SECTOR, out, 0, out.length);
        return out;
    }

    /** The whole image, which the track-loaded blocks are addressed in. */
    public byte[] image() {
        return image;
    }

    /** True when the root block is a valid AmigaDOS root. */
    public boolean hasFileSystem() {
        return u32(ROOT, 0) == 2 && u32(ROOT, 127) == 1;
    }

    /** Names of the files in the root directory. */
    public List<String> list() {
        List<String> out = new ArrayList<String>();
        if (!hasFileSystem()) {
            return out;
        }
        for (int i = 0; i < HASH_SIZE; i++) {
            for (int b = u32(ROOT, 6 + i); b != 0; b = u32(b, 124)) {
                if (u32(b, 127) == ST_FILE) {
                    out.add(entryName(b));
                }
            }
        }
        return out;
    }

    /** A file in the root directory, or null when there is none of that name. */
    public byte[] file(String fileName) throws IOException {
        if (!hasFileSystem()) {
            return null;
        }
        for (int b = u32(ROOT, 6 + hash(fileName)); b != 0; b = u32(b, 124)) {
            if (entryName(b).equalsIgnoreCase(fileName)) {
                if (u32(b, 127) != ST_FILE) {
                    return null;
                }
                return read(b);
            }
        }
        return null;
    }

    private byte[] read(int header) throws IOException {
        int size = u32(header, 81);
        byte[] out = new byte[size];
        int at = 0;
        // The data block pointers run backwards from long 77, in the header and then in each
        // extension block. OFS data blocks carry a 24 byte header of their own, FFS ones do not.
        for (int h = header; h != 0 && at < size; h = u32(h, 126)) {
            int count = Math.min(u32(h, 2), HASH_SIZE);
            for (int k = 0; k < count && at < size; k++) {
                int b = u32(h, 77 - k);
                checkBlock(b);
                boolean ofs = u32(b, 0) == T_DATA && u32(b, 1) == header;
                int off = ofs ? 24 : 0;
                int n = ofs ? u32(b, 3) : SECTOR;
                n = Math.min(n, size - at);
                System.arraycopy(image, b * SECTOR + off, out, at, n);
                at += n;
            }
        }
        if (at < size) {
            throw new IOException(name + ": file ends after " + at + " of " + size + " bytes");
        }
        return out;
    }

    private String entryName(int block) {
        int len = image[block * SECTOR + 432] & 0xff;
        return new String(image, block * SECTOR + 433, Math.min(len, 30),
                StandardCharsets.ISO_8859_1);
    }

    /** The AmigaDOS name hash, which picks the root table slot a name chains from. */
    static int hash(String n) {
        int h = n.length();
        for (int i = 0; i < n.length(); i++) {
            h = (h * 13 + n.toUpperCase(Locale.ROOT).charAt(i)) & 0x7ff;
        }
        return h % HASH_SIZE;
    }

    private void checkBlock(int b) throws IOException {
        if (b <= 0 || b >= SECTORS) {
            throw new IOException(name + ": block " + b + " is off the disk");
        }
    }

    /** Long word {@code index} of block {@code block}. */
    private int u32(int block, int index) {
        return PowerPacker.u32(image, block * SECTOR + index * 4);
    }
}
