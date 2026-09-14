package citadel.game;

import java.nio.ByteBuffer;

import citadel.core.GameState;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The compass needle from the COMPASS routine.
 *
 * The original clears a 32 x 27 patch — 27 longwords, one bitplane — and draws a line from the
 * centre at (16, 13) to a point 11 pixels out, obtained by running (0, -11) through sv_rotate at
 * the player's angle. Screen Y grows downward, so that lands on the direction the player faces.
 */
public final class Compass {

    private static final int W = 32;
    private static final int H = 27;
    private static final int CENTRE_X = 16;
    private static final int CENTRE_Y = 13;
    private static final int NEEDLE = 11;

    private static final int BACKDROP = 0xa0101018;
    private static final int RING = 0xff404858;
    private static final int NEEDLE_COLOUR = 0xff40ff40;
    private static final int PIVOT = 0xffc0c0d0;

    private final Picture picture;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private final int[] argb = new int[W * H];
    private int lastAngle = -1;

    public Compass(AssetManager assetManager, int screenWidth, int screenHeight, float mapSide) {
        pixels = BufferUtils.createByteBuffer(W * H * 4);
        Image image = new Image(Image.Format.RGBA8, W, H, pixels, ColorSpace.Linear);
        texture = new Texture2D(image);
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);

        picture = new Picture("compass");
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        float scale = 4f;
        picture.setWidth(W * scale);
        picture.setHeight(H * scale);
        // tucked under the automap panel
        picture.setPosition(screenWidth - W * scale - 12, screenHeight - mapSide - H * scale - 20);
    }

    public Picture picture() {
        return picture;
    }

    public void update(GameState game) {
        if (game.angle == lastAngle) {
            return;
        }
        lastAngle = game.angle;
        paint(game.angle);
        upload();
    }

    private void paint(int angle) {
        java.util.Arrays.fill(argb, BACKDROP);

        // a ring to read the needle against
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int dx = x - CENTRE_X;
                int dy = y - CENTRE_Y;
                int r2 = dx * dx + dy * dy;
                if (r2 >= 11 * 11 && r2 <= 13 * 13) {
                    argb[y * W + x] = RING;
                }
            }
        }

        // sv_rotate(0, -11) at the player's angle, with screen Y growing downward
        double a = angle * 2.0 * Math.PI / 512.0;
        int tipX = CENTRE_X + (int) Math.round(-NEEDLE * Math.cos(a));
        int tipY = CENTRE_Y + (int) Math.round(-NEEDLE * Math.sin(a));
        line(CENTRE_X, CENTRE_Y, tipX, tipY, NEEDLE_COLOUR);
        argb[CENTRE_Y * W + CENTRE_X] = PIVOT;
    }

    /** The engine draws the needle with its own Bresenham; any straight line will do here. */
    private void line(int x0, int y0, int x1, int y1, int colour) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            if (x0 >= 0 && x0 < W && y0 >= 0 && y0 < H) {
                argb[y0 * W + x0] = colour;
            }
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private void upload() {
        pixels.clear();
        for (int c : argb) {
            pixels.put((byte) ((c >> 16) & 0xff));
            pixels.put((byte) ((c >> 8) & 0xff));
            pixels.put((byte) (c & 0xff));
            pixels.put((byte) ((c >>> 24) & 0xff));
        }
        pixels.flip();
        texture.getImage().setUpdateNeeded();
    }
}
