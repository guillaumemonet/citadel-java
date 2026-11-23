package citadel.game;

import citadel.core.BombGfx;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.ui.Picture;

/**
 * The warhead gauge DrawBomb paints.
 *
 * sv_Glowica starts at 0 and ti_Head takes 2 off it for each warhead section picked up, down to
 * -12; DrawBomb turns that into one of six stages and blits a 48 by 31 picture. Until the first
 * section is in hand it takes the {@code beq.s db_1} at the top instead, "only clear": for all
 * 31 rows and five planes it keeps the first word's top four bits and the third word's bottom
 * four and zeroes the rest, so x 4 to 43 of the gauge goes to colour 0 and the panel artwork's
 * empty socket is blanked out.
 *
 * The stages themselves are a circuit board filling up with components, ending in the finished
 * casing. They come from Store/Bomba.rawb, decoded in {@link Assets}; see {@link BombGfx}.
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

    private int shownComplex = -1;

    public void update(com.jme3.scene.Node guiNode, citadel.core.GameState game, int complex) {
        int stage = BombGfx.stageFor(game.inventory.warhead);
        if (stage == shown && complex == shownComplex) {
            return;
        }
        shown = stage;
        shownComplex = complex;
        if (stage < 0) {
            // db_1: x 4 to 43 in colour 0, the four pixels either side left alone
            int colour;
            try {
                colour = assets.panelColour(complex, 0);
            } catch (java.io.IOException e) {
                colour = 0xff000000;
            }
            int[] argb = new int[BombGfx.WIDTH * BombGfx.HEIGHT];
            for (int y = 0; y < BombGfx.HEIGHT; y++) {
                for (int x = 4; x < BombGfx.WIDTH - 4; x++) {
                    argb[y * BombGfx.WIDTH + x] = colour;
                }
            }
            show(guiNode, new Assets.Sprite(BombGfx.WIDTH, BombGfx.HEIGHT, argb));
            return;
        }
        if (!assets.exists(complex, "bomb", stage)) {
            picture.removeFromParent();
            return;
        }
        show(guiNode, assets.sprite(complex, "bomb", stage));
    }

    private void show(com.jme3.scene.Node guiNode, Assets.Sprite sprite) {
        picture.setTexture(assetManager, Assets.texture(sprite, true), true);
        picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        if (picture.getParent() == null) {
            guiNode.attachChild(picture);
        }
    }
}
