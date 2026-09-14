package citadel.tools;

import java.io.File;

import citadel.core.GfxLayout;
import citadel.core.Level;
import citadel.core.MapFile;

/**
 * Prints the decoded contents of a small window of a level's grid. Handy when something looks
 * wrong in the 3D view and you need to know what the data actually says about a given cell.
 *
 * <pre>
 *   java -cp ... citadel.tools.Inspect Data/GAME/MAPS/LEVEL01A.MAP 24 7 [radius]
 * </pre>
 */
public final class Inspect {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: Inspect <map file> <cellX> <cellY> [radius]");
            return;
        }
        Level level = Level.load(new File(args[0]));
        int cx = Integer.parseInt(args[1]);
        int cy = Integer.parseInt(args[2]);
        int r = (args.length > 3) ? Integer.parseInt(args[3]) : 2;

        System.out.println(level);
        System.out.println("species 1: " + level.enemyTemplates[0]);
        System.out.println("species 2: " + level.enemyTemplates[1]);
        System.out.println();

        for (int y = cy + r; y >= cy - r; y--) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (!Level.inside(x, y)) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("(%2d,%2d)", x, y));
                if (x == cx && y == cy) {
                    sb.append(" <=");
                }
                for (int d = 0; d < 4; d++) {
                    int t = level.wallTexture(x, y, d);
                    if (t == 0) {
                        continue;
                    }
                    sb.append("  ").append(MapFile.DIR_NAMES[d]).append("=wall").append(t);
                    if (level.wallMirrored(x, y, d)) {
                        sb.append("(mirror)");
                    }
                    if (level.doorState(x, y, d) != Level.DoorState.NONE) {
                        sb.append("[").append(level.doorState(x, y, d)).append("]");
                    }
                    if (level.isSwitch(x, y, d)) {
                        sb.append("[switch]");
                    }
                    if (level.wallBlood(x, y, d) != 0) {
                        sb.append("[blood").append(level.wallBlood(x, y, d)).append("]");
                    }
                    if (level.wallPlaque(x, y, d) != 0) {
                        sb.append("[plaque").append(level.wallPlaque(x, y, d)).append("]");
                    }
                }
                if (level.column(x, y) != 0) {
                    int v = level.column(x, y);
                    sb.append("  column=").append(v)
                            .append(" -> column_").append(GfxLayout.spriteIndexFor(v))
                            .append(" ").append(GfxLayout.columnKind(v));
                }
                if (level.item(x, y) != 0) {
                    int v = level.item(x, y);
                    sb.append("  item=").append(v)
                            .append(" -> item_").append(GfxLayout.spriteIndexFor(v));
                }
                if (level.corpse(x, y) != 0) {
                    sb.append("  corpse=").append(level.corpse(x, y));
                }
                if (level.isBlocked(x, y)) {
                    sb.append("  BLOCKED");
                }
                if (level.enemyRef(x, y) != 0) {
                    sb.append("  enemy slot ").append(level.enemyRef(x, y));
                }
                System.out.println(sb);
            }
            System.out.println();
        }
    }
}
