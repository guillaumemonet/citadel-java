package citadel.core;

import java.util.ArrayList;
import java.util.List;

import static citadel.core.VirReader.HALF;
import static citadel.core.VirReader.STRIDE;
import static citadel.core.VirReader.TILE;

/**
 * The graphics memory map of one complex, transcribed from the offset tables at label st_Offsets
 * in Code/MAIN1/CytadelaHD_134.ss.
 *
 * The server (LoadFiles in Code/SERVER/SERVER_032_HD_ENG.ss) loads five files back to back at
 * MEMORY + co_Walls; every offset in the tables below is relative to that base. The layout is
 * cross checked by ITEMS01.VIR: the server copies it to MEMORY + 0x52c40, and 0x52c40 - co_Walls
 * (0x10c00) is exactly 65 * TILE, the first entry of sv_ItemOffsets.
 */
public final class GfxLayout {

    public static final int WALLS_A = 0;
    public static final int WALLS_B = 83_200;
    public static final int ENEMY_A = 166_400;
    public static final int ENEMY_B = 218_400;
    public static final int ITEMS = 270_400;
    public static final int BLOCK_SIZE = ITEMS + 28_080;

    /** One extracted graphic: where it lives, how big it is, and what the engine calls it. */
    public static final class Entry {
        public final String group;
        public final int index;
        public final int offset;
        public final int width;
        public final int height;
        public final String note;

        Entry(String group, int index, int offset, int width, int height, String note) {
            this.group = group;
            this.index = index;
            this.offset = offset;
            this.width = width;
            this.height = height;
            this.note = note;
        }

        public String fileName() {
            return String.format("%s_%02d.png", group, index);
        }
    }

    private GfxLayout() {
    }

    /** sv_WallOffsets: 27 wall textures, 64x64, index 0..26. The 28th slot is a scratch buffer. */
    public static List<Entry> walls() {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i <= 26; i++) {
            out.add(new Entry("wall", i, i * TILE, 64, 64, wallNote(i)));
        }
        return out;
    }

    /**
     * Wall semantics, read off the comparisons in the engine and in the editor DrawMap routine.
     * The map cell stores (index * 2) | mirrorFlag, so the editor tests values of 2 * index.
     */
    private static String wallNote(int i) {
        switch (i) {
            case 12: return "floor texture (wall_floor1)";
            case 13: return "ceiling texture (wall_floor2)";
            case 15: return "door 1 closed (cell value 30)";
            case 16: return "door 1 open (cell value 32)";
            case 17: return "door 1 animating (cell value 34)";
            case 18: return "door 2 closed (cell value 36)";
            case 19: return "door 2 open (cell value 38)";
            case 20: return "door 2 animating (cell value 40)";
            case 22: return "switch out (cell value 44)";
            case 23: return "switch in (cell value 46)";
            default: return "";
        }
    }

    /** sv_CollumnOffsets: 8 full height columns, 16 half height ones, 4 corpse sprites. */
    public static List<Entry> columns() {
        List<Entry> out = new ArrayList<Entry>();
        int i = 0;
        // sprite 0..7 = cell values 1..8: full height columns, halves of tiles 27..30
        for (int t = 27; t <= 30; t++) {
            out.add(new Entry("column", i++, t * TILE, 32, 64, "full height"));
            out.add(new Entry("column", i++, t * TILE + HALF, 32, 64, "full height"));
        }
        // sprite 8..15  = cell values  9..16: raised columns, quadrants of tiles 31 and 32
        // sprite 16..23 = cell values 17..24: lowered columns, quadrants of tiles 33 and 34
        for (int t = 31; t <= 34; t++) {
            String kind = (t <= 32) ? "raised (upper half)" : "lowered (lower half)";
            out.add(new Entry("column", i++, t * TILE, 32, 32, kind));
            out.add(new Entry("column", i++, t * TILE + 32, 32, 32, kind));
            out.add(new Entry("column", i++, t * TILE + HALF, 32, 32, kind));
            out.add(new Entry("column", i++, t * TILE + HALF + 32, 32, 32, kind));
        }
        // sprite 24..27 = cell values 25..28: dead enemies, tail of ENEMY A and of ENEMY B
        out.add(new Entry("column", i++, 52 * TILE, 32, 32, "corpse"));
        out.add(new Entry("column", i++, 52 * TILE + 32, 32, 32, "corpse"));
        out.add(new Entry("column", i++, 64 * TILE + HALF, 32, 32, "corpse"));
        out.add(new Entry("column", i++, 64 * TILE + HALF + 32, 32, 32, "corpse"));
        return out;
    }

    /** sv_ItemOffsets: pickups 0..19 (32x32 quadrants of tiles 65..69). */
    public static List<Entry> items() {
        List<Entry> out = new ArrayList<Entry>();
        int i = 0;
        for (int t = 65; t <= 69; t++) {
            out.add(new Entry("item", i++, t * TILE, 32, 32, ""));
            out.add(new Entry("item", i++, t * TILE + 32, 32, 32, ""));
            out.add(new Entry("item", i++, t * TILE + HALF, 32, 32, ""));
            out.add(new Entry("item", i++, t * TILE + HALF + 32, 32, 32, ""));
        }
        return out;
    }

    /**
     * sv_ItemOffsets continued: in flight objects 49..64, i.e. projectiles, flames and explosions.
     * Sizes here are provisional; the plus/minus 16 byte adjustments in the table are vertical,
     * so these are narrow sprites stacked inside tiles 70 and 71. Verify against the dumped PNGs.
     */
    public static List<Entry> objects() {
        List<Entry> out = new ArrayList<Entry>();
        int i = 0;
        for (int tile = 70; tile <= 71; tile++) {
            for (int col = 0; col < 4; col++) {
                for (int row = 0; row < 4; row++) {
                    out.add(new Entry("object", i++,
                            tile * TILE + col * 16 * STRIDE + row * 16, 16, 16, ""));
                }
            }
        }
        return out;
    }

    /** sv_BloodOffsets: 3 full size blood decals then 4 wall plaques / card slots. */
    public static List<Entry> blood() {
        List<Entry> out = new ArrayList<Entry>();
        out.add(new Entry("blood", 0, 35 * TILE, 64, 64, "blood decal"));
        out.add(new Entry("blood", 1, 36 * TILE, 64, 64, "blood decal"));
        out.add(new Entry("blood", 2, 37 * TILE, 64, 64, "blood decal"));
        out.add(new Entry("blood", 3, 38 * TILE, 32, 32, "wall plaque / card slot"));
        out.add(new Entry("blood", 4, 38 * TILE + 32, 32, 32, "wall plaque / card slot"));
        out.add(new Entry("blood", 5, 38 * TILE + HALF, 32, 32, "wall plaque / card slot"));
        out.add(new Entry("blood", 6, 38 * TILE + HALF + 32, 32, 32, "wall plaque / card slot"));
        return out;
    }

    /**
     * sv_Enemy1Offsets: 24 frames in tiles 40..51, two 32x64 halves per tile.
     * sv_EnemyDirTab maps (facing, walk phase) onto these, 6 view directions of 4 frames.
     */
    public static List<Entry> enemy1() {
        List<Entry> out = new ArrayList<Entry>();
        int i = 0;
        for (int t = 40; t <= 51; t++) {
            out.add(new Entry("enemy1", i++, t * TILE, 32, 64, ""));
            out.add(new Entry("enemy1", i++, t * TILE + HALF, 32, 64, ""));
        }
        return out;
    }

    /** sv_Enemy2Offsets: 25 frames, starting at the right half of tile 52 (= start of ENEMY B). */
    public static List<Entry> enemy2() {
        List<Entry> out = new ArrayList<Entry>();
        int i = 0;
        out.add(new Entry("enemy2", i++, 52 * TILE + HALF, 32, 64, ""));
        for (int t = 53; t <= 63; t++) {
            out.add(new Entry("enemy2", i++, t * TILE, 32, 64, ""));
            out.add(new Entry("enemy2", i++, t * TILE + HALF, 32, 64, ""));
        }
        out.add(new Entry("enemy2", i++, 64 * TILE, 32, 64, ""));
        return out;
    }

    /**
     * The engine's offset tables are indexed 1-based: ShowCollumns does {@code subq #1,d4} before
     * scaling the index, and a cell stores 0 to mean "nothing here". So a stored cell value of n
     * selects sprite n - 1.
     *
     * The same table is walked past its end for items: dr_checkITEM adds 28, the number of column
     * entries, so item value n lands on sv_ItemOffsets entry n - 1.
     */
    public static int spriteIndexFor(int cellValue) {
        return cellValue - 1;
    }

    /** Vertical placement of a column, from the {@code cmpi #9} / {@code cmpi #17} tests. */
    public enum ColumnKind { FULL, RAISED, LOWERED }

    public static ColumnKind columnKind(int cellValue) {
        if (cellValue < 9) {
            return ColumnKind.FULL;
        }
        return (cellValue < 17) ? ColumnKind.RAISED : ColumnKind.LOWERED;
    }

    /**
     * Animated graphics, from sv_DoAnims and the sv_AnimOffsets table it reads.
     *
     * Once per pass of MAIN_LOOP the engine rewrites a few entries of the offset tables, so the
     * same cell value draws a different sprite each pass: torches gutter, screens flicker. Two
     * frames for the wall and the plaque, four for each of the three columns.
     *
     * Cell values are 1-based as everywhere else, so the sprite indices here are one less.
     */
    /**
     * The one animated wall: {@code move.l 4(a1,d0.w),23*4(a2)} rewrites sv_WallOffsets[23] with
     * tile 23 or tile 24, so wall sprite 23 alternates with sprite 24.
     */
    public static final int ANIMATED_WALL_SPRITE = 23;

    /** sv_BloodOffsets[5] alternates between the two halves that are blood sprites 5 and 6. */
    public static final int ANIMATED_PLAQUE_SPRITE = 5;

    /**
     * The three animated columns. Each cycles through four consecutive sprites, and each base is
     * the first of its group of four: sv_CollumnOffsets entries 4, 12 and 20.
     */
    public static boolean isAnimatedColumnSprite(int sprite) {
        return sprite == 4 || sprite == 12 || sprite == 20;
    }

    /** Sprite for a column at a given phase; unchanged for the ones that do not animate. */
    public static int columnSprite(int sprite, int phase) {
        return isAnimatedColumnSprite(sprite) ? sprite + (phase & 3) : sprite;
    }

    /** Sprite for a wall face at a given phase. Only sprite 23 animates, over two frames. */
    public static int wallSprite(int sprite, int phase) {
        return (sprite == ANIMATED_WALL_SPRITE) ? sprite + (phase & 1) : sprite;
    }

    /** Sprite for a wall plaque at a given phase. Only sprite 5 animates, over two frames. */
    public static int plaqueSprite(int sprite, int phase) {
        return (sprite == ANIMATED_PLAQUE_SPRITE) ? sprite + (phase & 1) : sprite;
    }

    /** sv_EnemyDirTab: frame index (already multiplied by 4) per view direction and walk phase. */
    public static final int[] ENEMY_DIR_TAB = {
            52, 56, 52, 56,
            44, 40, 48, 40,
            32, 36, 32, 36,
            4, 0, 8, 0,
            12, 16, 12, 16,
            24, 20, 28, 20,
    };

    public static List<Entry> all() {
        List<Entry> out = new ArrayList<Entry>();
        out.addAll(walls());
        out.addAll(columns());
        out.addAll(items());
        out.addAll(objects());
        out.addAll(blood());
        out.addAll(enemy1());
        out.addAll(enemy2());
        return out;
    }
}
