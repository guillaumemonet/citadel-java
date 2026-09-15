package citadel.game;

import java.nio.ByteBuffer;
import java.util.Random;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The interference burst from sv_MAKE_SZUM — "szum" is Polish for static.
 *
 * The routine runs 81 times over the 128 row view. Each pass pulls two words out of Amiga ROM,
 * exclusive-ors them down to a row number in 0..127 via {@code andi #$7f}, looks that row up in
 * sv_ScrOffTab and fills it the whole way across with the byte $88.
 *
 * $88 is a colour index, not a dither: the ConvBits macro rolls bit 7 into bitplane 0 and bit 3
 * into bitplane 4, so a byte with just those two bits set is index 1 + 16 = 17, which every
 * complex palette has as the same pale blue #9999DD. So this is 81 solid lines, drawn with
 * repetition, which covers a little over half the view.
 *
 * Because the rows are solid and full width the whole effect fits in a texture one pixel wide,
 * stretched across the window; 81 draws into 128 rows is cheap enough to redo on every burst.
 */
public final class Interference {

    /** sv_ViewHeigth, and the size of sv_ScrOffTab: 128 rows. */
    private static final int ROWS = 128;
    /** {@code moveq #80,d0} then dbf, so 81 rows are hit. */
    private static final int STREAKS = 81;
    /** Palette index 17, a fixed engine colour identical in all eight complexes. */
    private static final int COLOUR = 0xff9999dd;


    private final Picture picture;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private final Random rnd = new Random();
    private int shownFrame = -1;

    public Interference(AssetManager assetManager, int screenWidth, int screenHeight) {
        pixels = BufferUtils.createByteBuffer(ROWS * 4);
        Image image = new Image(Image.Format.RGBA8, 1, ROWS, pixels, ColorSpace.sRGB);
        texture = new Texture2D(image);
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);

        picture = new Picture("interference");
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        picture.setWidth(screenWidth);
        picture.setHeight(screenHeight);
        picture.setLocalTranslation(0f, 0f, CitadelApp.Z_INTERFERENCE);
    }

    public Picture picture() {
        return picture;
    }

    /**
     * Shows or hides the burst. The engine re-reads ROM every pass it draws, so the rows differ
     * from one pass to the next; {@code frame} changing is the signal to re-roll them.
     */
    public void update(com.jme3.scene.Node guiNode, boolean active, int frame) {
        if (!active) {
            shownFrame = -1;
            picture.removeFromParent();
            return;
        }
        if (frame != shownFrame) {
            shownFrame = frame;
            roll();
        }
        if (picture.getParent() == null) {
            guiNode.attachChild(picture);
        }
    }

    private void roll() {
        pixels.clear();
        for (int i = 0; i < ROWS * 4; i++) {
            pixels.put((byte) 0);
        }
        for (int i = 0; i < STREAKS; i++) {
            // rows are picked with repetition, exactly as the exclusive-or of two ROM words is
            int row = rnd.nextInt(ROWS);
            int at = row * 4;
            pixels.put(at, (byte) ((COLOUR >> 16) & 0xff));
            pixels.put(at + 1, (byte) ((COLOUR >> 8) & 0xff));
            pixels.put(at + 2, (byte) (COLOUR & 0xff));
            pixels.put(at + 3, (byte) ((COLOUR >> 24) & 0xff));
        }
        pixels.rewind();
        texture.getImage().setUpdateNeeded();
    }
}
