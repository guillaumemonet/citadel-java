package citadel.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The playable state of one level: where the player is, which doors are moving, and what the
 * triggers have changed. Pure Java with no renderer attached, so it can be driven from a test as
 * easily as from jMonkeyEngine.
 *
 * Distances and angles keep the original engine's units. Positions are in world units with
 * {@link Level#CELL_UNITS} per cell; the angle runs 0..511 for a full turn and the forward vector
 * is {@code (-cos a, sin a)} in (X, Y), which is what sv_rotate computes for {@code 256 - angle}.
 */
public final class GameState {

    /** DrawBomb: {@code addi #77,d0}, the first of the six warhead section messages. */
    public static final int BOMB_TEXT = 77;

    /**
     * min_distance, from the equate {@code [500 * 2^SHLeft / mc_maxHeigth] + 2} evaluated with
     * 68000 integer division: 500 * 256 / 350 = 365, + 2.
     */
    public static final int MIN_DISTANCE = 367;
    /** How close the player's centre may get to a wall, i.e. min_distance - 60 in sv_Border. */
    public static final int PLAYER_RADIUS = MIN_DISTANCE - 60;
    /** Half-width of the blocking box around a column, from the {@code cmpi #512-208} tests. */
    public static final int COLUMN_HALF = 208;
    /** Where the player is pushed back to after entering a column, from {@code #512+210}. */
    public static final int COLUMN_PUSH = 210;

    /** Walk speed per original frame, from sv_WalkSpeed. */
    public static final int WALK_SPEED = 250;
    /** Turn speed per original frame, from sv_RotSpeed. */
    public static final int ROT_SPEED = 12;
    /** The PAL vertical blank, and with it the level clock and the low-energy beep. */
    public static final float FRAMES_PER_SECOND = 50f;

    /**
     * How many passes of MAIN_LOOP happen per second.
     *
     * This is <b>not</b> the vertical blank, and conflating the two is the easiest way to make the
     * whole game run several times too fast. NewLev3 is the level-3 interrupt and does tick at a
     * fixed 50 Hz, which is why the level clock counts real seconds. MAIN_LOOP only waits for
     * <em>at least</em> one blank ({@code VBLANK} then {@code dbf sv_Opoznienie}) and then takes as
     * long as the software renderer takes — several frames on real hardware.
     *
     * So sv_WalkSpeed's 250 units, sv_RotSpeed's 12, a door's six or seven steps and one call to
     * Move_Enemy are all *per pass of this loop*. Pacing them off the blank makes the player
     * sprint, the view spin, and the enemies notice and shoot roughly three times too quickly.
     *
     * The true figure depends on the machine and the window size and is not recorded anywhere, so
     * this is an estimate for an A1200 at the default window: adjustable, and documented as such.
     */
    public static final float DEFAULT_LOOP_HZ = 16f;

    /** Loop passes caught up in one call, so a stall cannot teleport the world. */
    private static final int MAX_CATCHUP = 4;

    private float loopHz = DEFAULT_LOOP_HZ;
    private float loopAccumulator;

    public float loopHz() {
        return loopHz;
    }

    /** Lets the front end offer this as a speed setting. */
    public void setLoopHz(float hz) {
        loopHz = Math.max(4f, Math.min(50f, hz));
    }

    /** Column values that block movement, per .br2_COLUMN2: 1, 2 and 4, plus 18 (the barrel). */
    private static boolean blockingColumn(int v) {
        return v == 18 || (v != 0 && v < 5 && v != 3);
    }

    /** Column value 4 is a teleport pad rather than an obstacle. */
    public static final int COLUMN_TELEPORT = 4;

    public final Level level;

    public int posX;
    public int posY;
    /** 0..511, a full turn. 0 faces -X (west), 128 faces +Y (north). */
    public int angle;

    private int lastInX = 512;
    private int lastInY = 512;
    private boolean levelComplete;
    private boolean geometryDirty;

    private final DoorSlot[] doors = {new DoorSlot(1), new DoorSlot(2)};
    private final List<String> log = new ArrayList<String>();

    public final Inventory inventory = new Inventory();
    /** The original message table, when the front end supplies one. */
    private TextTable texts;
    private final List<Enemy> enemies = new ArrayList<Enemy>();
    private final java.util.Random rnd = new java.util.Random(0x0C17ADE1L);

    /**
     * Cells the player has seen, which is the automap. UpdateMap marks the cell underfoot plus
     * every neighbour with no wall between, so the map fills in as you look down corridors.
     */
    private final boolean[] visited = new boolean[Level.GRID * Level.GRID];
    /** Seconds spent on this level; NewLev3 ticks sv_Time once every 50 frames. */
    private int levelSeconds;
    /**
     * Must stay floating point: a frame is dt * 50, which for dt = 1/50 comes out at 0.99999998,
     * and {@code int += float} truncates, so an int counter here never advances.
     */
    private float secondFrames;
    private boolean playerDead;
    /** VBLANK frames owed to the heart monitor; float for the same reason secondFrames is. */
    private float heartFrames;
    /**
     * Texture animation phase, advanced once per pass of MAIN_LOOP by sv_DoAnims. Walls and
     * columns that animate pick their sprite from this.
     */
    private int animPhase;

    public int animPhase() {
        return animPhase;
    }

    /**
     * sv_WalkState / 2, the enemy walk frame. Each of sv_DoAnims' three sv_DAdo calls runs
     * {@code addi #2,sv_WalkState / andi #7}, so it gains 6 a pass and goes 0, 6, 4, 2: the walk
     * cycle runs backwards through the four frames, 0, 3, 2, 1.
     */
    public int walkPhase() {
        return (animPhase * 3) & 3;
    }

    /**
     * sv_SzumTime: passes of MAIN_LOOP left showing the interference burst that sv_MAKE_SZUM
     * paints over the view. Set to 2 when a shot lands on the player (oc_PlayerHit) and when the
     * player walks into a wall at speed (br_BumpWall).
     */
    private int szumTime;
    /** Changes every pass the burst is drawn, so the renderer re-rolls its rows as the engine does. */
    private int szumFrame;

    /**
     * cc_RequestTab2, which the Z key toggles — "zakłócenia" is Polish for interference. The
     * counter still runs; MAIN_LOOP just skips the call to sv_MAKE_SZUM while this is set.
     */
    private boolean szumOff;

    /** @return true when the burst is now on */
    public boolean toggleSzum() {
        szumOff = !szumOff;
        // SCROLL 91 is DISTURBANCES: ON and 92 is OFF
        log.add(text(szumOff ? 92 : 91, szumOff ? "disturbances off" : "disturbances on"));
        return !szumOff;
    }

    public boolean szumActive() {
        return szumTime > 0 && !szumOff;
    }

    public int szumFrame() {
        return szumFrame;
    }

    /**
     * DRAW_HEART: the heart monitor, and with it the exhaustion that scales the walk speed.
     * Public because the readout draws straight from it.
     */
    public final Heart heart = new Heart();
    /** sv_Flag+2: whether the player moved this pass, which halves the rate they recover at. */
    private boolean movedThisPass;

    /** CheckCodes: the ring of recent keystrokes the cheats are matched against. */
    private final Cheats cheats = new Cheats();
    /** sv_NieUmieraj, toggled by HOTKIWI: energy stops draining. */
    private boolean immortal;
    /** sv_Energy+2, the previous reading TEST_COUNTERS puts back while immortal. */
    private int lastEnergy = Integer.MIN_VALUE;

    /**
     * CheckCodes, called from sv_joystick after every key. Applies whatever the key completed and
     * returns true when it was a cheat, so the caller knows the key was swallowed.
     */
    public boolean typeLetter(char c) {
        Cheats.Cheat cheat = cheats.type(c);
        if (cheat == null) {
            return false;
        }
        apply(cheat);
        if (cheat.message >= 0) {
            log.add(text(cheat.message, cheat.name().toLowerCase()));
        }
        return true;
    }

    private void apply(Cheats.Cheat cheat) {
        switch (cheat) {
            case ENERGY:
                // CheckCodes writes sv_Energy alone, so while HOTKIWI is on TEST_COUNTERS sees a
                // change and puts the old reading back: the cheat does nothing when immortal
                inventory.energy = -666;
                break;
            case ARSENAL:
                // chk_Ammo fills every weapon slot, rolls each condition, and sets each card to -6
                for (int i = 0; i < Inventory.WEAPON_COUNT; i++) {
                    inventory.owned[i] = true;
                    inventory.condition[i] = rnd.nextInt(1000);
                    inventory.ammo[i] = -666;
                }
                for (int i = 0; i < inventory.cards.length; i++) {
                    inventory.cards[i] = -6;
                }
                break;
            case HOP:
                // sv_Rotate of a 1024 unit vector along the view, with no collision check at all
                int[] hop = rotate(1024, (256 - angle) & 0x1fe);
                posX += hop[0];
                posY += hop[1];
                break;
            case IMMORTAL:
                immortal = !immortal;
                break;
            case MAP:
                java.util.Arrays.fill(visited, true);
                break;
            case EXIT:
                levelComplete = true;
                break;
            case BOMB:
                inventory.warhead = -12;
                break;
            default:
                break;
        }
    }

    /**
     * sv_DIFFICULT, which the server's options screen toggles. The engine reads it in five
     * places: the start of a level ({@link #beginLevel}), Take_Items (+70 on a weapon's
     * condition), and br_BumpWall, which skips the point of energy, so walking into walls is free.
     */
    private boolean easy;

    public void setEasy(boolean on) {
        easy = on;
        inventory.easy = on;
    }

    /**
     * What the engine does with STRUCTURE before the first frame of a level. Call it once, after
     * the inventory carried from the last level and the difficulty are in place.
     *
     * <ul>
     * <li>energy is capped at a reading of 500, and on easy a reading under 90 is raised to
     *     90 plus a random 0..63, made even ({@code andi #$0ffe});</li>
     * <li>cards are cleared: {@code clear cards}, every level starts without them;</li>
     * <li>on easy, every fourth slot of the enemy table is emptied ({@code .sv_UsunEn}, slots
     *     0, 4, 8...), and both species' aggression is cut to 4/5.</li>
     * </ul>
     */
    public void beginLevel() {
        int e = Math.min(inventory.energyReading(), 500);
        if (easy && e < 90) {
            e = (90 + rnd.nextInt(64)) & 0x0ffe;
        }
        inventory.energy = -e;
        java.util.Arrays.fill(inventory.cards, 0);
        inventory.resetLevelState();
        if (easy) {
            java.util.Iterator<Enemy> it = enemies.iterator();
            while (it.hasNext()) {
                Enemy en = it.next();
                if (en.slot % 4 == 0 && en.slot < 63 * 4) {
                    if (level.enemyRef(en.cellX(), en.cellY()) == en.slot) {
                        level.setEnemyRef(en.cellX(), en.cellY(), 0);
                    }
                    bySlot[en.slot] = null;
                    it.remove();
                }
            }
            for (Level.EnemyTemplate t : level.enemyTemplates) {
                t.aggression = t.aggression * 4 / 5;
            }
        }
    }

    public boolean isEasy() {
        return easy;
    }

    public boolean isImmortal() {
        return immortal;
    }

    /** Set when the player steps onto a teleport pad, so the view can run the TELEPORT dissolve. */
    private boolean teleported;

    public boolean consumeTeleported() {
        boolean was = teleported;
        teleported = false;
        return was;
    }

    /**
     * Two passes of sv_MAKE_SZUM, which fills 81 randomly chosen rows of the 128 row view with
     * byte $88 - palette index 17, once the chunky to planar convention is undone.
     *
     * Both br_BumpWall and oc_PlayerHit do this, and both push the player as well: br_BumpWall
     * through the rebound in sv_LastMove+4/+6, oc_PlayerHit through sv_AddMove (see
     * {@link #doMMove}).
     */
    void szum() {
        szumTime = 2;
    }

    /**
     * oc_PlayerHit: the static, plus a random turn that knocks the aim off. br_BumpWall has no
     * equivalent, so walking into a wall does not turn the player.
     */
    void jolt() {
        szum();
        heart.excite();
        // GetRandom / asr.b #4,d0 / ext d0 - a signed byte shifted down 4, so -8..7
        angle = (angle + asrByte(random(), 4)) & 0x1fe;
    }

    public boolean isVisited(int x, int y) {
        return Level.inside(x, y) && visited[x + y * Level.GRID];
    }

    public int levelSeconds() {
        return levelSeconds;
    }

    public boolean isPlayerDead() {
        return playerDead;
    }

    /** The engine flashes the counter red and beeps below 10 energy. */
    public boolean isEnergyCritical() {
        return inventory.energyReading() < 10;
    }

    public GameState(Level level) {
        this.level = level;
        this.posX = level.spawnCellX * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        this.posY = level.spawnCellY * Level.CELL_UNITS + Level.CELL_UNITS / 2;
        this.angle = level.spawnFacing & 511;
        for (Level.EnemySpawn spawn : level.enemySpawns()) {
            Enemy e = new Enemy(spawn);
            enemies.add(e);
            if (e.slot > 0 && e.slot < bySlot.length) {
                bySlot[e.slot] = e;
            }
        }
    }

    /** Hands the rules the original message table so they stop inventing their own wording. */
    public void setTexts(TextTable texts) {
        this.texts = texts;
    }

    /** A message from the table, falling back to the supplied wording when there is no table. */
    private String text(int index, String fallback) {
        if (texts == null) {
            return fallback;
        }
        String s = texts.get(index);
        return s.isEmpty() ? fallback : s;
    }

    public List<Enemy> enemies() {
        return enemies;
    }

    public int cellX() {
        return (posX / Level.CELL_UNITS) & 63;
    }

    public int cellY() {
        return (posY / Level.CELL_UNITS) & 63;
    }

    public boolean isLevelComplete() {
        return levelComplete;
    }

    /**
     * True once a trigger has changed the level's shape (a wall, item or column swapped out), so
     * the renderer knows it has to rebuild. Door animation does not set this: doors move every
     * frame and are animated in place instead.
     */
    public boolean consumeGeometryDirty() {
        boolean was = geometryDirty;
        geometryDirty = false;
        return was;
    }

    /** Adds a message to the same log the rules write to, for front-end feedback. */
    public void note(String message) {
        log.add(message);
    }

    /**
     * A sound the rules asked for. The front end decides how to play it; positional sounds carry
     * the world coordinates the engine would have used to scale the volume (Sound_Distance).
     */
    public static final class SoundEvent {
        /** Index into {@link SoundTable#ALL}. */
        public final int index;
        public final int worldX;
        public final int worldY;
        /** True when the sound comes from somewhere in the level rather than from the player. */
        public final boolean positional;

        SoundEvent(int index, int worldX, int worldY, boolean positional) {
            this.index = index;
            this.worldX = worldX;
            this.worldY = worldY;
            this.positional = positional;
        }
    }

    private final List<SoundEvent> sounds = new ArrayList<SoundEvent>();

    public List<SoundEvent> drainSounds() {
        List<SoundEvent> out = new ArrayList<SoundEvent>(sounds);
        sounds.clear();
        return out;
    }

    /** Queues a sound heard at the player's own position. */
    private void playSound(int index) {
        if (index >= 0) {
            sounds.add(new SoundEvent(index, posX, posY, false));
        }
    }

    /** Queues a sound coming from a point in the level, as the SOUND2 macro does. */
    private void playSoundAt(int index, int x, int y) {
        if (index >= 0) {
            sounds.add(new SoundEvent(index, x, y, true));
        }
    }

    /** Messages the last update produced, for the HUD or for debugging. Cleared by the caller. */
    public List<String> drainLog() {
        List<String> out = new ArrayList<String>(log);
        log.clear();
        return out;
    }

    public float angleRadians() {
        return (float) (angle * 2.0 * Math.PI / 512.0);
    }

    /**
     * The forward vector for an angle, in world axes.
     *
     * Both the player (sv_DoMMove) and the enemies (me_w4) hand sv_rotate {@code 256 - angle}
     * rather than the angle itself, and sv_rotate with no sideways component yields
     * {@code (L*cos(index), L*sin(index))}. Substituting gives <b>(-cos a, sin a)</b>: the X axis
     * runs the opposite way to the naive reading. Getting this wrong mirrors east and west, which
     * would send every "use" at the wall behind the one the player is facing.
     */
    public static double forwardX(int angle) {
        return -Math.cos(angle * 2.0 * Math.PI / 512.0);
    }

    public static double forwardY(int angle) {
        return Math.sin(angle * 2.0 * Math.PI / 512.0);
    }

    private double forwardX() {
        return forwardX(angle);
    }

    private double forwardY() {
        return forwardY(angle);
    }

    /** Strafing right, i.e. the forward vector turned a quarter turn, from the d3 cases. */
    private double rightX() {
        return Math.sin(angle * 2.0 * Math.PI / 512.0);
    }

    private double rightY() {
        return Math.cos(angle * 2.0 * Math.PI / 512.0);
    }

    /** The angle whose forward vector points along (dx, dy). Inverse of {@link #forwardX}. */
    public static int angleTowards(int dx, int dy) {
        return (int) Math.round(Math.atan2(dy, (double) -dx) * 256.0 / Math.PI) & 511;
    }

    // --------------------------------------------------------------- update

    /**
     * Advances the world.
     *
     * While the automap is up or the game is paused only the vertical blank runs: NewLev3 keeps
     * the level clock and the low-energy beep going, and MAIN_LOOP is held (ServeMap, or the
     * {@code tst sv_PAUSE} spin at {@code ml2}).
     *
     * @param forward -1..1, scaled by the original walk speed
     * @param strafe  -1..1
     * @param turn    -1..1, scaled by the original turn speed
     * @param dt      seconds since the last call
     */
    public void update(float forward, float strafe, float turn, float dt) {
        if (playerDead) {
            return;
        }
        float frames = dt * FRAMES_PER_SECOND;
        // The vertical blank runs at a fixed 50 Hz whatever the renderer is doing, and that is
        // where the level clock and the low-energy beep live.
        serveVblank(frames);
        if (mapOn) {
            return;                         // ServeMap waits in sm_Wait until the map goes
        }
        if (paused) {
            // ml2: MAIN_LOOP spins one VBLANK a turn, and every 750 of them says so again
            pauseFrames -= frames;
            while (pauseFrames <= 0f) {
                pauseFrames += 750f;
                log.add(text(TextTable.PAUSED, "... GAME IS PAUSED ..."));
            }
            return;
        }
        testCounters();
        if (playerDead) {
            return;
        }

        // Everything else is paced by MAIN_LOOP, which is a different clock entirely.
        loopAccumulator += dt * loopHz;
        int ticks = Math.min(MAX_CATCHUP, (int) loopAccumulator);
        loopAccumulator -= ticks;
        for (int i = 0; i < ticks && !playerDead; i++) {
            stepLoop(forward, strafe, turn);
        }
    }

    // ---------------------------------------------------------------- pause

    /** sv_Pause. P toggles it; ServeMap toggles it on the way in and again on the way out. */
    private boolean paused;
    /** sv_Pause+2: VBLANKs to the next SCROLL 41, reloaded with 750. */
    private float pauseFrames = 750f;
    /** sv_MapOn, which M and TAB toggle. */
    private boolean mapOn;

    /**
     * cc_p: P flips sv_Pause, with SCROLL 37 going in (and 750 VBLANKs to the first SCROLL 41)
     * and SCROLL 38 coming out. Ignored while the map is up ({@code cc_IsMap} swallows every key
     * but the map's own).
     *
     * @return true when the game is now paused
     */
    public boolean togglePause() {
        if (mapOn || playerDead) {
            return paused;
        }
        paused = !paused;
        if (paused) {
            log.add(text(37, "... GAME PAUSED ..."));
            pauseFrames = 750f;
        } else {
            log.add(text(38, "THE FIGHT GOES ON!"));
        }
        return paused;
    }

    /**
     * cc_m: M or TAB flips sv_MapOn, and works paused or not. MAIN_LOOP then hands over to
     * ServeMap, which flips sv_Pause for as long as the map is shown — so it stops the game,
     * the heart included, and a game paused with P runs its heart during the map and is paused
     * again after — and prints SCROLL 74 with the player's square written into it (SetLocation).
     *
     * @return true when the map is now up
     */
    public boolean toggleMap() {
        if (playerDead) {
            return mapOn;
        }
        mapOn = !mapOn;
        paused = !paused;
        if (mapOn) {
            log.add(location());
        }
        return mapOn;
    }

    /** SetLocation: the square's X and Y, two digits each, at characters 15 and 18 of text 74. */
    private String location() {
        String s = text(74, "YOUR LOCATION: 00-00");
        if (s.length() < 20) {
            return s;
        }
        char[] c = s.toCharArray();
        int x = cellX(), y = cellY();
        c[15] = (char) ('0' + x / 10);
        c[16] = (char) ('0' + x % 10);
        c[18] = (char) ('0' + y / 10);
        c[19] = (char) ('0' + y % 10);
        return new String(c);
    }

    /** sv_Pause as the engine holds it: note that it reads false while a P pause is under the map. */
    public boolean isPaused() {
        return paused;
    }

    public boolean isMapOn() {
        return mapOn;
    }

    /** True while MAIN_LOOP is held, by the map or by P: nothing but the level clock moves. */
    public boolean isHalted() {
        return mapOn || paused;
    }

    /**
     * One pass of MAIN_LOOP, in its order: the speed potion's countdown, sv_joystick
     * (sv_DoMMove), sv_Border, OpenCloseDoors, Take_Items, Move_Enemy, Check_RMB,
     * ServePriorDoor, then after the frame is drawn sv_DoAnims, the objects and UpdateMap.
     *
     * Every distance and rate the engine holds — sv_WalkSpeed's 250, sv_RotSpeed's 12, a door's
     * six or seven steps, one call to Move_Enemy — is expressed per pass of this loop, not per
     * video frame.
     */
    private void stepLoop(float forward, float strafe, float turn) {
        // sv_BlitUoK: tst sv_WalkSpeed+6 / subi #1 / bne / move sv_WalkSpeed+4,sv_WalkSpeed+2
        inventory.tickSpeed();

        movedThisPass = false;   // sv_joystick clears sv_Flag+2, sv_DoMMove sets it again if moved
        if (turn != 0f) {
            // A rising angle sweeps west, north, east, south, which is clockwise seen from above,
            // so turning right must *increase* it. Subtracting here mirrored every control.
            // add d7,sv_angle / andi #$1fe: the angle is always even.
            angle = (angle + Math.round(turn * ROT_SPEED)) & 0x1fe;
        }
        doMMove(forward, strafe);
        border();
        openCloseDoors();
        takeItems();
        testCounters();
        moveEnemies();
        // Check_RMB runs once per MAIN_LOOP pass, after Move_Enemy; the machinegun fires on
        // every pass the button is held
        if (triggerHeld && inventory.current == MACHINEGUN_SLOT) {
            fire();
        }
        servePriorDoor(doors[0]);
        servePriorDoor(doors[1]);
        // subi #1,sv_Flag+8 / bpl / move #0: the launcher's reload, after Check_RMB
        if (launcherReload > 0) {
            launcherReload--;
        }

        animPhase++;
        if (szumTime > 0) {
            szumTime--;
            szumFrame++;
        }
        updateProjectiles();
        markVisited();
    }

    private static final int MACHINEGUN_SLOT = 3;
    private boolean triggerHeld;

    /** The fire button's state, for the machinegun's automatic fire. */
    public void holdTrigger(boolean held) {
        triggerHeld = held;
    }

    /** do_pikaj+2: VBLANKs to the next low-energy beep. Starts at 50 and is never reset. */
    private float beepCountdown = 50f;

    /**
     * NewLev3, once per VBLANK: the level clock, Draw_Heart (skipped while sv_Pause is set) and
     * do_pikaj's beep.
     *
     * The beep's countdown is only reloaded when it expires, never when the warning starts or
     * stops, so the first beep waits out whatever was left of it: 50 frames the first time.
     */
    private void serveVblank(float frames) {
        secondFrames += frames;
        while (secondFrames >= FRAMES_PER_SECOND) {
            secondFrames -= FRAMES_PER_SECOND;
            levelSeconds++;
        }

        if (!paused) {
            // NewLev3 calls Draw_Heart every VBLANK, and that is where regeneration happens
            heartFrames += frames;
            while (heartFrames >= 1f) {
                heartFrames -= 1f;
                heart.tick(movedThisPass);
            }
        }

        if (beeping) {
            beepCountdown -= frames;
            while (beepCountdown <= 0f) {
                beepCountdown += 55f;
                playSound(SoundTable.BEEP);
            }
        }
    }

    /** do_pikaj, which TEST_COUNTERS sets below 10 energy and clears otherwise. */
    private boolean beeping;

    /**
     * TEST_COUNTERS, which MAIN_LOOP calls once a pass: watch for the player running out of
     * energy, and start or stop the beep. While sv_NieUmieraj is set any change to the reading
     * is put back, and the beep is left as it was.
     */
    private void testCounters() {
        if (lastEnergy == Integer.MIN_VALUE) {
            lastEnergy = inventory.energy;
        } else if (inventory.energy == lastEnergy) {
            return;                                   // beq.s .tc_EnOK
        } else if (immortal) {
            inventory.energy = lastEnergy;
            return;
        }
        lastEnergy = inventory.energy;
        if (!inventory.isAlive()) {
            inventory.energy = 0;
            playerDead = true;
            beeping = false;
            heart.setFlatline(true);
            log.add(text(39, "you are dead"));
            playSound(SoundTable.PLAYER_HURT);
            return;
        }
        beeping = isEnergyCritical();
    }

    /**
     * sc_NormWalk: a winded player is a slower one, down to 65% at the worst. The speed potion
     * takes the base speed straight, which is what {@code tst sv_WalkSpeed+6} skips ahead to.
     */
    private int walkSpeed() {
        int base = inventory.walkSpeed();
        return inventory.isSpeedBoosted() ? base : heart.walkSpeed(base);
    }

    // ---------------------------------------------------------------- moving

    /** sv_LastMove: the length of this pass's move as sv_DoMMove saved it, before any rebound. */
    private int lastMoveLen;
    /** sv_LastMove+2: its direction, as handed to sv_rotate ({@code 256 - angle} plus a turn). */
    private int lastMoveDir;
    /** sv_LastMove+4: the rebound length br_BumpWall and the point-blank shots leave behind. */
    private int reboundLen;
    /** sv_LastMove+6: the rebound's direction, in the same terms as {@link #lastMoveDir}. */
    private int reboundDir;
    /** sv_AddMove: the shove from a shot (oc_PlayerHit) or an enemy (me_PLAYER). */
    private int addMoveX;
    private int addMoveY;
    /** sv_LastPos: where the player stood before this pass's move. */
    private int lastPosX;
    private int lastPosY;

    /**
     * sv_joystick's d3 as {@code .sv_KORYGUJ} turns it into a change of direction. Forward is 0;
     * the keys give 256 back, -128 right, 128 left, 64 forward-left, -64 forward-right, 192 and
     * 320 back-left and back-right. With nothing pressed sv_MouseMove still calls sv_DoMMove,
     * with no length and d3 = 1, i.e. backwards, which is the way a drunk player drifts.
     */
    static int moveTurn(float forward, float strafe) {
        int f = Float.compare(forward, 0f);
        int s = Float.compare(strafe, 0f);
        if (f > 0) {
            return s < 0 ? 64 : s > 0 ? -64 : 0;
        }
        if (f < 0) {
            return s < 0 ? 256 - 64 : s > 0 ? 256 + 64 : 256;
        }
        return s > 0 ? -128 : s < 0 ? 128 : 256;
    }

    /**
     * sv_DoMMove. The keys give one vector of sv_WalkSpeed along the view turned by
     * {@link #moveTurn}, so a diagonal is as fast as a straight line. Then, in order:
     *
     * <ul>
     * <li>vodka: while sv_Vodka runs, two random seven-bit signed numbers (-64..63) are added
     *     to the sideways and the length components, and the second, {@code asr #3}, skews the
     *     direction by it and turns the view back by as much;</li>
     * <li>sv_LastMove takes the length and direction;</li>
     * <li>a rebound (sv_LastMove+4) loses 60 a pass and, while it stays at or above 0, replaces
     *     the move altogether: 250 into a wall comes back as 190, 130, 70 and 10;</li>
     * <li>sv_LastPos takes the position, and sv_AddMove, when it is not zero, replaces the move.
     *     The engine tests only the X word and reads both under {@code andi #$ff}, which throws
     *     the sign away: a shove west or south lands east or north. The port applies the shove
     *     the way it was meant, in its own direction, kept to the same 255 units at most.</li>
     * </ul>
     */
    private void doMMove(float forward, float strafe) {
        int d1 = 0;
        if (forward != 0f || strafe != 0f) {
            float mag = Math.min(1f, Math.max(Math.abs(forward), Math.abs(strafe)));
            d1 = Math.round(walkSpeed() * mag);
        }
        if (d1 != 0) {
            movedThisPass = true;               // move #1,sv_Flag+2
        }
        int d6 = (256 - angle + moveTurn(forward, strafe)) & 0x1fe;
        int d0 = 0;
        if (inventory.vodka > 0) {
            inventory.vodka--;
            d0 += vodkaJitter();
            int d4 = vodkaJitter();
            d1 += d4;
            d4 >>= 3;                           // asr #3,d4
            d6 = (d6 + d4) & 0x1fe;
            angle = (angle - d4) & 0x1fe;
        }
        lastMoveLen = d1;
        lastMoveDir = d6;
        if (reboundLen != 0) {
            reboundLen -= 60;
            if (reboundLen < 0) {
                reboundLen = 0;
            } else {
                d1 = reboundLen;
                d6 = reboundDir;
            }
        }
        int[] v = rotate(d0, d1, d6);
        lastPosX = posX;
        lastPosY = posY;
        if (addMoveX != 0 || addMoveY != 0) {
            // deliberate departure: the engine's andi #$ff would send a negative shove the
            // wrong way, see the list above
            v[0] = Math.max(-255, Math.min(255, addMoveX));
            v[1] = Math.max(-255, Math.min(255, addMoveY));
        }
        int fromX = cellX(), fromY = cellY();
        posX = clampCoord(posX + v[0]);
        posY = clampCoord(posY + v[1]);
        addMoveX = 0;
        addMoveY = 0;
        blockCrossing(fromX, fromY);
    }

    /**
     * One of the two ROM bytes a drunk step reads, {@code andi.b #127 / lsl.b / asr.b}: the low
     * seven bits as a signed number, -64..63.
     */
    private int vodkaJitter() {
        return ((random() & 127) << 25) >> 25;
    }

    /**
     * Keeps the player inside the cell the move started in whenever the face crossed is shut.
     *
     * The original has no such test, and mostly needs none: a sober move is at most 250 units
     * (300 with the potion) against a 307 unit clamp, and a sv_AddMove shove is at most 255. A
     * drunk one can reach 313, though, which takes a
     * player clamped against a wall 6 units through it, and sv_Border then clamps them on the
     * far side. The port refuses that crossing.
     */
    private void blockCrossing(int fromX, int fromY) {
        int toX = cellX();
        if (toX != fromX) {
            int dir = (toX > fromX) ? Level.EAST_DIR : Level.WEST_DIR;
            if (!level.isPassable(fromX, fromY, dir)) {
                posX = fromX * Level.CELL_UNITS
                        + (dir == Level.EAST_DIR ? Level.CELL_UNITS - 1 : 0);
            }
        }
        int toY = cellY();
        if (toY != fromY) {
            int dir = (toY > fromY) ? Level.NORTH_DIR : Level.SOUTH_DIR;
            if (!level.isPassable(fromX, fromY, dir)) {
                posY = fromY * Level.CELL_UNITS
                        + (dir == Level.NORTH_DIR ? Level.CELL_UNITS - 1 : 0);
            }
        }
    }

    private static int clampCoord(int v) {
        return Math.max(0, Math.min(64 * Level.CELL_UNITS - 1, v));
    }

    // ------------------------------------------------------------ collision

    /** {@code 1024-min_distance+60}: the in-cell coordinate beyond which a north or east face is near. */
    private static final int FAR = Level.CELL_UNITS - PLAYER_RADIUS;

    /**
     * sv_Border, in its order:
     *
     * <ol>
     * <li>the exit square ends the level;</li>
     * <li>br_Chk_OPEN, for each door channel not taken by a priority door;</li>
     * <li>the four faces: within 307 of a face that br_ChkDoors does not pass, clamp to 307 and
     *     call br_BumpWall. X first (west, else east), then Y (south, else north);</li>
     * <li>br2_COLUMN: the enemy whose number is in the cell's byte 7, if within 300 units on
     *     both axes, puts the player back where this pass began, and costs a point of energy
     *     if it is burning;</li>
     * <li>.br2_COLUMN2: a blocking column's box, or a teleport pad;</li>
     * <li>br2_UP / br2_DOWN: the corners;</li>
     * <li>br2_END: the in-cell position is written back, and kept for the next pass's column
     *     test.</li>
     * </ol>
     *
     * Clamping after the fact is what gives the original its sliding-along-walls feel.
     */
    private void border() {
        int cx = cellX(), cy = cellY();
        int inX = posX & 1023;
        int inY = posY & 1023;
        if (cx == level.exitCellX && cy == level.exitCellY && !levelComplete) {
            levelComplete = true;
            log.add("level complete");
        }
        // tst sv_DoorFlag1+22 / bne br_PriorD1: a channel serving a priority door is left alone
        for (DoorSlot slot : doors) {
            if (!slot.prior) {
                checkOpen(slot, cx, cy);
            }
        }

        final int r = PLAYER_RADIUS;
        if (inX < r) {
            if (!level.isPassable(cx, cy, Level.WEST_DIR)) {
                inX = r;
                // move #512,d4 / subi #256-62,d5: unmasked, so bmi throws a negative away
                bumpWall(lastMoveDir - (256 - 62), 512, level.faceByte(cx, cy, Level.WEST_DIR));
            }
        } else if (inX >= FAR && !level.isPassable(cx, cy, Level.EAST_DIR)) {
            inX = FAR;
            bumpWall((lastMoveDir + 62) & 0x1fe, 0, level.faceByte(cx, cy, Level.EAST_DIR));
        }
        if (inY < r) {
            if (!level.isPassable(cx, cy, Level.SOUTH_DIR)) {
                inY = r;
                bumpWall(lastMoveDir - (378 - 62), 378 + 378,
                        level.faceByte(cx, cy, Level.SOUTH_DIR));
            }
        } else if (inY >= FAR && !level.isPassable(cx, cy, Level.NORTH_DIR)) {
            inY = FAR;
            bumpWall(lastMoveDir - (128 - 62), 256, level.faceByte(cx, cy, Level.NORTH_DIR));
        }

        if (enemyInTheWay(cx, cy)) {
            return;
        }

        // .br2_COLUMN2: andi #31 on byte 5, then columns 1, 2 and 4, and 18, the barrel
        int col = level.column(cx, cy) & 31;
        boolean inBox;
        if (col == 18) {
            // beq.s .br2p lands on the bmi meant for the low X test with the flags of the cmpi,
            // which are never minus: the barrel's box runs to the west edge of the cell
            inBox = inX < 512 + COLUMN_HALF
                    && inY >= 512 - COLUMN_HALF && inY < 512 + COLUMN_HALF;
        } else if (col != 0 && col < 5 && col != 3) {
            inBox = inX >= 512 - COLUMN_HALF && inX < 512 + COLUMN_HALF
                    && inY >= 512 - COLUMN_HALF && inY < 512 + COLUMN_HALF;
        } else {
            inBox = false;
        }
        if (inBox) {
            if (col == COLUMN_TELEPORT) {
                // br_FindTeleport; an inactive pad goes straight to br2_END
                if (!teleportFrom(cx, cy)) {
                    finishBorder(inX, inY);
                }
                return;
            }
            // br2_c1..c3: back out along whichever axes the last pass's position was outside
            // the box on. Both axes are tested; the X branch falls into the Y one.
            if (lastInX < 512 - 209) {
                inX = 512 - COLUMN_PUSH;
            } else if (lastInX >= 512 + 209) {
                inX = 512 + COLUMN_PUSH;
            }
            if (lastInY < 512 - 209) {
                inY = 512 - COLUMN_PUSH;
            } else if (lastInY >= 512 + 209) {
                inY = 512 + COLUMN_PUSH;
            }
        }

        // br2_UP / br2_DOWN: the corners. tst.b on the two faces that meet at the corner from
        // the neighbouring cells - anything at all, open doors included - and then push back
        // along the axis that went in less, X when the two are equal.
        int nY = (cy + 1) & 63, sY = (cy - 1) & 63, eX = (cx + 1) & 63, wX = (cx - 1) & 63;
        if (inY >= FAR) {
            if (inX < r && (level.faceByte(cx, nY, Level.WEST_DIR) != 0
                    || level.faceByte(wX, cy, Level.NORTH_DIR) != 0)) {
                if (inY - FAR >= r - inX) {
                    inX = r;
                } else {
                    inY = FAR;
                }
            }
            if (inX >= FAR && (level.faceByte(cx, nY, Level.EAST_DIR) != 0
                    || level.faceByte(eX, cy, Level.NORTH_DIR) != 0)) {
                if (inY - FAR >= inX - FAR) {
                    inX = FAR;
                } else {
                    inY = FAR;
                }
            }
        }
        if (inY < r) {
            if (inX < r && (level.faceByte(cx, sY, Level.WEST_DIR) != 0
                    || level.faceByte(wX, cy, Level.SOUTH_DIR) != 0)) {
                if (r - inY >= r - inX) {
                    inX = r;
                } else {
                    inY = r;
                }
            }
            if (inX >= FAR && (level.faceByte(cx, sY, Level.EAST_DIR) != 0
                    || level.faceByte(eX, cy, Level.SOUTH_DIR) != 0)) {
                if (r - inY >= inX - FAR) {
                    inX = FAR;
                } else {
                    inY = r;
                }
            }
        }
        finishBorder(inX, inY);
    }

    /** br2_END: {@code andi #$fc00 / or}, and sv_InSquarePos for the next pass. */
    private void finishBorder(int inX, int inY) {
        posX = (posX & ~1023) | inX;
        posY = (posY & ~1023) | inY;
        lastInX = inX;
        lastInY = inY;
    }

    /**
     * br2_COLUMN: byte 7 of the cell names an enemy, and if it stands within 300 units of the
     * player on both axes the move is undone — sv_LastPos goes back into sv_PosX and the walls'
     * clamping is thrown away with it. A burning one costs a point of energy (not spared on
     * easy), SOUND 13 and SCROLL 71.
     *
     * @return true when the player was put back, which ends sv_Border at br2_END2
     */
    private boolean enemyInTheWay(int cx, int cy) {
        Enemy e = enemyAt(level.enemyRef(cx, cy) & 0xff);
        if (e == null || Math.abs(e.x - posX) >= 300 || Math.abs(e.y - posY) >= 300) {
            return false;
        }
        if (e.state == Enemy.BURNING) {
            inventory.hurt(1);
            playSound(SoundTable.PLAYER_HURT);
            log.add(text(71, "OUCH! IT'S HOT!"));
        }
        posX = lastPosX;
        posY = lastPosY;
        lastInX = posX & 1023;
        lastInY = posY & 1023;
        return true;
    }

    /**
     * br_BumpWall: walking into a wall at speed costs a point of energy and earns an insult, and
     * throws the player back off it.
     *
     * Each caller hands it sv_LastMove+2 less the direction that goes straight into its face,
     * plus 62 ({@code d5}), and the face's quadrant ({@code d4}: 512 west, 0 east, 756 south, 256
     * north). The hit counts only when d5 lands in 0..123 — within 62 of head on, so brushing a
     * wall as you walk along it is free — and only on a stride of 120 or more. Then:
     *
     * <ul>
     * <li>sv_LastMove+4 takes the stride and sv_LastMove+6 the reflected direction,
     *     {@code d4 - (sv_LastMove+2 + 256)}: the next passes carry the player back off the
     *     wall, 60 shorter each time (see {@link #doMMove});</li>
     * <li>two passes of interference, a point of energy unless on easy, SOUND 1, EXCITE;</li>
     * <li>SCROLL 40 for a shut door, 42 for one still moving, 1 for anything else.</li>
     * </ul>
     *
     * The south face's 378 is six units off the true 384 in the original, and is kept.
     */
    private void bumpWall(int d5, int quadrant, int bumpedWall) {
        if (d5 < 0 || d5 >= BUMP_WINDOW) {
            return;
        }
        if (lastMoveLen < 120) {
            return;
        }
        reboundLen = lastMoveLen;
        reboundDir = (quadrant - (lastMoveDir + 256)) & 0x1fe;
        szum();
        // tst sv_difficult / bne .NieOd: no energy lost on the easy setting
        if (!easy) {
            inventory.hurt(1);
        }
        playSound(SoundTable.BUMP);
        heart.excite();
        switch (bumpedWall & 62) {
            case 30: case 36:
                log.add(text(TextTable.BUMP_DOOR, "the door is shut"));
                break;
            case 34: case 40:
                log.add(text(TextTable.BUMP_OPENING_DOOR, "wait until the door opens"));
                break;
            default:
                log.add(text(TextTable.BUMP_INSULT, "ouch"));
                break;
        }
    }

    /** {@code bmi} then {@code cmpi #124,d5}: a window of 124 units, so 62 either side. */
    private static final int BUMP_WINDOW = 124;

    /**
     * UpdateMap: the square underfoot, then for each side whose face byte is zero
     * ({@code tst.b}, so an open door hides what is beyond it) the neighbour through it, and
     * from that neighbour the two squares to either side whose faces towards it are clear too.
     * So the map reveals up to a 3 x 3 block, which is why a room fills in as you enter it.
     */
    private void markVisited() {
        int cx = cellX(), cy = cellY();
        reveal(cx, cy);
        for (int d = 0; d < 4; d++) {
            if (level.faceByte(cx, cy, d) != 0) {
                continue;
            }
            int nx = cx + Level.DX[d], ny = cy + Level.DY[d];
            if (!Level.inside(nx, ny)) {
                continue;
            }
            reveal(nx, ny);
            // the two sides at right angles: N and S look east then west, E and W north then south
            int[] sides = (d == Level.NORTH_DIR || d == Level.SOUTH_DIR)
                    ? new int[]{Level.EAST_DIR, Level.WEST_DIR}
                    : new int[]{Level.NORTH_DIR, Level.SOUTH_DIR};
            for (int side : sides) {
                if (level.faceByte(nx, ny, side) == 0) {
                    reveal(nx + Level.DX[side], ny + Level.DY[side]);
                }
            }
        }
    }

    private void reveal(int x, int y) {
        if (Level.inside(x, y)) {
            visited[x + y * Level.GRID] = true;
        }
    }

    /**
     * Take_Items: an item is collected when the player is inside a 220 unit box around the centre
     * of its cell. The engine clears only the low six bits, keeping the block flag.
     */
    private void takeItems() {
        int cx = cellX(), cy = cellY();
        int item = level.item(cx, cy);
        if (item == 0) {
            return;
        }
        int inX = posX % Level.CELL_UNITS;
        int inY = posY % Level.CELL_UNITS;
        if (inX < 512 - 220 || inX > 512 + 220 || inY < 512 - 220 || inY > 512 + 220) {
            return;
        }
        // the engine deletes the item before dispatching on it, so 21..31 simply vanish
        level.removeItem(cx, cy);
        geometryDirty = true;
        String what = inventory.take(item);
        if (what == null) {
            return;
        }
        playSound(item >= Inventory.FIRST_AMMO && item < Inventory.FIRST_CARD
                ? SoundTable.TAKE_AMMO
                : item >= Inventory.MEDIKIT && item <= Inventory.VODKA
                        ? SoundTable.TAKE_AID : SoundTable.TAKE_ITEM);
        // Take_Items computes its SCROLL1 index as base + offset, and every branch works out to
        // the item value itself: weapons 2 + (v-2), ammunition 8 + (v-8), cards 14 + (v-14), and
        // the rest are literal SCROLLs at 17, 18 and 19.
        //
        // A warhead section is the exception. ti_Head falls through without a SCROLL of its own;
        // DrawBomb prints it, at 77 plus the stage, so the six sections each get their own line
        // from "FOUND THE FIRST PART OF THE BOMB!" to "YOU FOUND THE LAST PART OF THE BOMB!!!".
        if (item == Inventory.WARHEAD) {
            int stage = BombGfx.stageFor(inventory.warhead);
            log.add(text(BOMB_TEXT + Math.max(0, stage), what));
        } else {
            log.add(text(item, what));
        }
    }


    // ------------------------------------------------------------ teleports

    private boolean teleportFrom(int cx, int cy) {
        for (Level.Teleport t : level.teleports()) {
            if (t.fromX == cx && t.fromY == cy) {
                posX = t.toX * Level.CELL_UNITS + Level.CELL_UNITS / 2;
                posY = t.toY * Level.CELL_UNITS + Level.CELL_UNITS / 2;
                lastInX = 512;
                lastInY = 512;
                log.add("teleported to (" + t.toX + "," + t.toY + ")");
                teleported = true;
                return true;
            }
        }
        return false;
    }


    // ---------------------------------------------------------------- doors

    /**
     * One of the two door channels, sv_DoorFlag1 and sv_DoorFlag2, field for field:
     *
     * <pre>
     *   +0  flag     1 opening, 0 closing; br_Chk_OPEN clears it every pass
     *   +1  counter  a signed byte: 6 (5 for door 1 once it has shut) is shut, -1 open
     *   +2  faces    the faces being moved, each followed by its far side, -1 terminated
     *   +20 in use
     *   +22 serving a priority door
     *   +24 the priority door queued by rm_FixDoor: flag, counter and faces as above
     *   +44 the flag as it was when the door sound last played
     * </pre>
     *
     * A face is kept as the cell, the direction and the mirror bit it had when it was listed,
     * which is what {@code lsr d5 / roxl d4} packs into each offset word.
     */
    public static final class DoorSlot {
        /** 1 or 2: which family of door values this channel drives. */
        final int type;
        /** Cell values for this type: closed, open, animating. */
        final int closed;
        final int open;
        final int animating;
        /** The count at which a closing door is shut: {@code cmpi.b #5} for door 1, 6 for door 2. */
        final int shut;

        boolean opening;
        int counter;
        final int[] faces = new int[FACE_ROOM];
        int faceCount;
        boolean inUse;
        boolean prior;

        boolean queuedOpening;
        int queuedCounter;
        final int[] queued = new int[FACE_ROOM];
        int queuedCount;

        int soundFlag;

        DoorSlot(int type) {
            this.type = type;
            this.closed = (type == 1) ? 30 : 36;
            this.open = (type == 1) ? 32 : 38;
            this.animating = (type == 1) ? 34 : 40;
            this.shut = (type == 1) ? 5 : 6;
            this.counter = shut;         // dc.w 5 and dc.w 6
        }

        public boolean isActive() {
            return inUse;
        }

        /** True while this channel is moving a door a switch or card asked for. */
        public boolean isPriority() {
            return prior;
        }

        /** True while a priority door waits for this channel. */
        public boolean hasQueued() {
            return queuedCount > 0;
        }

        /** 1 or 2. */
        public int type() {
            return type;
        }

        /** Faces in the list, far sides included. */
        public int faceCount() {
            return faceCount;
        }

        public int faceX(int i) {
            return faces[i] & 63;
        }

        public int faceY(int i) {
            return (faces[i] >> 6) & 63;
        }

        public int faceDir(int i) {
            return (faces[i] >> 12) & 3;
        }

        /** The cell of the first face listed. */
        public int cellX() {
            return faceCount > 0 ? faceX(0) : 0;
        }

        public int cellY() {
            return faceCount > 0 ? faceY(0) : 0;
        }

        /** The counter as it stands, -1 open to 5 or 6 shut. */
        public int counter() {
            return counter;
        }

        /**
         * How far open a face with this channel's animating value is drawn, 0 shut to 1 open.
         * oc_DrawOpen draws every door-1 face holding 34 from one texture it rebuilds from the
         * counter, {@code counter + 1} rows of 5 still showing; oc_DrawOpen2 does the same for
         * door 2 over six steps.
         */
        public float openFraction() {
            float f = (type == 1) ? (4 - counter) / 5f : (5 - counter) / 6f;
            return Math.max(0f, Math.min(1f, f));
        }
    }

    /** Room for a face list: four doors, each with its far side, and more for a busy trigger. */
    private static final int FACE_ROOM = 64;

    public DoorSlot[] doorSlots() {
        return doors;
    }

    /**
     * How far open the door on a face is drawn: 0 for a shut (or broken) door, 1 for an open
     * one, and in between for a face holding a channel's animating value. That value draws one
     * shared texture per door type, so every face holding it shows the channel's counter.
     */
    public float doorOpenFraction(int x, int y, int dir) {
        switch (level.wallValue(x, y, dir) & 62) {
            case 32: case 38:
                return 1f;
            case 34:
                return doors[0].openFraction();
            case 40:
                return doors[1].openFraction();
            default:
                return 0f;
        }
    }

    private static int packFace(int x, int y, int dir, int mirror) {
        return (x & 63) | ((y & 63) << 6) | ((dir & 3) << 12) | ((mirror & 1) << 14);
    }

    /** Appends a face and its far side ({@code addi #512+2 ... andi #$7ffb}, wrapping). */
    private int listFace(int[] list, int n, int x, int y, int dir) {
        if (n + 2 > list.length) {
            return n;
        }
        list[n++] = packFace(x, y, dir, level.wallValue(x, y, dir) & 1);
        int nx = (x + Level.DX[dir]) & 63, ny = (y + Level.DY[dir]) & 63;
        int od = Level.OPPOSITE[dir];
        list[n++] = packFace(nx, ny, od, level.wallValue(nx, ny, od) & 1);
        return n;
    }

    /**
     * br_Chk_OPEN, from sv_Border, for one channel: the flag is cleared, and unless the cell
     * carries the block flag each N, E, S and W face whose whole byte lies in the channel's range
     * (30..35 or 36..41, so a door with blood on it never opens) is listed with its far side,
     * br_ChkDoorStatus sets the counter from it — 6 if shut, -1 if open, left alone if moving,
     * the last face listed winning — and the channel is put in use with the flag set.
     *
     * The list is rewritten only when the cell has such a door, so on leaving it the doors
     * listed keep closing; and a door left behind for another cell's stays as it was, open or
     * half way, until it is next listed.
     */
    private void checkOpen(DoorSlot s, int cx, int cy) {
        s.opening = false;
        if (level.isBlocked(cx, cy)) {
            return;
        }
        int n = 0;
        for (int d = 0; d < 4; d++) {
            int v = level.faceByte(cx, cy, d);
            if (v < s.closed || v >= s.closed + 6) {
                continue;
            }
            n = listFace(s.faces, n, cx, cy, d);
            int status = v & 0xfe;
            if (status == s.closed) {
                s.counter = 6;
            } else if (status == s.open) {
                s.counter = -1;
            }
            s.faceCount = n;
            s.opening = true;
            s.inUse = true;
        }
    }

    /**
     * OpenCloseDoors, for one channel in use.
     *
     * When the flag differs from the one remembered at +44 the door sound plays, from the first
     * face's square — so a door makes its noise starting to open and starting to close. Door 1
     * plays SOUND2 9, or 16 on a level whose header word 26 is set; door 2 plays 10, from a point
     * a square east of the one it means and half a square south of it ({@code addi #512,d0}
     * twice, never d1).
     *
     * Opening counts down: door 1 first comes down to 5, so from shut it shows the moving value
     * for five passes and is open on the sixth; door 2 for six and open on the seventh. Closing
     * counts up to 5 or 6, so door 1 takes six passes to shut and door 2 seven. A priority door
     * gives the channel back as soon as it is fully open or shut.
     */
    private void openCloseDoors() {
        for (DoorSlot s : doors) {
            if (!s.inUse) {
                continue;
            }
            int flag = s.opening ? 1 : 0;
            if (flag != s.soundFlag && s.faceCount > 0) {
                int fx = s.faceX(0), fy = s.faceY(0);
                if (s.type == 1) {
                    playSoundAt(level.doorSound16 ? SoundTable.DOOR3 : SoundTable.DOOR1,
                            fx * Level.CELL_UNITS + 512, fy * Level.CELL_UNITS + 512);
                } else {
                    playSoundAt(SoundTable.DOOR2,
                            fx * Level.CELL_UNITS + 1024, fy * Level.CELL_UNITS);
                }
            }
            s.soundFlag = flag;
            if (s.opening) {
                if (s.type == 1 && s.counter >= 6) {
                    s.counter = 5;                  // cmpi.b #6 / bmi / move.b #5
                }
                s.counter--;
                if (s.counter >= 0) {
                    writeDoor(s, s.animating);
                } else {
                    s.counter = -1;
                    writeDoor(s, s.open);
                    if (s.prior) {
                        release(s);
                    }
                }
            } else {
                s.counter++;
                if (s.counter >= s.shut) {
                    s.counter = s.shut;
                    writeDoor(s, s.closed);
                    release(s);
                } else {
                    writeDoor(s, s.animating);
                }
            }
        }
    }

    /** {@code move #-1,(a1) / move.l #0,20(a1)}: list emptied, not in use, not a priority. */
    private static void release(DoorSlot s) {
        s.faceCount = 0;
        s.inUse = false;
        s.prior = false;
    }

    /**
     * oc_FixMap: {@code move.b d2,(a2,d1.w)} writes the whole byte, the door value with the
     * mirror bit the face had when it was listed, so any blood on it goes.
     */
    private void writeDoor(DoorSlot s, int value) {
        for (int i = 0; i < s.faceCount; i++) {
            int f = s.faces[i];
            int x = f & 63, y = (f >> 6) & 63, d = (f >> 12) & 3;
            level.setWallValue(x, y, d, value | ((f >> 14) & 1));
            level.setBlood(x, y, d, 0);
        }
    }

    /**
     * ServePriorDoor, once a pass after Check_RMB: a channel not in use takes the priority door
     * queued for it, if there is one, and is marked in use and serving a priority. Until it
     * finishes, br_Chk_OPEN leaves that channel alone, so automatic doors of the same type stay
     * as they are.
     */
    private void servePriorDoor(DoorSlot s) {
        if (s.inUse || s.queuedCount == 0) {
            return;
        }
        s.opening = s.queuedOpening;
        s.counter = s.queuedCounter;
        System.arraycopy(s.queued, 0, s.faces, 0, s.queuedCount);
        s.faceCount = s.queuedCount;
        s.queuedCount = 0;
        s.inUse = true;
        s.prior = true;
    }

    /**
     * rm_OpenDoor / rm_CloseDoor and rm_FixDoor: a door a command moves is queued on the
     * priority table of its channel ({@code sv_DoorFlag+24}) rather than set, and animates once
     * ServePriorDoor hands it over.
     *
     * The face's whole byte, mirror bit dropped, decides: already open (or shut) is skipped, a
     * door already moving is skipped, 30 or 32 is door 1 and anything else door 2. The queue's
     * flag and counter become {@code $0106} to open or {@code $00ff} to close — so the last
     * command decides for every door queued with it — the face and its far side are appended,
     * and the channel's sound flag is set to 50 so that the next pass it is served plays the
     * sound.
     */
    private void fixDoor(int x, int y, int dir, boolean open) {
        int b = level.faceByte(x, y, dir) & 0xfe;
        if (open ? (b == 32 || b == 38) : (b == 30 || b == 36)) {
            return;
        }
        if (b == 34 || b == 40) {
            return;
        }
        DoorSlot s = (b == 30 || b == 32) ? doors[0] : doors[1];
        s.soundFlag = 50;
        s.queuedOpening = open;
        s.queuedCounter = open ? 6 : -1;
        s.queuedCount = listFace(s.queued, s.queuedCount, x, y, dir);
    }

    // -------------------------------------------------------------- enemies

    /** GetRandom: a word of Amiga ROM in the original, a word of a seeded PRNG here. */
    private int random() {
        return rnd.nextInt(65536);
    }

    /** {@code asr.b #n,d0 / ext d0}: the low byte of a word, as a signed byte, shifted down. */
    static int asrByte(int word, int n) {
        return ((byte) word) >> n;
    }

    /** sq_SQRT, which approximates a floor square root digit by digit. */
    static int isqrt(long v) {
        long r = (long) Math.sqrt((double) v);
        while (r * r > v) {
            r--;
        }
        while ((r + 1) * (r + 1) <= v) {
            r++;
        }
        return (int) r;
    }

    /**
     * sv_sinus, 256 entries a turn. The engine loads it from data at run time; it is taken here
     * as {@code round(32767 sin)}, the only Q15 table whose top entry fits a signed word.
     */
    private static final int[] SIN = new int[256];

    static {
        for (int k = 0; k < SIN.length; k++) {
            SIN[k] = (int) Math.round(Math.sin(2 * Math.PI * k / 256) * 32767);
        }
    }

    /**
     * sv_rotate with x = 0 and y = {@code len}, which is how every caller uses it to build a
     * vector: {@code muls} by the cosine and the sine at table offset {@code d6}, then
     * {@code add.l d0,d0 / swap}, i.e. {@code (len * table * 2) >> 16}.
     *
     * Callers hand it {@code 256 - angle} (plus any spread) masked with {@code $1fe}, which is
     * what turns the result into the forward vector {@code (-cos a, sin a)}.
     *
     * @return {x, y}
     */
    static int[] rotate(int len, int d6) {
        return rotate(0, len, d6);
    }

    /**
     * sv_rotate in full: {@code x' = x sin + y cos}, {@code y' = y sin - x cos}, each
     * {@code add.l / swap}, so {@code (v * 2) >> 16} — a floor, not a rounding. sv_DoMMove hands
     * it the drunk sideways jitter as x and the length of the step as y.
     *
     * @return {x', y'}
     */
    static int[] rotate(int x, int y, int d6) {
        int k = (d6 & 0x1fe) >> 1;
        int sin = SIN[k];
        int cos = SIN[(k + 64) & 255];
        return new int[]{((x * sin + y * cos) * 2) >> 16, ((y * sin - x * cos) * 2) >> 16};
    }

    /** sv_EnemyData by slot, which is how a cell's byte 7 finds its enemy. */
    private final Enemy[] bySlot = new Enemy[256];

    private Enemy enemyAt(int slot) {
        Enemy e = (slot > 0 && slot < bySlot.length) ? bySlot[slot] : null;
        return (e == null || e.gone) ? null : e;
    }

    /**
     * Move_Enemy. The list is built first, from byte 7 of every cell in the 24 x 24 window from
     * 12 cells behind the player to 11 ahead on each axis ({@code subi #[12*512]+[12*8]+1} then
     * 24 rows of 24), and only those enemies are served: the rest of the level stands still.
     */
    private void moveEnemies() {
        int px = cellX(), py = cellY();
        List<Enemy> found = new ArrayList<Enemy>();
        for (int y = py - 12; y < py + 12; y++) {
            for (int x = px - 12; x < px + 12; x++) {
                if (!Level.inside(x, y)) {
                    continue;
                }
                Enemy e = enemyAt(level.enemyRef(x, y));
                if (e != null) {
                    found.add(e);
                }
            }
        }
        for (Enemy e : found) {
            if (playerDead) {
                return;
            }
            switch (e.state) {
                case Enemy.HIT:
                    meHited(e);
                    break;
                case Enemy.KILLED:
                    meKilled(e);
                    break;
                case Enemy.BURNING:
                    meBurning(e);
                    break;
                case Enemy.SHOOTING:
                    meShootOn(e);
                    break;
                case Enemy.WALKING:
                    meWalk(e);
                    break;
                default:
                    break;   // bne.s me_Found: any other state is left alone
            }
        }
    }

    /**
     * me_angletab: the bearing to the player is not computed but looked up, by
     * {@code (dx * 16) / dist}, so an enemy aims in 33 steps across a half turn.
     */
    private static final int[] ME_ANGLE_TAB = {
            0, 22, 40, 50, 58, 66, 72, 80, 86, 92, 96, 102, 106, 112, 118, 124, 128,
            134, 138, 144, 150, 154, 160, 164, 170, 176, 184, 190, 198, 206, 216, 230, 256,
    };

    /**
     * The angle me_a1 and me_ShootIt set to face the player, from the offsets enemy less player:
     * {@code divs} by the distance, the table, negated when the enemy is south of the player,
     * {@code addi #256 / andi #$1fe}.
     */
    static int aimAt(int dx, int dy, int dist) {
        int q = (dist == 0) ? 0 : (dx * 16) / dist;
        int a = ME_ANGLE_TAB[Math.max(-16, Math.min(16, q)) + 16];
        if (dy < 0) {
            a = -a;
        }
        return (a + 256) & 0x1fe;
    }

    /**
     * me_walk: roll a number under 8192 against the distance plus the species' energy word.
     * At or under it is me_ShootIt — a roll under 5000 just walks on, anything else turns to the
     * player and fires if CanShoot allows. Over it, the enemy turns to the player and me_w3 fires
     * when the distance is under the roll modulo distance plus aggression; otherwise it walks.
     */
    private void meWalk(Enemy e) {
        Level.EnemyTemplate stats = level.enemyTemplates[e.species];
        int dx = e.x - posX;
        int dy = e.y - posY;
        int dist = isqrt((long) dx * dx + (long) dy * dy);
        int roll = random() & 0x1fff;
        if (dist + stats.energy >= roll) {
            if (roll < 5000) {
                meStep(e);
                return;
            }
            e.angle = aimAt(dx, dy, dist);
            if (lineOfSight(e.x, e.y, posX, posY, dist)) {
                beginShooting(e);
            } else {
                meStep(e);
            }
            return;
        }
        e.angle = aimAt(dx, dy, dist);
        int range = (dist + stats.aggression) & 0xffff;
        // divu d2,d1 / swap: the remainder; a zero divisor would trap, so it just walks
        if (range != 0 && dist < roll % range && lineOfSight(e.x, e.y, posX, posY, dist)) {
            beginShooting(e);
        } else {
            meStep(e);
        }
    }

    private void beginShooting(Enemy e) {
        e.state = Enemy.SHOOTING;
        e.counter = Enemy.SHOOT_COUNTER;
    }

    /**
     * me_w4: a roar now and then, one step along the facing, and what to do about the result.
     *
     * <ul>
     * <li>The roar takes a GetRandom word of 5..9, so 5 in 65536 a pass, and plays SOUND4
     *     18 + (word &amp; 3), with 3 read as 0.</li>
     * <li>A step that goes through is me_w2: one pass in 16 ({@code $f0} of a byte) the enemy
     *     turns by up to &plusmn;126, {@code (r0 ^ r1) &amp; $7e}, negative when r0 is even.</li>
     * <li>A blocked step is me_RepTurn: up to three turns of {@code (r0 ^ r1) &amp; $fe} each,
     *     retrying after each — but with the vector saved in sv_MovSav before the first try,
     *     not one along the new facing. So a blocked enemy does not move that pass; it only
     *     turns, and walks off in the new direction next time. Each retry still runs me_MOVE's
     *     whole test, so one pressed against the player shoves and turns him every time.</li>
     * </ul>
     */
    private void meStep(Enemy e) {
        int r = random();
        if (r >= 5 && r < 10) {
            int d2 = r & 3;
            if (d2 == 3) {
                d2 = 0;
            }
            playSoundAt(SoundTable.ENEMY_ROAR_FIRST + d2, e.x, e.y);
        }
        int[] v = rotate(e.speed, (256 - e.angle) & 0x1fe);
        if (meMove(e, v[0], v[1])) {
            int r0 = random(), r1 = random();
            if ((r0 & 0xff) >= 0xf0) {
                int t = (r0 ^ r1) & 0x7e;
                if ((r0 & 1) == 0) {
                    t = -t;
                }
                e.angle = (e.angle + t) & 0x1fe;
            }
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            int r0 = random(), r1 = random();
            int t = (r0 ^ r1) & 0xfe;
            if ((r0 & 1) == 0) {
                t = -t;
            }
            e.angle = (e.angle + t) & 0x1fe;
            if (meMove(e, v[0], v[1])) {
                return;
            }
        }
    }

    /**
     * me_shootON: four a pass from 60, the shot at 68 (es_EnemyShoot), and at 72 back to walking
     * with the counter at 60.
     *
     * The return to walking does {@code move.b #0,12(a3)} and then {@code bne me_Found}, which
     * cannot branch after a move of zero: it falls into me_hited. So every shot ends with the
     * enemy shoved along its stored knockback and its counter at 64 — harmless for the walk,
     * which never reads it, but a real move when the last hit left a knockback behind.
     */
    private void meShootOn(Enemy e) {
        e.counter = (e.counter + 4) & 0xff;
        if (e.counter == 68) {
            enemyShoot(e);
            return;
        }
        if (e.counter != 72) {
            return;
        }
        e.counter = Enemy.SHOOT_COUNTER;
        e.state = Enemy.WALKING;
        meHited(e);
    }

    /**
     * me_hited: two passes of recoil, the counter going 72, 76, 80, each moving the enemy by
     * twice its knockback bytes ({@code ext / lsl #1}). The grunt the engine plays on the first
     * of them is played by {@link #hurtEnemy} as the hit lands.
     */
    private void meHited(Enemy e) {
        meMove(e, e.knockX * 2, e.knockY * 2);
        e.counter = (e.counter + 4) & 0xff;
        if (e.counter == 80) {
            e.state = Enemy.WALKING;   // move #0,12(a3) clears the counter too
            e.counter = 0;
        }
    }

    /**
     * me_killed: three passes of falling, still shoved by the knockback. The counter goes 72,
     * then 76 which is bumped to 88, 92 and 96: frames 18, 22 and 23. All that time the enemy
     * keeps its cell, so it blocks the way and soaks up shots (EnemyHitServe ignores a dead one
     * but Object_Collision still stops there).
     */
    private void meKilled(Enemy e) {
        meMove(e, e.knockX * 2, e.knockY * 2);
        e.counter = (e.counter + 4) & 0xff;
        if (e.counter == 76) {
            e.counter = 88;
        }
        if (e.counter == 96) {
            finishKilled(e);
        }
    }

    /**
     * The end of me_killed. sv_NrKilled goes up, the enemy leaves its cell and its slot, and a
     * corpse goes into the top bits of byte 5: 32, or 96 when {@code flags & 3} is 2 — which in
     * every shipped map means the second species.
     *
     * Then one time in 12.8 ({@code GetRandom & $ff < 20}) the enemy leaves ammunition for its
     * weapon, item {@code weapon + 7}, unless the cell already holds an item. There is a second
     * roll, but only when the condition word of that weapon in sv_ITEMS is negative ("u have diz
     * weapon?"). Conditions are never negative — DamageWeapon clamps at 0 and Take_Items writes
     * 90 and more — so it is dead code for weapons 0..6; only a weapon 7, which reads the first
     * card counter, would take it.
     */
    private void finishKilled(Enemy e) {
        int cx = e.cellX(), cy = e.cellY();
        removeEnemy(e);
        boolean second = (e.flags & 3) == 2;
        level.setCorpse(cx, cy, second ? 3 : 1);
        int weapon = level.enemyTemplates[second ? 1 : 0].weapon;
        if ((random() & 0xff) < 20 && level.item(cx, cy) == 0) {
            boolean drop = weaponCondition(weapon) >= 0 || (random() & 0xff) < 20;
            if (drop) {
                level.setItem(cx, cy, (weapon + 7) & 31);
            }
        }
    }

    /** The word at sv_ITEMS + 4 + weapon * 6 that me_killed tests. */
    private int weaponCondition(int weapon) {
        if (weapon >= 0 && weapon < Inventory.WEAPON_COUNT) {
            return inventory.condition[weapon];
        }
        return -1;   // weapon 7 lands on sv_Cards' $9de0
    }

    /** {@code addi #1,sv_NrKilled}, the cell's byte 7 cleared and {@code move #0,(a3)}. */
    private void removeEnemy(Enemy e) {
        killed++;
        if (level.enemyRef(e.cellX(), e.cellY()) == e.slot) {
            level.setEnemyRef(e.cellX(), e.cellY(), 0);
        }
        e.gone = true;
        e.state = Enemy.KILLED;
        geometryDirty = true;
    }

    /**
     * me_burning: the energy word counts the burn down from 55, so 56 passes, while the counter
     * flips between 80 and 84 and the enemy staggers one step a pass in a random direction —
     * GetRandom masked to {@code $1fe} is the rotate index itself. A step into the player costs
     * him a point of energy (see {@link #touchPlayer}). Below 0 it is a burnt corpse, 64 in byte
     * 5, or 128 for the second species, and no ammunition.
     */
    private void meBurning(Enemy e) {
        if (--e.energy < 0) {
            if (fireOwner == e) {
                fireOwner = null;   // move #$0004,$dff096: the fire loop stops
            }
            int cx = e.cellX(), cy = e.cellY();
            removeEnemy(e);
            level.setCorpse(cx, cy, (e.flags & 3) == 2 ? 4 : 2);
            return;
        }
        if (fireOwner == e) {
            fireVolume = soundDistance2(e.x, e.y);
        }
        e.counter ^= 4;
        int[] v = rotate(e.speed, random() & 0x1fe);
        meMove(e, v[0], v[1]);
    }

    /**
     * eh_FirePos: the enemy whose burning owns the looping fire sample on audio channel 2. Each
     * new fire takes the channel over; only the owner's death silences it.
     */
    private Enemy fireOwner;
    /** The volume me_burning last wrote to the channel, 0..63, from Sound_Distance2. */
    private int fireVolume;

    /**
     * The looping fire sample (sound_list entry 13, {@link SoundTable#FIRE_LOOP}): -1 while no
     * enemy is burning, otherwise the Paula volume 0..63 me_burning computes each pass from the
     * owner's distance.
     */
    public int fireLoopVolume() {
        return fireOwner == null ? -1 : fireVolume;
    }

    /** Sound_Distance2: silent from 7990 units, else {@code 63 ^ (dist * 64 / 8000)}. */
    private int soundDistance2(int x, int y) {
        long dx = x - posX, dy = y - posY;
        int d = isqrt(dx * dx + dy * dy);
        if (d >= 7990) {
            return 0;
        }
        return ((d << 6) / 8000) ^ 63;
    }

    /**
     * me_MOVE, with the cell's byte 7 lifted while the step is tested and put back wherever the
     * enemy ends up.
     *
     * @return true when the enemy moved
     */
    private boolean meMove(Enemy e, int dx, int dy) {
        int fromX = e.cellX(), fromY = e.cellY();
        if (level.enemyRef(fromX, fromY) == e.slot) {
            level.setEnemyRef(fromX, fromY, 0);
        }
        boolean moved = meTryMove(e, dx, dy);
        level.setEnemyRef(e.cellX(), e.cellY(), e.slot);
        return moved;
    }

    /**
     * me_MOVE's tests on the new position, in its order: another enemy in the cell; a shut face
     * within 300 units ({@code cmpi #300} and {@code cmpi #1024-300}); a blocking column's
     * 210 box; the player within 300 on both axes; and me_CORNERS, which refuses a corner whose
     * two neighbouring faces hold anything at all ({@code tst.b}, open doors included).
     *
     * One test is the port's own: a crossing is also refused against the cell the step began
     * in. me_MOVE only looks at the destination's faces; with walls stored on both sides that
     * is the same thing, except on a face the map author left one-sided.
     */
    private boolean meTryMove(Enemy e, int dx, int dy) {
        int nx = e.x + dx, ny = e.y + dy;
        if (nx < 0 || ny < 0 || nx >= 64 * Level.CELL_UNITS || ny >= 64 * Level.CELL_UNITS) {
            return false;
        }
        int fromX = e.cellX(), fromY = e.cellY();
        int cx = nx >> 10, cy = ny >> 10;
        int inX = nx & 1023, inY = ny & 1023;

        if (level.enemyRef(cx, cy) != 0) {
            return false;
        }
        if (cx != fromX) {
            int dir = (cx > fromX) ? Level.EAST_DIR : Level.WEST_DIR;
            if (!level.isPassable(fromX, fromY, dir)) {
                return false;
            }
        }
        if (cy != fromY) {
            int dir = (cy > fromY) ? Level.NORTH_DIR : Level.SOUTH_DIR;
            if (!level.isPassable(cx, fromY, dir)) {
                return false;
            }
        }
        final int r = Enemy.RADIUS;
        if ((inX < r && !level.isPassable(cx, cy, Level.WEST_DIR))
                || (inX >= 1024 - r && !level.isPassable(cx, cy, Level.EAST_DIR))
                || (inY < r && !level.isPassable(cx, cy, Level.SOUTH_DIR))
                || (inY >= 1024 - r && !level.isPassable(cx, cy, Level.NORTH_DIR))) {
            return false;
        }
        int col = level.column(cx, cy);
        if (blockingColumn(col)
                && inX >= 512 - Enemy.COLUMN_HALF && inY >= 512 - Enemy.COLUMN_HALF
                && inX < 512 + Enemy.COLUMN_HALF && inY < 512 + Enemy.COLUMN_HALF) {
            return false;
        }
        if (Math.abs(posX - nx) < r && Math.abs(posY - ny) < r) {
            touchPlayer(e, dx, dy);
            return false;
        }
        if (cornerBlocked(cx, cy, inX, inY)) {
            return false;
        }
        e.x = nx;
        e.y = ny;
        return true;
    }

    /** me_CORNERS: within 300 of a corner, the two faces meeting it from the neighbours. */
    private boolean cornerBlocked(int cx, int cy, int inX, int inY) {
        final int r = Enemy.RADIUS;
        if (inX < r) {
            if (inY < r) {
                return anyFace(cx - 1, cy, Level.SOUTH_DIR) || anyFace(cx, cy - 1, Level.WEST_DIR);
            }
            if (inY >= 1024 - r) {
                return anyFace(cx - 1, cy, Level.NORTH_DIR) || anyFace(cx, cy + 1, Level.WEST_DIR);
            }
        } else if (inX >= 1024 - r) {
            if (inY < r) {
                return anyFace(cx + 1, cy, Level.SOUTH_DIR) || anyFace(cx, cy - 1, Level.EAST_DIR);
            }
            if (inY >= 1024 - r) {
                return anyFace(cx + 1, cy, Level.NORTH_DIR) || anyFace(cx, cy + 1, Level.EAST_DIR);
            }
        }
        return false;
    }

    private boolean anyFace(int x, int y, int dir) {
        return Level.inside(x, y) && level.faceByte(x, y, dir) != 0;
    }

    /**
     * me_PLAYER: an enemy stepping into the player shoves him by a quarter of its step
     * (sv_AddMove, see {@link #addMove}), turns him by {@code asr.b #5}, -4..3, and, if it is
     * burning, costs him a point of energy with SOUND 13 and SCROLL 71.
     */
    private void touchPlayer(Enemy e, int dx, int dy) {
        addMove(dx >> 2, dy >> 2);
        angle = (angle + asrByte(random(), 5)) & 0x1fe;
        if (e.state == Enemy.BURNING) {
            playSound(SoundTable.PLAYER_HURT);
            inventory.hurt(1);
            log.add(text(71, "you are burning"));
        }
    }

    /**
     * {@code add d2,sv_AddMove / add d3,sv_AddMove+2}: the push oc_PlayerHit and me_PLAYER give
     * the player. It adds up over a pass and the next sv_DoMMove spends it, see
     * {@link #doMMove} for how.
     */
    private void addMove(int dx, int dy) {
        addMoveX = (short) (addMoveX + dx);
        addMoveY = (short) (addMoveY + dy);
    }

    /**
     * {@code move #len,sv_LastMove+4 / move dir,sv_LastMove+6}: the recoil a point-blank
     * flamer, bolter or launcher shot throws the player back with, {@code moveAngle} being
     * {@code -sv_Angle} in sv_rotate's terms, i.e. straight backwards. It is spent like a wall's
     * rebound: 60 shorter on each of the following passes.
     */
    private void lastMove(int length, int moveAngle) {
        reboundLen = length;
        reboundDir = moveAngle & 0x1fe;
    }

    // -------------------------------------------------------- the object table

    /** sv_ObjectTab holds 30 slots; a shot that finds none is simply not fired. */
    public static final int OBJECT_SLOTS = 30;

    private final List<Projectile> projectiles = new ArrayList<Projectile>();

    public List<Projectile> projectiles() {
        return projectiles;
    }

    /** {@code .SeekEmpty}: whether a slot is free. */
    private boolean slotFree() {
        int used = 0;
        for (Projectile p : projectiles) {
            if (p.type != 0) {
                used++;
            }
        }
        return used < OBJECT_SLOTS;
    }

    /** Takes a slot for a new object, or returns null when the table is full. */
    private Projectile allocate(int type, int x, int y, int dx, int dy) {
        if (!slotFree()) {
            return null;
        }
        Projectile p = new Projectile(type, x, y, dx, dy);
        projectiles.add(p);
        return p;
    }

    /**
     * sv_HitFlag: while set, Object_Collision cannot hit the player. Check_RMB holds it for the
     * whole of the player's own shot, and ao_f2 / es_Launcher set it around ro_SetExplode when
     * the rocket itself struck the player, so the blast does not hurt him a second time.
     */
    private boolean hitFlag;

    /**
     * eh_FirePos+4: set by ro_SetExplode while it throws its fireballs, so barrels stop them like
     * walls and one explosion cannot set off the next.
     */
    private boolean barrelsShielded;

    /**
     * oc_beczkas: sixteen (map offset, damage) pairs, one per barrel that has been hit. A bullet
     * adds 5 and anything else 30, and at 30 the barrel goes. When all sixteen are taken the
     * first one is reused, damage and all.
     */
    private final int[] barrelOffset = new int[16];
    private final int[] barrelDamage = new int[16];

    /**
     * Anim_Objects. Marks age and go: impacts and discharges after their counter reaches 3,
     * explosions at 7. Flying objects add 2 to the counter, leave at 60 (fireball) or 40, and
     * otherwise take one Object_Collision call, 8 sub-steps:
     *
     * <ul>
     * <li>electricity that hits anything becomes a discharge mark ($0200);</li>
     * <li>a fireball becomes an explosion mark $0604 with SOUND2 11, or, if what it hit was a
     *     barrel it destroyed, sets the barrel off (the {@code bmi.w ao_f2} lands in the
     *     rocket's code);</li>
     * <li>a rocket goes off through ro_SetExplode, with sv_HitFlag set if it hit the player.</li>
     * </ul>
     *
     * Objects added during the pass are not served until the next; the engine's table can serve
     * one in the same pass when it lands in a later slot, which is not reproduced.
     */
    private void updateProjectiles() {
        int n = projectiles.size();
        for (int i = 0; i < n; i++) {
            Projectile p = projectiles.get(i);
            switch (p.type) {
                case Projectile.IMPACT:
                case Projectile.DISCHARGE:
                    if ((++p.counter & 3) == 3) {
                        p.type = 0;
                    }
                    break;
                case Projectile.EXPLOSION:
                    if ((++p.counter & 7) == 7) {
                        p.type = 0;
                    }
                    break;
                case Projectile.FIREBALL:
                case Projectile.ELECTRIC:
                case Projectile.ROCKET:
                    animateFlying(p);
                    break;
                default:
                    break;
            }
        }
        purgeObjects();
    }

    /** Drops the slots freed ({@code move #0,(a1)}) since the last look. */
    private void purgeObjects() {
        projectiles.removeIf(p -> p.type == 0);
    }

    private void animateFlying(Projectile p) {
        int limit = (p.type == Projectile.FIREBALL) ? 60 : 40;
        p.counter += 2;
        if ((p.counter & 0x3e) == limit) {
            p.type = 0;   // hit nothing
            return;
        }
        p.anim ^= 1;
        int hit = collide(p);
        if (hit == 0) {
            return;
        }
        switch (p.type) {
            case Projectile.ELECTRIC:
                toMark(p, Projectile.DISCHARGE, 0);
                break;
            case Projectile.FIREBALL:
                if (hit < 0) {
                    explode(p);
                } else {
                    toMark(p, Projectile.EXPLOSION, 4);
                    playSoundAt(SoundTable.EXPLODE, p.x, p.y);
                }
                break;
            default:
                hitFlag = hit == 2;
                explode(p);
                hitFlag = false;
                break;
        }
    }

    private static void toMark(Projectile p, int type, int counter) {
        p.type = type;
        p.counter = counter;
    }

    /** .ColFound: a bullet that hit something stays as an impact mark at a random height. */
    private void impactMark(Projectile p) {
        p.height = (random() & 0x0c) >> 2;
        toMark(p, Projectile.IMPACT, 0);
    }

    /**
     * ro_DirTab. The first ring takes all eight entries, the four orthogonal ones at
     * {@code 600/8} and the four diagonals at {@code 700/8} per axis; the second ring takes the
     * first four again.
     */
    private static final int[][] RO_DIR = {
            {75, 0}, {-75, 0}, {0, 75}, {0, -75},
            {87, 87}, {87, -87}, {-87, -87}, {-87, 87},
    };

    /**
     * ro_SetExplode. There is no splash radius and nothing flies: the rocket backs off an eighth
     * of its step ({@code asr #3} of the per-sub-step vector), becomes explosion mark $0603 with
     * SOUND2 11, and twelve fireball probes are resolved on the spot, each with Object_Collision
     * and so each able to hurt, set alight or stop on whatever it meets:
     *
     * <ul>
     * <li>four orthogonal, one call (600 units), a miss leaving mark $0601 where it stopped;</li>
     * <li>four diagonal, one call (696 per axis), a miss leaving $0600;</li>
     * <li>four orthogonal again, two calls (1200 units), a miss leaving $0600.</li>
     * </ul>
     *
     * A probe that hits something is simply freed. Barrels act as walls throughout
     * (eh_FirePos+4), and the player is only hurt when sv_HitFlag is clear — never by his own
     * point-blank rocket or by a barrel his own shot set off.
     *
     * The released engine has a bug here: the second ring reloads the table with
     * {@code lea ro_DirTab,a5} without {@code (pc)}, which in the shipped binary resolves to an
     * absolute $7020A8 that is not the table, so its four vectors are whatever lies there. The
     * port follows the evident intent and reuses the four orthogonal entries.
     */
    private void explode(Projectile p) {
        barrelsShielded = true;
        try {
            p.x -= p.dx >> 3;
            p.y -= p.dy >> 3;
            toMark(p, Projectile.EXPLOSION, 3);
            playSoundAt(SoundTable.EXPLODE, p.x, p.y);
            int bx = p.x, by = p.y;
            for (int i = 0; i < 8; i++) {
                Projectile f = allocate(Projectile.FIREBALL, bx, by, RO_DIR[i][0], RO_DIR[i][1]);
                if (f == null) {
                    return;   // bra ro_End: a full table ends the whole blast
                }
                if (collide(f) == 0) {
                    toMark(f, Projectile.EXPLOSION, i < 4 ? 1 : 0);
                } else {
                    f.type = 0;
                }
            }
            for (int i = 0; i < 4; i++) {
                Projectile f = allocate(Projectile.FIREBALL, bx, by, RO_DIR[i][0], RO_DIR[i][1]);
                if (f == null) {
                    return;
                }
                int hit = collide(f);
                if (hit == 0) {
                    hit = collide(f);
                }
                if (hit == 0) {
                    toMark(f, Projectile.EXPLOSION, 0);
                } else {
                    f.type = 0;
                }
            }
        } finally {
            barrelsShielded = false;
        }
    }

    /** Sets off a rocket with the given per-sub-step vector where it stands, for tests. */
    Projectile detonate(int x, int y, int dx, int dy) {
        Projectile p = allocate(Projectile.ROCKET, x, y, dx, dy);
        if (p != null) {
            explode(p);
        }
        purgeObjects();
        return p;
    }

    /** oc_AddTab: the cell itself, then E, W, N, S, NW, NE, SW, SE. */
    private static final int[][] OC_ADD = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {-1, 1}, {1, 1}, {-1, -1}, {1, -1},
    };

    /** Object_Collision's box around the player, {@code subi #250} and {@code addi #500}. */
    public static final int PLAYER_HIT_BOX = 250;
    /** A column stops an object inside a 190 box around the cell centre. */
    public static final int COLUMN_SHOT_HALF = 190;

    /**
     * Object_Collision: eight sub-steps along the object's vector, each tested in this order.
     *
     * <ol>
     * <li>{@code ori.l #$00010001}: both components forced odd, for the divisions below.</li>
     * <li>A crossing of a cell boundary in X and in Y, each worked out to the point where it
     *     meets the boundary — the last unit inside the old cell — and refused by br_ChkDoors on
     *     that cell's face. A refused crossing leaves the object at that point: a wall hit.
     *     When both are refused the X one wins: the code means to compare the Y hit's cell with
     *     the object's own, but reads {@code 14(a1)}, which is the next slot's vector.</li>
     * <li>Columns 1, 2 and 18 inside a 190 box. 18 is a barrel ({@link #hitBarrel}); the other
     *     two are walls, as is a barrel to electricity or during an explosion.</li>
     * <li>The player, within 250 on both axes, unless sv_HitFlag is set (oc_PlayerHit).</li>
     * <li>An enemy within 200 on both axes, looked up through byte 7 of the nine cells around
     *     (EnemyHitServe). Its state does not matter: a dying or burning one still stops it.</li>
     * </ol>
     *
     * @return 0 nothing, 1 a wall, 2 the player, 3 an enemy, -1 a barrel it destroyed
     */
    private int collide(Projectile p) {
        for (int s = 0; s < Projectile.SUBSTEPS; s++) {
            p.dx |= 1;
            p.dy |= 1;
            int nx = p.x + p.dx, ny = p.y + p.dy;
            if (nx < 0 || ny < 0 || nx >= 65536 || ny >= 65536) {
                return 1;   // the 16 bit world would wrap; the port stops it at the edge
            }

            boolean wallX = false;
            int hxX = 0, hxY = 0;
            int x0 = p.x & 0xfc00, x1 = nx & 0xfc00;
            if (x0 != x1) {
                int bx = (x1 > x0) ? x1 - 1 : x0;
                int dir = (bx - p.x >= 0) ? Level.EAST_DIR : Level.WEST_DIR;
                int by = (bx - p.x) * p.dy / p.dx + p.y;
                if (!passableAt(bx, by, dir)) {
                    wallX = true;
                    hxX = bx;
                    hxY = by;
                }
            }
            boolean wallY = false;
            int hyX = 0, hyY = 0;
            int y0 = p.y & 0xfc00, y1 = ny & 0xfc00;
            if (y0 != y1) {
                int by = (y1 > y0) ? y1 - 1 : y0;
                int dir = (by - p.y >= 0) ? Level.NORTH_DIR : Level.SOUTH_DIR;
                int bx = (by - p.y) * p.dx / p.dy + p.x;
                if (!passableAt(bx, by, dir)) {
                    wallY = true;
                    hyX = bx;
                    hyY = by;
                }
            }
            if (wallX) {
                p.x = hxX;
                p.y = hxY;
                return 1;
            }
            if (wallY) {
                p.x = hyX;
                p.y = hyY;
                return 1;
            }

            p.x = nx;
            p.y = ny;
            int cx = nx >> 10, cy = ny >> 10;
            int inX = nx & 1023, inY = ny & 1023;
            int col = level.column(cx, cy);
            if (col != 0 && (col == 18 || col < 3)
                    && inX >= 512 - COLUMN_SHOT_HALF && inY >= 512 - COLUMN_SHOT_HALF
                    && inX < 512 + COLUMN_SHOT_HALF && inY < 512 + COLUMN_SHOT_HALF) {
                if (col != 18 || barrelsShielded || p.type == Projectile.ELECTRIC) {
                    return 1;
                }
                return hitBarrel(p, cx, cy);
            }

            if (!hitFlag
                    && p.x > posX - PLAYER_HIT_BOX && p.x <= posX + PLAYER_HIT_BOX
                    && p.y > posY - PLAYER_HIT_BOX && p.y <= posY + PLAYER_HIT_BOX) {
                hitPlayer(p);
                return 2;
            }

            for (int[] a : OC_ADD) {
                int ex = cx + a[0], ey = cy + a[1];
                if (!Level.inside(ex, ey)) {
                    continue;
                }
                Enemy e = enemyAt(level.enemyRef(ex, ey));
                if (e != null && Math.abs(e.x - p.x) < Enemy.HIT_BOX
                        && Math.abs(e.y - p.y) < Enemy.HIT_BOX) {
                    enemyHitServe(e, p);
                    return 3;
                }
            }
        }
        return 0;
    }

    /** br_ChkDoors on the face of the cell holding the point (x, y). */
    private boolean passableAt(int x, int y, int dir) {
        int cx = ((x & 0xffff) >> 10) & 63, cy = ((y & 0xffff) >> 10) & 63;
        return level.isPassable(cx, cy, dir);
    }

    /**
     * The barrel ("beczka") branch of Object_Collision. Its damage is kept in oc_beczkas by map
     * offset; at 30 it is cleared, the column and the item go from the map
     * ({@code andi.b #%11100000} on byte 5 keeps a corpse, {@code andi.b #%11000000} on byte 6
     * keeps the block flag), and the caller sets it off with ro_SetExplode.
     *
     * @return -1 when this hit destroyed it, else 1, a wall hit
     */
    private int hitBarrel(Projectile p, int cx, int cy) {
        int offset = cx * 8 + cy * 512;
        int slot = -1;
        for (int i = 0; i < barrelOffset.length; i++) {
            if (barrelOffset[i] == offset) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            for (int i = 0; i < barrelOffset.length; i++) {
                if (barrelOffset[i] == 0) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                slot = 0;
            }
            barrelOffset[slot] = offset;
        }
        barrelDamage[slot] += (p.type == Projectile.BULLET) ? 5 : 30;
        if (barrelDamage[slot] < 30) {
            return 1;
        }
        barrelOffset[slot] = 0;
        barrelDamage[slot] = 0;
        level.setColumn(cx, cy, 0);
        level.removeItem(cx, cy);
        geometryDirty = true;
        return -1;
    }

    /**
     * oc_PlayerHit: SCROLL 72, SOUND 13, EXCITE, the damage for the object's type (5, 25, 20,
     * 40), four times its vector added to sv_AddMove, a turn of {@code asr.b #4} and two passes of
     * interference.
     */
    private void hitPlayer(Projectile p) {
        int damage = (p.type >= 0 && p.type < SHOT_DAMAGE.length) ? SHOT_DAMAGE[p.type] : 0;
        log.add(text(72, "hit for " + damage));
        playSound(SoundTable.PLAYER_HURT);
        inventory.hurt(damage);
        addMove(p.dx * 4, p.dy * 4);
        jolt();
    }

    // ---------------------------------------------------------- enemy weapons

    /** es_SND: the sample each enemy weapon fires with, 1-based as SOUND3 takes it. */
    private static final int[] ES_SND = {0, 7, 25, 24, 5, 6, 3, 3};

    /**
     * es_EnemyShoot, at counter 68 of the wind-up. The species' weapon byte picks a sample from
     * es_SND (SOUND3, so only within 7990 units) and a routine:
     *
     * <ul>
     * <li>1, 2, 3 — es_HandGun with 1, 3 or 2 bullets: real objects, each spread by
     *     {@code (r ^ r) asr.b #3} (-16..15), started 300 units ahead and walked through up to
     *     20 Object_Collision calls (8000 units) at once. They can miss, strike another enemy,
     *     or blow up a barrel, which ends the volley;</li>
     * <li>4 — a fireball, 5 — a bolt, 6 — a rocket, spread by {@code asr.b #5} (-4..3), also
     *     300 ahead, given their first collision call at once;</li>
     * <li>0 and 7 fire nothing (7 still makes the launcher's sound).</li>
     * </ul>
     *
     * There is no reload: the wind-up itself, and the roll in me_walk, are all the pacing.
     */
    private void enemyShoot(Enemy e) {
        int weapon = level.enemyTemplates[e.species].weapon;
        int sample = (weapon >= 0 && weapon < ES_SND.length) ? ES_SND[weapon] : 0;
        if (sample != 0 && soundDistance2(e.x, e.y) != 0) {
            playSoundAt(sample - 1, e.x, e.y);
        }
        switch (weapon) {
            case 1:
                enemyBullets(e, 1);
                break;
            case 2:
                enemyBullets(e, 3);
                break;
            case 3:
                enemyBullets(e, 2);
                break;
            case 4:
                enemyFireball(e);
                break;
            case 5:
            case 6:
                enemyBolt(e, weapon == 5 ? Projectile.ELECTRIC : Projectile.ROCKET);
                break;
            default:
                break;
        }
    }

    /** es_PrepStruct0 / es_PrepStruct: the vector, and the start {@code ahead} vectors out. */
    private Projectile launchFrom(int type, int x, int y, int len, int d6, int ahead) {
        int[] v = rotate(len, d6 & 0x1fe);
        return allocate(type, x + v[0] * ahead, y + v[1] * ahead, v[0], v[1]);
    }

    private void enemyBullets(Enemy e, int bullets) {
        for (int i = 0; i < bullets; i++) {
            int r1 = random(), r2 = random();
            Projectile p = launchFrom(Projectile.BULLET, e.x, e.y, 400 / 8,
                    256 - e.angle + asrByte(r1 ^ r2, 3), 6);
            if (p == null) {
                return;
            }
            int hit = 0;
            for (int k = 0; k < 20 && hit == 0; k++) {
                hit = collide(p);
            }
            if (hit < 0) {
                explode(p);
                return;
            }
            if (hit == 0) {
                p.type = 0;
            } else {
                impactMark(p);
            }
        }
    }

    /** es_Flamer: a hit on the first call is explosion mark $0603, without a sound. */
    private void enemyFireball(Enemy e) {
        if (!slotFree()) {
            return;
        }
        Projectile p = launchFrom(Projectile.FIREBALL, e.x, e.y, 400 / 8,
                256 - e.angle + asrByte(random(), 5), 6);
        int hit = collide(p);
        if (hit < 0) {
            explode(p);
        } else if (hit != 0) {
            toMark(p, Projectile.EXPLOSION, 3);
        }
    }

    /** es_Bolter and es_Launcher. */
    private void enemyBolt(Enemy e, int type) {
        if (!slotFree()) {
            return;
        }
        Projectile p = launchFrom(type, e.x, e.y, 600 / 8,
                256 - e.angle + asrByte(random(), 5), 4);
        int hit = collide(p);
        if (hit == 0) {
            return;
        }
        if (type == Projectile.ELECTRIC) {
            toMark(p, Projectile.DISCHARGE, 0);
        } else {
            hitFlag = hit == 2;
            explode(p);
            hitFlag = false;
        }
    }

    /** Applies damage to an enemy directly, as EnemyHitServe's subtraction does. */
    public void damageEnemy(Enemy e, int amount) {
        e.energy -= amount;
        if (e.energy < 0) {
            kill(e);
        } else {
            hurtEnemy(e);
        }
    }

    /**
     * eh_stillOk: two passes of recoil (the counter goes 72, 76, 80, see {@link #meHited}), and
     * three times in four a grunt, SOUND 18 + (random &amp; 3) unless that is 3, which is en2,
     * en3 or en4.
     */
    private void hurtEnemy(Enemy e) {
        e.state = Enemy.HIT;
        e.counter = Enemy.HIT_COUNTER;
        int r = rnd.nextInt(4);
        if (r != 3) {
            playSoundAt(SoundTable.ENEMY_ROAR_FIRST + r, e.x, e.y);
        }
    }

    /** sv_NrKilled: enemies killed on this level, counted as each one's corpse is laid down. */
    private int killed;

    public int enemiesKilled() {
        return killed;
    }

    /**
     * EnemyHitServe's kill: state 2 with the counter at 72. The enemy keeps its slot and its
     * cell; {@link #meKilled} takes it down over the next three passes.
     */
    private void kill(Enemy e) {
        // me_killed: SOUND 17 + (random & 3), en1 to en4
        playSoundAt(SoundTable.ENEMY_ROAR_FIRST - 1 + rnd.nextInt(4), e.x, e.y);
        e.state = Enemy.KILLED;
        e.counter = Enemy.HIT_COUNTER;
        log.add("enemy down");
    }

    /**
     * eh_Burning: state 3, counter 80, and the energy word becomes the burn's countdown, 55. The
     * fire sample starts looping on channel 2 at volume 0, and this enemy now owns it.
     */
    private void startBurning(Enemy e) {
        e.state = Enemy.BURNING;
        e.counter = Enemy.BURN_COUNTER;
        e.energy = Enemy.BURN_ENERGY;
        fireOwner = e;
        fireVolume = 0;
    }

    /**
     * EnemyHitServe, for any object that reaches an enemy, the player's or another enemy's.
     *
     * Nothing happens to one already dying or burning. Otherwise the species' mask decides
     * whether the type hurts at all (5 bullet, 25 fireball, 20 electricity, 40 rocket); if it
     * does, the low bytes of the object's vector become the knockback, the enemy turns to face
     * the way the <em>player</em> is looking reversed — whoever fired — and the damage comes off.
     * At 0 it is still standing; below, a fireball sets it burning and anything else kills it
     * and splashes blood ({@link #splashBlood}).
     */
    private void enemyHitServe(Enemy e, Projectile p) {
        if (e.state == Enemy.KILLED || e.state == Enemy.BURNING) {
            return;
        }
        Level.EnemyTemplate stats = level.enemyTemplates[e.species];
        int type = p.type;
        if (type != SHOT_BULLET && type != SHOT_FIREBALL && type != SHOT_ELECTRIC
                && type != SHOT_ROCKET) {
            return;
        }
        if (!stats.vulnerableTo(type)) {
            return;
        }
        int damage = SHOT_DAMAGE[type];
        e.knockX = (byte) p.dx;
        e.knockY = (byte) p.dy;
        e.angle = (angle + 256) & 0x1fe;
        e.energy -= damage;
        if (e.energy >= 0) {
            hurtEnemy(e);
        } else if (damage == 25) {
            startBurning(e);
        } else {
            kill(e);
            splashBlood(e, p);
        }
    }

    /**
     * EnemyHitServe's blood. The face of the enemy's cell the shot was travelling towards — N or
     * S when {@code |dy| >= |dx|}, else E or W — gets stain $40 or $80 at random, and each face
     * either side of it gets $C0 three times in four. Only walls take it: not an empty face, not
     * the door values 30..41, not 48..55.
     */
    private void splashBlood(Enemy e, Projectile p) {
        int cx = e.cellX(), cy = e.cellY();
        int dir;
        if (Math.abs(p.dy) >= Math.abs(p.dx)) {
            dir = p.dy >= 0 ? Level.NORTH_DIR : Level.SOUTH_DIR;
        } else {
            dir = p.dx >= 0 ? Level.EAST_DIR : Level.WEST_DIR;
        }
        int stain = ((random() & 1) == 0) ? 1 : 2;
        if (takesBlood(cx, cy, dir)) {
            level.setBlood(cx, cy, dir, stain);
        }
        for (int side : new int[]{(dir + 3) & 3, (dir + 1) & 3}) {
            if ((random() & 3) != 0 && takesBlood(cx, cy, side)) {
                level.setBlood(cx, cy, side, 3);
            }
        }
        geometryDirty = true;
    }

    private boolean takesBlood(int x, int y, int dir) {
        int w = level.wallValue(x, y, dir) & 0x3e;
        return w != 0 && !(w >= 30 && w < 42) && !(w >= 48 && w < 56);
    }

    /**
     * Line of sight from a point to another, as {@link #lineOfSight} with the distance worked
     * out the way me_walk does.
     */
    public boolean canShoot(int fromX, int fromY, int toX, int toY) {
        long dx = toX - fromX, dy = toY - fromY;
        return lineOfSight(fromX, fromY, toX, toY, isqrt(dx * dx + dy * dy));
    }

    /**
     * CanShoot, transcribed. The line is cut into {@code dist / 1024 + 1} equal steps
     * ({@code divs}), each under a cell long, and every step that changes cell is checked with
     * cs_ChkDoors on the faces it crosses. Wall 52, the transparent one, does not block. A
     * diagonal step, which crosses a corner, looks at the four faces meeting there — the old
     * cell's two and the neighbours' two — and is only refused when at least two are shut
     * (cs_ChkDoors2).
     */
    private boolean lineOfSight(int fromX, int fromY, int toX, int toY, int dist) {
        int sx = (short) (toX - fromX), sy = (short) (toY - fromY);
        int n = (dist & 0xffff) >> 10;
        if (n != 0) {
            sx /= n + 1;
            sy /= n + 1;
        }
        int x = fromX, y = fromY;
        for (int i = 0; i <= n; i++) {
            int ocx = (x >> 10) & 63, ocy = (y >> 10) & 63;
            x = (x + sx) & 0xffff;
            y = (y + sy) & 0xffff;
            int ncx = (x >> 10) & 63, ncy = (y >> 10) & 63;
            int h = Integer.compare(ncx, ocx);
            int v = Integer.compare(ncy, ocy);
            if (h == 0 && v == 0) {
                continue;
            }
            int hDir = h > 0 ? Level.EAST_DIR : Level.WEST_DIR;
            int vDir = v > 0 ? Level.NORTH_DIR : Level.SOUTH_DIR;
            if (v == 0) {
                if (sightShut(ocx, ocy, hDir)) {
                    return false;
                }
            } else if (h == 0) {
                if (sightShut(ocx, ocy, vDir)) {
                    return false;
                }
            } else {
                int shut = 0;
                shut += sightShut(ocx, ocy, vDir) ? 1 : 0;
                shut += sightShut(ocx, ocy, hDir) ? 1 : 0;
                shut += sightShut(ocx + Level.DX[hDir], ocy, vDir) ? 1 : 0;
                shut += sightShut(ocx, ocy + Level.DY[vDir], hDir) ? 1 : 0;
                if (shut >= 2) {
                    return false;
                }
            }
        }
        return true;
    }

    /** cs_ChkDoors: anything but nothing, an open door, wall 52 or a broken door blocks. */
    private boolean sightShut(int x, int y, int dir) {
        if (!Level.inside(x, y)) {
            return false;
        }
        int v = level.wallValue(x, y, dir) & 0x3e;
        return !(v == 0 || v == 32 || v == 38 || v == 52 || v == 54);
    }

    // -------------------------------------------------------------- weapons

    /**
     * Damage types and their damage, from the btst chain in EnemyHitServe. The engine calls them
     * pocisk, kula, prad and rakieta: bullet, fireball, electricity, rocket.
     */
    public static final int SHOT_BULLET = 1;
    public static final int SHOT_FIREBALL = 3;
    public static final int SHOT_ELECTRIC = 4;
    public static final int SHOT_ROCKET = 5;

    private static final int[] SHOT_DAMAGE = {0, 5, 0, 25, 20, 40};

    /** Bullets per trigger pull: rm_AmmoLoop runs 3 times for the shotgun, twice for the MG. */
    private static final int[] WEAPON_PELLETS = {0, 1, 3, 2, 1, 1, 1};

    /**
     * Collision calls per bullet, 400 units each: rm_HandGunUsed does 20 ("up to 8000"), the
     * shotgun and machinegun's rm_AmmoLoop 25 ("up to 10000").
     */
    private static final int[] WEAPON_SHOT_STEPS = {0, 20, 25, 25, 0, 0, 0};

    private static final int FLAMER_SLOT = 4;
    private static final int BOLTER_SLOT = 5;
    private static final int LAUNCHER_SLOT = 6;

    /**
     * sv_Flag+8, the launcher's reload: rm_LauncherUsed sets it to 5 and MAIN_LOOP takes one off
     * after Check_RMB every pass, so a rocket can go every fifth pass. Asking sooner prints
     * SCROLL 68 and nothing else.
     */
    private int launcherReload;

    public int launcherReload() {
        return launcherReload;
    }

    /**
     * The fire action, i.e. Check_RMB with a weapon in hand. With the bare hand it falls through
     * to {@link #use()}, which is what the engine does with sv_SpaceOn.
     *
     * sv_HitFlag is held for the whole shot, so nothing the player fires can hit him on the way
     * out, nor can a barrel he sets off.
     *
     * @return true when a shot was actually fired
     */
    public boolean fire() {
        int w = inventory.current;
        if (w == Inventory.HAND) {
            return use();
        }
        if (Inventory.isCard(w)) {
            return useCard(w - Inventory.FIRST_CARD_SLOT);
        }
        if (!inventory.owned[w]) {
            log.add("you have no " + Inventory.WEAPON_NAMES[w]);
            return false;
        }
        if (w == LAUNCHER_SLOT && launcherReload != 0) {
            log.add(text(68, "reloading"));
            return false;
        }
        if (inventory.ammo[w] >= 0) {
            playSound(SoundTable.NO_AMMO);
            log.add(text(TextTable.OUT_OF_AMMO, "no ammunition"));
            return false;
        }
        // DamageWeapon wears the weapon down by 0 or 1 first, and the shot is refused when that
        // leaves it at 0: a weapon at 1 that wears out does not fire.
        inventory.condition[w] = Math.max(0, inventory.condition[w] - rnd.nextInt(2));
        if (inventory.condition[w] == 0) {
            playSound(SoundTable.NO_AMMO);
            log.add(text(TextTable.WEAPON_DAMAGED,
                    "the " + Inventory.WEAPON_NAMES[w] + " is broken"));
            return false;
        }
        inventory.ammo[w]++;
        playSound(SoundTable.fireSound(w));
        if (w == LAUNCHER_SLOT) {
            launcherReload = 5;
        }

        hitFlag = true;
        try {
            switch (w) {
                case FLAMER_SLOT:
                    playerObject(Projectile.FIREBALL);
                    break;
                case BOLTER_SLOT:
                    playerObject(Projectile.ELECTRIC);
                    break;
                case LAUNCHER_SLOT:
                    playerObject(Projectile.ROCKET);
                    break;
                default:
                    playerBullets(WEAPON_PELLETS[w], WEAPON_SHOT_STEPS[w], w != 1);
                    break;
            }
        } finally {
            hitFlag = false;
            purgeObjects();
        }
        return true;
    }

    /**
     * rm_HandGunUsed and rm_AmmoLoop: each bullet is an object started at the player and run
     * through all its collision calls at once. The shotgun and the machinegun spread theirs by
     * {@code (random &amp; $e0) asr.b #4}, an even -8..6 added to the rotate index. A miss frees
     * the slot, a hit leaves an impact mark, and a barrel destroyed goes off and ends the volley.
     */
    private void playerBullets(int bullets, int steps, boolean spread) {
        for (int i = 0; i < bullets; i++) {
            int d6 = 256 - angle;
            if (spread) {
                d6 += asrByte(random() & 0xe0, 4);
            }
            Projectile p = launchFrom(Projectile.BULLET, posX, posY, 400 / 8, d6, 0);
            if (p == null) {
                return;
            }
            int hit = 0;
            for (int k = 0; k < steps && hit == 0; k++) {
                hit = collide(p);
            }
            if (hit < 0) {
                explode(p);
                return;
            }
            if (hit == 0) {
                p.type = 0;
            } else {
                impactMark(p);
            }
        }
    }

    /**
     * rm_FlamerUsed, rm_BolterUsed and rm_LauncherUsed. PrepareStruct adds
     * {@code random &amp; 6}, 0..6, to the rotate index, and the first Object_Collision call is
     * made there and then. A miss leaves the object in flight. A hit is point blank:
     *
     * <ul>
     * <li>flamer — explosion mark $0603 (a barrel it destroyed goes off instead), 5 energy;</li>
     * <li>bolter — discharge mark $0200, 1 energy;</li>
     * <li>launcher — ro_SetExplode, 5 energy;</li>
     * </ul>
     *
     * each with two passes of interference, EXCITE and a recoil in sv_LastMove of 190 units
     * (250 for the launcher) along {@code -sv_Angle} ({@link #lastMove}).
     */
    private void playerObject(int type) {
        int len = (type == Projectile.FIREBALL) ? 400 / 8 : 600 / 8;
        Projectile p = launchFrom(type, posX, posY, len, 256 - angle + (random() & 6), 0);
        if (p == null) {
            return;
        }
        int hit = collide(p);
        if (hit == 0) {
            return;
        }
        switch (type) {
            case Projectile.FIREBALL:
                if (hit < 0) {
                    explode(p);   // .rm_beczka3: no point-blank cost
                    return;
                }
                toMark(p, Projectile.EXPLOSION, 3);
                pointBlank(5, 190);
                break;
            case Projectile.ELECTRIC:
                toMark(p, Projectile.DISCHARGE, 0);
                pointBlank(1, 190);
                break;
            default:
                explode(p);
                pointBlank(5, 250);
                break;
        }
    }

    private void pointBlank(int energy, int recoil) {
        lastMove(recoil, (-angle) & 0x1fe);
        szum();
        inventory.hurt(energy);
        heart.excite();
    }

    // ------------------------------------------------------------- triggers

    /**
     * The wall face the player is looking at, transcribed from rm_HandUsed:
     *
     * <pre>
     *   d0 = (angle + 64) &amp; $1fe ; d1 = 2
     *   repeat { d1++ ; d0 -= 128 } while d0 &gt;= 0
     *   dir = d1 &amp; 3
     * </pre>
     *
     * The engine's own comment pins it down: "W for 0 degrees".
     */
    public int facingDir() {
        int d0 = (angle + 64) & 0x1fe;
        int d1 = 2;
        do {
            d1++;
            d0 -= 128;
        } while (d0 >= 0);
        return d1 & 3;
    }

    /**
     * rm_HandUsed, what the bare hand does: switches go in and out, and everything else gets a
     * line on the message bar saying what the player is looking at.
     *
     * @return true when a switch was used
     */
    public boolean use() {
        int cx = cellX(), cy = cellY(), dir = facingDir();
        int face = level.wallValue(cx, cy, dir);
        if (face == 0) {
            // nothing on the face: look into the cell instead
            if (level.enemyRef(cx, cy) != 0) {
                log.add(text(70, "an enemy"));
            } else if (level.item(cx, cy) != 0) {
                log.add(text(43, "an item"));
            } else if (level.corpse(cx, cy) != 0) {
                log.add(text(46, "a corpse"));
            } else if (level.column(cx, cy) != 0) {
                log.add(level.column(cx, cy) < 3
                        ? text(45, "a column in the way")
                        : text(TextTable.SEE_COLUMN, "a column"));
            } else {
                log.add(text(TextTable.SEE_NOTHING, "nothing here"));
            }
            return false;
        }
        int wall = face & 0x3e;
        switch (wall) {
            case 54:
                log.add(text(TextTable.SEE_NOTHING, "nothing here"));
                return false;
            case 30: case 36:
                log.add(text(TextTable.DOOR_SHUT, "a shut door"));
                return false;
            case 32: case 38:
                log.add(text(TextTable.DOOR_OPEN, "an open door"));
                return false;
            case 34: case 40:
                log.add(text(TextTable.DOOR_ANIMATING, "the door is moving"));
                return false;
            case 44:
                return serveSwitch(cx, cy, dir, 46, false);
            case 46:
                return serveSwitch(cx, cy, dir, 44, true);
            default:
                break;
        }
        int slot = level.wallPlaque(cx, cy, dir);
        if (slot == 1) {
            log.add(text(55, "an empty card slot"));
        } else if (slot == 2) {
            log.add(text(56, "a full card slot"));
        } else if (level.wallBlood(cx, cy, dir) != 0) {
            log.add(text(48, "blood on the wall"));
        } else {
            log.add(text(TextTable.SEE_WALL, "a wall"));
        }
        return false;
    }

    /**
     * rm_ServeSwitch. The switch flips whether or not the map has a record for it; a switch going
     * in runs the record's first command list, one coming out runs the list after the {@code -2}.
     *
     * It refuses with SCROLL 54, leaving the switch where it is, while either channel is moving
     * a priority door or has one queued ({@code tst sv_DoorFlag1+22}, {@code cmpi #-1,+26}).
     *
     * @return true when the switch moved
     */
    private boolean serveSwitch(int cx, int cy, int dir, int newValue, boolean comingOut) {
        for (DoorSlot s : doors) {
            if (s.prior || s.queuedCount > 0) {
                log.add(text(TextTable.SWITCH_STUCK, "IT DOESN'T MOVE! TRY AGAIN!"));
                return false;
            }
        }
        log.add(text(TextTable.SWITCH_USED, "switch used"));
        playSound(SoundTable.SWITCH);
        int mirror = level.wallValue(cx, cy, dir) & 1;
        level.setWallValue(cx, cy, dir, newValue | mirror);
        geometryDirty = true;
        for (Level.Trigger t : level.triggers()) {
            if (!t.isCardReader() && t.cellX == cx && t.cellY == cy && t.dir == dir) {
                runCommands(t, comingOut);
                break;
            }
        }
        return true;
    }

    /**
     * rm_CardUsed: firing a card held in hand at a card reader on the face ahead. The reader's
     * slot must be empty (1) and a record for this cell must want this colour; the card is used
     * up, the slot shows full (2), and the record's commands run.
     *
     * @param colour 0 red, 1 green, 2 blue
     */
    private boolean useCard(int colour) {
        if (!inventory.hasCard(colour)) {
            log.add(text(TextTable.NO_CARD, "you have no such card"));
            return false;
        }
        int cx = cellX(), cy = cellY(), dir = facingDir();
        int slot = level.wallPlaque(cx, cy, dir);
        if (slot == 2) {
            log.add(text(TextTable.SLOT_FULL, "the slot is full"));
            return false;
        }
        if (slot != 1) {
            log.add(text(57, "nowhere to put the card"));
            return false;
        }
        boolean wrongColour = false;
        for (Level.Trigger t : level.triggers()) {
            if (!t.isCardReader() || t.cellX != cx || t.cellY != cy) {
                continue;
            }
            if (t.cardColour != colour + 1) {
                wrongColour = true;
                continue;
            }
            inventory.cards[colour]++;
            level.setPlaque(cx, cy, dir, 2);
            geometryDirty = true;
            log.add(text(62, "the card fits"));
            playSound(SoundTable.CARD);
            runCommands(t, false);
            return true;
        }
        log.add(wrongColour ? text(TextTable.WRONG_COLOUR, "wrong colour")
                : text(60, "the slot is dead"));
        return false;
    }

    /** rm_CommandLoop over one of a record's two lists; it stops at the first negative word. */
    private void runCommands(Level.Trigger t, boolean secondList) {
        for (Level.Command c : t.commands) {
            if (c.onRelease == secondList) {
                apply(c);
            }
        }
    }

    private void apply(Level.Command c) {
        int x = c.cellX, y = c.cellY, d = c.dir;
        if (!Level.inside(x, y)) {
            return;
        }
        switch (c.code) {
            case Level.Command.OPEN_DOOR:
                fixDoor(x, y, d, true);
                break;
            case Level.Command.CLOSE_DOOR:
                fixDoor(x, y, d, false);
                break;
            case Level.Command.SET_BLOCK:
                level.setBlocked(x, y, true);
                break;
            case Level.Command.REMOVE_BLOCK:
                level.setBlocked(x, y, false);
                break;
            case Level.Command.SET_WALL:
                level.setWallValue(x, y, d, c.param);
                geometryDirty = true;
                break;
            case Level.Command.SET_ITEM:
                level.setItem(x, y, c.param);
                geometryDirty = true;
                break;
            case Level.Command.SET_COLUMN:
                level.setColumn(x, y, c.param);
                geometryDirty = true;
                break;
            default:
                break;
        }
    }
}
