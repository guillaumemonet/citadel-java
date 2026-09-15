package citadel.core;

/**
 * CheckCodes: the seven cheats, and the sixteen byte ring the engine watches for them.
 *
 * The codes are stored obfuscated, as {@code dc.b -$b1,-$95,...}, and getting a plaintext out of
 * them takes two steps. cco_check does {@code neg.b d3} to undo the negation, and what it then
 * compares against is not ASCII: cc_NoKey saves the byte the CIA handed over, untouched. The
 * Amiga keyboard sends a key code rotated left one and inverted, so
 *
 * <pre>
 *   stored = -( ~ROL(rawkey, 1) )
 * </pre>
 *
 * and undoing that on {@code AmmoCode} gives H O T K I S S. Three of the labels in version 1.30
 * still carry the author's own comments — {@code ;kiss}, {@code ;kicaj}, {@code ;kotek} — and the
 * codes there decode to exactly KISS, KICAJ and KOTEK, which is what confirms the scheme. By 1.34
 * the codes had been changed and the comments had not, so they are stale and misleading.
 */
public final class Cheats {

    /** What a code does. */
    public enum Cheat {
        /** sv_Energy to -666, and "POWER IS YOURS!". */
        ENERGY("KBBMORMS", 66),
        /** Every weapon, every round, every card, and "GIFT FROM HELL!". */
        ARSENAL("HOTKISS", 67),
        /** A 1024 unit hop along the view vector, walls included. Silent. */
        HOP("IDAHO", -1),
        /** Toggles sv_NieUmieraj: energy stops draining. */
        IMMORTAL("HOTKIWI", 66),
        /** Fills in the whole automap, and "NOW YOU CAN SEE THE TRUTH!". */
        MAP("LORIEN", 75),
        /** Ends the level as if the exit had been reached. */
        EXIT("KITIARA", -1),
        /** All six warhead sections at once. */
        BOMB("ALIBABA", -1);

        public final String code;
        /** SCROLL index the engine prints, or -1 when it prints nothing. */
        public final int message;

        Cheat(String code, int message) {
            this.code = code;
            this.message = message;
        }
    }

    /** {@code andi #15,d0}: the ring holds the last sixteen keystrokes. */
    public static final int BUFFER = 16;

    private final char[] ring = new char[BUFFER];
    private int at;

    /**
     * Records one key and reports the cheat it completed, if any.
     *
     * Anything that is not a letter still goes in, exactly as it does in the engine, which stores
     * every key press without looking at it.
     */
    public Cheat type(char c) {
        ring[at] = Character.toUpperCase(c);
        at = (at + 1) % BUFFER;
        for (Cheat cheat : Cheat.values()) {
            if (matches(cheat.code)) {
                java.util.Arrays.fill(ring, '\0');
                return cheat;
            }
        }
        return null;
    }

    /**
     * cco_check walks the ring from each of its sixteen positions in turn, wrapping, so a code
     * counts as typed wherever it sits in the last sixteen keys.
     */
    private boolean matches(String code) {
        for (int start = 0; start < BUFFER; start++) {
            int i = 0;
            while (i < code.length() && ring[(start + i) % BUFFER] == code.charAt(i)) {
                i++;
            }
            if (i == code.length()) {
                return true;
            }
        }
        return false;
    }
}
