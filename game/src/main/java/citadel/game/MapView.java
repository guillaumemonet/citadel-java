package citadel.game;

import java.io.File;
import java.nio.ByteBuffer;

import citadel.core.HudGfx;
import citadel.core.WorldMap;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * MAPA drawn: the map of the citadel with an arrow over the chosen target and a line of status
 * under it.
 *
 * The picture is 320 by 192 and the screen is 200, so the eight rows left over are where ma_draw
 * writes — one bitplane, seven rows deep, forty characters of the in-game 8 by 8 font. The arrow
 * bobs on the VBLANK clock through the heights in Asinus.
 */
public final class MapView {

    private static final int W = 320;
    private static final int H = 200;
    /** The map picture's own height; the rest of the screen is the status line. */
    private static final int MAP_H = 192;
    private static final int TEXT_ROW = MAP_H + 1;
    private static final int INK = 0xffffffff;

    private final HudGfx font;
    private final boolean[] arrow;
    private final int[] arrowColour;
    private final int arrowW;
    private final int arrowH;

    private final Picture background;
    private final Picture overlay;
    private final Texture2D texture;
    private final ByteBuffer pixels;
    private final int[] argb = new int[W * H];

    private int bob;
    private float vblanks;
    private String shown = "";
    private String flash = "";

    public MapView(AssetManager assetManager, Assets assets, int width, int height)
            throws Exception {
        font = HudGfx.loadFrom(new File(assets.dataRoot(), "GAME/Gfx_VIR"));

        java.awt.image.BufferedImage a = javax.imageio.ImageIO.read(
                new File(assets.assetRoot(), "screens/arrow.png"));
        arrowW = a.getWidth();
        arrowH = a.getHeight();
        arrow = new boolean[arrowW * arrowH];
        arrowColour = new int[arrowW * arrowH];
        for (int y = 0; y < arrowH; y++) {
            for (int x = 0; x < arrowW; x++) {
                int v = a.getRGB(x, y);
                arrow[y * arrowW + x] = (v >>> 24) != 0;
                arrowColour[y * arrowW + x] = v;
            }
        }

        com.jme3.asset.TextureKey key = new com.jme3.asset.TextureKey("screens/mapka.png", true);
        key.setGenerateMips(false);
        com.jme3.texture.Texture tex = assetManager.loadTexture(key);
        tex.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        tex.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        background = new Picture("mapka");
        background.setTexture(assetManager, (Texture2D) tex, false);
        background.setWidth(width);
        background.setHeight(height * MAP_H / H);
        background.setLocalTranslation(0f, height * (H - MAP_H) / (float) H,
                CitadelApp.Z_PANEL);

        pixels = BufferUtils.createByteBuffer(W * H * 4);
        texture = new Texture2D(new Image(Image.Format.RGBA8, W, H, pixels, ColorSpace.sRGB));
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        overlay = new Picture("mapkaText");
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

    /** Shows one of the refusal lines instead of the status until the choice moves again. */
    public void flash(String text) {
        flash = text;
        shown = "";
    }

    public void update(WorldMap map, float tpf) {
        vblanks += tpf * citadel.core.GameState.FRAMES_PER_SECOND;
        boolean moved = false;
        while (vblanks >= 1f) {
            vblanks -= 1f;
            bob = (bob + 1) % WorldMap.BOB.length;
            moved = true;
        }
        String line = flash.isEmpty() ? map.statusLine() : flash;
        String state = map.choice() + "/" + line + "/" + bob;
        if (!moved && state.equals(shown)) {
            return;
        }
        shown = state;

        java.util.Arrays.fill(argb, 0);
        // the picture is 192 rows of a 200 row screen; the eight left over are the status line,
        // and they are black in the original rather than a hole onto whatever is behind
        for (int y = MAP_H; y < H; y++) {
            java.util.Arrays.fill(argb, y * W, (y + 1) * W, 0xff000000);
        }
        int[] at = WorldMap.ARROW[map.choice()];
        drawArrow(at[0], at[1] + WorldMap.BOB[bob]);
        text(0, TEXT_ROW, line);

        pixels.clear();
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

    private void drawArrow(int x, int row) {
        for (int y = 0; y < arrowH; y++) {
            for (int px = 0; px < arrowW; px++) {
                if (arrow[y * arrowW + px]) {
                    plot(x + px, row + y, arrowColour[y * arrowW + px]);
                }
            }
        }
    }

    /** ma_draw: Font8 is the in-game 8 by 8 font, seven rows of it, one bitplane. */
    private void text(int x, int row, String s) {
        for (int i = 0; i < s.length(); i++) {
            boolean[] g = font.glyph(s.charAt(i));
            int at = x + i * HudGfx.GLYPH_W;
            for (int y = 0; y < 7; y++) {
                for (int px = 0; px < HudGfx.GLYPH_W; px++) {
                    if (g[y * HudGfx.GLYPH_W + px]) {
                        plot(at + px, row + y, INK);
                    }
                }
            }
        }
    }

    private void plot(int x, int y, int colour) {
        if (x >= 0 && x < W && y >= 0 && y < H) {
            argb[y * W + x] = colour;
        }
    }
}
