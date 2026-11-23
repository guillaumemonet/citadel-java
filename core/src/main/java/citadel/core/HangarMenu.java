package citadel.core;

/**
 * The server's own menus, transcribed from the text blocks in SERVER_032_HD_ENG.ss.
 *
 * Each screen is six lines of sixteen characters with a flag byte in front. DrukTekst turns the
 * flag into {@code sne d5} and picks between two glyph routines: {@code m_CopFont} writes the
 * glyph's byte where it is, {@code m_CopFont2} rotates it four bits and writes it across two
 * bytes, which draws the same glyph four pixels further right. The flagged lines are the ones
 * whose text has an odd number of padding spaces, so the shift centres them.
 *
 * The cursor is {@code Kropa}, a dot either side of the line: PostawKropa writes an eight pixel
 * circle at {@code iff_screen+[63*40*6]+11} stepped by {@code kropa * 40*15*6}, so the lines are
 * fifteen rows apart from row 63, and the dots sit at bytes 11 and 28.
 *
 * Only the rules live here; drawing is the front end's business. The options are the ones in
 * STRUCTURE (+0 size, +2 floors, +4 details, +50 difficulty), which is where UstawOpcje keeps
 * them; the engine here only reads the difficulty.
 */
public final class HangarMenu {

    /** {@code moveq #5,d7}: six lines. */
    public static final int LINES = 6;
    /** {@code moveq #15,d6}: sixteen characters. */
    public static final int COLUMNS = 16;

    /** DrukTekst puts the block at {@code SERscr+8*16*6}, which is eight rows into the panel. */
    public static final int TEXT_ROW = 61;
    /** {@code lea 15*16*6(a5),a5}. */
    public static final int LINE_STEP = 15;
    /** SERscr starts at the panel's own left edge. */
    public static final int TEXT_X = 96;
    /** PostawKropa: {@code iff_screen+[63*40*6]+11}, and the second dot 17 bytes further on. */
    public static final int CURSOR_ROW = 63;
    public static final int CURSOR_LEFT_X = 11 * 8;
    public static final int CURSOR_RIGHT_X = 28 * 8;
    /** KropaDat, an eight by eight circle. */
    public static final int CURSOR_SIZE = 8;
    /** m_CopFont2's {@code ror #4}: a flagged line is drawn four pixels to the right. */
    public static final int FLAG_SHIFT = 4;

    /** Which screen is showing. */
    public enum Screen { MAIN, LOAD, OPTIONS, TRAINING }

    /** What picking a line asked for. */
    public enum Action {
        NONE, START, LOAD, OPTIONS, TRAINING, INFO, BACK, TRAIN_LEVEL, DIFFICULTY, OPTION, LOAD_SLOT
    }

    /** s_scrMain1. */
    private static final String[] MAIN = {
        "     START      ",
        "   LOAD GAME    ",
        "    OPTIONS     ",
        "    TRAINING    ",
        "                ",
        "      INFO      ",
    };
    private static final boolean[] MAIN_FLAGS = {true, true, true, false, false, false};

    /** s_scrTRENING. */
    private static final String[] TRAINING = {
        "    DUNGEONS    ",
        "    STORES 1    ",
        "    STORES 2    ",
        "   LABORATORY   ",
        "     SEWERS     ",
        "      MENU      ",
    };

    /** s_scrOPCJE, before u_Poustawiaj writes the values in; the typo is the original's. */
    private static final String[] OPTIONS = {
        "DIFFICULTY HARD ",
        " SCREEEN SIZE 1 ",
        "DETAILS MAKSIMUM",
        "   FLOORS YES   ",
        "                ",
        "      MENU      ",
    };
    private static final boolean[] OPTIONS_FLAGS = {true, false, false, false, false, false};

    /** ut1..ut4, the values u_Poustawiaj prints. */
    private static final String[] DIFFICULTY = {"HARD", "EASY"};
    private static final String[] DETAILS = {"MAKSIMUM", "MEDIUM  ", "MINIMUM "};

    /**
     * FileStructure2 gives a map per training entry, and all five ship: M1T, M3T, M3K, M5T, M6T.
     * The complex numbers also name the menu: 1 is the dungeons, 3 the stores, 5 the laboratory
     * and 6 the sewers.
     */
    public static final String[] TRAINING_MAPS = {
        "LEVEL01T.MAP", "LEVEL03T.MAP", "LEVEL03K.MAP", "LEVEL05T.MAP", "LEVEL06T.MAP",
    };

    private Screen screen = Screen.MAIN;
    private int line;
    private Structure structure = Structure.fresh();
    /** s_scrLOAD: the five slot names WczytajStan copies in, then MENU. */
    private final String[] load = {
        "                ", "                ", "                ", "                ",
        "                ", "      MENU      ",
    };

    /** The STRUCTURE the options are read from and written to. */
    public void setStructure(Structure s) {
        structure = s;
    }

    public Screen screen() {
        return screen;
    }

    public int line() {
        return line;
    }

    /** sv_difficult, STRUCTURE+50. */
    public boolean easy() {
        return structure.easy();
    }

    /** MainServer: the main screen with the cursor on START. */
    public void reset() {
        screen = Screen.MAIN;
        line = 0;
    }

    /** The six lines of the screen showing, sixteen characters each. */
    public String[] lines() {
        switch (screen) {
            case TRAINING:
                return TRAINING.clone();
            case OPTIONS:
                return options();
            case LOAD:
                return load.clone();
            default:
                return MAIN.clone();
        }
    }

    /** True where the screen's flag byte is set, which shifts the line four pixels right. */
    public boolean isShifted(int index) {
        if (index < 0 || index >= LINES) {
            return false;
        }
        switch (screen) {
            case MAIN:
                return MAIN_FLAGS[index];
            case OPTIONS:
                return OPTIONS_FLAGS[index];
            default:
                return false;
        }
    }

    /** u_Poustawiaj: the four values written into s_scrOPCJE. */
    private String[] options() {
        String[] out = OPTIONS.clone();
        out[0] = put(out[0], 11, DIFFICULTY[easy() ? 1 : 0]);
        out[1] = put(out[1], 14, String.valueOf(Math.max(1, Math.min(5, structure.size() - 1))));
        out[2] = put(out[2], 8, DETAILS[Math.max(0, Math.min(2, structure.details()))]);
        out[3] = put(out[3], 10, structure.floors() != 0 ? "YES" : "NO ");
        return out;
    }

    private static String put(String line, int at, String s) {
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < s.length() && at + i < sb.length(); i++) {
            sb.setCharAt(at + i, s.charAt(i));
        }
        return sb.toString();
    }

    /** Moves the cursor, wrapping, as NewLev3 does with Kropa. */
    public void move(int by) {
        line = ((line + by) % LINES + LINES) % LINES;
    }

    /** Opens a screen with the cursor at the top, as Trening, WczytajStan and UstawOpcje do. */
    public void open(Screen s) {
        screen = s;
        line = 0;
    }

    /**
     * WczytajStan: blanks s_scrLOAD's five lines, then copies each slot's name up to its '@'.
     *
     * @param names the five names as Savegame.DAT holds them
     */
    public void openLoad(String[] names) {
        for (int i = 0; i < 5; i++) {
            String n = i < names.length && names[i] != null ? names[i] : "";
            StringBuilder sb = new StringBuilder(n.length() > COLUMNS ? n.substring(0, COLUMNS) : n);
            while (sb.length() < COLUMNS) {
                sb.append(' ');
            }
            load[i] = sb.toString();
        }
        open(Screen.LOAD);
    }

    /**
     * What the current line does. Leaving a screen puts the cursor back on the line that opened
     * it: {@code move #1,Kropa} from LOAD, {@code #2} from the options, {@code #3} from training.
     */
    public Action select() {
        switch (screen) {
            case TRAINING:
                if (line == 5) {
                    back(3);
                    return Action.BACK;
                }
                return Action.TRAIN_LEVEL;
            case LOAD:
                if (line == 5) {
                    back(1);
                    return Action.BACK;
                }
                return Action.LOAD_SLOT;
            case OPTIONS:
                switch (line) {
                    case 0:
                        structure.toggleDifficulty();
                        return Action.DIFFICULTY;
                    case 1: {
                        // addi #1,(a1) / cmpi #7 / move #2
                        int size = structure.size() + 1;
                        structure.put(Structure.OFF_SIZE, size == 7 ? 2 : size);
                        return Action.OPTION;
                    }
                    case 2: {
                        int details = structure.details() + 1;
                        structure.put(Structure.OFF_DETAILS, details == 3 ? 0 : details);
                        return Action.OPTION;
                    }
                    case 3:
                        structure.put(Structure.OFF_FLOOR, structure.floors() ^ 1);
                        return Action.OPTION;
                    case 5:
                        back(2);
                        return Action.BACK;
                    default:
                        return Action.NONE;
                }
            default:
                switch (line) {
                    case 0: return Action.START;
                    case 1: return Action.LOAD;
                    case 2: return Action.OPTIONS;
                    case 3: return Action.TRAINING;
                    case 5: return Action.INFO;
                    default: return Action.NONE;   // the blank line: cmpi #4,d0 / beq.s Main01
                }
        }
    }

    /** Back to the main screen with the cursor on a given line. */
    public void back(int cursor) {
        screen = Screen.MAIN;
        line = cursor;
    }
}
