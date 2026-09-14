package citadel.core;

/**
 * An object in flight, from the sv_ObjectTab that Anim_Objects walks.
 *
 * The engine keeps 30 slots of 12 bytes: a type and animation counter, the per-frame movement,
 * the position, and the map offset. Types are the ones Anim_Objects dispatches on — 1 and 2 are
 * the brief impact and discharge marks, 3 a fireball, 4 electricity, 5 a rocket, 6 an explosion.
 *
 * Not everything the player fires becomes one of these. The handgun, shotgun and machinegun
 * resolve their shot in the frame the trigger is pulled (rm_HandGunUsed runs its whole collision
 * loop at once); only the flamer, bolter and launcher put an object into the table.
 */
public final class Projectile {

    public static final int IMPACT = 1;
    public static final int DISCHARGE = 2;
    public static final int FIREBALL = 3;
    public static final int ELECTRIC = 4;
    public static final int ROCKET = 5;
    public static final int EXPLOSION = 6;

    /**
     * Speeds are written in the source as {@code 400/8} and {@code 600/8}, i.e. 50 and 75 per
     * step, and an object advances eight steps a frame. Multiplying through gives 400 and 600
     * units per frame, which lines up on both ends: the handgun's 20-step hitscan reaches the
     * 8000 its comment claims, and every projectile type ends up with the same 12 cell range.
     */
    public static final int SPEED_SLOW = 400;   // bullets and fireballs
    public static final int SPEED_FAST = 600;   // electricity and rockets

    /** Frames each type lives, from the counter limits in Anim_Objects. */
    public static int lifetimeOf(int type) {
        switch (type) {
            case FIREBALL: return 30;   // counter runs to 60 in steps of 2
            case ELECTRIC: return 20;   // to 40
            case ROCKET:   return 20;   // to 40
            case EXPLOSION: return 7;   // counter runs to 7 in steps of 1
            default:       return 3;    // impact and discharge marks, to 3
        }
    }

    public static int speedOf(int type) {
        return (type == ELECTRIC || type == ROCKET) ? SPEED_FAST : SPEED_SLOW;
    }

    public int type;
    public int x;
    public int y;
    public int dx;
    public int dy;
    public int life;
    /** Animation phase; the engine flips the low bit every frame. */
    public int anim;
    /** Enemy slot that fired this, or 0 for the player, so it cannot hit its own shooter. */
    public int ownerSlot;
    public boolean fromPlayer;

    public Projectile(int type, int x, int y, int angle, int ownerSlot, boolean fromPlayer) {
        this.type = type;
        this.x = x;
        this.y = y;
        int speed = speedOf(type);
        this.dx = (int) Math.round(GameState.forwardX(angle) * speed);
        this.dy = (int) Math.round(GameState.forwardY(angle) * speed);
        this.life = lifetimeOf(type);
        this.ownerSlot = ownerSlot;
        this.fromPlayer = fromPlayer;
    }

    /** A stationary effect, such as the explosion mark or an impact. */
    public Projectile(int type, int x, int y) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.life = lifetimeOf(type);
    }

    public boolean isEffect() {
        return type == IMPACT || type == DISCHARGE || type == EXPLOSION;
    }

    public int cellX() {
        return (x / Level.CELL_UNITS) & 63;
    }

    public int cellY() {
        return (y / Level.CELL_UNITS) & 63;
    }
}
