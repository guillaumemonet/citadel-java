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
    /** The original runs on the PAL vertical blank. */
    public static final float FRAMES_PER_SECOND = 50f;

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
    /** Accumulates fractional engine frames so the simulation ticks at the original rate. */
    private float frameAccumulator;

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
    /** Frames until the low-energy warning beeps again, from do_pikaj's 55 frame reload. */
    private float beepCountdown;
    /** True when the last move ran into something hard enough to count, per br_BumpWall. */
    private boolean bumped;

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
            enemies.add(new Enemy(spawn));
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
        if (turn != 0f) {
            angle = (angle - Math.round(turn * ROT_SPEED * frames)) & 511;
        }

        if (forward != 0f || strafe != 0f) {
            // The original moves along a single vector whose length is capped by sv_WalkSpeed, so
            // clamp the combined input rather than letting a diagonal run 41% faster.
            float mag = (float) Math.sqrt(forward * forward + strafe * strafe);
            if (mag > 1f) {
                forward /= mag;
                strafe /= mag;
            }
            double dx = (forward * forwardX() + strafe * rightX()) * inventory.walkSpeed() * frames;
            double dy = (forward * forwardY() + strafe * rightY()) * inventory.walkSpeed() * frames;
            bumped = false;
            step(dx, dy);
            // br_BumpWall only counts a collision when the move was a real stride: it needs the
            // vector length at 120 or more, so brushing a wall while edging along costs nothing.
            if (bumped && Math.hypot(dx, dy) >= 120) {
                hitWall();
            }
        }

        serveDoors();
        takeItems();
        markVisited();
        checkExit();
        serveCounters(frames);

        // Enemies, counters and timers run on the original's frame clock, not on the renderer's.
        frameAccumulator += frames;
        int ticks = Math.min(4, (int) frameAccumulator);
        frameAccumulator -= ticks;
        for (int i = 0; i < ticks; i++) {
            inventory.tick();
            moveEnemies();
            updateProjectiles();
        }
    }

    /**
     * Test_Counters and the second-tick in NewLev3: run the level clock, watch for the player
     * running out of energy, and beep while it is low.
     */
    private void serveCounters(float frames) {
        secondFrames += frames;
        while (secondFrames >= FRAMES_PER_SECOND) {
            secondFrames -= FRAMES_PER_SECOND;
            levelSeconds++;
        }

        if (!inventory.isAlive()) {
            playerDead = true;
            log.add(text(39, "you are dead"));
            playSound(SoundTable.PLAYER_HURT);
            return;
        }
        if (isEnergyCritical()) {
            beepCountdown -= frames;
            if (beepCountdown <= 0) {
                beepCountdown = 55;
                playSound(SoundTable.BEEP);
            }
        } else {
            beepCountdown = 0;
        }
    }

    /** br_BumpWall: walking into a wall at speed costs a point of energy and earns an insult. */
    private void hitWall() {
        inventory.hurt(1);
        log.add(text(TextTable.BUMP_WALL, "ouch"));
    }

    /**
     * UpdateMap: mark the cell underfoot, then every neighbour the player can see into, meaning
     * those with no wall between. That is why the automap fills in ahead of you down a corridor.
     */
    private void markVisited() {
        int cx = cellX(), cy = cellY();
        visited[cx + cy * Level.GRID] = true;
        for (int d = 0; d < 4; d++) {
            if (level.hasWall(cx, cy, d)) {
                continue;
            }
            int nx = cx + Level.DX[d], ny = cy + Level.DY[d];
            if (Level.inside(nx, ny)) {
                visited[nx + ny * Level.GRID] = true;
            }
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
        String what = inventory.take(item);
        if (what == null) {
            return;
        }
        playSound(item >= Inventory.FIRST_AMMO && item < Inventory.FIRST_CARD
                ? SoundTable.TAKE_AMMO
                : item >= Inventory.MEDIKIT && item <= Inventory.VODKA
                        ? SoundTable.TAKE_AID : SoundTable.TAKE_ITEM);
        level.setItem(cx, cy, 0);
        geometryDirty = true;
        // Take_Items computes its SCROLL1 index as base + offset, and every branch works out to
        // the item value itself: weapons 2 + (v-2), ammunition 8 + (v-8), cards 14 + (v-14), and
        // the rest are literal SCROLLs at 17, 18 and 19.
        log.add(text(item, what));
    }

    /**
     * Applies a movement, split so no single sub-step can cross more than one cell boundary.
     * The original never needs this because it moves at most 250 units per frame against a
     * 307 unit radius, but a variable frame rate can ask for much more.
     */
    private void step(double dx, double dy) {
        double len = Math.max(Math.abs(dx), Math.abs(dy));
        int steps = Math.max(1, (int) Math.ceil(len / 200.0));
        for (int i = 0; i < steps; i++) {
            int fromX = cellX(), fromY = cellY();
            posX = clampCoord(posX + (int) Math.round(dx / steps));
            posY = clampCoord(posY + (int) Math.round(dy / steps));
            blockCrossing(fromX, fromY);
            collide();
        }
    }

    /**
     * Keeps the player inside the cell the sub-step started in whenever the face they would cross
     * is shut.
     *
     * The original gets this for free: it moves at most 250 units per frame against a 307 unit
     * clamp, so it can never reach a boundary that is closed. It can still be straddling a doorway
     * when that door shuts, though, and then the next step slips through before the clamp applies.
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

    /**
     * Reimplements sv_Border: clamp the in-cell position away from any face that is not passable,
     * then push out of a blocking column, then fix up the diagonals.
     *
     * Clamping after the fact is what gives the original its sliding-along-walls feel, so it is
     * reproduced rather than replaced with a swept test.
     */
    private void collide() {
        int cx = cellX(), cy = cellY();
        int inX = posX % Level.CELL_UNITS;
        int inY = posY % Level.CELL_UNITS;
        final int r = PLAYER_RADIUS;
        final int far = Level.CELL_UNITS - r;

        if (inX < r && !level.isPassable(cx, cy, Level.WEST_DIR)) {
            inX = r;
            bumped = true;
        } else if (inX > far && !level.isPassable(cx, cy, Level.EAST_DIR)) {
            inX = far;
            bumped = true;
        }
        if (inY < r && !level.isPassable(cx, cy, Level.SOUTH_DIR)) {
            inY = r;
            bumped = true;
        } else if (inY > far && !level.isPassable(cx, cy, Level.NORTH_DIR)) {
            inY = far;
            bumped = true;
        }

        int col = level.column(cx, cy);
        if (col != 0 && insideColumnBox(inX, inY)) {
            if (col == COLUMN_TELEPORT) {
                if (teleportFrom(cx, cy)) {
                    return;
                }
            } else if (blockingColumn(col)) {
                // Push back along whichever axis the player came from, as br2_c1..c3 do.
                if (lastInX < 512 - 209) {
                    inX = 512 - COLUMN_PUSH;
                } else if (lastInX > 512 + 209) {
                    inX = 512 + COLUMN_PUSH;
                } else if (lastInY < 512 - 209) {
                    inY = 512 - COLUMN_PUSH;
                } else if (lastInY > 512 + 209) {
                    inY = 512 + COLUMN_PUSH;
                }
            }
        }

        inX = fixDiagonal(cx, cy, inX, inY, true);
        inY = fixDiagonal(cx, cy, inX, inY, false);

        posX = cx * Level.CELL_UNITS + inX;
        posY = cy * Level.CELL_UNITS + inY;
        lastInX = inX;
        lastInY = inY;
    }

    private static boolean insideColumnBox(int inX, int inY) {
        return inX >= 512 - COLUMN_HALF && inX <= 512 + COLUMN_HALF
                && inY >= 512 - COLUMN_HALF && inY <= 512 + COLUMN_HALF;
    }

    /**
     * Stops the player cutting a corner diagonally when both cells beyond the corner are walled
     * off, which is what br2_UP and br2_DOWN do. Pushes back along the axis with less penetration.
     */
    private int fixDiagonal(int cx, int cy, int inX, int inY, boolean wantX) {
        final int r = PLAYER_RADIUS;
        final int far = Level.CELL_UNITS - r;
        int dirX = (inX < r) ? Level.WEST_DIR : (inX > far) ? Level.EAST_DIR : -1;
        int dirY = (inY < r) ? Level.SOUTH_DIR : (inY > far) ? Level.NORTH_DIR : -1;
        if (dirX < 0 || dirY < 0) {
            return wantX ? inX : inY;
        }
        int nx = cx + Level.DX[dirX], ny = cy + Level.DY[dirX];
        int mx = cx + Level.DX[dirY], my = cy + Level.DY[dirY];
        boolean blocked =
                (Level.inside(nx, ny) && !level.isPassable(nx, ny, dirY))
                || (Level.inside(mx, my) && !level.isPassable(mx, my, dirX));
        if (!blocked) {
            return wantX ? inX : inY;
        }
        int penX = (dirX == Level.WEST_DIR) ? (r - inX) : (inX - far);
        int penY = (dirY == Level.SOUTH_DIR) ? (r - inY) : (inY - far);
        if (penY >= penX) {
            return wantX ? inX : ((dirY == Level.SOUTH_DIR) ? r : far);
        }
        return wantX ? ((dirX == Level.WEST_DIR) ? r : far) : inY;
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
                return true;
            }
        }
        return false;
    }

    private void checkExit() {
        if (!levelComplete && cellX() == level.exitCellX && cellY() == level.exitCellY) {
            levelComplete = true;
            log.add("level complete");
        }
    }

    // ---------------------------------------------------------------- doors

    /**
     * One of the two door channels the engine keeps, sv_DoorFlag1 and sv_DoorFlag2. Only one door
     * of each type can be moving at a time, which is why the original stores a single slot.
     */
    public static final class DoorSlot {
        /** 1 or 2: which family of door values this channel drives. */
        final int type;
        /** Cell values for this type: closed, open, animating. */
        final int closed;
        final int open;
        final int animating;

        boolean inUse;
        boolean opening;
        /** 5 is fully closed, 0 is fully open, matching the counter in OpenCloseDoors. */
        int counter = 5;
        int cellX, cellY, dir;
        boolean hasBack;
        int backX, backY, backDir;

        DoorSlot(int type) {
            this.type = type;
            this.closed = (type == 1) ? 30 : 36;
            this.open = (type == 1) ? 32 : 38;
            this.animating = (type == 1) ? 34 : 40;
        }

        public boolean isActive() {
            return inUse;
        }

        public int cellX() {
            return cellX;
        }

        public int cellY() {
            return cellY;
        }

        public int dir() {
            return dir;
        }

        /** 0 shut, 1 fully open. */
        public float openFraction() {
            return (5 - counter) / 5f;
        }
    }

    public DoorSlot[] doorSlots() {
        return doors;
    }

    /**
     * br_Chk_OPEN plus OpenCloseDoors: any door of the right family on a face of the cell the
     * player stands in opens by itself, unless that cell carries the block flag. When the player
     * leaves, the same door closes again.
     */
    private void serveDoors() {
        int cx = cellX(), cy = cellY();
        for (DoorSlot slot : doors) {
            boolean wantOpen = false;
            if (!level.isBlocked(cx, cy)) {
                for (int d = 0; d < 4; d++) {
                    int v = level.wallValue(cx, cy, d) & 0xff;
                    if (v >= slot.closed && v < slot.closed + 6) {
                        if (!slot.inUse || slot.cellX != cx || slot.cellY != cy || slot.dir != d) {
                            claim(slot, cx, cy, d);
                        }
                        wantOpen = true;
                        break;
                    }
                }
            }
            slot.opening = wantOpen;
            animate(slot);
        }
    }

    private void claim(DoorSlot slot, int cx, int cy, int d) {
        if (slot.inUse && slot.counter != 5 && slot.counter != 0) {
            return; // leave a door that is still mid-animation alone
        }
        slot.inUse = true;
        slot.cellX = cx;
        slot.cellY = cy;
        slot.dir = d;
        int nx = cx + Level.DX[d], ny = cy + Level.DY[d];
        slot.hasBack = Level.inside(nx, ny);
        if (slot.hasBack) {
            slot.backX = nx;
            slot.backY = ny;
            slot.backDir = Level.OPPOSITE[d];
        }
        int v = level.wallValue(cx, cy, d) & 0xff;
        slot.counter = (v == slot.open) ? 0 : 5;
        if (slot.counter != 0) {
            // OpenCloseDoors plays the door sound at the door's own cell, via SOUND2.
            playSoundAt(slot.type == 1 ? SoundTable.DOOR1 : SoundTable.DOOR2,
                    cx * Level.CELL_UNITS + 512, cy * Level.CELL_UNITS + 512);
        }
    }

    private void animate(DoorSlot slot) {
        if (!slot.inUse) {
            return;
        }
        if (slot.opening) {
            if (slot.counter > 0) {
                slot.counter--;
                writeDoor(slot, slot.counter == 0 ? slot.open : slot.animating);
            }
        } else {
            if (slot.counter < 5) {
                slot.counter++;
                writeDoor(slot, slot.counter == 5 ? slot.closed : slot.animating);
            } else {
                writeDoor(slot, slot.closed);
                slot.inUse = false;
            }
        }
    }

    private void writeDoor(DoorSlot slot, int value) {
        setFace(slot.cellX, slot.cellY, slot.dir, value);
        if (slot.hasBack) {
            setFace(slot.backX, slot.backY, slot.backDir, value);
        }
    }

    /** Writes a cell value while keeping the mirror bit the map author chose for that face. */
    private void setFace(int x, int y, int dir, int value) {
        int mirror = level.wallValue(x, y, dir) & 1;
        level.setWallValue(x, y, dir, value | mirror);
    }

    // -------------------------------------------------------------- enemies

    /**
     * Move_Enemy. The original only simulates the 24 x 24 cells around the player, which is both
     * a performance measure and a gameplay one: enemies further away stand still.
     */
    private void moveEnemies() {
        int px = cellX(), py = cellY();
        for (Enemy e : enemies) {
            if (e.isDead()) {
                continue;
            }
            if (Math.abs(e.cellX() - px) > 12 || Math.abs(e.cellY() - py) > 12) {
                continue;
            }
            switch (e.state) {
                case Enemy.HIT:
                    if (--e.counter <= 0) {
                        e.state = Enemy.WALKING;
                    }
                    break;
                case Enemy.BURNING:
                    if (--e.energy <= 0) {
                        kill(e);
                    }
                    break;
                case Enemy.SHOOTING:
                    // me_shootON adds 4 a frame and fires at 68, so eight frames of wind-up.
                    e.counter += 4;
                    if (e.counter >= 68) {
                        fire(e);
                        e.state = Enemy.WALKING;
                        e.counter = 0;
                    }
                    break;
                default:
                    if (e.reload > 0) {
                        e.reload--;
                    }
                    think(e);
                    break;
            }
        }
    }

    /**
     * me_walk: roll against distance plus the species' stats to decide between closing in and
     * standing off, then shoot if there is a clear line.
     */
    private void think(Enemy e) {
        int dx = e.x - posX;
        int dy = e.y - posY;
        int dist = (int) Math.sqrt((double) dx * dx + (double) dy * dy);
        Level.EnemyTemplate stats = level.enemyTemplates[e.species];
        int roll = rnd.nextInt(8192);

        if (e.reload > 0) {
            wander(e);
            return;
        }
        boolean standOff = dist + stats.energy >= roll;
        int toPlayer = angleTowards(-dx, -dy);

        if (standOff) {
            if (roll < 5000) {
                wander(e);
                return;
            }
            e.angle = toPlayer;
            if (canShoot(e.x, e.y, posX, posY)) {
                beginShooting(e);
            } else {
                wander(e);
            }
            return;
        }

        e.angle = toPlayer;
        // me_w3: the closer the enemy is relative to its aggression, the likelier it fires.
        int spread = dist + stats.aggression;
        if (dist < (spread == 0 ? 1 : roll % spread) && canShoot(e.x, e.y, posX, posY)) {
            beginShooting(e);
        } else {
            wander(e);
        }
    }

    private void beginShooting(Enemy e) {
        e.state = Enemy.SHOOTING;
        e.counter = Enemy.SHOOT_COUNTER;
    }

    /** me_w4 then me_RepTurn: walk, and on a blocked step turn at random and retry up to 3 times. */
    private void wander(Enemy e) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (stepEnemy(e)) {
                e.phase = (e.phase + 1) & 3;
                if (rnd.nextInt(256) >= 0xf0) {
                    e.angle = (e.angle + (rnd.nextInt(127) - 63) * 2) & 511;
                }
                return;
            }
            e.angle = (e.angle + (rnd.nextInt(127) - 63) * 2) & 511;
        }
    }

    /**
     * me_MOVE: try to advance along the enemy's facing, refusing the step if it would cross a
     * shut face, enter a column, land on another enemy, or walk into the player.
     *
     * @return true when the enemy actually moved
     */
    private boolean stepEnemy(Enemy e) {
        int nx = e.x + (int) Math.round(forwardX(e.angle) * e.speed);
        int ny = e.y + (int) Math.round(forwardY(e.angle) * e.speed);
        if (nx < 0 || ny < 0 || nx >= 64 * Level.CELL_UNITS || ny >= 64 * Level.CELL_UNITS) {
            return false;
        }
        int fromX = e.cellX(), fromY = e.cellY();
        int cx = nx / Level.CELL_UNITS, cy = ny / Level.CELL_UNITS;
        int inX = nx % Level.CELL_UNITS, inY = ny % Level.CELL_UNITS;

        int other = level.enemyRef(cx, cy);
        if (other != 0 && other != e.slot) {
            return false;
        }
        final int r = Enemy.RADIUS;
        final int far = Level.CELL_UNITS - r;
        if ((inX < r && !level.isPassable(cx, cy, Level.WEST_DIR))
                || (inX > far && !level.isPassable(cx, cy, Level.EAST_DIR))
                || (inY < r && !level.isPassable(cx, cy, Level.SOUTH_DIR))
                || (inY > far && !level.isPassable(cx, cy, Level.NORTH_DIR))) {
            return false;
        }
        int col = level.column(cx, cy);
        if (blockingColumn(col)
                && inX >= 512 - Enemy.COLUMN_HALF && inX <= 512 + Enemy.COLUMN_HALF
                && inY >= 512 - Enemy.COLUMN_HALF && inY <= 512 + Enemy.COLUMN_HALF) {
            return false;
        }
        if (Math.abs(posX - nx) < Enemy.RADIUS && Math.abs(posY - ny) < Enemy.RADIUS) {
            return false;
        }

        if (level.enemyRef(fromX, fromY) == e.slot) {
            level.setEnemyRef(fromX, fromY, 0);
        }
        e.x = nx;
        e.y = ny;
        level.setEnemyRef(cx, cy, e.slot);
        return true;
    }

    /**
     * Frames an enemy waits between shots.
     *
     * The flamer, bolter and launcher now fire real projectiles whose flight time paces them on
     * its own, but the handgun is hitscan in the original too, so a room of pistol-armed enemies
     * would still empty an energy bar in seconds. This keeps them to a sane rate.
     */
    public static final int ENEMY_RELOAD = 25;

    /** Sub-steps a projectile is advanced in per frame, so it cannot skip over a target. */
    private static final int PROJECTILE_SUBSTEPS = 4;

    private final List<Projectile> projectiles = new ArrayList<Projectile>();

    public List<Projectile> projectiles() {
        return projectiles;
    }

    /**
     * Anim_Objects: advance every object, age it, and resolve what it runs into. An object that
     * hits nothing before its counter runs out simply disappears.
     */
    private void updateProjectiles() {
        for (int i = projectiles.size() - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            p.anim ^= 1;
            if (--p.life <= 0) {
                projectiles.remove(i);
                continue;
            }
            if (p.isEffect()) {
                continue;   // impact marks and explosions just animate in place
            }
            if (!advanceProjectile(p)) {
                projectiles.remove(i);
            }
        }
    }

    /** @return false when the projectile is spent and should be removed */
    private boolean advanceProjectile(Projectile p) {
        for (int s = 0; s < PROJECTILE_SUBSTEPS; s++) {
            int nx = p.x + p.dx / PROJECTILE_SUBSTEPS;
            int ny = p.y + p.dy / PROJECTILE_SUBSTEPS;
            if (nx < 0 || ny < 0 || nx >= 64 * Level.CELL_UNITS || ny >= 64 * Level.CELL_UNITS) {
                return false;
            }
            int cx = p.cellX(), cy = p.cellY();
            int ncx = nx / Level.CELL_UNITS, ncy = ny / Level.CELL_UNITS;
            if (ncx != cx && !level.isPassable(cx, cy, ncx > cx ? Level.EAST_DIR : Level.WEST_DIR)) {
                return impact(p);
            }
            if (ncy != cy && !level.isPassable(ncx, cy, ncy > cy ? Level.NORTH_DIR : Level.SOUTH_DIR)) {
                return impact(p);
            }
            p.x = nx;
            p.y = ny;

            if (!p.fromPlayer
                    && Math.abs(posX - p.x) < Enemy.RADIUS && Math.abs(posY - p.y) < Enemy.RADIUS) {
                hitPlayer(p);
                return impact(p);
            }
            Enemy hit = enemyNear(p.x, p.y, p.ownerSlot);
            if (hit != null) {
                hitEnemy(hit, p.type);
                return impact(p);
            }
        }
        return true;
    }

    /**
     * What an object turns into when it lands. A rocket detonates, which ro_SetExplode does by
     * replacing it with an explosion mark and throwing eight fireballs outwards — the splash
     * damage is those fireballs, not a radius check.
     *
     * @return false, so the caller removes the original object
     */
    private boolean impact(Projectile p) {
        if (p.type == Projectile.ROCKET) {
            explode(p.x, p.y);
        } else {
            projectiles.add(new Projectile(
                    p.type == Projectile.ELECTRIC ? Projectile.DISCHARGE : Projectile.IMPACT,
                    p.x, p.y));
        }
        return false;
    }

    /** ro_DirTab: four cardinal directions then four diagonals, all at projectile speed. */
    private void explode(int x, int y) {
        projectiles.add(new Projectile(Projectile.EXPLOSION, x, y));
        playSoundAt(SoundTable.EXPLODE, x, y);
        for (int i = 0; i < 8; i++) {
            Projectile f = new Projectile(Projectile.FIREBALL, x, y, i * 64, -1, false);
            projectiles.add(f);
        }
    }

    private void hitPlayer(Projectile p) {
        int damage = SHOT_DAMAGE[Math.min(p.type, SHOT_DAMAGE.length - 1)];
        inventory.hurt(damage);
        playSound(SoundTable.PLAYER_HURT);
        log.add(text(72, "hit for " + damage));
    }

    private Enemy enemyNear(int x, int y, int exceptSlot) {
        for (Enemy e : enemies) {
            if (e.isDead() || e.slot == exceptSlot) {
                continue;
            }
            if (Math.abs(e.x - x) < Enemy.RADIUS && Math.abs(e.y - y) < Enemy.RADIUS) {
                return e;
            }
        }
        return null;
    }

    /** Puts an object into flight, as the flamer, bolter and launcher branches do. */
    private void spawnProjectile(int type, int x, int y, int angle, int ownerSlot,
                                 boolean fromPlayer) {
        if (projectiles.size() >= 30) {
            return;   // the engine's table is 30 slots and simply drops the shot when full
        }
        projectiles.add(new Projectile(type, x, y, angle, ownerSlot, fromPlayer));
    }

    /**
     * An enemy shot landing on the player. oc_PlayerHit uses the same damage table as
     * EnemyHitServe does for enemies, keyed on the projectile type the enemy's weapon fires:
     * 5 for a bullet, 25 for a fireball, 20 for electricity, 40 for a rocket.
     *
     * The original spawns a travelling object and only hurts the player when it arrives. Here the
     * shot resolves at once, so the enemy is given a reload delay to stand in for the flight time.
     * Without it a room full of enemies empties a full energy bar in about two seconds.
     */
    private void fire(Enemy e) {
        e.reload = ENEMY_RELOAD;
        Level.EnemyTemplate stats = level.enemyTemplates[e.species];
        int type = enemyShotType(stats.weapon);
        playSoundAt(SoundTable.fireSound(Math.max(1, stats.weapon)), e.x, e.y);

        if (type != SHOT_BULLET) {
            spawnProjectile(type, e.x, e.y, e.angle, e.slot, false);
            return;
        }
        // es_HandGun resolves at once, like the player's
        if (!canShoot(e.x, e.y, posX, posY)) {
            return;
        }
        int damage = SHOT_DAMAGE[type];
        inventory.hurt(damage);
        playSound(SoundTable.PLAYER_HURT);
        log.add(text(72, "hit for " + damage));
    }

    /** Maps an enemy's weapon number onto the projectile type it fires, as es_EnemyShoot does. */
    private static int enemyShotType(int weapon) {
        switch (weapon) {
            case 4: return SHOT_FIREBALL;
            case 5: return SHOT_ELECTRIC;
            case 6: return SHOT_ROCKET;
            default: return SHOT_BULLET;
        }
    }

    /** Applies damage to an enemy, from the player's weapons. */
    public void damageEnemy(Enemy e, int amount) {
        e.energy -= amount;
        if (e.energy <= 0) {
            kill(e);
        } else {
            e.state = Enemy.HIT;
            e.counter = 8;
        }
    }

    private void kill(Enemy e) {
        e.state = Enemy.KILLED;
        if (level.enemyRef(e.cellX(), e.cellY()) == e.slot) {
            level.setEnemyRef(e.cellX(), e.cellY(), 0);
        }
        log.add("enemy down");
    }

    /**
     * Line of sight between two world positions.
     *
     * This is a grid walk rather than a transcription of CanShoot: it steps cell by cell from the
     * shooter to the target and refuses the first face that is shut. The original unrolls the same
     * idea per quadrant; the observable result is the same and this is far easier to follow.
     */
    public boolean canShoot(int fromX, int fromY, int toX, int toY) {
        int cx = fromX / Level.CELL_UNITS, cy = fromY / Level.CELL_UNITS;
        int tx = toX / Level.CELL_UNITS, ty = toY / Level.CELL_UNITS;
        double dx = toX - fromX, dy = toY - fromY;
        double len = Math.max(Math.abs(dx), Math.abs(dy));
        if (len < 1) {
            return true;
        }
        double x = fromX, y = fromY;
        double sx = dx / len * 64, sy = dy / len * 64;
        for (int guard = 0; guard < 4096; guard++) {
            if (cx == tx && cy == ty) {
                return true;
            }
            x += sx;
            y += sy;
            int ncx = (int) (x / Level.CELL_UNITS);
            int ncy = (int) (y / Level.CELL_UNITS);
            if (!Level.inside(ncx, ncy)) {
                return false;
            }
            if (ncx != cx) {
                int dir = (ncx > cx) ? Level.EAST_DIR : Level.WEST_DIR;
                if (!level.isPassable(cx, cy, dir)) {
                    return false;
                }
                cx = ncx;
            }
            if (ncy != cy) {
                int dir = (ncy > cy) ? Level.NORTH_DIR : Level.SOUTH_DIR;
                if (!level.isPassable(cx, cy, dir)) {
                    return false;
                }
                cy = ncy;
            }
        }
        return false;
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

    /** Damage type each weapon slot delivers; slot 0 is the bare hand. */
    private static final int[] WEAPON_SHOT = {
            0, SHOT_BULLET, SHOT_BULLET, SHOT_BULLET, SHOT_FIREBALL, SHOT_ELECTRIC, SHOT_ROCKET,
    };
    /** Pellets per trigger pull; only the shotgun fires more than one. */
    private static final int[] WEAPON_PELLETS = {0, 1, 5, 1, 1, 1, 1};

    /** Step length and step count of a shot, from the hitscan loop in rm_HandGunUsed. */
    private static final int SHOT_STEP = 400;
    private static final int SHOT_STEPS = 20;

    /**
     * The fire action, i.e. Check_RMB with a weapon in hand. With the bare hand it falls through
     * to {@link #use()}, which is what the engine does with sv_SpaceOn.
     *
     * Shots are resolved immediately rather than as travelling objects: rm_HandGunUsed runs its
     * whole collision loop in the frame the trigger is pulled. Only slow objects (flames, rockets
     * in flight) live in sv_ObjectTab across frames, and those are not modelled yet.
     *
     * @return true when a shot was actually fired
     */
    public boolean fire() {
        int w = inventory.current;
        if (w == Inventory.HAND) {
            return use();
        }
        if (!inventory.owned[w]) {
            log.add("you have no " + Inventory.WEAPON_NAMES[w]);
            return false;
        }
        if (inventory.ammo[w] >= 0) {
            playSound(SoundTable.NO_AMMO);
            log.add(text(TextTable.OUT_OF_AMMO, "no ammunition"));
            return false;
        }
        if (inventory.condition[w] <= 0) {
            playSound(SoundTable.NO_AMMO);
            log.add(text(TextTable.WEAPON_DAMAGED,
                    "the " + Inventory.WEAPON_NAMES[w] + " is broken"));
            return false;
        }
        // DamageWeapon wears the weapon down by 0 or 1 per shot.
        inventory.condition[w] = Math.max(0, inventory.condition[w] - rnd.nextInt(2));
        inventory.ammo[w]++;
        playSound(SoundTable.fireSound(w));

        int type = WEAPON_SHOT[w];
        if (type != SHOT_BULLET) {
            // flamer, bolter and launcher put an object into sv_ObjectTab instead of resolving now
            spawnProjectile(type, posX, posY, angle, 0, true);
            return true;
        }

        int pellets = WEAPON_PELLETS[w];
        int hits = 0;
        for (int i = 0; i < pellets; i++) {
            // The shotgun sprays; everything else fires straight down the middle.
            int spread = (pellets == 1) ? 0 : rnd.nextInt(17) - 8;
            if (shoot((angle + spread) & 511, type)) {
                hits++;
            }
        }
        if (hits > 0) {
            log.add(hits == 1 ? "hit" : hits + " hits");
        }
        return true;
    }

    /**
     * Walks a shot out from the player until it meets a wall or an enemy.
     *
     * @return true when it hit a living enemy
     */
    private boolean shoot(int shotAngle, int type) {
        double dx = forwardX(shotAngle) * SHOT_STEP;
        double dy = forwardY(shotAngle) * SHOT_STEP;
        double x = posX, y = posY;
        int cx = cellX(), cy = cellY();

        for (int step = 0; step < SHOT_STEPS; step++) {
            x += dx;
            y += dy;
            if (x < 0 || y < 0 || x >= 64 * Level.CELL_UNITS || y >= 64 * Level.CELL_UNITS) {
                return false;
            }
            int ncx = (int) (x / Level.CELL_UNITS);
            int ncy = (int) (y / Level.CELL_UNITS);
            if (ncx != cx) {
                if (!level.isPassable(cx, cy, ncx > cx ? Level.EAST_DIR : Level.WEST_DIR)) {
                    return false;
                }
                cx = ncx;
            }
            if (ncy != cy) {
                if (!level.isPassable(cx, cy, ncy > cy ? Level.NORTH_DIR : Level.SOUTH_DIR)) {
                    return false;
                }
                cy = ncy;
            }
            Enemy hit = enemyNear((int) x, (int) y, -1);
            if (hit != null) {
                hitEnemy(hit, type);
                return true;
            }
        }
        return false;
    }

    /**
     * EnemyHitServe: a species only takes damage from the types its vulnerability mask allows,
     * and a fireball that finishes it off leaves it burning rather than simply dead.
     */
    private void hitEnemy(Enemy e, int type) {
        if (e.state == Enemy.KILLED || e.state == Enemy.BURNING) {
            return;
        }
        Level.EnemyTemplate stats = level.enemyTemplates[e.species];
        if (!stats.vulnerableTo(type)) {
            log.add("no effect");
            return;
        }
        // the enemy turns to face whoever shot it
        e.angle = (angle + 256) & 511;
        e.energy -= SHOT_DAMAGE[type];
        playSoundAt(SoundTable.ENEMY_ROAR_FIRST + rnd.nextInt(SoundTable.ENEMY_ROAR_COUNT),
                e.x, e.y);
        if (e.energy > 0) {
            e.state = Enemy.HIT;
            e.counter = 8;
        } else if (type == SHOT_FIREBALL) {
            e.state = Enemy.BURNING;
            e.energy = 20;
        } else {
            kill(e);
        }
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
     * The "use" action, i.e. what Check_RMB does with an empty hand: if the player faces a switch,
     * find its record in the event stream and run the commands.
     *
     * @return true when something was actually triggered
     */
    public boolean use() {
        int cx = cellX(), cy = cellY(), dir = facingDir();
        if (!level.isSwitch(cx, cy, dir)) {
            log.add(text(TextTable.SEE_NOTHING, "nothing to use here"));
            return false;
        }
        for (Level.Trigger t : level.triggers()) {
            if (t.cellX == cx && t.cellY == cy && t.dir == dir) {
                toggleSwitch(cx, cy, dir);
                playSound(SoundTable.SWITCH);
                runCommands(t);
                return true;
            }
        }
        log.add(text(TextTable.SWITCH_STUCK, "switch has no wiring"));
        return false;
    }

    /** Switch out is cell value 44, switch in is 46; using one flips it. */
    private void toggleSwitch(int x, int y, int dir) {
        int v = level.wallValue(x, y, dir);
        int mirror = v & 1;
        int base = v & 62;
        level.setWallValue(x, y, dir, (base == 44 ? 46 : 44) | mirror);
    }

    private void runCommands(Level.Trigger t) {
        int n = 0;
        for (Level.Command c : t.commands) {
            if (c.onRelease) {
                continue; // press actions only; release actions need the held-button model
            }
            apply(c);
            n++;
        }
        log.add(text(TextTable.SWITCH_USED,
                "switch fired " + n + " command" + (n == 1 ? "" : "s")));
    }

    private void apply(Level.Command c) {
        int x = c.cellX, y = c.cellY, d = c.dir;
        if (!Level.inside(x, y)) {
            return;
        }
        switch (c.code) {
            case Level.Command.OPEN_DOOR:
                forceDoor(x, y, d, true);
                break;
            case Level.Command.CLOSE_DOOR:
                forceDoor(x, y, d, false);
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

    /**
     * rm_OpenDoor / rm_CloseDoor: a command driven door is set directly rather than animated by
     * the two automatic channels, and it also flips the face on the far side.
     */
    private void forceDoor(int x, int y, int dir, boolean open) {
        int v = level.wallValue(x, y, dir) & 62;
        int type = (v >= 36 && v <= 41) ? 2 : 1;
        int target = open ? (type == 1 ? 32 : 38) : (type == 1 ? 30 : 36);
        setFace(x, y, dir, target);
        int nx = x + Level.DX[dir], ny = y + Level.DY[dir];
        if (Level.inside(nx, ny)) {
            setFace(nx, ny, Level.OPPOSITE[dir], target);
        }
    }
}
