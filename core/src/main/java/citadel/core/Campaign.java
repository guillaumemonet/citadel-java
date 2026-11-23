package citadel.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The order the levels are played in, from the ETAPY table in the server
 * (Code/SERVER/SERVER_032_HD_ENG.ss):
 *
 * <pre>
 *   ETAPY: dc.b "AB",2,0     ;1   0 - to next level, 1 - end!
 *          dc.b "ABC",0      ;2
 *          dc.b "ABC",0      ;3 .. 7
 *          dc.b "A",1,0,0    ;8
 * </pre>
 *
 * One entry per complex, listing its sub-levels by letter. A byte of 0 ends the complex and moves
 * on to the next; a byte of 1 ends the game. The stray 2 after complex 1 is not a letter either,
 * so it is treated the same as the 0 terminator.
 *
 * Maps are named LEVEL{nn}{letter}.MAP, so complex 3 sub-level B is LEVEL03B.MAP. Several extra
 * maps ship alongside those (LEVEL01T, LEVEL03K, LEVEL05D and so on) which the campaign never
 * visits; they are playable on their own but are not part of the sequence.
 */
public final class Campaign {

    /** Sub-level letters per complex, transcribed from ETAPY. */
    private static final String[] COMPLEX_LEVELS = {
            "AB",   // 1
            "ABC",  // 2
            "ABC",  // 3
            "ABC",  // 4
            "ABC",  // 5
            "ABC",  // 6
            "ABC",  // 7
            "A",    // 8, then the game ends
    };

    public static final class Stage {
        public final int complex;
        public final char letter;

        Stage(int complex, char letter) {
            this.complex = complex;
            this.letter = letter;
        }

        public String mapName() {
            return String.format("LEVEL%02d%c.MAP", complex, letter);
        }

        @Override
        public String toString() {
            return mapName();
        }
    }

    private final List<Stage> stages = new ArrayList<Stage>();

    public Campaign() {
        for (int c = 0; c < COMPLEX_LEVELS.length; c++) {
            for (char letter : COMPLEX_LEVELS[c].toCharArray()) {
                stages.add(new Stage(c + 1, letter));
            }
        }
    }

    public int size() {
        return stages.size();
    }

    public Stage stage(int index) {
        return stages.get(index);
    }

    public List<Stage> stages() {
        return new ArrayList<Stage>(stages);
    }

    /** Index of a map in the sequence, or -1 when it is one of the extras. */
    public int indexOf(String mapName) {
        String want = mapName.toUpperCase();
        if (!want.endsWith(".MAP")) {
            want = want + ".MAP";
        }
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).mapName().equals(want)) {
                return i;
            }
        }
        return -1;
    }

    /** The stage after this one, or null at the end of the game. */
    public Stage next(String mapName) {
        int i = indexOf(mapName);
        if (i < 0 || i + 1 >= stages.size()) {
            return null;
        }
        return stages.get(i + 1);
    }

    /** ETAPY itself, four bytes a complex: letters, then 0 (next complex), 1 (the end) or 2. */
    private static final byte[] ETAPY = {
        'A', 'B', 2, 0,
        'A', 'B', 'C', 0,
        'A', 'B', 'C', 0,
        'A', 'B', 'C', 0,
        'A', 'B', 'C', 0,
        'A', 'B', 'C', 0,
        'A', 'B', 'C', 0,
        'A', 1, 0, 0,
    };

    /** What LevelLoop decides to do next. */
    public enum Next { PLAY, MAP, END }

    /** LevelLoop's decision, and the map when it is to play one. */
    public record Step(Next next, String mapName) {
    }

    /**
     * LevelLoop, run on STRUCTURE: {@code addi #1,62(a2)} moves on a level, and the ETAPY byte
     * there says what that means. A letter is a map to play; 0 sends the player to MAPA to
     * choose the next complex; 1 is the end of the game (END_PART2); the 2 after complex 1 runs
     * the protection check ({@code SprawdzZab}) and then goes to MAPA as 0 does. When the byte
     * after a letter is 1, the level is the last one and STRUCTURE+92 gets "KANE".
     *
     * Since a save is taken after a level, with +62 counting it, loading a slot goes through
     * here too: the level after the one saved, or MAPA when that was the last of its complex.
     */
    public static Step levelLoop(Structure s) {
        int complex = Math.max(1, Math.min(8, s.complex()));
        s.setLevel(s.level() + 1);
        int at = (complex - 1) * 4 + s.level() - 1;
        int code = (at >= 0 && at < ETAPY.length) ? ETAPY[at] : 0;
        if (code == 0 || code == 2) {
            return new Step(Next.MAP, null);
        }
        if (code == 1) {
            return new Step(Next.END, null);
        }
        if (at + 1 < ETAPY.length && ETAPY[at + 1] == 1) {
            s.markLastLevel();
        }
        return new Step(Next.PLAY, String.format("LEVEL%02d%c.MAP", complex, (char) code));
    }

    /** Resolves a stage against a maps directory, or null when the file is absent. */
    public File file(File mapsDir, Stage stage) {
        File f = new File(mapsDir, stage.mapName());
        return f.isFile() ? f : null;
    }
}
