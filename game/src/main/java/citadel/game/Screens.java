package citadel.game;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The full-screen screens that bracket play: the intro animation, the title, and the end-of-game
 * cards.
 *
 * All of it comes from converted originals — the IFF ANIMs under Data/INTRO/ANIM2 and the title
 * artwork beside them — so this is presentation only, with no rules attached.
 */
public final class Screens {

    /** Frames of the intro shown per second, roughly the rate the Amiga player ran them at. */
    private static final float INTRO_FPS = 12f;

    private final AssetManager assetManager;
    private final Node gui;
    private final File assetRoot;
    private final int width;
    private final int height;

    private final Picture picture = new Picture("screen");
    private final BitmapText caption;
    private boolean showing;

    private List<String> introFrames = new ArrayList<String>();
    private int introIndex;
    private float introTimer;

    public Screens(AssetManager assetManager, Node gui, BitmapFont font,
                   File assetRoot, int width, int height) {
        this.assetManager = assetManager;
        this.gui = gui;
        this.assetRoot = assetRoot;
        this.width = width;
        this.height = height;

        picture.setWidth(width);
        picture.setHeight(height);
        picture.setPosition(0, 0);

        caption = new BitmapText(font);
        caption.setSize(22f);
        caption.setColor(ColorRGBA.White);
    }

    public boolean isShowing() {
        return showing;
    }

    /** Collects the intro frames, in the order the original plays its three sequences. */
    public boolean hasIntro() {
        return !introFrameList().isEmpty();
    }

    private List<String> introFrameList() {
        if (!introFrames.isEmpty()) {
            return introFrames;
        }
        for (String anim : new String[]{"start", "kabina", "explozycja"}) {
            File dir = new File(assetRoot, "intro/" + anim);
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            java.util.Arrays.sort(files);
            for (File f : files) {
                if (f.getName().endsWith(".png")) {
                    introFrames.add("intro/" + anim + "/" + f.getName());
                }
            }
        }
        return introFrames;
    }

    public void startIntro() {
        introFrameList();
        introIndex = 0;
        introTimer = 0f;
        if (introFrames.isEmpty()) {
            return;
        }
        show(introFrames.get(0), "");
    }

    /** @return true once the intro has run out of frames */
    public boolean advanceIntro(float tpf) {
        if (introFrames.isEmpty()) {
            return true;
        }
        introTimer += tpf;
        while (introTimer >= 1f / INTRO_FPS) {
            introTimer -= 1f / INTRO_FPS;
            introIndex++;
            if (introIndex >= introFrames.size()) {
                return true;
            }
            show(introFrames.get(introIndex), "");
        }
        return false;
    }

    /** The title card, with whatever prompt the caller wants under it. */
    public void showTitle(String prompt) {
        show(firstExisting(
                "intro/art/CITADEL_title_ENG.iff.png",
                "intro/art/Plansza_Tytulowa.iff.png",
                "intro/art/Winieta_EngCover.iff.png"), prompt);
    }

    public void showMessage(String text) {
        show(null, text);
    }

    /**
     * The death card. Store/zabity.rawb is the picture the server shows when you are killed, and
     * the converter now decodes it; the prompt goes under it.
     */
    public void showDeath(String prompt) {
        show(firstExisting("screens/zabity.png"), prompt);
    }

    /**
     * A list with one entry marked, used for the five save slots. Drawn in a monospaced-ish block
     * over black, which is as close as this gets to the server's own slot picker.
     */
    public void showList(String heading, List<String> entries, int selected, String prompt) {
        StringBuilder sb = new StringBuilder(heading).append("\n\n");
        for (int i = 0; i < entries.size(); i++) {
            sb.append(i == selected ? " > " : "   ").append(entries.get(i)).append('\n');
        }
        sb.append('\n').append(prompt);
        show(null, sb.toString());
    }

    public void hide() {
        showing = false;
        picture.removeFromParent();
        caption.removeFromParent();
        showDim(false);
    }

    private String firstExisting(String... paths) {
        for (String p : paths) {
            if (new File(assetRoot, p).isFile()) {
                return p;
            }
        }
        return null;
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

    private void show(String texturePath, String text) {
        showing = true;
        showDim(texturePath == null && text != null && !text.isEmpty());
        if (texturePath != null) {
            try {
                TextureKey key = new TextureKey(texturePath, true);
                key.setGenerateMips(false);
                Texture tex = assetManager.loadTexture(key);
                tex.setMagFilter(Texture.MagFilter.Nearest);
                tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
                picture.setTexture(assetManager, (com.jme3.texture.Texture2D) tex, true);
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
        caption.setText(text);
        // A single line sits low, over artwork; a block is centred, since it is the screen itself.
        float block = caption.getLineHeight() * caption.getLineCount();
        float y = (caption.getLineCount() > 1)
                ? (height + block) / 2f
                : block + height * 0.06f;
        caption.setLocalTranslation((width - caption.getLineWidth()) / 2f, y, 1f);
        if (caption.getParent() == null) {
            gui.attachChild(caption);
        }
        caption.setCullHint(Spatial.CullHint.Never);
    }
}
