package citadel.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The in-game messages, from Store/TEXT.ENG, TEXT.POL or TEXT.GER.
 *
 * The file is plain text split by '@', one block per message, numbered from zero in the order
 * they appear. That numbering is what the SCROLL macro takes: SCROLL 40 is the message for
 * walking into a shut door, 53 for pressing a switch, 65 for an empty weapon, and so on.
 *
 * A block whose first line starts with '^' holds several interchangeable lines and the engine
 * picks one at random, which is why bumping into walls insults you differently every time.
 * Anything after a ';' on a line is an author's note and is dropped.
 *
 * Indices verified against the source at 40 (shut door), 41 (paused), 44 (column), 52 (door
 * moving), 53 (switch), 54 (switch stuck), 65 (out of ammo) and 76 (weapon damaged).
 */
public final class TextTable {

    // Message numbers used by the port, each taken from its SCROLL call site.
    public static final int BUMP_DOOR = 40;          // br_BW2
    public static final int PAUSED = 41;             // MAIN_LOOP
    public static final int BUMP_WALL = 42;          // br_BW3
    public static final int DOOR_MOVING = 43;        // br_BW, door still animating
    public static final int SEE_COLUMN = 44;         // rm_Column2
    public static final int SEE_WALL = 47;           // rm_NoBlood
    public static final int SEE_NOTHING = 49;        // rm_Nothing
    public static final int DOOR_SHUT = 50;          // rm_ClosedDoor
    public static final int DOOR_OPEN = 51;          // rm_OpenedDoor1
    public static final int DOOR_ANIMATING = 52;     // rm_AnimDoor1
    public static final int SWITCH_USED = 53;        // rm_DoUse
    public static final int SWITCH_STUCK = 54;       // .rm_CantUse
    public static final int SLOT_FULL = 58;          // rm_UsedSlot
    public static final int WRONG_COLOUR = 59;       // rm_BadColor
    public static final int NO_CARD = 61;            // rm_CardUsed
    public static final int OUT_OF_AMMO = 65;        // rm_HandGunUsed and friends
    public static final int WEAPON_DAMAGED = 76;     // the DamageWeapon failure path

    /** Pickup messages are SCROLL1 with a computed index; these are the bases Take_Items uses. */
    public static final int TAKE_WEAPON_BASE = 2;    // + weapon slot - 1
    public static final int TAKE_AMMO_BASE = 8;      // + weapon slot - 1
    public static final int TAKE_CARD_BASE = 14;     // + colour
    public static final int TAKE_MEDIKIT = 17;
    public static final int TAKE_POWER = 18;
    public static final int TAKE_VODKA = 19;

    private final List<String[]> messages = new ArrayList<String[]>();
    private final Random rnd = new Random(0x7E787AB1EL);

    private TextTable() {
    }

    public int size() {
        return messages.size();
    }

    public static TextTable load(File f) throws IOException {
        String raw = new String(Io.readAll(f), "ISO-8859-1");
        TextTable t = new TextTable();
        // The file opens with '@', so the first split piece is empty and is not a message.
        String[] blocks = raw.split("@", -1);
        for (int i = 1; i < blocks.length; i++) {
            t.messages.add(parseBlock(blocks[i]));
        }
        return t;
    }

    /** Finds the language file next to the maps, preferring English then Polish then German. */
    public static TextTable loadFrom(File dataRoot) throws IOException {
        for (String name : new String[]{"TEXT.ENG", "TEXT.POL", "TEXT.GER"}) {
            File f = new File(new File(dataRoot, "GAME/Store"), name);
            if (f.isFile()) {
                return load(f);
            }
        }
        throw new IOException("no TEXT.* found under " + dataRoot);
    }

    private static String[] parseBlock(String block) {
        List<String> lines = new ArrayList<String>();
        for (String line : block.split("\r?\n")) {
            // '^' marks the block as having interchangeable variants; strip the marker
            String s = line.replace("^", "");
            int note = s.indexOf(';');
            if (note >= 0) {
                s = s.substring(0, note);
            }
            s = s.trim();
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines.toArray(new String[0]);
    }

    /** One message, picking at random between variants the way the engine does. */
    public String get(int index) {
        if (index < 0 || index >= messages.size()) {
            return "";
        }
        String[] variants = messages.get(index);
        return variants[variants.length == 1 ? 0 : rnd.nextInt(variants.length)];
    }

    /** All variants of a message, for tests and for dumping. */
    public String[] variants(int index) {
        if (index < 0 || index >= messages.size()) {
            return new String[0];
        }
        return messages.get(index).clone();
    }
}
