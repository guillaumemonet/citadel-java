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
 *   +14 knockback X, +15 knockback Y: the low bytes of the vector of the last thing that hit it
 * </pre>
 *
 * The counter doubles as the sprite: dr_DrawEnemy draws {@code counter / 4} for every state but
 * walking, so the counter values below are frame numbers times four.
 */
public final class Enemy {

    public static final int WALKING = 0;
    public static final int HIT = 1;
    public static final int KILLED = 2;
    public static final int BURNING = 3;
    public static final int SHOOTING = 4;

    /** How close an enemy's centre may get to a wall or the player, from me_MOVE's {@code cmpi #300}. */
    public static final int RADIUS = 300;
    /**
     * Object_Collision's box around an enemy: {@code cmpi #200} on both axes (the comment beside
     * it still says 256). Shots use this, not {@link #RADIUS}.
     */
    public static final int HIT_BOX = 200;
    /** Half-width of the box a column denies it, from {@code cmpi #512-210}. */
    public static final int COLUMN_HALF = 210;
    /** me_walk and me_ShootIt start the wind-up at 60; me_shootON fires at 68 (frames 15..17). */
    public static final int SHOOT_COUNTER = 60;
    /** EnemyHitServe: {@code move.b #72,13(a4)} for a hit and a kill alike (frame 18). */
    public static final int HIT_COUNTER = 72;
    /** eh_Burning: {@code move.b #80,13(a4)}, then me_burning toggles 80/84 (frames 20, 21). */
    public static final int BURN_COUNTER = 80;
    /** eh_Burning: {@code move #55,2(a4)}, the burn counted down in the energy word. */
    public static final int BURN_ENERGY = 55;

    /** Slot number, which is what a cell's byte 7 refers to. */
    public final int slot;
    public final int species;
    /** +1: 1 for the first species, 2 for the second in every shipped map. */
    public final int flags;
    public int x;
    public int y;
    public int angle;
    public int speed;
    public int energy;
    public int state;
    public int counter;
    /** +14 and +15, signed bytes; me_hited and me_killed move the enemy by twice these. */
    public int knockX;
    public int knockY;
    /**
     * True once the slot has been cleared ({@code move #0,(a3)}) at the end of me_killed or
     * me_burning: the corpse is on the map and the enemy is no more.
     */
    public boolean gone;

    public Enemy(Level.EnemySpawn spawn) {
        this.slot = spawn.slot;
        this.species = spawn.species;
        this.x = spawn.worldX;
        this.y = spawn.worldY;
        byte[] raw = spawn.raw;
        this.flags = Io.u8(raw, 1);
        this.energy = Io.u16(raw, 2);
        this.angle = Io.u16(raw, 8) & 511;
        this.speed = Io.u16(raw, 10);
        this.state = Io.u8(raw, 12);
        this.counter = Io.u8(raw, 13);
        this.knockX = raw[14];
        this.knockY = raw[15];
    }

    public int cellX() {
        return (x / Level.CELL_UNITS) & 63;
    }

    public int cellY() {
        return (y / Level.CELL_UNITS) & 63;
    }

    /** Killed, whether still falling or already a corpse on the map. */
    public boolean isDead() {
        return state == KILLED || gone;
    }

    public boolean isAlive() {
        return !isDead();
    }

    /** Off the board: nothing left to draw but the corpse in the cell. */
    public boolean isGone() {
        return gone;
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
     * Sprite frame, from dr_DrawEnemy.
     *
     * A walking enemy ({@code tst.b 12(a4)} zero) goes through de_WALK: sv_EnemyDirTab by view
     * direction and sv_WalkState, which is global and advanced once per pass of MAIN_LOOP by
     * sv_DoAnims, so every enemy on screen steps in time. Any other state draws the counter
     * itself, {@code move.b 13(a4),d4}: 15..17 shooting, 18..19 hit, 20..21 burning and 22..23
     * falling dead.
     */
    public int frame(int viewAngle, int walkPhase) {
        if (state != WALKING) {
            return (counter & 0xff) / 4;
        }
        int dir = viewDirection(viewAngle);
        return GfxLayout.ENEMY_DIR_TAB[dir * 4 + (walkPhase & 3)] / 4;
    }
}
