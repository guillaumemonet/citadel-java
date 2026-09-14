package citadel.core;

/**
 * A live enemy, decoded from its 16 byte slot in the map's enemy table.
 *
 * The layout is fixed by Move_Enemy and its neighbours:
 *
 * <pre>
 *   +1  flags, bit 0 selects the species, bit 7 asks the renderer to draw it behind a column
 *   +2  energy
 *   +4  world X
 *   +6  world Y
 *   +8  facing, 0..511
 *   +10 walk speed, engine units per frame
 *   +12 state: 0 walking, 1 hit, 2 killed, 3 burning, 4 shooting
 *   +13 state counter
 * </pre>
 */
public final class Enemy {

    public static final int WALKING = 0;
    public static final int HIT = 1;
    public static final int KILLED = 2;
    public static final int BURNING = 3;
    public static final int SHOOTING = 4;

    /** How close an enemy's centre may get to a wall, from the {@code cmpi #300} tests. */
    public static final int RADIUS = 300;
    /** Half-width of the box a column denies it, from {@code cmpi #512-210}. */
    public static final int COLUMN_HALF = 210;
    /** Frames a shoot wind-up lasts: the counter starts at 60 and fires at 68, 4 per frame. */
    public static final int SHOOT_COUNTER = 60;

    /** Slot number, which is what a cell's byte 7 refers to. */
    public final int slot;
    public final int species;
    public int x;
    public int y;
    public int angle;
    public int speed;
    public int energy;
    public int state;
    public int counter;
    /** Walk animation phase, 0..3, advanced as the enemy moves. */
    public int phase;

    public Enemy(Level.EnemySpawn spawn) {
        this.slot = spawn.slot;
        this.species = spawn.species;
        this.x = spawn.worldX;
        this.y = spawn.worldY;
        byte[] raw = spawn.raw;
        this.energy = Io.u16(raw, 2);
        this.angle = Io.u16(raw, 8) & 511;
        this.speed = Io.u16(raw, 10);
        this.state = Io.u8(raw, 12);
        this.counter = Io.u8(raw, 13);
    }

    public int cellX() {
        return (x / Level.CELL_UNITS) & 63;
    }

    public int cellY() {
        return (y / Level.CELL_UNITS) & 63;
    }

    public boolean isDead() {
        return state == KILLED;
    }

    public boolean isAlive() {
        return state != KILLED;
    }

    /**
     * Which of the six drawn view directions this enemy presents to the player, from
     * sv_EnemyDirTab: the difference between where it faces and where the player is, in sixths
     * of a turn.
     */
    public int viewDirection(int playerX, int playerY) {
        int toPlayer = GameState.angleTowards(playerX - x, playerY - y);
        int rel = (angle - toPlayer + 512 + 43) & 511;
        return Math.min(5, rel * 6 / 512);
    }

    /** Sprite frame for the current facing and walk phase, via sv_EnemyDirTab. */
    public int frame(int playerX, int playerY) {
        int dir = viewDirection(playerX, playerY);
        return GfxLayout.ENEMY_DIR_TAB[dir * 4 + (phase & 3)] / 4;
    }
}
