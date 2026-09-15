package citadel.game;

import java.nio.ByteBuffer;

import citadel.core.ChaosTable;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The TELEPORT transition: the view the player is leaving breaks up into the one they arrive in.
 *
 * The original has both frames in hand — the new view is already drawn into the hidden buffer —
 * and copies it into the shown one four pixels and two rows at a time, in the order
 * {@code Store/CHAOSTAC.DAT} gives, 160 cells a frame for 32 VBLANK frames. Since that file is a
 * permutation of 0..1023 and each entry is reused at five offsets 1024 apart, the 80 by 64 grid
 * of cells is covered exactly once and the dissolve finishes clean.
 *
 * Here the new view is simply what the scene renders, so what is needed is the *old* one, held
 * still on top and eaten away. That is one read of the framebuffer, taken on the frame the
 * teleport lands — before the camera is moved, which is why {@link CitadelApp} holds the camera
 * back for that single frame.
 */
public final class Dissolve implements SceneProcessor {

    private final AssetManager assetManager;
    private final ChaosTable chaos;

    private Renderer renderer;
    private boolean initialized;

    /** True for the one frame whose output we want kept. */
    private boolean arming;
    /** Set once the read has happened, so the app knows it can start playing the dissolve. */
    private boolean captured;

    private ByteBuffer frameBytes;
    private Texture2D texture;
    private Picture picture;
    private int width;
    private int height;

    private boolean running;
    private int frame;
    private float vblanks;

    public Dissolve(AssetManager assetManager, ChaosTable chaos, int width, int height) {
        this.assetManager = assetManager;
        this.chaos = chaos;
        this.width = width;
        this.height = height;
    }

    /** Asks for this frame's output to be kept. The read lands in {@link #postFrame}. */
    public void arm() {
        arming = true;
    }

    public boolean isCaptured() {
        return captured;
    }

    public boolean isRunning() {
        return running;
    }

    /** Puts the captured frame on screen and starts eating it away. */
    public void start(com.jme3.scene.Node guiNode) {
        if (!captured) {
            return;
        }
        running = true;
        frame = 0;
        vblanks = 0f;
        if (picture.getParent() == null) {
            guiNode.attachChild(picture);
        }
    }

    /**
     * Advances the dissolve on the VBLANK clock, since {@code te_LOOP} waits for one per frame.
     * Does nothing once every cell has been revealed.
     */
    public void update(float tpf) {
        if (!running) {
            return;
        }
        vblanks += tpf * citadel.core.GameState.FRAMES_PER_SECOND;
        while (vblanks >= 1f && running) {
            vblanks -= 1f;
            reveal(frame++);
            if (frame >= ChaosTable.FRAMES) {
                running = false;
                captured = false;
                picture.removeFromParent();
            }
        }
    }

    /** Punches one frame's worth of cells out of the held image, so the live view shows through. */
    private void reveal(int f) {
        for (int[] cell : chaos.frame(f)) {
            // each edge is taken to the next cell's edge, so rounding never leaves a seam
            int x0 = cell[0] * width / ChaosTable.COLUMNS;
            int x1 = (cell[0] + 1) * width / ChaosTable.COLUMNS;
            int y0 = cell[1] * height / ChaosTable.ROW_PAIRS;
            int y1 = (cell[1] + 1) * height / ChaosTable.ROW_PAIRS;
            for (int y = y0; y < y1 && y < height; y++) {
                // the framebuffer read comes back bottom up; the engine counts rows from the top
                int at = ((height - 1 - y) * width + x0) * 4 + 3;
                for (int x = x0; x < x1 && x < width; x++) {
                    frameBytes.put(at, (byte) 0);
                    at += 4;
                }
            }
        }
        texture.getImage().setUpdateNeeded();
    }

    // ------------------------------------------------------- SceneProcessor

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        renderer = rm.getRenderer();
        initialized = true;
    }

    @Override
    public void reshape(ViewPort vp, int w, int h) {
        // a resize invalidates the held image; the next teleport reads a fresh one
        captured = false;
        running = false;
        if (picture != null) {
            picture.removeFromParent();
        }
        frameBytes = null;
        width = w;
        height = h;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void preFrame(float tpf) {
    }

    @Override
    public void postQueue(RenderQueue rq) {
    }

    @Override
    public void postFrame(FrameBuffer out) {
        if (!arming || width <= 0 || height <= 0) {
            arming = false;
            return;
        }
        arming = false;
        if (frameBytes == null) {
            build();
        }
        frameBytes.clear();
        // out is null for the screen's own buffer, which is what the renderer then reads
        renderer.readFrameBufferWithFormat(out, frameBytes, Image.Format.BGRA8);
        frameBytes.rewind();
        // the read keeps whatever alpha the scene wrote; the held frame has to start opaque
        for (int i = 3; i < frameBytes.capacity(); i += 4) {
            frameBytes.put(i, (byte) 0xff);
        }
        texture.getImage().setUpdateNeeded();
        captured = true;
    }

    private void build() {
        frameBytes = BufferUtils.createByteBuffer(width * height * 4);
        Image image = new Image(Image.Format.BGRA8, width, height, frameBytes, ColorSpace.Linear);
        texture = new Texture2D(image);
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        picture = new Picture("dissolve");
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        picture.setWidth(width);
        picture.setHeight(height);
        // under the readout and the compass, which the engine draws after the dissolve
        picture.setLocalTranslation(0f, 0f, CitadelApp.Z_DISSOLVE);
    }

    @Override
    public void cleanup() {
        initialized = false;
    }

    @Override
    public void setProfiler(AppProfiler profiler) {
    }
}
