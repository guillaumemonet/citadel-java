package citadel.core;

import java.util.Arrays;

/**
 * STRUCTURE, the 128 byte record SERVER keeps at {@code $7f800} and hands the engine between
 * levels. It is also exactly one block of Savegame.DAT, so a save is a copy of it and a load
 * copies one back ({@code WczytajStan}: 32 longwords from the slot to STRUCTURE).
 *
 * <pre>
 *   +0   size (screen size, 2..6)       +2  floors on        +4  details 0..2
 *   +6   energy, negative                +8  sv_Glowica       +10 enemies killed this level
 *   +12  seconds on this level           +14 sv_GUNS, 18 words: per weapon
 *        (condition, ammunition a pickup, ammunition)
 *   +50  difficulty, 1 easy              +60 complex 1..8     +62 levels finished in it
 *   +64  one byte a complex, set once it has fallen (MAPA: move.b #1,64(a1,d0.w))
 *   +72  slot name, 16 bytes, '@' ended  +88 slot occupied (WczytajStan tests it)
 *   +90  enemies killed in all           +92 "KANE" on the last level
 *   +98  complexes taken, which the centre wants at seven
 *   +100 cc_RequestTab2, the interference switch
 * </pre>
 *
 * The engine reads +0, +2, +4, +6, +8, +50, +100 and the guns when a level starts, and writes
 * them back with the kills and the time when it ends ({@code start} and the "fix new structure"
 * block of CytadelaHD_134.ss); everything else belongs to SERVER.
 */
public final class Structure {

    public static final int SIZE = 128;

    public static final int OFF_SIZE = 0;
    public static final int OFF_FLOOR = 2;
    public static final int OFF_DETAILS = 4;
    public static final int OFF_ENERGY = 6;
    public static final int OFF_GLOWICA = 8;
    public static final int OFF_KILLED = 10;
    public static final int OFF_TIME = 12;
    public static final int OFF_GUNS = 14;
    public static final int OFF_DIFFICULTY = 50;
    public static final int OFF_COMPLEX = 60;
    public static final int OFF_LEVEL = 62;
    public static final int OFF_DONE = 64;
    public static final int OFF_NAME = 72;
    public static final int NAME_LENGTH = 16;
    public static final int OFF_OCCUPIED = 88;
    public static final int OFF_TOTAL_KILLED = 90;
    public static final int OFF_LAST_LEVEL = 92;
    public static final int OFF_TAKEN = 98;
    public static final int OFF_SZUM = 100;

    /** Weapons in sv_GUNS, inventory slots 1..6. */
    public static final int GUNS = 6;

    private final byte[] data;

    private Structure(byte[] data) {
        this.data = data;
    }

    /** A copy of a 128 byte block. */
    public static Structure of(byte[] block, int offset) {
        return new Structure(Arrays.copyOfRange(block, offset, offset + SIZE));
    }

    /** The shipped head block, as Savegame_DAT.ss assembles it. */
    public static Structure fresh() {
        Structure s = new Structure(new byte[SIZE]);
        s.put(OFF_SIZE, 6);
        s.put(OFF_FLOOR, 1);
        s.put(OFF_ENERGY, -150);
        int[] perPickup = {-10, -5, -10, -5, -5, -1};
        for (int w = 0; w < GUNS; w++) {
            s.put(OFF_GUNS + w * 6 + 2, perPickup[w]);
        }
        s.put(OFF_COMPLEX, 1);
        s.put(OFF_OCCUPIED, 1);
        return s;
    }

    public Structure copy() {
        return new Structure(data.clone());
    }

    public byte[] bytes() {
        return data.clone();
    }

    void copyInto(byte[] block, int offset) {
        System.arraycopy(data, 0, block, offset, SIZE);
    }

    // ------------------------------------------------------------- fields

    public int word(int off) {
        return Io.s16(data, off);
    }

    public void put(int off, int value) {
        data[off] = (byte) (value >> 8);
        data[off + 1] = (byte) value;
    }

    public int complex() {
        return word(OFF_COMPLEX);
    }

    public void setComplex(int c) {
        put(OFF_COMPLEX, c);
    }

    /** {@code 62(a2)}: how many levels of the complex are behind you, 1 based once one is. */
    public int level() {
        return word(OFF_LEVEL);
    }

    public void setLevel(int l) {
        put(OFF_LEVEL, l);
    }

    public int energy() {
        return word(OFF_ENERGY);
    }

    public int glowica() {
        return word(OFF_GLOWICA);
    }

    public int killed() {
        return word(OFF_KILLED);
    }

    public int seconds() {
        return Io.u16(data, OFF_TIME);
    }

    public boolean easy() {
        return word(OFF_DIFFICULTY) != 0;
    }

    /** UstawOpcje: {@code eori #1,50(a1)}. */
    public void toggleDifficulty() {
        put(OFF_DIFFICULTY, word(OFF_DIFFICULTY) ^ 1);
    }

    public int size() {
        return word(OFF_SIZE);
    }

    public int floors() {
        return word(OFF_FLOOR);
    }

    public int details() {
        return word(OFF_DETAILS);
    }

    public boolean isDone(int complex) {
        return complex >= 1 && complex <= 8 && data[OFF_DONE + complex - 1] != 0;
    }

    /** MAPA, on the way in: {@code move.b #1,64(a1,d0.w)} and {@code addi #1,98(a1)}. */
    public void markDone(int complex) {
        if (complex >= 1 && complex <= 8) {
            data[OFF_DONE + complex - 1] = 1;
        }
        put(OFF_TAKEN, word(OFF_TAKEN) + 1);
    }

    public int taken() {
        return word(OFF_TAKEN);
    }

    public int totalKilled() {
        return Io.u16(data, OFF_TOTAL_KILLED);
    }

    /** GoodExit: {@code move 10(a1),d0 / add d0,90(a1)}. */
    public void addKillsToTotal() {
        put(OFF_TOTAL_KILLED, Io.u16(data, OFF_TOTAL_KILLED) + Io.u16(data, OFF_KILLED));
    }

    public boolean isOccupied() {
        return word(OFF_OCCUPIED) != 0;
    }

    /** NieKoniec: {@code move.l #"KANE",92(a2)} when the next ETAPY byte is the end. */
    public void markLastLevel() {
        data[OFF_LAST_LEVEL] = 'K';
        data[OFF_LAST_LEVEL + 1] = 'A';
        data[OFF_LAST_LEVEL + 2] = 'N';
        data[OFF_LAST_LEVEL + 3] = 'E';
    }

    /** The slot name, up to the '@' that ends it. */
    public String name() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAME_LENGTH; i++) {
            int c = data[OFF_NAME + i] & 0xff;
            if (c == '@' || c == 0) {
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    /**
     * Writes a name and its '@' over the start of the field and leaves the rest as it was, as
     * ENDLEVEL_PART's {@code .allela} loop does.
     */
    public void setName(String name) {
        String s = name.length() > NAME_LENGTH - 1 ? name.substring(0, NAME_LENGTH - 1) : name;
        for (int i = 0; i < s.length(); i++) {
            data[OFF_NAME + i] = (byte) s.charAt(i);
        }
        data[OFF_NAME + s.length()] = '@';
    }

    // ------------------------------------------------------- the engine

    /**
     * The engine's {@code start}: energy, Glowica, the guns and the difficulty out of STRUCTURE.
     * {@link GameState#beginLevel} then does the rest of what the engine does with them.
     */
    public void toGame(GameState game) {
        Inventory inv = game.inventory;
        inv.energy = energy();
        inv.warhead = glowica();
        for (int w = 0; w < GUNS; w++) {
            int slot = w + 1;
            int condition = word(OFF_GUNS + w * 6);
            int ammo = word(OFF_GUNS + w * 6 + 4);
            inv.owned[slot] = condition != 0;
            inv.condition[slot] = condition;
            inv.ammo[slot] = ammo;
        }
        inv.current = Inventory.HAND;
        game.setEasy(easy());
    }

    /**
     * The engine's "fix new structure" on the way out: energy, Glowica, sv_NrKilled, sv_time and
     * the guns back into STRUCTURE.
     */
    public void fromGame(GameState game) {
        Inventory inv = game.inventory;
        put(OFF_ENERGY, inv.energy);
        put(OFF_GLOWICA, inv.warhead);
        put(OFF_KILLED, game.enemiesKilled());
        put(OFF_TIME, game.levelSeconds());
        for (int w = 0; w < GUNS; w++) {
            int slot = w + 1;
            put(OFF_GUNS + w * 6, inv.owned[slot] ? inv.condition[slot] : 0);
            put(OFF_GUNS + w * 6 + 4, inv.ammo[slot]);
        }
    }

    /**
     * RememberOpt keeps size, floors and details across a return to {@code s}, which reloads
     * STRUCTURE from the save file's head; OptSav puts them back.
     */
    public void restoreOptions(Structure remembered) {
        put(OFF_SIZE, remembered.size());
        put(OFF_FLOOR, remembered.floors());
        put(OFF_DETAILS, remembered.details());
    }
}
