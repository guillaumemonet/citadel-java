package citadel.game;

import java.io.File;
import java.nio.ByteBuffer;

import citadel.core.GameState;
import citadel.core.HudGfx;

import com.jme3.asset.AssetManager;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

/**
 * The original status panel, laid over the bottom of the view.
 *
 * WINDOW1.RAW is a whole 320 by 200 screen, and everything below row 160 is the panel: the engine
 * addresses every widget in it as an offset from {@code screen+$7d00}, and $7d00 is 32000, which
 * is 160 rows of 200 bytes. Reading those offsets back gives the layout exactly, since one row is
 * five bitplanes of 40 bytes:
 *
 * <pre>
 *   sv_BombPos    screen+$7d00+$320        row 164, x 0     the warhead gauge
 *   sv_Counter1   screen+$7d00+$640+7      row 168, x 56    NRG
 *   sv_Heart      screen+$7d00+$640+13     row 168, x 104   the HI TECH trace
 *   sv_Compas     screen+$7d00+200+18      row 161, x 144
 *   sv_Weapon     screen+$7d00+200+23      row 161, x 184   the weapon in hand
 *   sv_Counter2   screen+$7d00+$640+28     row 168, x 224   AMMO
 *   sv_CardCnt    screen+$7d00+600+39      row 163, x 312   the three ID CARD counts
 *   sv_ScrollArea screen+$7d00+$1770+40    row 190, x 0     the message line
 * </pre>
 *
 * The port keeps the view full screen rather than shrinking it into the window the panel was built
 * around, so the strip is drawn over the bottom instead of framing a 192 by 128 hole.
 */
public final class HudPanel {

    public static final int PANEL_W = HudGfx.SCREEN_W;
    /** screen+$7d00 is 32000 bytes and a row is 200, so the panel starts here. */
    public static final int ROW0 = 160;
    public static final int PANEL_H = HudGfx.SCREEN_H - ROW0;

    public static final int BOMB_X = 0, BOMB_ROW = 164;
    public static final int NRG_X = 56, NRG_ROW = 168;
    public static final int HEART_X = 104, HEART_ROW = 168;
    public static final int COMPASS_X = 144, COMPASS_ROW = 161;
    public static final int WEAPON_X = 184, WEAPON_ROW = 161;
    public static final int AMMO_X = 224, AMMO_ROW = 168;
    public static final int CARD_X = 312, CARD_ROW = 163;

    /** {@code moveq #16,d1} in Test_Counters, and {@code moveq #9,d1} when energy runs low. */
    private static final int WHITE = 0xffffffff;
    private static final int RED = 0xffff1100;
    /** sv_ScrollArea sits in bitplane 0, so the message line is palette index 1. */
    private static final int SCROLL_COLOUR = 0xffaaaaaa;

    private final float scale;
    private final HudGfx gfx;
    private final Picture strip;
    private final Picture overlay;
    private final Texture2D overlayTexture;
    private final ByteBuffer overlayPixels;
    private final int[] argb = new int[PANEL_W * PANEL_H];
    private final citadel.core.ScrollLine scroll;
    private String shownState = "";
    private String shownMessage = "";
    private float vblanks;

    public HudPanel(AssetManager assetManager, Assets assets, int complex, int screenWidth)
            throws Exception {
        gfx = HudGfx.loadFrom(new File(assets.dataRoot(), "GAME/Gfx_VIR"));
        scroll = new citadel.core.ScrollLine(gfx);
        scale = screenWidth / (float) PANEL_W;

        java.awt.image.BufferedImage whole = javax.imageio.ImageIO.read(
                new File(assets.assetRoot(), String.format("gfx/complex%d/hud.png", complex)));
        ByteBuffer stripPixels = BufferUtils.createByteBuffer(PANEL_W * PANEL_H * 4);
        // the png is top down and a jME texture is bottom up
        for (int y = PANEL_H - 1; y >= 0; y--) {
            for (int x = 0; x < PANEL_W; x++) {
                int v = whole.getRGB(x, ROW0 + y);
                stripPixels.put((byte) ((v >> 16) & 0xff)).put((byte) ((v >> 8) & 0xff))
                        .put((byte) (v & 0xff)).put((byte) 0xff);
            }
        }
        stripPixels.rewind();
        strip = picture(assetManager, "hudStrip",
                new Texture2D(new Image(Image.Format.RGBA8, PANEL_W, PANEL_H, stripPixels,
                        ColorSpace.sRGB)), screenWidth);

        overlayPixels = BufferUtils.createByteBuffer(PANEL_W * PANEL_H * 4);
        overlayTexture = new Texture2D(new Image(Image.Format.RGBA8, PANEL_W, PANEL_H,
                overlayPixels, ColorSpace.sRGB));
        overlay = picture(assetManager, "hudCounters", overlayTexture, screenWidth);
        overlay.setLocalTranslation(0f, 0f, CitadelApp.Z_TEXT);
    }

    private Picture picture(AssetManager assetManager, String name, Texture2D tex, int width) {
        tex.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
        tex.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
        Picture p = new Picture(name);
        p.setTexture(assetManager, tex, true);
        p.getMaterial().getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        p.setWidth(width);
        p.setHeight(PANEL_H * scale);
        p.setLocalTranslation(0f, 0f, CitadelApp.Z_PANEL);
        return p;
    }

    public Picture strip() {
        return strip;
    }

    public Picture overlay() {
        return overlay;
    }

    /**
     * Puts one of the panel's own widgets where the engine puts it. Panel rows count from the top
     * of the screen and jME's gui counts from the bottom, hence the flip.
     */
    public void place(Picture p, int panelX, int panelRow, int w, int h) {
        p.setWidth(w * scale);
        p.setHeight(h * scale);
        p.setLocalTranslation(panelX * scale,
                (HudGfx.SCREEN_H - panelRow - h) * scale, CitadelApp.Z_FURNITURE);
    }

    /**
     * Advances the message line and redraws the readouts.
     *
     * sc_DoScroll runs every VBLANK frame, so the line is on that clock rather than the render
     * one; the counters only change when a reading does.
     */
    public void update(GameState game, String message, float tpf) {
        if (!message.equals(shownMessage)) {
            shownMessage = message;
            scroll.show(message);
        }
        vblanks += tpf * GameState.FRAMES_PER_SECOND;
        boolean moved = false;
        while (vblanks >= 1f) {
            vblanks -= 1f;
            if (!scroll.isEmpty()) {
                scroll.tick();
                moved = true;
            }
        }
        redraw(game, moved);
    }

    private void redraw(GameState game, boolean scrolled) {
        int energy = game.inventory.energyReading();
        int ammo = game.inventory.ammoReading();
        String state = energy + "/" + ammo + "/" + game.inventory.current + "/" + game.inventory.cardReading(0)
                + "," + game.inventory.cardReading(1) + "," + game.inventory.cardReading(2)
                + "/" + game.isEnergyCritical();
        if (state.equals(shownState) && !scrolled) {
            return;
        }
        shownState = state;

        java.util.Arrays.fill(argb, 0);
        counter(NRG_X, NRG_ROW, energy, game.isEnergyCritical() ? RED : WHITE);
        // ci_NewWeapon: "no counter if hand". The bare hand's 1111 would not fit three digits
        // anyway - divu #100 would hand tc_DrawCounter an 11 and send it off the end of the table
        if (game.inventory.current != citadel.core.Inventory.HAND) {
            counter(AMMO_X, AMMO_ROW, ammo, WHITE);
        }
        for (int card = 0; card < 3; card++) {
            // tc_DrawCardCnt steps a whole screen row per glyph row and adds a blank one after
            // each count, so the three sit one under another
            smallDigit(CARD_X, CARD_ROW + card * (HudGfx.SMALL_DIGIT_H + 1),
                    clamp(game.inventory.cardReading(card)));
        }
        blit(scroll.pixels(), citadel.core.ScrollLine.WIDTH, citadel.core.ScrollLine.HEIGHT,
                citadel.core.ScrollLine.X, citadel.core.ScrollLine.ROW, SCROLL_COLOUR);

        // argb is top down like the panel rows; the texture is bottom up like the strip
        overlayPixels.clear();
        for (int y = PANEL_H - 1; y >= 0; y--) {
            for (int x = 0; x < PANEL_W; x++) {
                int v = argb[y * PANEL_W + x];
                overlayPixels.put((byte) ((v >> 16) & 0xff)).put((byte) ((v >> 8) & 0xff))
                        .put((byte) (v & 0xff)).put((byte) ((v >>> 24) & 0xff));
            }
        }
        overlayPixels.rewind();
        overlayTexture.getImage().setUpdateNeeded();
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(9, v));
    }

    /**
     * tc_DrawCounter: divide by 100, then by 10, three digits left to right. The glyph's set bits
     * take the colour and the cleared ones leave the panel's own eight showing, which is why the
     * readout reads like a segment display with segments going dark.
     */
    private void counter(int x, int row, int value, int colour) {
        int v = Math.max(0, Math.min(999, value));
        int[] digits = {v / 100, (v / 10) % 10, v % 10};
        for (int i = 0; i < digits.length; i++) {
            blit(gfx.digit(digits[i]), HudGfx.DIGIT_W, HudGfx.DIGIT_H,
                    x + i * HudGfx.DIGIT_W, row, colour);
        }
    }

    private void smallDigit(int x, int row, int value) {
        blit(gfx.smallDigit(value), HudGfx.SMALL_DIGIT_W, HudGfx.SMALL_DIGIT_H, x, row, WHITE);
    }

    private void blit(boolean[] bits, int w, int h, int x, int row, int colour) {
        int y0 = row - ROW0;
        for (int y = 0; y < h; y++) {
            int py = y0 + y;
            if (py < 0 || py >= PANEL_H) {
                continue;
            }
            for (int px = 0; px < w; px++) {
                if (bits[y * w + px] && x + px >= 0 && x + px < PANEL_W) {
                    argb[py * PANEL_W + x + px] = colour;
                }
            }
        }
    }
}
