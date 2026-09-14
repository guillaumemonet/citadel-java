package citadel.game;

import java.io.File;
import java.util.List;

import citadel.core.GameState;
import citadel.core.Level;
import citadel.core.SoundTable;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

/**
 * Plays the converted sound effects.
 *
 * The rules queue {@link GameState.SoundEvent}s and this drains them once a frame, so nothing in
 * {@code core} has to know about an audio engine.
 *
 * The original mixes four Paula voices and scales volume with distance in Sound_Distance. Here
 * jMonkeyEngine does the mixing and the attenuation; positional sounds are placed in the world and
 * everything else plays flat at the listener.
 */
public final class SoundSystem {

    /** Beyond this many world units a sound is inaudible, roughly matching Sound_Distance. */
    private static final float MAX_DISTANCE = 12f;

    private final AssetManager assetManager;
    private final Node root;
    private final AudioNode[] cache = new AudioNode[SoundTable.ALL.length];
    private final File soundsDir;
    private final boolean available;

    public SoundSystem(AssetManager assetManager, Node root, Assets assets) {
        this.assetManager = assetManager;
        this.root = root;
        this.soundsDir = new File(assets.assetRoot(), "sounds");
        this.available = soundsDir.isDirectory();
        if (!available) {
            System.out.println("no converted sounds found, running silent"
                    + " (run :tools:convert first)");
        }
    }

    /** Plays everything the last update queued. */
    public void serve(GameState game) {
        if (!available) {
            game.drainSounds();
            return;
        }
        List<GameState.SoundEvent> events = game.drainSounds();
        for (GameState.SoundEvent e : events) {
            play(e);
        }
    }

    private void play(GameState.SoundEvent e) {
        if (e.index < 0 || e.index >= SoundTable.ALL.length) {
            return;
        }
        AudioNode node = cache[e.index];
        if (node == null) {
            String path = "sounds/" + SoundTable.ALL[e.index].name + ".wav";
            if (!new File(soundsDir, SoundTable.ALL[e.index].name + ".wav").isFile()) {
                return;
            }
            try {
                node = new AudioNode(assetManager, path, AudioData.DataType.Buffer);
            } catch (RuntimeException ex) {
                System.out.println("cannot load " + path + ": " + ex.getMessage());
                return;
            }
            node.setLooping(false);
            node.setVolume(1f);
            root.attachChild(node);
            cache[e.index] = node;
        }
        if (e.positional) {
            node.setPositional(true);
            node.setMaxDistance(MAX_DISTANCE);
            node.setRefDistance(1f);
            node.setLocalTranslation(new Vector3f(
                    e.worldX / (float) Level.CELL_UNITS,
                    LevelGeometry.WALL_H / 2f,
                    e.worldY / (float) Level.CELL_UNITS));
        } else {
            node.setPositional(false);
        }
        node.playInstance();
    }
}
