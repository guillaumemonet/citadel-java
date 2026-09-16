package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * Read and write Store/Savegame.DAT in the original layout, so a save made here is the same
 * file the Amiga game reads.
 *
 * The file is 768 bytes: the current state in the first 128, then five slots of 128. Each block
 * is the engine's STRUCTURE, the record the server keeps at $7f800 and hands to the game between
 * levels. Slots carry a 16 byte name at +72, filled with "WOLNE@" — Polish for "free" — when
 * empty, '@' being the string terminator the text files use too.
 *
 * Offsets come from the load in the main engine, which reads size, floor, details, energy and
 * Glowica individually and then copies 18 words from +14 into sv_GUNS, and are cross-checked
 * against the shipped Savegame.DAT: -10, -5, -10, -5, -5, -1 land exactly on the six weapons'
 * ammunition-per-pickup fields.
 */
public final class SaveGame {

    public static final int BLOCK = 128;
    public static final int SLOT_COUNT = 5;
    public static final int FILE_SIZE = BLOCK * (1 + SLOT_COUNT);

    // Confirmed against the engine's own load and the shipped file.
    private static final int OFF_SIZE = 0;
    private static final int OFF_FLOOR = 2;
    private static final int OFF_DETAILS = 4;
    private static final int OFF_ENERGY = 6;
    private static final int OFF_WARHEAD = 8;       // sv_Glowica
    private static final int OFF_KILLED = 10;
    private static final int OFF_TIME = 12;
    /** 18 words copied straight into sv_GUNS: six weapons of (condition, ammoPerPickup, ammo). */
    private static final int OFF_GUNS = 14;
    private static final int OFF_DIFFICULTY = 50;
    /** Where the server records which stage to resume at; 1 and 0 in the shipped file. */
    private static final int OFF_COMPLEX = 60;
    private static final int OFF_LEVEL = 62;
    private static final int OFF_NAME = 72;
    private static final int NAME_LENGTH = 16;
    /**
     * MAPA's own fields. It marks a complex off with {@code move.b #1,64(a1,d0.w)} where d0 is the
     * complex less one, and counts them up in the word at 98 — which is what the centre checks for
     * seven before it will let you in.
     */
    private static final int OFF_DONE = 64;
    private static final int OFF_TAKEN = 98;

    private static final int OFF_TOTAL_KILLED = 90;

    /** Ammunition granted per pickup, the middle word of each sv_GUNS entry. */
    private static final int[] AMMO_PER_PICKUP = {-10, -5, -15, -5, -5, -1};

    public static final String EMPTY_NAME = "WOLNE";

    private final byte[] data;

    private SaveGame(byte[] data) {
        this.data = data;
    }

    public static SaveGame empty() {
        byte[] d = new byte[FILE_SIZE];
        SaveGame s = new SaveGame(d);
        for (int i = 0; i <= SLOT_COUNT; i++) {
            s.writeDefaults(i * BLOCK);
            if (i > 0) {
                s.putName(i * BLOCK, EMPTY_NAME);
            }
        }
        return s;
    }

    public static SaveGame load(File f) throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length < FILE_SIZE) {
            byte[] padded = new byte[FILE_SIZE];
            System.arraycopy(raw, 0, padded, 0, raw.length);
            raw = padded;
        }
        return new SaveGame(raw);
    }

    public void save(File f) throws IOException {
        Io.write(f, data);
    }

    private void writeDefaults(int base) {
        putWord(base + OFF_SIZE, 6);
        putWord(base + OFF_FLOOR, 1);
        putWord(base + OFF_ENERGY, -150);
        for (int w = 0; w < 6; w++) {
            putWord(base + OFF_GUNS + w * 6 + 2, AMMO_PER_PICKUP[w]);
        }
        putWord(base + OFF_COMPLEX, 1);
    }

    // ------------------------------------------------------------- accessors

    private int base(int slot) {
        return (slot + 1) * BLOCK;   // slot -1 is the live block at offset 0
    }

    public boolean isEmpty(int slot) {
        return name(slot).isEmpty() || EMPTY_NAME.equals(name(slot));
    }

    public String name(int slot) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAME_LENGTH; i++) {
            int c = data[base(slot) + OFF_NAME + i] & 0xff;
            if (c == '@' || c == 0) {
                break;
            }
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    public int complex(int slot) {
        return word(base(slot) + OFF_COMPLEX);
    }

    public int levelIndex(int slot) {
        return word(base(slot) + OFF_LEVEL);
    }

    public int energy(int slot) {
        return word(base(slot) + OFF_ENERGY);
    }

    public int totalKilled(int slot) {
        return word(base(slot) + OFF_TOTAL_KILLED);
    }

    // ------------------------------------------------------------- transfer

    /**
     * Writes the player's state into a slot.
     *
     * @param stageIndex position in the campaign, split into complex and sub-level the way the
     *                   server stores it
     */
    public void store(int slot, GameState game, Campaign campaign, int stageIndex, String name) {
        int b = base(slot);
        java.util.Arrays.fill(data, b, b + BLOCK, (byte) 0);

        Inventory inv = game.inventory;
        putWord(b + OFF_SIZE, 6);
        putWord(b + OFF_FLOOR, 1);
        putWord(b + OFF_DETAILS, 0);
        putWord(b + OFF_ENERGY, inv.energy);
        putWord(b + OFF_WARHEAD, inv.warhead);
        putWord(b + OFF_KILLED, countDead(game));
        putWord(b + OFF_TIME, game.levelSeconds());
        putWord(b + OFF_DIFFICULTY, 0);

        for (int w = 0; w < 6; w++) {
            int slotNo = w + 1;   // inventory slot 0 is the bare hand
            putWord(b + OFF_GUNS + w * 6, inv.owned[slotNo] ? inv.condition[slotNo] : 0);
            putWord(b + OFF_GUNS + w * 6 + 2, AMMO_PER_PICKUP[w]);
            putWord(b + OFF_GUNS + w * 6 + 4, inv.ammo[slotNo]);
        }

        Campaign.Stage stage = (stageIndex >= 0 && stageIndex < campaign.size())
                ? campaign.stage(stageIndex) : campaign.stage(0);
        putWord(b + OFF_COMPLEX, stage.complex);
        putWord(b + OFF_LEVEL, stage.letter - 'A');
        putName(b, name);
    }

    /** Writes which complexes have fallen into the slot, where MAPA keeps them. */
    public void storeMap(int slot, WorldMap map) {
        int b = base(slot);
        for (int complex = 1; complex <= 8; complex++) {
            data[b + OFF_DONE + complex - 1] = (byte) (map.isDone(complex) ? 1 : 0);
        }
        putWord(b + OFF_TAKEN, map.complexesTaken());
    }

    /** Reads them back. */
    public void restoreMap(int slot, WorldMap map) {
        int b = base(slot);
        for (int complex = 1; complex <= 8; complex++) {
            if (data[b + OFF_DONE + complex - 1] != 0) {
                map.markDone(complex);
            }
        }
    }

    /** Restores a slot into a fresh game state, returning the campaign stage to load. */
    public Campaign.Stage restore(int slot, GameState game, Campaign campaign) {
        int b = base(slot);
        Inventory inv = game.inventory;
        inv.energy = word(b + OFF_ENERGY);
        inv.warhead = word(b + OFF_WARHEAD);
        for (int w = 0; w < 6; w++) {
            int slotNo = w + 1;
            int condition = word(b + OFF_GUNS + w * 6);
            int ammo = word(b + OFF_GUNS + w * 6 + 4);
            inv.owned[slotNo] = condition != 0 || ammo != 0;
            inv.condition[slotNo] = condition;
            inv.ammo[slotNo] = ammo;
        }
        inv.current = Inventory.HAND;

        int complex = complex(slot);
        char letter = (char) ('A' + levelIndex(slot));
        for (Campaign.Stage s : campaign.stages()) {
            if (s.complex == complex && s.letter == letter) {
                return s;
            }
        }
        return campaign.stage(0);
    }

    private static int countDead(GameState game) {
        int n = 0;
        for (Enemy e : game.enemies()) {
            if (e.isDead()) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ raw

    private void putName(int base, String name) {
        for (int i = 0; i < NAME_LENGTH; i++) {
            data[base + OFF_NAME + i] = (byte) ' ';
        }
        String s = name.length() > NAME_LENGTH - 1 ? name.substring(0, NAME_LENGTH - 1) : name;
        for (int i = 0; i < s.length(); i++) {
            data[base + OFF_NAME + i] = (byte) s.charAt(i);
        }
        data[base + OFF_NAME + s.length()] = (byte) '@';
    }

    private int word(int off) {
        return Io.s16(data, off);
    }

    private void putWord(int off, int value) {
        data[off] = (byte) (value >> 8);
        data[off + 1] = (byte) value;
    }
}
