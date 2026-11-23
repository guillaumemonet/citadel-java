package citadel.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import citadel.core.DataSource;
import citadel.core.ProModule;
import citadel.core.ProTrackerPlayer;

import com.jme3.audio.AudioBuffer;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioNode;
import com.jme3.scene.Node;

/**
 * The music, which the original plays from its level 3 interrupt ({@code bsr mt_music} in every
 * NewLev3 of SERVER and Death14).
 *
 * <ul>
 * <li>{@link #MAIN}, mod.aconcagua, from {@code s}'s {@code mt_init} until LoadFirstData's
 *     {@code mt_end}: the hangar, its menus and the credits;</li>
 * <li>{@link #MAP}, mod.brief1, MAPA's ({@code MUSname "DF0:MOD"});</li>
 * <li>{@link #END_OF_LEVEL}, mod.MiedzyEtapy, ENDLEVEL_PART's;</li>
 * <li>{@link #DEATH}, mod.death, inside the death sequence DET;</li>
 * <li>{@link #GOOD_END}, mod.dirranbandi, END_PART2's.</li>
 * </ul>
 * The engine has none: play is silent but for the effects.
 *
 * As in the author's Breathless port, which this follows ({@code JmeSoundPlayer}), a song is
 * rendered whole on a worker thread and then looped; {@code mt_music} wraps the song position at
 * the song length, so a loop is what the Amiga plays too. Rendered songs are kept.
 */
final class Music {

    static final String MAIN = "INTRO/MODS/mod.aconcagua.pro";
    static final String MAP = "INTRO/MODS/mod.brief1.pro";
    static final String END_OF_LEVEL = "INTRO/MODS/mod.MiedzyEtapy.pro";
    static final String DEATH = "INTRO/STORE/mod.death.pro";
    static final String GOOD_END = "INTRO/MODS/mod.dirranbandi.pro";

    private static final int RATE = 22050;
    private static final int MAX_SECONDS = 600;
    private static final float VOLUME = 0.6f;

    private final Node root;
    private final DataSource data;
    private final java.util.function.Consumer<Runnable> onRenderThread;
    private final Map<String, short[]> rendered = new HashMap<String, short[]>();
    private AudioNode playing;
    private volatile String wanted;

    Music(Node root, DataSource data, java.util.function.Consumer<Runnable> onRenderThread) {
        this.root = root;
        this.data = data;
        this.onRenderThread = onRenderThread;
    }

    /** mt_init: starts a song from its first position. Render thread only. */
    void play(String path) {
        stop();
        wanted = path;
        short[] pcm;
        synchronized (rendered) {
            pcm = rendered.get(path);
        }
        if (pcm != null) {
            start(path, pcm);
            return;
        }
        Thread worker = new Thread(() -> {
            short[] out;
            try {
                out = ProTrackerPlayer.render(ProModule.read(data.read(path)), RATE, MAX_SECONDS);
            } catch (Exception e) {
                System.out.println("cannot play " + path + ": " + e.getMessage());
                return;
            }
            synchronized (rendered) {
                rendered.put(path, out);
            }
            onRenderThread.accept(() -> {
                if (path.equals(wanted) && playing == null) {
                    start(path, out);
                }
            });
        }, "music");
        worker.setDaemon(true);
        worker.start();
    }

    /** mt_end. */
    void stop() {
        wanted = null;
        if (playing != null) {
            playing.stop();
            playing.removeFromParent();
            playing = null;
        }
    }

    private void start(String path, short[] pcm) {
        if (pcm.length == 0) {
            return;
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(pcm.length * 2).order(ByteOrder.nativeOrder());
        for (short s : pcm) {
            buf.putShort(s);
        }
        buf.flip();
        AudioBuffer audio = new AudioBuffer();
        audio.setupFormat(1, 16, RATE);
        audio.updateData(buf);
        AudioNode node = new AudioNode(audio, new AudioKey(path));
        node.setPositional(false);
        node.setLooping(true);
        node.setVolume(VOLUME);
        root.attachChild(node);
        node.play();
        playing = node;
    }
}
