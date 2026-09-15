package citadel.game;

import java.nio.ByteBuffer;

import citadel.core.Heart;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The heart monitor DRAW_HEART paints, which is also the exhaustion gauge.
 *
 * The original restores a 5 byte by 12 row patch of one bitplane, then walks 29 entries of the
 * sc_PosTab2 ring drawing one vertical run per column. The {@code bset/bset/bclr/bclr/bset}
 * pattern over five interleaved bitplanes picks out index 19, which every palette holds as the
 * same green.
 *
 * The faster the trace repeats, the more winded the player is — see {@link Heart}.
 */
public final class HeartMonitor {

    private static final int W = Heart.WIDTH;
    private static final int H = Heart.HEIGHT;
    private static final int SCALE = 4;

    private static final int BACKDROP = 0xa0101018;
    /** Palette index 19, #00CC00, the colour the bitplane pattern spells out. */
    private static final int TRACE = 0xff00cc00;

    private final Picture picture;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private final int[] argb = new int[W * H];

    public HeartMonitor(AssetManager assetManager) {
        pixels = BufferUtils.createByteBuffer(W * H * 4);
        Image image = new Image(Image.Format.RGBA8, W, H, pixels, ColorSpace.Linear);
        texture = new Texture2D(image);
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);

        picture = new Picture("heart");
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        picture.setWidth(W * SCALE);
        picture.setHeight(H * SCALE);
        picture.setPosition(12, 12);
    }

    public Picture picture() {
        return picture;
    }

    public void update(citadel.core.GameState game) {
        java.util.Arrays.fill(argb, BACKDROP);
        for (int[] column : game.heart.trace()) {
            int x = column[0];
            for (int i = 0; i < column[2]; i++) {
                int y = column[1] + i;
                if (x >= 0 && x < W && y >= 0 && y < H) {
                    argb[y * W + x] = TRACE;
                }
            }
        }

        pixels.clear();
        for (int v : argb) {
            pixels.put((byte) ((v >> 16) & 0xff));
            pixels.put((byte) ((v >> 8) & 0xff));
            pixels.put((byte) (v & 0xff));
            pixels.put((byte) ((v >> 24) & 0xff));
        }
        pixels.rewind();
        texture.getImage().setUpdateNeeded();
    }
}
