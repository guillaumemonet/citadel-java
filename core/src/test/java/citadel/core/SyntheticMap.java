package citadel.core;

/**
 * A blank map built byte by byte, for tests that need exact positions rather than whatever a
 * shipped level happens to have: an open 64 x 64 floor with nothing on it until a test adds it.
 */
final class SyntheticMap {

    final byte[] raw = new byte[MapFile.EVENTS_OFF + 512];
    /** Where the next event record goes. */
    private int events = MapFile.EVENTS_OFF;

    SyntheticMap() {
        for (int i = MapFile.EVENTS_OFF; i < raw.length; i++) {
            raw[i] = (byte) 0xff;   // -1, -1: no events
        }
        header16(16, 2);    // spawn
        header16(18, 2);
        header16(22, 63);   // exit, out of the way
        header16(24, 63);
        species(0, 0xf, 1, 0, 0);
        species(1, 0xf, 1, 0, 0);
    }

    private void header16(int off, int v) {
        raw[MapFile.HEADER_OFF + off] = (byte) (v >> 8);
        raw[MapFile.HEADER_OFF + off + 1] = (byte) v;
    }

    /** A word of the level header, sv_LevelData, e.g. 26 for the door sound. */
    SyntheticMap header(int off, int v) {
        header16(off, v);
        return this;
    }

    /** Byte 6 of a cell: bit 6 is the block flag, the low five bits the item. */
    SyntheticMap blocked(int x, int y) {
        raw[y * 512 + x * 8 + 6] |= 0x40;
        return this;
    }

    /** The face offset rm_ServeSwitch and the commands use: Y * 512 + X * 8 + face. */
    static int offset(int x, int y, int dir) {
        return y * 512 + x * 8 + dir;
    }

    /**
     * A switch record: {@code -1}, the switch's offset, then pairs of command word (code in the
     * low byte, parameter in the high one; -2 starts the switch-out list) and target offset.
     */
    SyntheticMap trigger(int x, int y, int dir, int... words) {
        word(-1);
        word(offset(x, y, dir));
        for (int w : words) {
            word(w);
        }
        return this;
    }

    private void word(int v) {
        raw[events++] = (byte) (v >> 8);
        raw[events++] = (byte) v;
    }

    /** One of the two templates: vulnerability mask, weapon, energy word, aggression. */
    SyntheticMap species(int which, int mask, int weapon, int energy, int aggression) {
        int base = (which == 0) ? 4 : 10;
        raw[MapFile.HEADER_OFF + base] = (byte) mask;
        raw[MapFile.HEADER_OFF + base + 1] = (byte) weapon;
        header16(base + 2, energy);
        header16(base + 4, aggression);
        return this;
    }

    /** A face value on both sides of the boundary, as the editor stores walls. */
    SyntheticMap wall(int x, int y, int dir, int value) {
        raw[y * 512 + x * 8 + dir] = (byte) value;
        int nx = x + Level.DX[dir], ny = y + Level.DY[dir];
        if (Level.inside(nx, ny)) {
            raw[ny * 512 + nx * 8 + Level.OPPOSITE[dir]] = (byte) value;
        }
        return this;
    }

    /** All four faces of a cell. */
    SyntheticMap box(int x, int y, int value) {
        for (int d = 0; d < 4; d++) {
            wall(x, y, d, value);
        }
        return this;
    }

    SyntheticMap column(int x, int y, int value) {
        raw[y * 512 + x * 8 + 5] = (byte) value;
        return this;
    }

    /**
     * An enemy in a slot: flags 1 is the first species, 2 the second. World coordinates, so it
     * can stand anywhere in its cell.
     */
    SyntheticMap enemy(int slot, int flags, int energy, int wx, int wy, int angle, int speed) {
        int off = MapFile.ENEMY_OFF + slot * MapFile.ENEMY_BYTES;
        raw[off + 1] = (byte) flags;
        raw[off + 2] = (byte) (energy >> 8);
        raw[off + 3] = (byte) energy;
        raw[off + 4] = (byte) (wx >> 8);
        raw[off + 5] = (byte) wx;
        raw[off + 6] = (byte) (wy >> 8);
        raw[off + 7] = (byte) wy;
        raw[off + 8] = (byte) (angle >> 8);
        raw[off + 9] = (byte) angle;
        raw[off + 10] = (byte) (speed >> 8);
        raw[off + 11] = (byte) speed;
        raw[(wy >> 10) * 512 + (wx >> 10) * 8 + 7] = (byte) slot;
        return this;
    }

    Level level() {
        return new Level(new MapFile("SYNTHETIC", raw.clone()));
    }

    /** A cell centre in world units. */
    static int at(int cell) {
        return cell * Level.CELL_UNITS + 512;
    }
}
