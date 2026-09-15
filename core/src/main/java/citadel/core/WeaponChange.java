package citadel.core;

import java.io.File;
import java.io.IOException;

/**
 * {@code Store/CHAOSTAB.DAT}, the order ci_DrawWeapon reveals a newly drawn weapon in.
 *
 * The sibling of {@link ChaosTable} and the same idea, one level down: 1728 bytes, 864 big endian
 * words, a permutation of 0..863. 864 is 32 by 27, the weapon window, so each entry is one pixel —
 * {@code lsr #5} gives the row and {@code andi #31} the column.
 *
 * ci_NewWeapon arms it by setting sv_ChaosAddr to {@code [32*27*2]-2}, the last entry, and
 * ci_DrawWeapon walks **backwards** 32 entries a call with {@code subq #2,d0} until it goes
 * negative. NewLev3 calls it every VBLANK, so a weapon change takes 27 frames to appear.
 *
 * Nothing here fires when the trigger is pulled: sv_ChaosAddr is only ever rearmed by
 * ci_NewWeapon. The original has no recoil animation.
 */
public final class WeaponChange {

    public static final int WIDTH = 32;
    public static final int HEIGHT = 27;
    public static final int SIZE = WIDTH * HEIGHT;
    /** {@code move #31,d1}: entries per call. */
    public static final int PER_FRAME = 32;
    /** VBLANK frames a change takes, which falls out of the two above. */
    public static final int FRAMES = SIZE / PER_FRAME;

    private final int[] order = new int[SIZE];

    private WeaponChange() {
    }

    public static WeaponChange load(File f) throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length < SIZE * 2) {
            throw new IOException(f + ": expected " + (SIZE * 2) + " bytes, got " + raw.length);
        }
        WeaponChange t = new WeaponChange();
        for (int i = 0; i < SIZE; i++) {
            t.order[i] = ((raw[i * 2] & 0xff) << 8) | (raw[i * 2 + 1] & 0xff);
        }
        return t;
    }

    public static WeaponChange loadFrom(File dataRoot) throws IOException {
        return load(new File(new File(dataRoot, "GAME/Store"), "CHAOSTAB.DAT"));
    }

    public int entry(int i) {
        return order[i];
    }

    public boolean isPermutation() {
        boolean[] seen = new boolean[SIZE];
        for (int v : order) {
            if (v < 0 || v >= SIZE || seen[v]) {
                return false;
            }
            seen[v] = true;
        }
        return true;
    }

    /**
     * The pixels revealed on one frame, as {@code {column, row}}. Frame 0 is the tail of the
     * table, since sv_ChaosAddr starts at the end and counts down.
     */
    public int[][] frame(int frame) {
        int[][] out = new int[PER_FRAME][2];
        int at = SIZE - 1 - frame * PER_FRAME;
        for (int i = 0; i < PER_FRAME; i++, at--) {
            int v = order[at];
            out[i][0] = v & 31;    // andi #31,d2
            out[i][1] = v >> 5;    // lsr #5,d3
        }
        return out;
    }
}
