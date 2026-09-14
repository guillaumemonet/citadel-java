package citadel.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for LEVELnnX.MAP.
 *
 * Layout, from the equates in Code/MAIN1/CytadelaHD_134.ss (sv_MAP / sv_LEVELDATA /
 * sv_EnemyDATA / sv_SwitchDATA) and from Editor/EDYTOR_210707.ss:
 *
 * <pre>
 *   0x0000  grid,    64 * 64 cells of 8 bytes            = 32768 bytes
 *   0x8000  header,  40 bytes (enemy templates, spawn, exit)
 *   0x8028  enemies, 256 entries of 16 bytes             =  4096 bytes
 *   0x9028  events,  word stream, terminated by -1 -1
 * </pre>
 *
 * A map location is addressed as {@code y * 512 + x * 8 + sub}, which is the raw byte offset of
 * the cell plus a sub index. For event records the sub index doubles as a discriminator: 0..3 is
 * a wall direction (button), 4 is a teleport, 5 is a card reader.
 */
public final class MapFile {

    public static final int GRID = 64;
    public static final int CELL_BYTES = 8;
    public static final int HEADER_OFF = 0x8000;
    public static final int HEADER_SIZE = 40;
    public static final int ENEMY_OFF = HEADER_OFF + HEADER_SIZE;
    public static final int ENEMY_COUNT = 256;
    public static final int ENEMY_BYTES = 16;
    public static final int EVENTS_OFF = HEADER_OFF + 4136;

    /** Wall byte index inside a cell. Confirmed by du_S / du_E / du_W in DrawUserMap. */
    public static final int NORTH = 0, EAST = 1, SOUTH = 2, WEST = 3;
    public static final String[] DIR_NAMES = {"N", "E", "S", "W"};

    /** Event command codes, matching the "ODCDSBRB W I C" table in the editor. */
    public static final String[] COMMANDS = {
            "OD open door", "CD close door", "SB set block", "RB remove block",
            "W set wall", "I set item", "C set column",
    };

    public final String name;
    public final byte[] raw;

    public MapFile(String name, byte[] raw) {
        this.name = name;
        this.raw = raw;
    }

    public static MapFile load(File f) throws IOException {
        byte[] raw = Io.readAll(f);
        if (raw.length < EVENTS_OFF) {
            throw new IOException(f + ": too short for a Citadel map (" + raw.length + " bytes)");
        }
        return new MapFile(f.getName(), raw);
    }

    // ---------------------------------------------------------------- grid

    public int cellOffset(int x, int y) {
        return y * 512 + x * CELL_BYTES;
    }

    public int cellByte(int x, int y, int i) {
        return raw[cellOffset(x, y) + i] & 0xff;
    }

    /** Wall descriptor of one side: bit 0 mirror, bits 1..5 texture index, bits 6..7 blood. */
    public int wall(int x, int y, int dir) {
        return cellByte(x, y, dir);
    }

    public int wallTexture(int x, int y, int dir) {
        return (wall(x, y, dir) & 0x3e) >> 1;
    }

    public boolean wallMirrored(int x, int y, int dir) {
        return (wall(x, y, dir) & 1) != 0;
    }

    public int wallBlood(int x, int y, int dir) {
        return (wall(x, y, dir) >> 6) & 3;
    }

    /** Byte 4: two bits per direction selecting a plaque or card slot on that wall. */
    public int plaque(int x, int y, int dir) {
        return (cellByte(x, y, 4) >> (dir * 2)) & 3;
    }

    /** Byte 5, low 5 bits: column index, 0 for none. */
    public int column(int x, int y) {
        return cellByte(x, y, 5) & 0x1f;
    }

    /** Byte 5, top 3 bits: corpse sprite, see dr_checkDEAD. */
    public int corpse(int x, int y) {
        return (cellByte(x, y, 5) >> 5) & 7;
    }

    /** Byte 6, low 6 bits: item index, 0 for none. */
    public int item(int x, int y) {
        return cellByte(x, y, 6) & 0x3f;
    }

    /** Byte 6, bit 6: the cell cannot be walked through and nearby doors stay shut. */
    public boolean blocked(int x, int y) {
        return (cellByte(x, y, 6) & 0x40) != 0;
    }

    /** Byte 7: index into the enemy table, 0 for none. */
    public int enemy(int x, int y) {
        return cellByte(x, y, 7);
    }

    // -------------------------------------------------------------- header

    public int header16(int off) {
        return Io.u16(raw, HEADER_OFF + off);
    }

    public int header8(int off) {
        return Io.u8(raw, HEADER_OFF + off);
    }

    public int spawnX() {
        return header16(16);
    }

    public int spawnY() {
        return header16(18);
    }

    public int exitX() {
        return header16(22);
    }

    public int exitY() {
        return header16(24);
    }

    /** Template for one of the two enemy species used on this level. */
    public String enemyTemplate(int which) {
        int base = (which == 0) ? 4 : 10;
        int hits = header8(base);
        int weapon = header8(base + 1);
        int energy = header16(base + 2);
        int aggression = header16(base + 4);
        return String.format(
                "hitsA=%d hitsB=%d weapon=%d energy=%d aggression=%d (raw/50 = %d)",
                (hits >> 2) & 3, hits & 3, weapon, energy, aggression, aggression / 50);
    }

    // -------------------------------------------------------------- enemies

    public static final class Enemy {
        public int index;
        public int flags;
        public int energy;
        public int x;
        public int y;
        public int state;
        public int counter;
        public byte[] rawBytes;

        public boolean isSecondSpecies() {
            return (flags & 0x80) != 0;
        }
    }

    /** Enemy slots that are actually populated, i.e. referenced by at least one cell. */
    public List<Enemy> enemies() {
        boolean[] used = new boolean[ENEMY_COUNT];
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                used[enemy(x, y)] = true;
            }
        }
        List<Enemy> out = new ArrayList<Enemy>();
        for (int i = 1; i < ENEMY_COUNT; i++) {
            if (!used[i]) {
                continue;
            }
            int off = ENEMY_OFF + i * ENEMY_BYTES;
            Enemy e = new Enemy();
            e.index = i;
            e.flags = Io.u8(raw, off + 1);
            e.energy = Io.u16(raw, off + 2);
            e.x = Io.u16(raw, off + 4);
            e.y = Io.u16(raw, off + 6);
            e.state = Io.u8(raw, off + 12);
            e.counter = Io.u8(raw, off + 13);
            e.rawBytes = new byte[ENEMY_BYTES];
            System.arraycopy(raw, off, e.rawBytes, 0, ENEMY_BYTES);
            out.add(e);
        }
        return out;
    }

    // --------------------------------------------------------------- events

    /**
     * Decodes the event stream.
     *
     * The stream is {@code -1, record, -1, record, ..., -1, -1}. Each record starts with its
     * trigger location; the low 3 bits say what kind of trigger it is. Buttons and card readers
     * are followed by (command, location) word pairs, with -2 separating the "on press" actions
     * from the "on release" actions. Teleports are followed by their destination location.
     */
    public List<String> events() {
        List<String> out = new ArrayList<String>();
        int p = EVENTS_OFF;
        // The stream opens with the -1 that the engine's seek loop expects.
        while (p + 1 < raw.length) {
            int w = Io.s16(raw, p);
            p += 2;
            if (w != -1) {
                continue;
            }
            if (p + 1 >= raw.length || Io.s16(raw, p) == -1) {
                break;
            }
            int loc = Io.u16(raw, p);
            p += 2;
            int kind = loc & 7;
            int x = (loc >> 3) & 63;
            int y = (loc >> 9) & 63;
            StringBuilder sb = new StringBuilder();
            if (kind == 4) {
                int dest = Io.u16(raw, p);
                p += 2;
                sb.append(String.format("T teleport  from (%2d,%2d) -> (%2d,%2d)",
                        x, y, (dest >> 3) & 63, (dest >> 9) & 63));
                out.add(sb.toString());
                continue;
            }
            if (kind == 5) {
                int colour = Io.u16(raw, p);
                p += 2;
                sb.append(String.format("K card reader (%2d,%2d) colour=%s",
                        x, y, "-RGB".charAt(Math.max(0, Math.min(3, colour)))));
            } else {
                sb.append(String.format("G button      (%2d,%2d) wall=%s",
                        x, y, DIR_NAMES[kind & 3]));
            }
            // command list
            while (p + 3 < raw.length) {
                int cmd = Io.s16(raw, p);
                if (cmd == -1) {
                    break;
                }
                p += 2;
                if (cmd == -2) {
                    sb.append("\n      -- on release --");
                    continue;
                }
                int target = Io.u16(raw, p);
                p += 2;
                int code = cmd & 0xff;
                int param = (cmd >> 8) & 0xff;
                String label = (code < COMMANDS.length) ? COMMANDS[code] : ("?" + code);
                sb.append(String.format("%n      %-16s at (%2d,%2d)%s%s",
                        label, (target >> 3) & 63, (target >> 9) & 63,
                        (target & 7) < 4 ? " wall=" + DIR_NAMES[target & 3] : "",
                        code >= 4 ? " param=" + param : ""));
            }
            out.add(sb.toString());
        }
        return out;
    }

    // ----------------------------------------------------------------- text

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(name).append("  (").append(raw.length).append(" bytes)\n\n");
        sb.append("## Header\n");
        sb.append("enemy species 1 : ").append(enemyTemplate(0)).append('\n');
        sb.append("enemy species 2 : ").append(enemyTemplate(1)).append('\n');
        sb.append(String.format("player spawn    : (%d,%d)%n", spawnX(), spawnY()));
        sb.append(String.format("level exit      : (%d,%d)%n", exitX(), exitY()));
        sb.append("header+20       : ").append(header16(20))
                .append("   (spawn facing, unconfirmed)\n");
        sb.append("unused words    : +0=").append(header16(0))
                .append(" +2=").append(header16(2)).append('\n');

        List<Enemy> en = enemies();
        sb.append("\n## Enemies (").append(en.size()).append(" placed)\n");
        sb.append("slot  species  energy   pos          raw\n");
        for (Enemy e : en) {
            StringBuilder hex = new StringBuilder();
            for (byte b : e.rawBytes) {
                hex.append(String.format("%02x ", b));
            }
            sb.append(String.format("%4d  %7d  %6d   (%5d,%5d)  %s%n",
                    e.index, e.isSecondSpecies() ? 2 : 1, e.energy, e.x, e.y, hex.toString().trim()));
        }

        List<String> ev = events();
        sb.append("\n## Events (").append(ev.size()).append(")\n");
        for (String s : ev) {
            sb.append(s).append('\n');
        }

        sb.append("\n## Cell census\n");
        int walls = 0, doors = 0, columns = 0, items = 0, enemies = 0, blocks = 0, plaques = 0;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                for (int d = 0; d < 4; d++) {
                    int v = wall(x, y, d) & 0x3e;
                    if (v == 0) {
                        continue;
                    }
                    if (isDoor(v)) {
                        doors++;
                    } else {
                        walls++;
                    }
                    if (plaque(x, y, d) != 0) {
                        plaques++;
                    }
                }
                if (column(x, y) != 0) {
                    columns++;
                }
                if (item(x, y) != 0) {
                    items++;
                }
                if (enemy(x, y) != 0) {
                    enemies++;
                }
                if (blocked(x, y)) {
                    blocks++;
                }
            }
        }
        sb.append("wall faces  : ").append(walls).append('\n');
        sb.append("door faces  : ").append(doors).append('\n');
        sb.append("plaques     : ").append(plaques).append('\n');
        sb.append("columns     : ").append(columns).append('\n');
        sb.append("items       : ").append(items).append('\n');
        sb.append("enemy cells : ").append(enemies).append('\n');
        sb.append("blocked     : ").append(blocks).append('\n');
        return sb.toString();
    }

    /** Door cell values, per the editor DrawMap dispatch. */
    public static boolean isDoor(int maskedValue) {
        return maskedValue == 30 || maskedValue == 32 || maskedValue == 34
                || maskedValue == 36 || maskedValue == 38 || maskedValue == 40
                || maskedValue == 54;
    }

    public static boolean isSwitch(int maskedValue) {
        return maskedValue == 44 || maskedValue == 46;
    }
}
