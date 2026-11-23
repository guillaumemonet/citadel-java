package citadel.game;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import citadel.core.BombGfx;
import citadel.core.DataSource;
import citadel.core.GfxLayout;
import citadel.core.Level;
import citadel.core.Palette;
import citadel.core.RawScreen;
import citadel.core.VirReader;
import citadel.core.disk.GameDisks;

/**
 * The game's data, read from the original floppy images and decoded in memory.
 *
 * Nothing is converted ahead of time and nothing is written out: the graphics block of a complex
 * is rebuilt the way the engine builds it — {@code WALLSnA}, {@code WALLSnB}, {@code ENEMYnA},
 * {@code ENEMYnB} and {@code ITEMS01} back to back — sliced with the engine's own offset tables,
 * coloured with the complex's palette and handed to jMonkeyEngine as textures.
 */
public final class Assets {

    /** A decoded graphic, ARGB, row major from the top. */
    public static final class Sprite {
        public final int width;
        public final int height;
        public final int[] argb;

        Sprite(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }

    private final AssetManager assetManager;
    private final GameDisks disks;
    private final Map<String, Material> cache = new HashMap<String, Material>();
    private final Map<Integer, Map<String, Sprite>> complexes = new HashMap<Integer, Map<String, Sprite>>();

    public Assets(AssetManager assetManager) throws IOException {
        this.assetManager = assetManager;
        this.disks = GameDisks.open(Path.of(System.getProperty("citadel.adf", "../adf")));
    }

    public DataSource data() {
        return disks;
    }

    // ------------------------------------------------------------ maps

    /** Every map on the disks, as file names such as LEVEL01A.MAP. */
    public List<String> mapNames() {
        List<String> out = new ArrayList<String>();
        for (String p : disks.paths()) {
            if (p.startsWith(Level.MAPS)) {
                out.add(p.substring(Level.MAPS.length()));
            }
        }
        return out;
    }

    /** The map named exactly, or the first whose name starts with {@code want}; null if none. */
    public String findMap(String want) {
        String needle = want.toUpperCase(Locale.ROOT);
        List<String> names = mapNames();
        for (String n : names) {
            if (n.equalsIgnoreCase(want)) {
                return n;
            }
        }
        for (String n : names) {
            if (n.toUpperCase(Locale.ROOT).startsWith(needle)) {
                return n;
            }
        }
        return null;
    }

    public boolean hasMap(String mapName) {
        return disks.has(Level.MAPS + mapName);
    }

    public Level level(String mapName) throws IOException {
        return Level.load(disks, mapName);
    }

    /** Complex number 1..8 for a map file name such as LEVEL03B.MAP. */
    public static int complexOf(String mapName) {
        for (int i = 0; i + 1 < mapName.length(); i++) {
            if (Character.isDigit(mapName.charAt(i)) && Character.isDigit(mapName.charAt(i + 1))) {
                int n = Integer.parseInt(mapName.substring(i, i + 2));
                return (n >= 1 && n <= 8) ? n : 1;
            }
        }
        return 1;
    }

    // ------------------------------------------------------------ graphics

    /** True when the complex has that graphic; unused sprite slots, all key colour, do not count. */
    public boolean exists(int complex, String group, int index) {
        return sprite(complex, group, index) != null;
    }

    /** A graphic of a complex, or null when the slot is unused. */
    public Sprite sprite(int complex, String group, int index) {
        return graphics(complex).get(group + "_" + index);
    }

    private Map<String, Sprite> graphics(int complex) {
        Map<String, Sprite> g = complexes.get(complex);
        if (g == null) {
            try {
                g = decode(complex);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read the graphics of complex " + complex, e);
            }
            complexes.put(complex, g);
        }
        return g;
    }

    private Map<String, Sprite> decode(int complex) throws IOException {
        byte[] block = new byte[GfxLayout.BLOCK_SIZE];
        place(block, GfxLayout.WALLS_A, "GAME/Gfx_VIR/WALLS" + complex + "A.VIR");
        place(block, GfxLayout.WALLS_B, "GAME/Gfx_VIR/WALLS" + complex + "B.VIR");
        place(block, GfxLayout.ENEMY_A, "GAME/Gfx_VIR/ENEMY" + complex + "A.VIR");
        place(block, GfxLayout.ENEMY_B, "GAME/Gfx_VIR/ENEMY" + complex + "B.VIR");
        place(block, GfxLayout.ITEMS, "GAME/Gfx_VIR/ITEMS01.VIR");
        String cols = String.format("GAME/Gfx_VIR/COLS%02d.DAT", complex);
        Palette pal = Palette.load(cols, disks.read(disks.has(cols) ? cols : "GAME/Gfx_VIR/COLS01.DAT"));

        Map<String, Sprite> out = new HashMap<String, Sprite>();
        VirReader vir = new VirReader(block, true);
        for (GfxLayout.Entry e : GfxLayout.all()) {
            boolean sprite = !"wall".equals(e.group);
            if (sprite && vir.isBlank(e.offset, e.width, e.height)) {
                continue;
            }
            out.put(e.group + "_" + e.index, new Sprite(e.width, e.height,
                    vir.pixels(e.offset, e.width, e.height, pal.lut(sprite))));
        }

        // Store/Bomba.rawb, the six stages of the warhead gauge, in the complex's colours
        BombGfx bomb = BombGfx.loadFrom(disks);
        int[] lut = pal.lut(true);
        for (int stage = 0; stage < BombGfx.STAGES; stage++) {
            int[] px = bomb.pixels(stage);
            int[] argb = new int[px.length];
            for (int i = 0; i < px.length; i++) {
                argb[i] = lut[px[i] & 31];
            }
            out.put("bomb_" + stage, new Sprite(BombGfx.WIDTH, BombGfx.HEIGHT, argb));
        }
        return out;
    }

    /** One colour of a complex's panel palette, ARGB: COMPASS draws in 1, DrawBomb clears to 0. */
    public int panelColour(int complex, int index) throws IOException {
        String cols = String.format("GAME/Gfx_VIR/COLS%02d.DAT", complex);
        Palette pal = Palette.load(cols, disks.read(disks.has(cols) ? cols : "GAME/Gfx_VIR/COLS01.DAT"));
        return pal.lut(false)[index & 31];
    }

    /** The status panel, WINDOW1.RAW, in a complex's colours. */
    public Sprite panel(int complex, citadel.core.HudGfx hud) throws IOException {
        String cols = String.format("GAME/Gfx_VIR/COLS%02d.DAT", complex);
        Palette pal = Palette.load(cols, disks.read(disks.has(cols) ? cols : "GAME/Gfx_VIR/COLS01.DAT"));
        int[] px = hud.panel();
        int[] lut = pal.lut(false);
        int[] argb = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            argb[i] = lut[px[i] & 31];
        }
        return new Sprite(citadel.core.HudGfx.SCREEN_W, citadel.core.HudGfx.SCREEN_H, argb);
    }

    private void place(byte[] block, int at, String path) throws IOException {
        if (!disks.has(path)) {
            return;
        }
        byte[] d = disks.read(path);
        System.arraycopy(d, 0, block, at, Math.min(d.length, block.length - at));
    }

    // ------------------------------------------------------------ screens

    /** How the display shows a screen's six planes, if it has six. */
    private enum Mode { PLAIN, HAM, EHB }

    /** A headerless screen: interleaved bitplanes then the palette, as SERVER blits them. */
    private Sprite screen(String path, int width, int planes, int paletteBytes, Mode mode)
            throws IOException {
        Sprite cached = screens.get(path);
        if (cached == null) {
            RawScreen s = RawScreen.load(path, disks.read(path), width, planes, paletteBytes);
            int[] px = mode == Mode.HAM ? s.hamPixels() : mode == Mode.EHB ? s.ehbPixels() : s.pixels();
            cached = new Sprite(s.width, s.height, px);
            screens.put(path, cached);
        }
        return cached;
    }

    private final Map<String, Sprite> screens = new HashMap<String, Sprite>();

    /** A headerless screen as SERVER keeps it, indices and palette. */
    public RawScreen raw(String path, int width, int planes, int paletteBytes) throws IOException {
        return RawScreen.load(path, disks.read(path), width, planes, paletteBytes);
    }

    /** strzalka.rawb as palette indices, 16 by 11; 0 is where DrukArrow's mask lets the map through. */
    public int[] arrowIndices() throws IOException {
        return RawScreen.load("strzalka.rawb", disks.read("INTRO/GFX/strzalka.rawb"), 16, 6, 0)
                .indices();
    }

    /** The hangar, the server's menu backdrop. */
    public Sprite hangar() throws IOException {
        return screen("INTRO/GFX/Hangar_ENG.rawb", 320, 6, 128, Mode.EHB);
    }

    /** The complex map MAPA shows between levels. */
    public Sprite mapka() throws IOException {
        return screen("INTRO/GFX/MAPKA.rawb", 320, 6, 128, Mode.EHB);
    }

    /** The picture SERVER shows when the player is killed. */
    public Sprite zabity() throws IOException {
        return screen("INTRO/STORE/zabity.rawb", 320, 5, 64, Mode.PLAIN);
    }

    /** The cover picture, the title card. */
    public Sprite cover() throws IOException {
        return screen("INTRO/GFX/cytadela.rawb", 320, 6, 128, Mode.HAM);
    }

    /**
     * strzalka.rawb, the arrow MAPA bobs over the chosen target. 132 bytes is 2 by 11 in six
     * planes, and it is blitted straight into the map picture, so it borrows that palette, half
     * brite included.
     */
    public Sprite arrow() throws IOException {
        int[] palette = RawScreen.load("MAPKA.rawb", disks.read("INTRO/GFX/MAPKA.rawb"),
                320, 6, 128).ehbPalette();
        RawScreen a = RawScreen.load("strzalka.rawb", disks.read("INTRO/GFX/strzalka.rawb"),
                16, 6, 0);
        int[] idx = a.indices();
        int[] argb = new int[idx.length];
        for (int i = 0; i < idx.length; i++) {
            argb[i] = (idx[i] == 0) ? 0 : palette[idx[i] & 63];
        }
        return new Sprite(a.width, a.height, argb);
    }

    // ------------------------------------------------------------ jME

    /**
     * A nearest-filtered texture. {@code flip} has the meaning of {@code TextureKey}'s: true puts
     * the first row at the bottom of the image, which is what a jME Quad or Picture wants.
     */
    public static Texture2D texture(Sprite s, boolean flip) {
        ByteBuffer buf = BufferUtils.createByteBuffer(s.width * s.height * 4);
        put(buf, s, flip);
        // sRGB: gamma correction is on, and a texture that claims to be linear comes out washed out
        Texture2D tex = new Texture2D(new Image(Image.Format.RGBA8, s.width, s.height, buf,
                ColorSpace.sRGB));
        tex.setMagFilter(Texture.MagFilter.Nearest);
        tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        return tex;
    }

    /** Rewrites a texture made by {@link #texture} with another sprite of the same size. */
    public static void fill(Texture2D tex, Sprite s, boolean flip) {
        Image img = tex.getImage();
        put(img.getData(0), s, flip);
        img.setUpdateNeeded();
    }

    private static void put(ByteBuffer buf, Sprite s, boolean flip) {
        buf.clear();
        for (int r = 0; r < s.height; r++) {
            int y = flip ? s.height - 1 - r : r;
            for (int x = 0; x < s.width; x++) {
                int v = s.argb[y * s.width + x];
                buf.put((byte) (v >> 16)).put((byte) (v >> 8)).put((byte) v).put((byte) (v >>> 24));
            }
        }
        buf.rewind();
    }

    /**
     * An unshaded, nearest-filtered material, which is what keeps the original pixel art looking
     * like itself. Sprites additionally get alpha blending and no back face culling.
     */
    public Material material(int complex, String group, int index, boolean sprite) {
        return build(complex, group, index, sprite ? "#s" : "#w", sprite, sprite, sprite);
    }

    /**
     * A decal drawn over a wall face: blended like a sprite, but on the same hand-built UVs as
     * the wall, so it must not flip. Getting this wrong is silent — the texture simply appears
     * upside down, which on a blood splatter is hard to notice and on a KEEP OUT sign is not.
     */
    public Material decal(int complex, String group, int index) {
        return build(complex, group, index, "#d", false, true, false);
    }

    /**
     * Wall quads carry hand-built UVs with v=0 at the top, so their textures are not flipped.
     * Sprites sit on a jME Quad, whose generated UVs put v=0 at the bottom, so those flip.
     */
    private Material build(int complex, String group, int index, String kind,
                           boolean flip, boolean blend, boolean twoSided) {
        String cacheKey = complex + "/" + group + "_" + index + kind;
        Material cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Sprite s = sprite(complex, group, index);
        if (s == null) {
            throw new IllegalArgumentException("complex " + complex + " has no " + group + " " + index);
        }
        Texture2D tex = texture(s, flip);
        tex.setWrap(Texture.WrapMode.EdgeClamp);

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setTexture("ColorMap", tex);
        if (blend) {
            mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
            mat.setFloat("AlphaDiscardThreshold", 0.5f);
        }
        if (twoSided) {
            mat.getAdditionalRenderState().setFaceCullMode(
                    com.jme3.material.RenderState.FaceCullMode.Off);
        }
        cache.put(cacheKey, mat);
        return mat;
    }

    public Material solid(ColorRGBA colour) {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", colour);
        return mat;
    }
}
