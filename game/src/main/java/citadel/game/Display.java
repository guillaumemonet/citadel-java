package citadel.game;

import java.nio.ByteBuffer;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.scene.Node;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The server's screen: a 320 by 200 picture of palette indices and the colour registers that show
 * it, so the front end can do what the copper lists do — fade the registers, flash one of them,
 * give a few rows colours of their own — and have the picture follow.
 *
 * Colours are kept as Amiga 12 bit {@code 0RGB} words, the way the copper lists hold them, and
 * the fades work on those words exactly as {@code se_setcolors}, {@code fadecolors} and
 * {@code setcolors} do. Indices 32 to 63 are Extra Half-Brite when {@link #ehb} is on: the colour
 * 32 below at half brightness. Indices from 64 up are free for screens that need more registers
 * than a picture has, such as MAPA's status line, which the copper list switches to a palette of
 * its own.
 *
 * A HAM picture cannot be described by registers, so {@link #argb} can carry one directly.
 */
final class Display {

    static final int W = 320;
    static final int H = 200;
    static final int REGISTERS = 256;

    /** Palette indices, row major. */
    final int[] index = new int[W * H];
    /** The colour registers, 12 bit. */
    final int[] colours = new int[REGISTERS];
    /** Six planes without HAM, BPLCON0 $6300: 32 to 63 are 0 to 31 at half brightness. */
    boolean ehb;
    /** When not null, shown instead of {@link #index}: ARGB, W by H. */
    int[] argb;

    private final Picture picture;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private volatile boolean dirty = true;

    Display(AssetManager assetManager, int screenWidth, int screenHeight) {
        pixels = BufferUtils.createByteBuffer(W * H * 4);
        texture = new Texture2D(new Image(Image.Format.RGBA8, W, H, pixels, ColorSpace.sRGB));
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        picture = new Picture("display");
        picture.setTexture(assetManager, texture, false);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Off);
        picture.setWidth(screenWidth);
        picture.setHeight(screenHeight);
        picture.setLocalTranslation(0f, 0f, CitadelApp.Z_DISPLAY);
    }

    void attach(Node gui) {
        if (picture.getParent() == null) {
            gui.attachChild(picture);
        }
        dirty = true;
    }

    void detach() {
        picture.removeFromParent();
    }

    boolean isShowing() {
        return picture.getParent() != null;
    }

    /** Asks for the picture to be redrawn on the next {@link #upload}. */
    void touch() {
        dirty = true;
    }

    /** copper0: nothing but black. */
    void black() {
        java.util.Arrays.fill(index, 0);
        java.util.Arrays.fill(colours, 0);
        argb = null;
        ehb = false;
        touch();
    }

    /** Rebuilds the texture if anything changed; render thread only. */
    void upload() {
        if (!dirty) {
            return;
        }
        dirty = false;
        pixels.clear();
        for (int y = H - 1; y >= 0; y--) {       // the texture is bottom up
            for (int x = 0; x < W; x++) {
                int v = argb != null ? argb[y * W + x] : rgb(index[y * W + x]);
                pixels.put((byte) (v >> 16)).put((byte) (v >> 8)).put((byte) v).put((byte) 0xff);
            }
        }
        pixels.rewind();
        texture.getImage().setUpdateNeeded();
    }

    private int rgb(int i) {
        int c;
        if (ehb && i >= 32 && i < 64) {
            int v = colours[i - 32];
            c = (((v >> 8) & 0xf) >> 1) << 8 | (((v >> 4) & 0xf) >> 1) << 4 | ((v & 0xf) >> 1);
        } else {
            c = colours[i & (REGISTERS - 1)];
        }
        return argb12(c);
    }

    /** A 12 bit colour as ARGB, each nibble doubled up. */
    static int argb12(int c) {
        return 0xff000000 | ((c >> 8) & 0xf) * 0x110000 | ((c >> 4) & 0xf) * 0x1100
                | (c & 0xf) * 0x11;
    }

    /** ARGB back to 12 bits. */
    static int rgb12(int argb) {
        return (((argb >> 20) & 0xf) << 8) | (((argb >> 12) & 0xf) << 4) | ((argb >> 4) & 0xf);
    }

    // ------------------------------------------------------------- the fades

    /**
     * One step of {@code se_setcolors}: each component of each register below {@code count}
     * moves one towards the target, from wherever it is. Sixteen of them, two VBLANKs apart,
     * bring black all the way up.
     */
    void stepTowards(int[] target, int count) {
        for (int i = 0; i < count; i++) {
            int c = colours[i];
            int t = target[i];
            for (int shift = 0; shift <= 8; shift += 4) {
                if (((c >> shift) & 0xf) != ((t >> shift) & 0xf)) {
                    c += 1 << shift;
                }
            }
            colours[i] = c;
        }
        touch();
    }

    /** One step of {@code fadecolors}: every component that is not zero loses one. */
    void stepDown(int count) {
        for (int i = 0; i < count; i++) {
            int c = colours[i];
            for (int shift = 0; shift <= 8; shift += 4) {
                if (((c >> shift) & 0xf) != 0) {
                    c -= 1 << shift;
                }
            }
            colours[i] = c;
        }
        touch();
    }

    /** {@code p_SetC} at step d0: each component of the target times d0 / 16. */
    void scaled(int[] target, int count, int d0) {
        for (int i = 0; i < count; i++) {
            int t = target[i];
            int b = ((t & 0xf) * d0) >> 4;
            int g = (((t & 0xf0) * d0) >> 4) & 0xf0;
            int r = (((t & 0xf00) * d0) >> 4) & 0xf00;
            colours[i] = r | g | b;
        }
        touch();
    }
}
