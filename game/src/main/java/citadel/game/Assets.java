package citadel.game;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.texture.Texture;

/**
 * Locates the converted assets and turns them into jMonkeyEngine materials.
 *
 * Textures come from build/assets, produced by {@code ./gradlew :tools:convert}; level data is
 * read straight out of the original .MAP files under Data/GAME/MAPS, since
 * {@link citadel.core.MapFile} already parses them.
 */
public final class Assets {

    private final AssetManager assetManager;
    private final File assetRoot;
    private final File dataRoot;
    private final Map<String, Material> cache = new HashMap<String, Material>();

    public Assets(AssetManager assetManager) {
        this.assetManager = assetManager;
        this.assetRoot = resolve("citadel.assets", "build/assets");
        this.dataRoot = resolve("citadel.data", "Data");
        assetManager.registerLocator(assetRoot.getAbsolutePath(), FileLocator.class);
    }

    private static File resolve(String property, String fallback) {
        String v = System.getProperty(property);
        File f = (v != null) ? new File(v) : new File(fallback);
        if (!f.isDirectory()) {
            File up = new File("..", fallback);
            if (up.isDirectory()) {
                return up;
            }
        }
        return f;
    }

    public File dataRoot() {
        return dataRoot;
    }

    public File mapsDir() {
        return new File(dataRoot, "GAME/MAPS");
    }

    public File assetRoot() {
        return assetRoot;
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

    public String texturePath(int complex, String group, int index) {
        return String.format("gfx/complex%d/%s_%02d.png", complex, group, index);
    }

    public boolean exists(int complex, String group, int index) {
        return new File(assetRoot, texturePath(complex, group, index)).isFile();
    }

    /**
     * An unshaded, nearest-filtered material, which is what keeps the original pixel art looking
     * like itself. Sprites additionally get alpha blending and no back face culling.
     */
    public Material material(int complex, String group, int index, boolean sprite) {
        String path = texturePath(complex, group, index);
        String cacheKey = path + (sprite ? "#s" : "#w");
        Material cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // Wall quads carry hand-built UVs with v=0 at the top, so their textures load unflipped.
        // Sprites sit on a jME Quad, whose generated UVs put v=0 at the bottom, so those flip.
        TextureKey key = new TextureKey(path, sprite);
        key.setGenerateMips(false);
        Texture tex = assetManager.loadTexture(key);
        tex.setMagFilter(Texture.MagFilter.Nearest);
        tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        tex.setWrap(Texture.WrapMode.EdgeClamp);

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setTexture("ColorMap", tex);
        if (sprite) {
            mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
            mat.getAdditionalRenderState().setFaceCullMode(
                    com.jme3.material.RenderState.FaceCullMode.Off);
            mat.setFloat("AlphaDiscardThreshold", 0.5f);
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
