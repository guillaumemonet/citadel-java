package citadel.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A level decoded out of its raw {@link MapFile} bytes into something a renderer and a game loop
 * can use directly.
 *
 * World units follow the original engine: the grid is 64 x 64 cells of {@link #CELL_UNITS} units,
 * so a world coordinate is {@code cell * 1024 + offsetInCell} and fits in an unsigned 16 bit
 * value, exactly like sv_PosX / sv_PosY.
 *
 * Directions are ordered N, E, S, W to match the first four bytes of a cell. The engine treats
 * the far side of a wall as the opposite face of the neighbouring cell (see rm_FixDoor, which
 * walks N to +512 bytes, E to +8, S to -512 and W to -8), which fixes the axes:
 * <b>N is +Y, E is +X, S is -Y, W is -X</b>.
 */
public final class Level {

    public static final int GRID = MapFile.GRID;
    /** World units per grid cell, as used by sv_PosX / sv_PosY. */
    public static final int CELL_UNITS = 1024;

    public static final int NORTH_DIR = 0;
    public static final int EAST_DIR = 1;
    public static final int SOUTH_DIR = 2;
    public static final int WEST_DIR = 3;

    /** Neighbour offsets per direction, indexed N, E, S, W. */
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DY = {1, 0, -1, 0};
    /** The face of the neighbouring cell that backs onto this one. */
    public static final int[] OPPOSITE = {2, 3, 0, 1};

    /** Wall texture index used for the floor, from the wall_floor1 equate. */
    public static final int FLOOR_TEXTURE = 12;
    /** Wall texture index used for the ceiling, from the wall_floor2 equate. */
    public static final int CEILING_TEXTURE = 13;

    public enum DoorState { NONE, CLOSED, OPEN, ANIMATING, BROKEN }

    public final String name;
    public final MapFile source;

    private final byte[] wallTex = new byte[GRID * GRID * 4];
    private final byte[] wallFlags = new byte[GRID * GRID * 4];
    private final byte[] blood = new byte[GRID * GRID * 4];
    private final byte[] plaque = new byte[GRID * GRID * 4];
    private final byte[] column = new byte[GRID * GRID];
    private final byte[] corpse = new byte[GRID * GRID];
    private final byte[] item = new byte[GRID * GRID];
    private final boolean[] blocked = new boolean[GRID * GRID];
    private final short[] enemyRef = new short[GRID * GRID];

    private final List<EnemySpawn> spawns = new ArrayList<EnemySpawn>();
    private final List<Trigger> triggers = new ArrayList<Trigger>();
    private final List<Teleport> teleports = new ArrayList<Teleport>();

    public final EnemyTemplate[] enemyTemplates = new EnemyTemplate[2];
    public final int spawnCellX;
    public final int spawnCellY;
    public final int spawnFacing;
    public final int exitCellX;
    public final int exitCellY;

    // ------------------------------------------------------------- loading

    public static Level load(File f) throws IOException {
        return new Level(MapFile.load(f));
    }

    public Level(MapFile map) {
        this.source = map;
        this.name = map.name;

        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int c = x + y * GRID;
                for (int d = 0; d < 4; d++) {
                    int w = map.wall(x, y, d);
                    wallTex[c * 4 + d] = (byte) ((w & 0x3e) >> 1);
                    wallFlags[c * 4 + d] = (byte) (w & 1);
                    blood[c * 4 + d] = (byte) ((w >> 6) & 3);
                    plaque[c * 4 + d] = (byte) map.plaque(x, y, d);
                }
                column[c] = (byte) map.column(x, y);
                corpse[c] = (byte) map.corpse(x, y);
                item[c] = (byte) map.item(x, y);
                blocked[c] = map.blocked(x, y);
                enemyRef[c] = (short) map.enemy(x, y);
            }
        }

        enemyTemplates[0] = new EnemyTemplate(map, 0);
        enemyTemplates[1] = new EnemyTemplate(map, 1);
        spawnCellX = map.spawnX();
        spawnCellY = map.spawnY();
        spawnFacing = map.header16(20);
        exitCellX = map.exitX();
        exitCellY = map.exitY();

        for (MapFile.Enemy e : map.enemies()) {
            spawns.add(new EnemySpawn(e));
        }
        parseEvents(map);
    }

    // ---------------------------------------------------------------- grid

    private static int idx(int x, int y) {
        return x + y * GRID;
    }

    public static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < GRID && y < GRID;
    }

    /**
     * The wall id stored in the cell, i.e. {@code cellValue / 2}, with 0 meaning no wall. This is
     * the 1-based number the engine compares against (15 is a shut door, 22 a switch, and so on).
     */
    public int wallTexture(int x, int y, int dir) {
        return wallTex[idx(x, y) * 4 + dir] & 0xff;
    }

    /**
     * The texture to actually draw for a face: sprite index, 0-based, or -1 for no wall.
     *
     * The offset tables are 1-based, as ShowCollumns' {@code subq #1,d4} shows, and the wall table
     * is no exception: a shut door stores 30, which is wall id 15, which is sprite 14 - the barred
     * door. Sprite 15 is the black opening you see once it is open.
     */
    public int wallSprite(int x, int y, int dir) {
        return GfxLayout.spriteIndexFor(wallTexture(x, y, dir));
    }

    /** True when the texture should be mirrored horizontally. */
    public boolean wallMirrored(int x, int y, int dir) {
        return wallFlags[idx(x, y) * 4 + dir] != 0;
    }

    /** Blood stain variant on this face, 0 for none. */
    public int wallBlood(int x, int y, int dir) {
        return blood[idx(x, y) * 4 + dir] & 0xff;
    }

    /** Plaque or card slot on this face, 0 for none. */
    public int wallPlaque(int x, int y, int dir) {
        return plaque[idx(x, y) * 4 + dir] & 0xff;
    }

    public boolean hasWall(int x, int y, int dir) {
        return wallTexture(x, y, dir) != 0;
    }

    // ------------------------------------------------------------ mutation

    /**
     * Sets the raw cell value of a wall face, as the SET_WALL command and the door animation do.
     * The value is the byte the engine stores: {@code textureIndex * 2 | mirror}.
     */
    public void setWallValue(int x, int y, int dir, int value) {
        int i = idx(x, y) * 4 + dir;
        wallTex[i] = (byte) ((value & 0x3e) >> 1);
        wallFlags[i] = (byte) (value & 1);
    }

    public int wallValue(int x, int y, int dir) {
        int i = idx(x, y) * 4 + dir;
        return ((wallTex[i] & 0xff) << 1) | (wallFlags[i] & 1);
    }

    public void setBlocked(int x, int y, boolean value) {
        blocked[idx(x, y)] = value;
    }

    public void setItem(int x, int y, int value) {
        item[idx(x, y)] = (byte) value;
    }

    public void setColumn(int x, int y, int value) {
        column[idx(x, y)] = (byte) value;
    }

    /** Byte 7 doubles as an occupancy grid: Move_Enemy clears the old cell and writes the new. */
    public void setEnemyRef(int x, int y, int slot) {
        enemyRef[idx(x, y)] = (short) slot;
    }

    public int column(int x, int y) {
        return column[idx(x, y)] & 0xff;
    }

    public int corpse(int x, int y) {
        return corpse[idx(x, y)] & 0xff;
    }

    public int item(int x, int y) {
        return item[idx(x, y)] & 0xff;
    }

    public boolean isBlocked(int x, int y) {
        return blocked[idx(x, y)];
    }

    public int enemyRef(int x, int y) {
        return enemyRef[idx(x, y)] & 0xffff;
    }

    /** Classifies a face, so a renderer can pick an animated door mesh over a static quad. */
    public DoorState doorState(int x, int y, int dir) {
        switch (wallTexture(x, y, dir)) {
            case 15: case 18: return DoorState.CLOSED;
            case 16: case 19: return DoorState.OPEN;
            case 17: case 20: return DoorState.ANIMATING;
            case 27: return DoorState.BROKEN;
            default: return DoorState.NONE;
        }
    }

    public boolean isSwitch(int x, int y, int dir) {
        int t = wallTexture(x, y, dir);
        return t == 22 || t == 23;
    }

    /**
     * True when the player may cross this face, following br_ChkDoors: a face is passable when
     * its cell value masked with 62 is 0 (nothing), 32 (door 1 open), 38 (door 2 open) or
     * 54 (broken door).
     */
    public boolean isPassable(int x, int y, int dir) {
        int v = wallValue(x, y, dir) & 62;
        return v == 0 || v == 32 || v == 38 || v == 54;
    }

    // ------------------------------------------------------------- content

    public static final class EnemyTemplate {
        /**
         * Which damage types hurt this species, one bit each, from the btst chain in
         * EnemyHitServe: bit 0 bullets, bit 1 fireballs, bit 2 electricity, bit 3 rockets.
         * The editor shows it as two 2-bit "hits" fields, but the engine reads it as a mask.
         */
        public final int vulnerability;
        public final int weapon;
        public final int energy;
        public final int aggression;

        EnemyTemplate(MapFile m, int which) {
            int base = (which == 0) ? 4 : 10;
            vulnerability = m.header8(base) & 0xf;
            weapon = m.header8(base + 1);
            energy = m.header16(base + 2);
            aggression = m.header16(base + 4);
        }

        /** @param type the projectile type, 1 bullet, 3 fireball, 4 electricity, 5 rocket */
        public boolean vulnerableTo(int type) {
            switch (type) {
                case 1: return (vulnerability & 1) != 0;
                case 3: return (vulnerability & 2) != 0;
                case 4: return (vulnerability & 4) != 0;
                case 5: return (vulnerability & 8) != 0;
                default: return true;
            }
        }

        @Override
        public String toString() {
            return "vulnerability=" + Integer.toBinaryString(vulnerability) + " weapon=" + weapon
                    + " energy=" + energy + " aggression=" + aggression;
        }
    }

    public static final class EnemySpawn {
        /** Slot number, which is what cells reference in their byte 7. */
        public final int slot;
        /** 0 for the level's first species, 1 for the second. */
        public final int species;
        public final int worldX;
        public final int worldY;
        public final int energy;
        public final byte[] raw;

        EnemySpawn(MapFile.Enemy e) {
            slot = e.index;
            // EnemyHitServe and dr_DrawEnemy both pick with btst #0 on the flags byte, and both
            // take the SET bit to mean the first species: beq.s .me01 falls through to
            // sv_LevelData+4, and bne.s de_enemy2 skips the lea of sv_Enemy2Offsets. So the bit
            // is inverted relative to the index used here. Bit 7 is unrelated; it only tells the
            // renderer to draw this one behind a column.
            species = (e.flags & 1) ^ 1;
            worldX = e.x;
            worldY = e.y;
            energy = e.energy;
            raw = e.rawBytes;
        }

        public int cellX() {
            return worldX / CELL_UNITS;
        }

        public int cellY() {
            return worldY / CELL_UNITS;
        }
    }

    /** One command of a trigger, e.g. "open the door on the south face of (21,33)". */
    public static final class Command {
        public static final int OPEN_DOOR = 0;
        public static final int CLOSE_DOOR = 1;
        public static final int SET_BLOCK = 2;
        public static final int REMOVE_BLOCK = 3;
        public static final int SET_WALL = 4;
        public static final int SET_ITEM = 5;
        public static final int SET_COLUMN = 6;

        public final int code;
        public final int param;
        public final int cellX;
        public final int cellY;
        public final int dir;
        /** True when this command fires when the button is released rather than pressed. */
        public final boolean onRelease;

        Command(int code, int param, int loc, boolean onRelease) {
            this.code = code;
            this.param = param;
            this.cellX = (loc >> 3) & 63;
            this.cellY = (loc >> 9) & 63;
            this.dir = loc & 3;
            this.onRelease = onRelease;
        }

        @Override
        public String toString() {
            return MapFile.COMMANDS[Math.min(code, MapFile.COMMANDS.length - 1)]
                    + " (" + cellX + "," + cellY + ") " + MapFile.DIR_NAMES[dir]
                    + (code >= SET_WALL ? " param=" + param : "")
                    + (onRelease ? " [on release]" : "");
        }
    }

    /** A button on a wall, or a card reader, with the commands it runs. */
    public static final class Trigger {
        public final int cellX;
        public final int cellY;
        /** Wall direction for a button; -1 for a card reader. */
        public final int dir;
        /** 0 none, 1 red, 2 green, 3 blue; -1 when this is a plain button. */
        public final int cardColour;
        public final List<Command> commands;

        Trigger(int cellX, int cellY, int dir, int cardColour, List<Command> commands) {
            this.cellX = cellX;
            this.cellY = cellY;
            this.dir = dir;
            this.cardColour = cardColour;
            this.commands = Collections.unmodifiableList(commands);
        }

        public boolean isCardReader() {
            return cardColour >= 0;
        }
    }

    public static final class Teleport {
        public final int fromX;
        public final int fromY;
        public final int toX;
        public final int toY;

        Teleport(int fromX, int fromY, int toX, int toY) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
        }
    }

    public List<EnemySpawn> enemySpawns() {
        return Collections.unmodifiableList(spawns);
    }

    public List<Trigger> triggers() {
        return Collections.unmodifiableList(triggers);
    }

    public List<Teleport> teleports() {
        return Collections.unmodifiableList(teleports);
    }

    // -------------------------------------------------------------- events

    /**
     * Walks the event stream. Structure and opcodes are documented in tools/README.md; the
     * authority is rm_ServeSwitch / rm_CommandLoop and br_FindTeleport in the engine.
     */
    private void parseEvents(MapFile map) {
        byte[] raw = map.raw;
        int p = MapFile.EVENTS_OFF;
        while (p + 1 < raw.length) {
            if (Io.s16(raw, p) != -1) {
                p += 2;
                continue;
            }
            p += 2;
            if (p + 1 >= raw.length || Io.s16(raw, p) == -1) {
                break;
            }
            int loc = Io.u16(raw, p);
            p += 2;
            int kind = loc & 7;
            int cx = (loc >> 3) & 63;
            int cy = (loc >> 9) & 63;

            if (kind == 4) {
                int dest = Io.u16(raw, p);
                p += 2;
                teleports.add(new Teleport(cx, cy, (dest >> 3) & 63, (dest >> 9) & 63));
                continue;
            }

            int colour = -1;
            if (kind == 5) {
                colour = Io.u16(raw, p);
                p += 2;
            }
            List<Command> cmds = new ArrayList<Command>();
            boolean onRelease = false;
            while (p + 3 < raw.length) {
                int cmd = Io.s16(raw, p);
                if (cmd == -1) {
                    break;
                }
                p += 2;
                if (cmd == -2) {
                    onRelease = true;
                    continue;
                }
                int target = Io.u16(raw, p);
                p += 2;
                cmds.add(new Command(cmd & 0xff, (cmd >> 8) & 0xff, target, onRelease));
            }
            triggers.add(new Trigger(cx, cy, kind == 5 ? -1 : (kind & 3), colour, cmds));
        }
    }

    @Override
    public String toString() {
        return name + " spawn=(" + spawnCellX + "," + spawnCellY + ")"
                + " exit=(" + exitCellX + "," + exitCellY + ")"
                + " enemies=" + spawns.size()
                + " triggers=" + triggers.size()
                + " teleports=" + teleports.size();
    }
}
