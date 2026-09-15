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
    /** Frames left before this one can shoot again; see GameState.ENEMY_RELOAD. */
    public int reload;

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
     * Widths of the six view sectors, from {@code sv_EDirSub: dc.w 128,64+32,64,64,64,64+32}.
     * They are deliberately uneven: front and back get a quarter and three sixteenths of the
     * turn, the four side views a sixteenth each.
     */
    private static final int[] VIEW_SECTORS = {128, 96, 64, 64, 64, 96};

    /**
     * Which of the six drawn view directions this enemy presents, from de_WALK: the difference
     * between where it faces and where the *camera* looks, biased by 64 so the front view is
     * centred, then walked down sv_EDirSub.
     *
     * The camera angle rather than the bearing to the player is what the original uses, and it
     * matters: the sprite is screen aligned, so an enemy near the edge of the view shows the same
     * side as one dead ahead.
     */
    public int viewDirection(int viewAngle) {
        int rel = (angle - viewAngle + 64 + 512) & 0x1fe;
        for (int d = 0; d < VIEW_SECTORS.length; d++) {
            rel -= VIEW_SECTORS[d];
            if (rel < 0) {
                return d;
            }
        }
        return VIEW_SECTORS.length - 1;
    }

    /**
     * Sprite frame for the current facing and walk phase, via sv_EnemyDirTab.
     *
     * The walk phase is sv_WalkState, which is global and advanced once per pass of MAIN_LOOP by
     * sv_DoAnims, so every enemy on screen steps in time.
     */
    public int frame(int viewAngle, int walkPhase) {
        int dir = viewDirection(viewAngle);
        return GfxLayout.ENEMY_DIR_TAB[dir * 4 + (walkPhase & 3)] / 4;
    }
}
