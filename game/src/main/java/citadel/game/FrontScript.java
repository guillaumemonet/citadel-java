package citadel.game;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * A piece of SERVER run the way SERVER runs: straight-line code that waits for the vertical
 * blank with the {@code VBLANK} macro, under a level 3 interrupt that does its own work once a
 * frame.
 *
 * The code runs on a thread of its own, in lockstep with the render thread: {@link #tick} lets it
 * run up to its next {@link #vblank} and waits for it to get there, so only one of the two is
 * ever running and the script may touch the {@link Display} freely. Anything that has to happen
 * on the render thread, such as starting the music, goes through {@link #later}.
 *
 * That keeps the transcriptions close to the assembly: a {@code move #110,d0 / .ma_1: VBLANK /
 * dbf d0,.ma_1} is {@code vblanks(111)}.
 */
abstract class FrontScript {

    /** Thrown inside the script thread to unwind it when the script is abandoned. */
    static final class Abort extends RuntimeException {
        Abort() {
            super(null, null, false, false);
        }
    }

    /**
     * The inputs SERVER reads. {@code el_DIR}'s five words are latched by key presses and cleared
     * by the code that reads them; the held states stand in for the polls of the mouse buttons
     * and the joystick fire ({@code btst #6,$bfe001}, {@code btst #2,$16(a0)}).
     */
    static final class Input {
        boolean up;
        boolean down;
        boolean left;
        boolean right;
        /** el_DIR+8: RETURN, ENTER, SPACE or ESC in KeyMode 0 and 3, or a button. */
        boolean fire;
        /** A key that is not a direction, latched: the death sequence's KeyQuit and friends. */
        boolean key;
        /** RETURN, ENTER or ESC, which CREDIT_PART takes as "quit" (cp_Twait+2). */
        boolean quitKey;
        /** SPACE held down, CREDIT_PART's pause (cp_Twait). */
        boolean spaceHeld;
        /** Any key held, END_PART2's pause (ko_Twait). */
        int keysHeld;
        /** A mouse button or the joystick fire held. */
        boolean buttonHeld;
        /** A button pressed since last cleared. */
        boolean button;

        void clearDirs() {
            up = down = left = right = false;
            fire = false;
        }

        void clearAll() {
            clearDirs();
            key = quitKey = button = false;
            keysHeld = 0;
            buttonHeld = spaceHeld = false;
        }
    }

    final Input input = new Input();

    private final Semaphore go = new Semaphore(0);
    private final Semaphore stopped = new Semaphore(0);
    private final List<Runnable> queue = new ArrayList<Runnable>();
    private volatile boolean aborted;
    private volatile boolean finished;
    private Thread thread;
    private Runnable interrupt;

    /** The script's body. */
    protected abstract void run();

    void start() {
        thread = new Thread(() -> {
            try {
                go.acquire();
                if (!aborted) {
                    run();
                }
            } catch (Abort | InterruptedException ignored) {
                // abandoned
            } catch (RuntimeException e) {
                e.printStackTrace();
            } finally {
                finished = true;
                stopped.release();
            }
        }, getClass().getSimpleName());
        thread.setDaemon(true);
        thread.start();
    }

    boolean isFinished() {
        return finished;
    }

    /**
     * One vertical blank, from the render thread: the interrupt, then the script up to its next
     * VBLANK.
     */
    void tick() {
        if (finished) {
            return;
        }
        Runnable irq = interrupt;
        if (irq != null) {
            irq.run();
        }
        go.release();
        stopped.acquireUninterruptibly();
    }

    /** Runs what the script asked for on the render thread. */
    List<Runnable> drain() {
        synchronized (queue) {
            List<Runnable> out = new ArrayList<Runnable>(queue);
            queue.clear();
            return out;
        }
    }

    /** Lets the script thread go without running any more of it. */
    void abort() {
        aborted = true;
        go.release();
    }

    // ---------------------------------------------------------- for the script

    /** The {@code VBLANK} macro: hands the frame back and waits for the next one. */
    protected void vblank() {
        stopped.release();
        go.acquireUninterruptibly();
        if (aborted) {
            throw new Abort();
        }
    }

    /** {@code move #n-1,d0 / VBLANK / dbf d0}: n frames. */
    protected void vblanks(int n) {
        for (int i = 0; i < n; i++) {
            vblank();
        }
    }

    /** Puts a level 3 interrupt in place, as {@code move.l a2,$6c(a1)} does; null for none. */
    protected void setInterrupt(Runnable r) {
        interrupt = r;
    }

    /** Something for the render thread to do, after this frame of the script. */
    protected void later(Runnable r) {
        synchronized (queue) {
            queue.add(r);
        }
    }

    /** WAITKLIK: waits for el_DIR+8 and clears it. */
    protected void waitKlik() {
        while (!input.fire) {
            vblank();
        }
        input.fire = false;
    }
}
