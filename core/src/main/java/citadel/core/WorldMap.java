package citadel.core;

/**
 * MAPA: the screen between complexes, where the next one is chosen off a map of the citadel.
 *
 * Seven targets, and the order is not fixed. `Kolejnosc` lists the complexes in the order they are
 * meant to fall, and Printnij walks it skipping the ones already taken; the first three still
 * standing are the ones that can be entered. So there is always a choice of up to three.
 *
 * <pre>
 *   moveq #3,d3
 *   lea   Kolejnosc,a1
 * ma_p2: move (a1)+,d2
 *   tst.b 64-1(a2,d2.w)      ;already done? then it does not use up the budget
 *   bne.s ma_p2
 *   cmp   d1,d2
 *   beq.s ma_p1              ;this is the one asked about, so it is open
 *   subi  #1,d3
 *   bne.s ma_p2
 *   ;fell through: no access
 * </pre>
 *
 * The centre is the exception: ma_wybrany wants all seven complexes behind you and the warhead
 * complete before it will let you in.
 */
public final class WorldMap {

    /** {@code cmpi #7,wybor}, so the choice wraps at seven. */
    public static final int TARGETS = 7;
    /** The index of the citadel itself, which comes last. */
    public static final int CENTRE = 6;

    /** KompNames, eleven characters each. */
    public static final String[] NAMES = {
        "PRISON", "HANGAR", "POWER PLANT", "STORES", "LABORATORY", "SEWERS", "CENTER",
    };

    /** Kon2Tab: which complex each target is. */
    public static final int[] COMPLEX = {7, 4, 2, 3, 5, 6, 8};

    /**
     * Kon1Tab, the other way round: which target the cursor starts on once a complex has fallen,
     * indexed by that complex less one. Complex 1 is not on the map at all - it is where the game
     * begins - so its entry points at the centre like complex 8's does.
     */
    public static final int[] CURSOR_AFTER = {6, 2, 3, 1, 4, 5, 0, 6};

    /** Kolejnosc: the order the complexes are meant to fall in. */
    public static final int[] ORDER = {2, 5, 6, 3, 4, 7, 8};

    /** {@code moveq #3,d3}: how many of the remaining complexes are open at once. */
    public static final int OPEN_AT_ONCE = 3;

    /**
     * ArrowPos, as {@code {x, row}} on the 320 by 192 map. The stored longwords are offsets into
     * a six plane picture, so {@code [row*40*6] + byte} unpacks to a row and a byte column.
     */
    public static final int[][] ARROW = {
        {32 * 8, 119}, {34 * 8, 58}, {26 * 8, 2}, {10 * 8, 15},
        {2 * 8, 32}, {6 * 8, 93}, {20 * 8, 60},
    };

    /** Asinus: the arrow bobs through these heights, one step a VBLANK frame. */
    public static final int[] BOB = {
        0, 1, 2, 3, 4, 5, 5, 6, 7, 7, 8, 8, 8, 9, 9, 9,
        9, 9, 9, 8, 8, 8, 7, 7, 6, 5, 5, 4, 3, 2, 1, 0,
    };

    /** The three lines Printnij can put in the status field, from KompNames' tail. */
    public enum Status { UNEXPLORED, EXPLORED, NO_ACCESS }

    public static final String[] STATUS_NAMES = {"UNEXPLORED", "EXPLORED", "NO ACCESS"};

    /** What picking a target did. */
    public enum Pick { CHOSEN, ALREADY_EXPLORED, INACCESSIBLE, NEED_THE_BOMB }

    /** ZBADtext, NIEDtext and CENTtext, printed when a pick is refused. */
    public static final String EXPLORED_TEXT = "THIS COMPLEX HAS ALREADY BEEN EXPLORED";
    public static final String NO_ACCESS_TEXT = " THIS COMPLEX IS STILL INACCESSIBLE!  ";
    public static final String NEED_BOMB_TEXT = "YOU MUST HAVE THE WHOLE BOMB TO ENTER!";

    /** STRUCTURE+64 onward, one byte a complex, indexed from 1. */
    private final boolean[] done = new boolean[9];
    private int choice;

    public int choice() {
        return choice;
    }

    /** ma_odejmij and ma_dodaj, which wrap at both ends. */
    public void move(int by) {
        choice = ((choice + by) % TARGETS + TARGETS) % TARGETS;
    }

    public void setChoice(int target) {
        choice = ((target % TARGETS) + TARGETS) % TARGETS;
    }

    /** Marks a complex taken, as MAPA does on the way in with {@code move.b #1,64(a1,d0.w)}. */
    public void markDone(int complex) {
        if (complex >= 1 && complex < done.length) {
            done[complex] = true;
        }
    }

    public boolean isDone(int complex) {
        return complex >= 1 && complex < done.length && done[complex];
    }

    /** STRUCTURE+98, which MAPA counts up and the centre checks for seven. */
    public int complexesTaken() {
        int n = 0;
        for (int c = 1; c < done.length; c++) {
            if (done[c]) {
                n++;
            }
        }
        return n;
    }

    public Status statusOf(int target) {
        int complex = COMPLEX[target];
        if (isDone(complex)) {
            return Status.EXPLORED;
        }
        int budget = OPEN_AT_ONCE;
        for (int c : ORDER) {
            if (isDone(c)) {
                continue;           // taken already, so it does not use up the budget
            }
            if (c == complex) {
                return Status.UNEXPLORED;
            }
            if (--budget == 0) {
                break;
            }
        }
        return Status.NO_ACCESS;
    }

    /**
     * ma_fire. {@code warhead} is sv_Glowica, which has to be at -12 for the centre.
     */
    public Pick pick(int warhead) {
        Status status = statusOf(choice);
        if (status == Status.EXPLORED) {
            return Pick.ALREADY_EXPLORED;
        }
        if (status == Status.NO_ACCESS) {
            return Pick.INACCESSIBLE;
        }
        if (choice == CENTRE && (complexesTaken() != TARGETS || warhead != -12)) {
            return Pick.NEED_THE_BOMB;
        }
        return Pick.CHOSEN;
    }

    /** The complex the current choice starts. */
    public int chosenComplex() {
        return COMPLEX[choice];
    }

    /** KomplexTexts with the two fields filled in, forty characters wide. */
    public String statusLine() {
        StringBuilder sb = new StringBuilder(" COMPLEX:            STATUS:            ");
        // ma_CopIt writes eleven characters at offset 9, then eleven more at 28
        put(sb, 10, NAMES[choice]);
        put(sb, 29, STATUS_NAMES[statusOf(choice).ordinal()]);
        return sb.toString();
    }

    private static void put(StringBuilder sb, int at, String text) {
        for (int i = 0; i < text.length() && at + i < sb.length(); i++) {
            sb.setCharAt(at + i, text.charAt(i));
        }
    }
}
