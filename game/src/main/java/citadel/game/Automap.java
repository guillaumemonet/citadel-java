package citadel.game;

import java.nio.ByteBuffer;

import citadel.core.GameState;
import citadel.core.Level;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The automap, drawn into a texture and shown in a corner of the screen.
 *
 * The engine keeps one bit per cell in sv_UserMap and reveals it in UpdateMap: the cell underfoot
 * plus every neighbour with no wall between, so a corridor fills in ahead of you. {@link GameState}
 * tracks the same thing; this just paints it.
 *
 * Only revealed cells are drawn, walls included, which is what makes it a map you earn rather than
 * a floor plan you are handed.
 */
public final class Automap {

    /** Pixels per cell in the map texture. */
    private static final int SCALE = 4;
    private static final int SIZE = Level.GRID * SCALE;

    private static final int FLOOR = 0x50404860;
    private static final int WALL = 0xffc0c0d0;
    private static final int DOOR = 0xff40c0f0;
    private static final int SWITCH = 0xffffc040;
    private static final int PLAYER = 0xff40ff40;

    private final Picture picture;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private final int[] argb = new int[SIZE * SIZE];
    private boolean visible = true;
    private int lastCells = -1;

    public Automap(AssetManager assetManager, int screenWidth, int screenHeight) {
        pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        Image image = new Image(Image.Format.RGBA8, SIZE, SIZE, pixels, ColorSpace.Linear);
        texture = new Texture2D(image);
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);

        picture = new Picture("automap");
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        int side = Math.min(256, screenHeight / 3);
        picture.setWidth(side);
        picture.setHeight(side);
        picture.setPosition(screenWidth - side - 12, screenHeight - side - 12);
    }

    public Picture picture() {
        return picture;
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggle() {
        visible = !visible;
        picture.setCullHint(visible
                ? com.jme3.scene.Spatial.CullHint.Never
                : com.jme3.scene.Spatial.CullHint.Always);
    }

    /** Repaints only when the revealed set or the player's cell has changed. */
    public void update(GameState game) {
        if (!visible) {
            return;
        }
        int signature = signatureOf(game);
        if (signature == lastCells) {
            return;
        }
        lastCells = signature;
        paint(game);
        upload();
    }

    private static int signatureOf(GameState game) {
        // cheap change detector: how much is revealed, plus where the player stands
        int n = 0;
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (game.isVisited(x, y)) {
                    n++;
                }
            }
        }
        return n * 65536 + game.cellX() * 256 + game.cellY();
    }

    private static final int BACKDROP = 0x90101018;

    private void paint(GameState game) {
        // A dim backdrop, so the map reads as a panel even before much of it is revealed.
        java.util.Arrays.fill(argb, BACKDROP);
        Level level = game.level;

        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (!game.isVisited(x, y)) {
                    continue;
                }
                // the texture's Y runs the other way to the grid's, so flip it here
                int px = x * SCALE;
                int py = (Level.GRID - 1 - y) * SCALE;
                fill(px, py, SCALE, SCALE, FLOOR);

                for (int d = 0; d < 4; d++) {
                    int tex = level.wallTexture(x, y, d);
                    if (tex == 0) {
                        continue;
                    }
                    int colour = level.doorState(x, y, d) != Level.DoorState.NONE ? DOOR
                            : level.isSwitch(x, y, d) ? SWITCH : WALL;
                    switch (d) {
                        case Level.NORTH_DIR: fill(px, py, SCALE, 1, colour); break;
                        case Level.SOUTH_DIR: fill(px, py + SCALE - 1, SCALE, 1, colour); break;
                        case Level.EAST_DIR:  fill(px + SCALE - 1, py, 1, SCALE, colour); break;
                        default:              fill(px, py, 1, SCALE, colour); break;
                    }
                }
            }
        }

        int px = game.cellX() * SCALE;
        int py = (Level.GRID - 1 - game.cellY()) * SCALE;
        fill(px + 1, py + 1, SCALE - 2, SCALE - 2, PLAYER);
    }

    private void fill(int x, int y, int w, int h, int colour) {
        for (int j = y; j < y + h; j++) {
            if (j < 0 || j >= SIZE) {
                continue;
            }
            for (int i = x; i < x + w; i++) {
                if (i >= 0 && i < SIZE) {
                    argb[j * SIZE + i] = colour;
                }
            }
        }
    }

    private void upload() {
        pixels.clear();
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            pixels.put((byte) ((c >> 16) & 0xff));
            pixels.put((byte) ((c >> 8) & 0xff));
            pixels.put((byte) (c & 0xff));
            pixels.put((byte) ((c >>> 24) & 0xff));
        }
        pixels.flip();
        texture.getImage().setUpdateNeeded();
    }
}
