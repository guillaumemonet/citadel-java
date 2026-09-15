package citadel.game;

import java.io.File;
import java.nio.ByteBuffer;

import citadel.core.WeaponChange;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The weapon in hand, and the way a new one arrives.
 *
 * The item sprites double as the held ones: an item worth n on the floor is sprite n-1, and
 * picking it up puts it in slot n-1, so the slot number indexes the sprite directly. Slot 0 is
 * the bare hand.
 *
 * ci_NewWeapon arms sv_ChaosAddr and ci_DrawWeapon then copies the new sprite in one pixel at a
 * time, 32 pixels every VBLANK in the order {@code Store/CHAOSTAB.DAT} gives, which is a
 * permutation of the 32 by 27 window. 27 frames, and the weapon has swapped. Nothing rearms it
 * when the trigger is pulled — the original has no recoil.
 *
 * The table addresses 27 rows and the converted sprites are square, so the rows are stretched to
 * fit, the same accommodation {@link Dissolve} makes for the window size.
 */
public final class WeaponDisplay {

    private final AssetManager assetManager;
    private final Assets assets;
    private final WeaponChange order;

    private Picture picture;
    private Texture2D texture;
    private ByteBuffer pixels;
    /** The finished sprite, as ARGB; the texture starts empty and is filled from this. */
    private int[] sprite;
    private int width;
    private int height;

    private int shown = -1;
    private int frame;
    private float vblanks;
    private boolean revealing;

    public WeaponDisplay(AssetManager assetManager, Assets assets, WeaponChange order) {
        this.assetManager = assetManager;
        this.assets = assets;
        this.order = order;
    }

    /**
     * Picks up a change of weapon and starts the reveal. Call every frame; it is a no-op while
     * the weapon in hand has not changed.
     */
    public void sync(com.jme3.scene.Node guiNode, citadel.core.GameState game, int complex) {
        int slot = game.inventory.current;
        if (slot == shown) {
            return;
        }
        shown = slot;
        if (!assets.exists(complex, "item", slot)) {
            if (picture != null) {
                picture.removeFromParent();
            }
            revealing = false;
            return;
        }
        if (!load(assets.texturePath(complex, "item", slot))) {
            return;
        }
        if (picture == null) {
            picture = new Picture("weapon");
        }
        picture.setTexture(assetManager, texture, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        // sv_Weapon is a 32 by 27 slot in the status panel; HudPanel places it
        if (picture.getParent() == null) {
            guiNode.attachChild(picture);
        }
        revealing = true;
        frame = 0;
        vblanks = 0f;
    }

    /** The picture, once a weapon has been shown; null before that. */
    public Picture picture() {
        return picture;
    }

    /** ci_DrawWeapon is called from NewLev3, so the reveal runs on the VBLANK clock. */
    public void update(float tpf) {
        if (!revealing) {
            return;
        }
        vblanks += tpf * citadel.core.GameState.FRAMES_PER_SECOND;
        while (vblanks >= 1f && revealing) {
            vblanks -= 1f;
            reveal(frame++);
            if (frame >= WeaponChange.FRAMES) {
                revealing = false;
            }
        }
    }

    private void reveal(int f) {
        for (int[] p : order.frame(f)) {
            int x0 = p[0] * width / WeaponChange.WIDTH;
            int x1 = (p[0] + 1) * width / WeaponChange.WIDTH;
            int y0 = p[1] * height / WeaponChange.HEIGHT;
            int y1 = (p[1] + 1) * height / WeaponChange.HEIGHT;
            for (int y = y0; y < y1 && y < height; y++) {
                for (int x = x0; x < x1 && x < width; x++) {
                    put(x, y, sprite[y * width + x]);
                }
            }
        }
        texture.getImage().setUpdateNeeded();
    }

    /** jME textures are bottom up and the sprite rows are read top down, hence the flip. */
    private void put(int x, int y, int argb) {
        int at = ((height - 1 - y) * width + x) * 4;
        pixels.put(at, (byte) ((argb >> 16) & 0xff));
        pixels.put(at + 1, (byte) ((argb >> 8) & 0xff));
        pixels.put(at + 2, (byte) (argb & 0xff));
        pixels.put(at + 3, (byte) ((argb >>> 24) & 0xff));
    }

    /** Reads the converted PNG straight off disk, since the reveal needs the pixels. */
    private boolean load(String path) {
        try {
            File f = new File(assets.assetRoot(), path);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
            if (img == null) {
                return false;
            }
            width = img.getWidth();
            height = img.getHeight();
            sprite = img.getRGB(0, 0, width, height, null, 0, width);
            if (pixels == null || pixels.capacity() != width * height * 4) {
                // a fresh buffer is already zeroed, so the first weapon fades up out of nothing
                pixels = BufferUtils.createByteBuffer(width * height * 4);
                // sRGB, not Linear: gamma correction is on, so a texture that claims to be
                // linear skips the conversion on sampling and comes out washed out
                texture = new Texture2D(new Image(Image.Format.RGBA8, width, height, pixels,
                        ColorSpace.sRGB));
                texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
                texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
            }
            // whatever was in hand stays put and is overwritten a pixel at a time, which is what
            // ci_DrawWeapon does: it copies sv_ItemBuf into sv_Weapon, it does not clear first
            return true;
        } catch (Exception e) {
            System.out.println("cannot read " + path + ": " + e);
            return false;
        }
    }
}
