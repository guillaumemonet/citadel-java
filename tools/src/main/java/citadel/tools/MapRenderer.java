package citadel.tools;

import citadel.core.GfxLayout;
import citadel.core.Io;
import citadel.core.MapFile;
import citadel.core.Palette;
import citadel.core.VirReader;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Renders a .MAP as a top down overview, mainly so the decoded cell format can be eyeballed. */
public final class MapRenderer {

    private static final int CELL = 12;
    private static final int PAD = 16;

    private static final Color BG = new Color(0x14, 0x14, 0x18);
    private static final Color GRID = new Color(0x24, 0x24, 0x2c);
    private static final Color WALL = new Color(0xd0, 0xd0, 0xd8);
    private static final Color DOOR = new Color(0x50, 0xc0, 0xf0);
    private static final Color SWITCH = new Color(0xff, 0xc0, 0x40);
    private static final Color BLOCK = new Color(0x40, 0x20, 0x20);
    private static final Color COLUMN = new Color(0x90, 0x90, 0xa8);
    private static final Color ITEM = new Color(0x50, 0xe0, 0x70);
    private static final Color ENEMY = new Color(0xe0, 0x40, 0x40);
    private static final Color SPAWN = new Color(0x40, 0x80, 0xff);
    private static final Color EXIT = new Color(0x40, 0xff, 0x40);

    private MapRenderer() {
    }

    public static BufferedImage render(MapFile map) {
        int n = MapFile.GRID;
        int size = n * CELL + PAD * 2;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(BG);
        g.fillRect(0, 0, size, size);

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int px = PAD + x * CELL;
                int py = PAD + y * CELL;

                if (map.blocked(x, y)) {
                    g.setColor(BLOCK);
                    g.fillRect(px, py, CELL, CELL);
                }
                g.setColor(GRID);
                g.drawRect(px, py, CELL, CELL);

                // walls, one line per face
                g.setStroke(new BasicStroke(2f));
                for (int d = 0; d < 4; d++) {
                    int v = map.wall(x, y, d) & 0x3e;
                    if (v == 0) {
                        continue;
                    }
                    g.setColor(MapFile.isDoor(v) ? DOOR : MapFile.isSwitch(v) ? SWITCH : WALL);
                    switch (d) {
                        case MapFile.NORTH: g.drawLine(px, py, px + CELL, py); break;
                        case MapFile.EAST:  g.drawLine(px + CELL, py, px + CELL, py + CELL); break;
                        case MapFile.SOUTH: g.drawLine(px, py + CELL, px + CELL, py + CELL); break;
                        default:            g.drawLine(px, py, px, py + CELL); break;
                    }
                }
                g.setStroke(new BasicStroke(1f));

                if (map.column(x, y) != 0) {
                    g.setColor(COLUMN);
                    g.fillRect(px + 4, py + 4, 5, 5);
                }
                if (map.item(x, y) != 0) {
                    g.setColor(ITEM);
                    g.fillOval(px + 4, py + 4, 5, 5);
                }
                if (map.enemy(x, y) != 0) {
                    g.setColor(ENEMY);
                    g.fillOval(px + 3, py + 3, 7, 7);
                }
            }
        }

        markCell(g, map.spawnX(), map.spawnY(), SPAWN);
        markCell(g, map.exitX(), map.exitY(), EXIT);

        g.dispose();
        return img;
    }

    private static void markCell(Graphics2D g, int x, int y, Color c) {
        if (x < 0 || y < 0 || x >= MapFile.GRID || y >= MapFile.GRID) {
            return;
        }
        g.setColor(c);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(PAD + x * CELL + 1, PAD + y * CELL + 1, CELL - 2, CELL - 2);
        g.setStroke(new BasicStroke(1f));
    }
}
