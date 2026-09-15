package citadel.game;

import citadel.core.BombGfx;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.ui.Picture;

/**
 * The warhead gauge DrawBomb paints.
 *
 * sv_Glowica starts at 0 and ti_Head takes 2 off it for each warhead section picked up, down to
 * -12; DrawBomb turns that into one of six stages and blits a 48 by 31 picture. Nothing is drawn
 * at all until the first section is in hand, which is the {@code beq.s db_1} at the top.
 *
 * The stages themselves are a circuit board filling up with components, ending in the finished
 * casing. They come from Store/Bomba.rawb, which the converter decodes; see {@link BombGfx}.
 */
public final class BombGauge {

    private static final int SCALE = 3;

    private final AssetManager assetManager;
    private final Assets assets;
    private final Picture picture;
    private int shown = Integer.MIN_VALUE;

    public BombGauge(AssetManager assetManager, Assets assets, float x, float y) {
        this.assetManager = assetManager;
        this.assets = assets;
        picture = new Picture("bomb");
        picture.setWidth(BombGfx.WIDTH * SCALE);
        picture.setHeight(BombGfx.HEIGHT * SCALE);
        picture.setLocalTranslation(x, y, CitadelApp.Z_FURNITURE);
    }

    public Picture picture() {
        return picture;
    }

    public void update(com.jme3.scene.Node guiNode, citadel.core.GameState game, int complex) {
        int stage = BombGfx.stageFor(game.inventory.warhead);
        if (stage == shown) {
            return;
        }
        shown = stage;
        if (stage < 0 || !assets.exists(complex, "bomb", stage)) {
            picture.removeFromParent();
            return;
        }
        TextureKey key = new TextureKey(assets.texturePath(complex, "bomb", stage), true);
        key.setGenerateMips(false);
        Texture tex = assetManager.loadTexture(key);
        tex.setMagFilter(Texture.MagFilter.Nearest);
        tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        picture.setTexture(assetManager, (Texture2D) tex, true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        if (picture.getParent() == null) {
            guiNode.attachChild(picture);
        }
    }
}
