package citadel.game;

import java.nio.ByteBuffer;

import citadel.core.DeathWipe;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * sv_Death drawn: blood runs down the view in colour 10, three VBLANK frames to a pass.
 *
 * The model is in {@link DeathWipe}; this is one texture the width of the original's 320 columns,
 * stretched over the window, with each column filled down to its own running height.
 */
public final class BloodWipe {

    private final DeathWipe wipe = new DeathWipe();
    private final Picture picture;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private float frames;
    private boolean running;

    public BloodWipe(AssetManager assetManager, int width, int height) {
        pixels = BufferUtils.createByteBuffer(DeathWipe.COLUMNS * DeathWipe.ROWS * 4);
        Image image = new Image(Image.Format.RGBA8, DeathWipe.COLUMNS, DeathWipe.ROWS, pixels,
                ColorSpace.sRGB);
        texture = new Texture2D(image);
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);

        picture = new Picture("blood");
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        picture.setWidth(width);
        picture.setHeight(height);
        picture.setLocalTranslation(0f, 0f, CitadelApp.Z_TEXT + 0.5f);
    }

    public Picture picture() {
        return picture;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isDone() {
        return wipe.isDone();
    }

    public void start(com.jme3.scene.Node guiNode) {
        running = true;
        frames = 0f;
        if (picture.getParent() == null) {
            guiNode.attachChild(picture);
        }
    }

    public void clear() {
        running = false;
        picture.removeFromParent();
    }

    public void update(float tpf) {
        if (!running || wipe.isDone()) {
            return;
        }
        frames += tpf * citadel.core.GameState.FRAMES_PER_SECOND;
        boolean changed = false;
        while (frames >= DeathWipe.FRAMES_PER_PASS && !wipe.isDone()) {
            frames -= DeathWipe.FRAMES_PER_PASS;
            changed |= wipe.step();
        }
        if (changed) {
            paint();
        }
    }

    private void paint() {
        for (int x = 0; x < DeathWipe.COLUMNS; x++) {
            int h = wipe.height(x);
            for (int y = 0; y < h; y++) {
                // the image is bottom up and the blood comes down from the top
                int at = ((DeathWipe.ROWS - 1 - y) * DeathWipe.COLUMNS + x) * 4;
                pixels.put(at, (byte) ((DeathWipe.COLOUR >> 16) & 0xff));
                pixels.put(at + 1, (byte) ((DeathWipe.COLOUR >> 8) & 0xff));
                pixels.put(at + 2, (byte) (DeathWipe.COLOUR & 0xff));
                pixels.put(at + 3, (byte) 0xff);
            }
        }
        texture.getImage().setUpdateNeeded();
    }
}
