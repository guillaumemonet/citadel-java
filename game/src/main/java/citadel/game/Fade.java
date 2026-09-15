package citadel.game;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * p_FadeColors: the screen goes to black in seventeen steps, two VBLANK frames apart.
 *
 * Each step multiplies every four bit palette component by {@code d0 / 16} with d0 counting down
 * from 16, which is the same thing as blending the whole picture towards black — so one black
 * plate whose alpha climbs in the same seventeen steps is exact, not an approximation.
 */
public final class Fade {

    /** {@code move #16,d0} down to and including zero. */
    public static final int STEPS = 17;
    /** The two VBLANKs at the end of p_SetC. */
    public static final int FRAMES_PER_STEP = 2;

    private final Picture picture;
    private final java.nio.ByteBuffer pixel;
    private final Texture2D texture;
    private int step = -1;
    private float frames;

    public Fade(AssetManager assetManager, int width, int height) {
        pixel = BufferUtils.createByteBuffer(new byte[]{0, 0, 0, 0});
        texture = new Texture2D(new Image(Image.Format.RGBA8, 1, 1, pixel, ColorSpace.sRGB));
        picture = new Picture("fade");
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        picture.setWidth(width);
        picture.setHeight(height);
        picture.setLocalTranslation(0f, 0f, CitadelApp.Z_TEXT + 1f);
    }

    public Picture picture() {
        return picture;
    }

    public boolean isRunning() {
        return step >= 0 && step < STEPS;
    }

    public boolean isDone() {
        return step >= STEPS;
    }

    public void start(com.jme3.scene.Node guiNode) {
        step = 0;
        frames = 0f;
        apply();
        if (picture.getParent() == null) {
            guiNode.attachChild(picture);
        }
    }

    /** Takes the plate off and resets, for when the next screen is ready. */
    public void clear() {
        step = -1;
        picture.removeFromParent();
    }

    public void update(float tpf) {
        if (!isRunning()) {
            return;
        }
        frames += tpf * citadel.core.GameState.FRAMES_PER_SECOND;
        while (frames >= FRAMES_PER_STEP && isRunning()) {
            frames -= FRAMES_PER_STEP;
            step++;
            apply();
        }
    }

    /** The plate is one pixel, so the step is just its alpha byte. */
    private void apply() {
        float alpha = Math.min(1f, step / (float) (STEPS - 1));
        pixel.put(3, (byte) Math.round(alpha * 255f));
        texture.getImage().setUpdateNeeded();
    }
}
