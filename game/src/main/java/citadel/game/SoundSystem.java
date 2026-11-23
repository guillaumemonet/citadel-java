package citadel.game;

import java.nio.ByteBuffer;
import java.util.List;

import citadel.core.GameState;
import citadel.core.Level;
import citadel.core.SoundTable;

import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioNode;
import com.jme3.audio.AudioSource;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

/**
 * Plays the sound effects, straight out of the sample bank ST.DAT.
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
    private final byte[] bank;
    private final boolean available;

    public SoundSystem(AssetManager assetManager, Node root, Assets assets) {
        this.assetManager = assetManager;
        this.root = root;
        byte[] b = null;
        try {
            b = SoundTable.samples(assets.data().read("GAME/Store/ST.DAT"));
        } catch (Exception e) {
            System.out.println("cannot read the sample bank, running silent: " + e.getMessage());
        }
        this.bank = b;
        this.available = b != null;
    }

    /**
     * One effect as an OpenAL buffer. Paula plays signed 8 bit samples; OpenAL's 8 bit format is
     * unsigned, hence the flip of the top bit.
     */
    private AudioNode load(int index) {
        SoundTable.Sfx s = SoundTable.ALL[index];
        if (s.offset + s.length > bank.length) {
            return null;
        }
        ByteBuffer pcm = ByteBuffer.allocateDirect(s.length);
        for (int i = 0; i < s.length; i++) {
            pcm.put((byte) (bank[s.offset + i] ^ 0x80));
        }
        pcm.flip();
        AudioBuffer buf = new AudioBuffer();
        buf.setupFormat(1, 8, s.sampleRate());
        buf.updateData(pcm);
        return new AudioNode(buf, new AudioKey(s.name));
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
        serveFireLoop(game.fireLoopVolume());
    }

    /** The burning enemy's sample, looping on its own voice like Paula channel 2. */
    private AudioNode fireLoop;

    /**
     * eh_Burning starts the fire sample looping and me_burning sets its volume every pass from
     * Sound_Distance2; the owner's death stops it. Paula's volume is linear in 0..64, and so is
     * the gain here; the distance is already in the volume, so the node is not positional.
     */
    private void serveFireLoop(int volume) {
        if (volume < 0) {
            if (fireLoop != null && fireLoop.getStatus() == AudioSource.Status.Playing) {
                fireLoop.stop();
            }
            return;
        }
        if (fireLoop == null) {
            try {
                fireLoop = load(SoundTable.FIRE_LOOP);
            } catch (RuntimeException ex) {
                System.out.println("cannot load the fire loop: " + ex.getMessage());
            }
            if (fireLoop == null) {
                return;
            }
            fireLoop.setPositional(false);
            fireLoop.setLooping(true);
            root.attachChild(fireLoop);
        }
        fireLoop.setVolume(volume / 64f);
        if (fireLoop.getStatus() != AudioSource.Status.Playing) {
            fireLoop.play();
        }
    }

    private void play(GameState.SoundEvent e) {
        if (e.index < 0 || e.index >= SoundTable.ALL.length) {
            return;
        }
        AudioNode node = cache[e.index];
        if (node == null) {
            try {
                node = load(e.index);
            } catch (RuntimeException ex) {
                System.out.println("cannot load " + SoundTable.ALL[e.index].name + ": "
                        + ex.getMessage());
                return;
            }
            if (node == null) {
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
