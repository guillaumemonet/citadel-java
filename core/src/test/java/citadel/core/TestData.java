package citadel.core;

import java.io.File;

/**
 * Where the original game data lives. It is not part of this repository: Gradle passes the
 * {@code citadel.data} property, and the fallback is a sibling Amiga-Citadel checkout.
 */
final class TestData {

    private TestData() {
    }

    /** The original {@code Data} folder; tests assume it away when it is missing. */
    static File root() {
        String v = System.getProperty("citadel.data");
        return new File(v != null ? v : "../../Amiga-Citadel/Data");
    }

    static File maps() {
        return new File(root(), "GAME/MAPS");
    }
}
