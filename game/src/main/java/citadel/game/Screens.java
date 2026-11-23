package citadel.game;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture2D;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The port's own quick save and load picker, F5 and F9 in play: a list over the hangar picture.
 *
 * The original only saves from ENDLEVEL_PART and only loads from the hangar's LOAD GAME, both of
 * which {@link Server} draws as SERVER does; this is an extra and looks like one.
 */
public final class Screens {

    /** Point size of the caption text when it is not being squeezed into the hangar panel. */
    private static final float CAPTION_SIZE = 22f;

    private final AssetManager assetManager;
    private final Node gui;
    private final Assets assets;
    private final int width;
    private final int height;

    private final Picture picture = new Picture("screen");
    private final BitmapText caption;
    private boolean showing;

    /** Textures already made, so a screen shown twice is uploaded once. */
    private final Map<Assets.Sprite, Texture2D> textures = new IdentityHashMap<Assets.Sprite, Texture2D>();

    public Screens(AssetManager assetManager, Node gui, BitmapFont font,
                   Assets assets, int width, int height) {
        this.assetManager = assetManager;
        this.gui = gui;
        this.assets = assets;
        this.width = width;
        this.height = height;

        picture.setWidth(width);
        picture.setHeight(height);
        picture.setPosition(0, 0);

        caption = new BitmapText(font);
        caption.setSize(CAPTION_SIZE);
        caption.setColor(ColorRGBA.White);
    }

    public boolean isShowing() {
        return showing;
    }

    /**
     * The hangar is where the server keeps its menus, and the picture has a text panel built into
     * it. MainServer copies from {@code SERoff}, which is {@code iff_screen+[53*40*6]+12}, and
     * walks 612 plane rows of 16 bytes; interleaved six planes deep that is x 96 to 223 and rows
     * 53 to 154. So the slot picker goes in there rather than on a black plate.
     */
    private static final int PANEL_X = 96;
    private static final int PANEL_ROW = 53;
    private static final int PANEL_W = 16 * 8;
    private static final int PANEL_H = 612 / 6;

    /** True while the caption belongs in the hangar's panel rather than centred on the screen. */
    private boolean inPanel;

    /** A list with one entry marked, used for the five save slots. */
    public void showList(String heading, List<String> entries, int selected, String prompt) {
        StringBuilder sb = new StringBuilder(heading).append("\n\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append(i == selected ? "> " : "  ").append(entries.get(i)).append('\n');
        }
        sb.append('\n').append(prompt);
        inPanel = true;
        show(read(Assets::hangar), sb.toString());
        inPanel = false;
    }

    /** Fits a block into the hangar's own panel, since that is the box the artwork leaves. */
    private void placeInHangarPanel(String text) {
        float sx = width / 320f;
        float sy = height / 200f;
        float boxW = PANEL_W * sx;
        float boxH = PANEL_H * sy;
        int lines = Math.max(1, text.split("\n", -1).length);

        float size = Math.min(CAPTION_SIZE, boxH / (lines + 1));
        caption.setSize(size);
        caption.setText(text);
        while (caption.getLineWidth() > boxW && size > 7f) {
            size -= 1f;
            caption.setSize(size);
            caption.setText(text);
        }
        float used = caption.getLineHeight() * caption.getLineCount();
        float top = (200 - PANEL_ROW) * sy - Math.max(0f, (boxH - used) / 2f);
        caption.setLocalTranslation(PANEL_X * sx + 4f, top, 1f);
    }

    public void hide() {
        showing = false;
        picture.removeFromParent();
        caption.removeFromParent();
        showDim(false);
    }

    private interface Screen {
        Assets.Sprite read(Assets assets) throws Exception;
    }

    /** One of the pictures, or null, which shows the text alone, when it cannot be read. */
    private Assets.Sprite read(Screen s) {
        try {
            return s.read(assets);
        } catch (Exception e) {
            System.out.println("cannot read a screen: " + e.getMessage());
            return null;
        }
    }

    /** A flat panel behind text-only screens, so a menu is not read against the level. */
    private Picture dim;

    private void showDim(boolean on) {
        if (!on) {
            if (dim != null) {
                dim.removeFromParent();
            }
            return;
        }
        if (dim == null) {
            com.jme3.texture.Image img = new com.jme3.texture.Image(
                    com.jme3.texture.Image.Format.RGBA8, 1, 1,
                    BufferUtils.createByteBuffer(new byte[]{0, 0, 0, (byte) 0xd8}),
                    com.jme3.texture.image.ColorSpace.sRGB);
            dim = new Picture("dim");
            dim.setTexture(assetManager, new com.jme3.texture.Texture2D(img), true);
            dim.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
            dim.setWidth(width);
            dim.setHeight(height);
            dim.setPosition(0, 0);
        }
        if (dim.getParent() == null) {
            gui.attachChild(dim);
        }
    }

    private void show(Assets.Sprite image, String text) {
        showing = true;
        showDim(image == null && text != null && !text.isEmpty());
        if (image != null) {
            try {
                Texture2D tex = textures.computeIfAbsent(image, s -> Assets.texture(s, true));
                picture.setTexture(assetManager, tex, true);
                picture.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Off);
                if (picture.getParent() == null) {
                    gui.attachChild(picture);
                }
            } catch (RuntimeException e) {
                picture.removeFromParent();
            }
        } else {
            picture.removeFromParent();
        }

        if (text == null || text.isEmpty()) {
            caption.removeFromParent();
            return;
        }
        if (inPanel) {
            placeInHangarPanel(text);
        } else {
            caption.setSize(CAPTION_SIZE);
            caption.setText(text);
            // A single line sits low over artwork; a block is centred, being the screen itself.
            float block = caption.getLineHeight() * caption.getLineCount();
            float y = (caption.getLineCount() > 1)
                    ? (height + block) / 2f
                    : block + height * 0.06f;
            caption.setLocalTranslation((width - caption.getLineWidth()) / 2f, y, 1f);
        }
        if (caption.getParent() == null) {
            gui.attachChild(caption);
        }
        caption.setCullHint(Spatial.CullHint.Never);
    }
}
