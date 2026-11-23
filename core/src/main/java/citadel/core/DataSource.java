package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * Where the original game files come from, addressed by their path in the Amiga-Citadel
 * {@code Data} folder, for instance {@code GAME/MAPS/LEVEL01A.MAP}.
 *
 * The game reads them from the original floppy images, {@link citadel.core.disk.GameDisks};
 * the tools and the tests can also read a {@code Data} checkout with {@link #directory}.
 */
public interface DataSource {

    /** The file's bytes, unpacked. */
    byte[] read(String path) throws IOException;

    /** True when {@link #read} would find the file. */
    boolean has(String path);

    /** The Amiga-Citadel {@code Data} folder, or {@code Data/GAME}'s parent. */
    static DataSource directory(File root) {
        return new DataSource() {
            @Override
            public byte[] read(String path) throws IOException {
                return Io.readAll(new File(root, path));
            }

            @Override
            public boolean has(String path) {
                return new File(root, path).isFile();
            }

            @Override
            public String toString() {
                return root.getPath();
            }
        };
    }
}
