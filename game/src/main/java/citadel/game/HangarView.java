package citadel.game;

import java.io.File;
import java.nio.ByteBuffer;

import citadel.core.HangarMenu;
import citadel.core.KaneFont;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The hangar with the server's menu printed in its panel.
 *
 * The picture is a full 320 by 200 screen, so the overlay is too, and everything lands where
 * DrukTekst and PostawKropa put it: six lines of sixteen characters from row 61, fifteen rows
 * apart, with a dot either side of the chosen one.
 */
public final class HangarView {

    private static final int W = 320;
    private static final int H = 200;
    private static final int INK = 0xffffffff;
    /** KropaDat: an eight by eight circle, rows top to bottom. */
    private static final int[] DOT = {
        0b00111100, 0b01111110, 0b11111111, 0b11111111,
        0b11111111, 0b11111111, 0b01111110, 0b00111100,
    };

    private final KaneFont font;
    private final Picture background;
    private final Picture overlay;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private final int[] argb = new int[W * H];
    private String shown = "";

    public HangarView(AssetManager assetManager, Assets assets, int width, int height)
            throws Exception {
        font = KaneFont.loadFrom(assets.dataRoot());

        String path = new File(assets.assetRoot(), "screens/hangar_eng.png").isFile()
                ? "screens/hangar_eng.png" : "screens/hangar.png";
        com.jme3.asset.TextureKey key = new com.jme3.asset.TextureKey(path, true);
        key.setGenerateMips(false);
        com.jme3.texture.Texture tex = assetManager.loadTexture(key);
        tex.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        tex.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        background = new Picture("hangar");
        background.setTexture(assetManager, (Texture2D) tex, false);
        background.setWidth(width);
        background.setHeight(height);
        background.setLocalTranslation(0f, 0f, CitadelApp.Z_PANEL);

        pixels = BufferUtils.createByteBuffer(W * H * 4);
        texture = new Texture2D(new Image(Image.Format.RGBA8, W, H, pixels, ColorSpace.sRGB));
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        overlay = new Picture("hangarText");
        overlay.setTexture(assetManager, texture, true);
        overlay.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        overlay.setWidth(width);
        overlay.setHeight(height);
        overlay.setLocalTranslation(0f, 0f, CitadelApp.Z_TEXT);
    }

    public void attach(com.jme3.scene.Node gui) {
        if (background.getParent() == null) {
            gui.attachChild(background);
            gui.attachChild(overlay);
        }
    }

    public void detach() {
        background.removeFromParent();
        overlay.removeFromParent();
    }

    public boolean isShowing() {
        return background.getParent() != null;
    }

    public void update(HangarMenu menu) {
        String[] lines = menu.lines();
        StringBuilder state = new StringBuilder().append(menu.screen()).append(menu.line());
        for (String s : lines) {
            state.append(s);
        }
        if (state.toString().equals(shown)) {
            return;
        }
        shown = state.toString();

        java.util.Arrays.fill(argb, 0);
        for (int i = 0; i < lines.length; i++) {
            int row = HangarMenu.TEXT_ROW + i * HangarMenu.LINE_STEP;
            text(HangarMenu.TEXT_X, row, lines[i], menu.isWide(i));
        }
        int cursorRow = HangarMenu.CURSOR_ROW + menu.line() * HangarMenu.LINE_STEP;
        dot(HangarMenu.CURSOR_LEFT_X, cursorRow);
        dot(HangarMenu.CURSOR_RIGHT_X, cursorRow);

        pixels.clear();
        // argb counts rows from the top, the texture from the bottom
        for (int y = H - 1; y >= 0; y--) {
            for (int x = 0; x < W; x++) {
                int v = argb[y * W + x];
                pixels.put((byte) ((v >> 16) & 0xff)).put((byte) ((v >> 8) & 0xff))
                        .put((byte) (v & 0xff)).put((byte) ((v >>> 24) & 0xff));
            }
        }
        pixels.rewind();
        texture.getImage().setUpdateNeeded();
    }

    /**
     * {@code wide} is DrukTekst's second routine, which rolls each byte four bits and writes two,
     * so the glyph comes out at double width and half of it in the next column.
     */
    private void text(int x, int row, String s, boolean wide) {
        for (int i = 0; i < s.length(); i++) {
            boolean[] g = font.glyph(s.charAt(i));
            int at = x + i * KaneFont.GLYPH_W;
            for (int y = 0; y < KaneFont.GLYPH_H; y++) {
                for (int px = 0; px < KaneFont.GLYPH_W; px++) {
                    if (g[y * KaneFont.GLYPH_W + px]) {
                        plot(at + px, row + y);
                    }
                }
            }
        }
    }

    private void dot(int x, int row) {
        for (int y = 0; y < DOT.length; y++) {
            for (int px = 0; px < 8; px++) {
                if (((DOT[y] >> (7 - px)) & 1) != 0) {
                    plot(x + px, row + y);
                }
            }
        }
    }

    private void plot(int x, int y) {
        if (x >= 0 && x < W && y >= 0 && y < H) {
            argb[y * W + x] = INK;
        }
    }
}
