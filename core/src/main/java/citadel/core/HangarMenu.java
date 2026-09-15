package citadel.core;

/**
 * The server's own menus, transcribed from the text blocks in SERVER_032_HD_ENG.ss.
 *
 * Each screen is six lines of sixteen characters with a byte in front of it. DrukTekst reads that
 * byte and {@code sne d5} turns it into the choice between two glyph routines, the second of which
 * rolls each byte four bits and writes two, so the line comes out at double width. Whatever the
 * intent, the flag rides along here.
 *
 * The cursor is a dot either side of the line — PostawKropa writes an eight pixel circle at
 * {@code iff_screen+[63*40*6]+11} stepped by {@code kropa * 40*15*6}, so the lines are fifteen
 * rows apart from row 63, and the dots sit at bytes 11 and 28.
 *
 * Only the rules live here; drawing is the front end's business.
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

    /** Which screen is showing. */
    public enum Screen { MAIN, OPTIONS, TRAINING }

    /** What picking a line asked for. */
    public enum Action { NONE, START, LOAD, OPTIONS, TRAINING, INFO, BACK, TRAIN_LEVEL, DIFFICULTY }

    private static final String[] MAIN = {
        "     START      ",
        "   LOAD GAME    ",
        "    OPTIONS     ",
        "    TRAINING    ",
        "                ",
        "      INFO      ",
    };

    private static final String[] TRAINING = {
        "    DUNGEONS    ",
        "    STORES 1    ",
        "    STORES 2    ",
        "   LABORATORY   ",
        "     SEWERS     ",
        "      MENU      ",
    };

    /**
     * FileStructure2 gives a map per training entry, and all five ship: M1T, M3T, M3K, M5T, M6T.
     * The complex numbers also name the menu: 1 is the dungeons, 3 the stores, 5 the laboratory
     * and 6 the sewers.
     */
    public static final String[] TRAINING_MAPS = {
        "LEVEL01T.MAP", "LEVEL03T.MAP", "LEVEL03K.MAP", "LEVEL05T.MAP", "LEVEL06T.MAP",
    };

    /** Lines the flag byte marks, which DrukTekst draws at double width. */
    private static final boolean[] MAIN_WIDE = {true, true, true, false, false, false};

    private Screen screen = Screen.MAIN;
    private int line;
    /**
     * sv_difficult, which UstawOpcje toggles with {@code eori #1,50(a1)}. The engine reads it in
     * exactly one place: br_BumpWall skips the energy loss when it is set.
     */
    private boolean easy;

    public Screen screen() {
        return screen;
    }

    public int line() {
        return line;
    }

    public boolean easy() {
        return easy;
    }

    /** The six lines of the screen showing, already padded to sixteen characters. */
    public String[] lines() {
        switch (screen) {
            case TRAINING:
                return TRAINING.clone();
            case OPTIONS:
                return options();
            default:
                return MAIN.clone();
        }
    }

    /** True where the screen's flag byte is set. */
    public boolean isWide(int index) {
        return screen == Screen.MAIN && index >= 0 && index < MAIN_WIDE.length
                && MAIN_WIDE[index];
    }

    /**
     * The original's options are the renderer's: screen size, detail level, floors on or off.
     * None of those mean anything to a port that draws with a GPU, so only the one the engine
     * itself reads is kept, and the interference toggle the Z key already has.
     */
    private String[] options() {
        return new String[]{
            pad("DIFFICULTY " + (easy ? "EASY" : "HARD")),
            pad(""),
            pad(""),
            pad(""),
            pad(""),
            "      MENU      ",
        };
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < COLUMNS) {
            sb.insert(0, ' ');
            if (sb.length() < COLUMNS) {
                sb.append(' ');
            }
        }
        return sb.substring(0, COLUMNS);
    }

    /** Moves the cursor, wrapping, as ma_odejmij and ma_dodaj do on the map. */
    public void move(int by) {
        line = ((line + by) % LINES + LINES) % LINES;
    }

    public void open(Screen s) {
        screen = s;
        line = 0;
    }

    /** What the current line does. Blank lines do nothing, as the original's do. */
    public Action select() {
        if (screen == Screen.TRAINING) {
            return (line == 5) ? Action.BACK : Action.TRAIN_LEVEL;
        }
        if (screen == Screen.OPTIONS) {
            if (line == 5) {
                return Action.BACK;
            }
            if (line == 0) {
                easy = !easy;
                return Action.DIFFICULTY;
            }
            return Action.NONE;
        }
        switch (line) {
            case 0: return Action.START;
            case 1: return Action.LOAD;
            case 2: return Action.OPTIONS;
            case 3: return Action.TRAINING;
            case 5: return Action.INFO;
            default: return Action.NONE;
        }
    }
}
