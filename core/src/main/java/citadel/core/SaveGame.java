package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * Savegame.DAT, in the layout SERVER reads and writes, so a file made here is one the Amiga game
 * loads and the other way round.
 *
 * 768 bytes: a head block, then five slots, each a 128 byte {@link Structure}. On the released
 * disks it lives on disk 4 from sector 1; SERVER copies it to {@code STRUCTURE} at {@code s}
 * ({@code .copsav}), so the head block is the state a new game starts from.
 *
 * <ul>
 * <li>A slot is occupied when the word at +88 is not zero: {@code WczytajStan} tests
 *     {@code SAVEGAMES+88} and refuses an empty one. Free slots carry the name "EMPTY@" on
 *     the English disk ("WOLNE@" in the repository's Polish file) and 0 at +88; the head block
 *     carries 1, which is how a saved STRUCTURE comes to be marked.</li>
 * <li>Saving happens only in ENDLEVEL_PART: the slot gets a copy of STRUCTURE, then the name
 *     (NazwyPoziomow2 and the level digit), and the file is written as the pristine head
 *     ({@code STRUCTURE+$300}, the copy {@code s} takes before anything touches it) followed
 *     by the five slots.</li>
 * </ul>
 */
public final class SaveGame {

    public static final int BLOCK = Structure.SIZE;
    public static final int SLOT_COUNT = 5;
    public static final int FILE_SIZE = BLOCK * (1 + SLOT_COUNT);

    /** What the repository's file calls a free slot. */
    public static final String EMPTY_NAME = "WOLNE";

    private final byte[] data;

    private SaveGame(byte[] data) {
        this.data = data;
    }

    /** The repository's Savegame_DAT.ss, for when there are no disks to read it from. */
    public static SaveGame empty() {
        byte[] d = new byte[FILE_SIZE];
        Structure.fresh().copyInto(d, 0);
        SaveGame s = new SaveGame(d);
        for (int i = 0; i < SLOT_COUNT; i++) {
            int b = s.base(i);
            for (int k = 0; k < Structure.NAME_LENGTH; k++) {
                d[b + Structure.OFF_NAME + k] = ' ';
            }
            byte[] name = (EMPTY_NAME + "@").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            System.arraycopy(name, 0, d, b + Structure.OFF_NAME, name.length);
        }
        return s;
    }

    public static SaveGame of(byte[] raw) {
        byte[] d = new byte[FILE_SIZE];
        System.arraycopy(raw, 0, d, 0, Math.min(raw.length, FILE_SIZE));
        return new SaveGame(d);
    }

    public static SaveGame load(File f) throws IOException {
        return of(Io.readAll(f));
    }

    public void save(File f) throws IOException {
        Io.write(f, data);
    }

    public byte[] bytes() {
        return data.clone();
    }

    private int base(int slot) {
        return (slot + 1) * BLOCK;   // slot -1 is the head block at offset 0
    }

    // ------------------------------------------------------------- reading

    /** The head block: what {@code s} copies to STRUCTURE for a new game. */
    public Structure head() {
        return Structure.of(data, 0);
    }

    public Structure slot(int slot) {
        return Structure.of(data, base(slot));
    }

    /** {@code tst (a1,d0.w)} on {@code SAVEGAMES+88}. */
    public boolean isOccupied(int slot) {
        return Io.s16(data, base(slot) + Structure.OFF_OCCUPIED) != 0;
    }

    public boolean isEmpty(int slot) {
        return !isOccupied(slot);
    }

    /** The name the menus print, read up to its '@'. */
    public String name(int slot) {
        return slot(slot).name();
    }

    public int complex(int slot) {
        return slot(slot).complex();
    }

    public int energy(int slot) {
        return slot(slot).energy();
    }

    // ------------------------------------------------------------- writing

    /**
     * ENDLEVEL_PART's save: the slot becomes a copy of STRUCTURE and then takes the name; the
     * head block is replaced by the pristine one {@code s} kept.
     *
     * @param pristineHead {@code STRUCTURE+$300}, or null to keep the head as it is
     */
    public void store(int slot, Structure structure, String name, Structure pristineHead) {
        Structure s = structure.copy();
        // +88 comes along from STRUCTURE, which has it from the head; set it outright so a
        // STRUCTURE built here rather than read from a file is marked as well
        s.put(Structure.OFF_OCCUPIED, 1);
        s.setName(name);
        s.copyInto(data, base(slot));
        if (pristineHead != null) {
            pristineHead.copyInto(data, 0);
        }
    }

    /**
     * The slot name ENDLEVEL_PART builds: {@code NazwyPoziomow2} for the complex, up to its '@',
     * then {@code el_poz}, the level digit ({@code STRUCTURE+62} plus '0').
     */
    public static String slotName(int complex, int level) {
        String base = NAZWY_POZIOMOW_2[Math.max(0, Math.min(7, complex - 1))];
        return base.substring(0, base.indexOf('@')) + (char) ('0' + level);
    }

    /** NazwyPoziomow2, fourteen characters each. */
    private static final String[] NAZWY_POZIOMOW_2 = {
        "DUNGEONS  @   ", "POWER PLANT @ ", "STORES @      ", "HANGAR @      ",
        "LABORATORY @  ", "SEWERS @      ", "PRISON @      ", "CENTER @      ",
    };
}
