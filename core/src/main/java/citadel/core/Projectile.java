package citadel.core;

/**
 * An object in the sv_ObjectTab that Anim_Objects walks.
 *
 * The engine keeps 30 slots of 12 bytes: a type byte and a counter byte, the movement per
 * sub-step (2(a1), 4(a1)), the position (6(a1), 8(a1)) and the map offset. Types are the ones
 * Anim_Objects dispatches on — 1 and 2 are the brief impact and discharge marks, 3 a fireball,
 * 4 electricity, 5 a rocket, 6 an explosion.
 *
 * Bullets are objects too, but only for the length of the trigger pull: rm_HandGunUsed and
 * es_HandGun run their whole Object_Collision loop at once and either clear the slot or turn it
 * into an impact mark. Only the flamer, bolter and launcher leave something in flight.
 */
public final class Projectile {

    /** Type 1, "odprysk": the bullet while it is resolved, then the impact mark it leaves. */
    public static final int IMPACT = 1;
    public static final int BULLET = 1;
    /** Type 2, "wyladowanie": the discharge an electric bolt leaves. */
    public static final int DISCHARGE = 2;
    public static final int FIREBALL = 3;
    public static final int ELECTRIC = 4;
    public static final int ROCKET = 5;
    public static final int EXPLOSION = 6;

    /** Object_Collision moves an object in eight sub-steps per call ({@code moveq #7,d6}). */
    public static final int SUBSTEPS = 8;

    /**
     * Speeds are written in the source as {@code 400/8} and {@code 600/8}: the vector stored in
     * the slot is per sub-step, and one call of Object_Collision takes eight of them, so 400 and
     * 600 units a pass. (Object_Collision ORs 1 into both components to keep its divisions safe,
     * so a 50 really moves 51.)
     */
    public static final int SPEED_SLOW = 400;   // bullets and fireballs
    public static final int SPEED_FAST = 600;   // electricity and rockets

    /**
     * Object_Collision calls an object gets before Anim_Objects drops it: one when it is fired,
     * then one a pass until the counter reaches its limit — 60 in steps of 2 for a fireball,
     * 40 for electricity and rockets. Every type comes out at 12 000 units.
     */
    public static int lifetimeOf(int type) {
        switch (type) {
            case FIREBALL: return 30;
            case ELECTRIC: return 20;
            case ROCKET:   return 20;
            default:       return 0;
        }
    }

    public static int speedOf(int type) {
        return (type == ELECTRIC || type == ROCKET) ? SPEED_FAST : SPEED_SLOW;
    }

    /** 0 once the slot has been freed ({@code move #0,(a1)}). */
    public int type;
    public int x;
    public int y;
    /** Movement per sub-step, 2(a1) and 4(a1). */
    public int dx;
    public int dy;
    /**
     * The counter byte, less the animation bit and the before/behind flag: ao_OBJECT1 adds 1 and
     * frees the slot at {@code &3 == 3}, ao_EXPLODE adds 1 and frees it at {@code &7 == 7}, the
     * flying types add 2 until their limit.
     */
    public int counter;
    /** Animation phase; the engine flips bit 0 of the counter every pass ({@code eori #1}). */
    public int anim;
    /**
     * An impact mark's height, 0..3: .ColFound keeps {@code GetRandom & 3<<2} ("wys") in the
     * counter byte, so marks do not all sit at the same level on a wall.
     */
    public int height;

    /** An object put into flight with its per-sub-step vector. */
    public Projectile(int type, int x, int y, int dx, int dy) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.dx = dx;
        this.dy = dy;
    }

    /** A stationary mark, such as {@code $0603}: type 6 with its counter starting at 3. */
    public static Projectile mark(int type, int x, int y, int counter) {
        Projectile p = new Projectile(type, x, y, 0, 0);
        p.counter = counter;
        return p;
    }

    /**
     * Calls of Anim_Objects until the mark's slot is freed, the last one included: an impact or
     * discharge 3 (counter 0 to 3), an explosion {@code 7 - counter}, so 4 for $0603, 6 for
     * $0601, 7 for $0600 and 3 for $0604. A mark is therefore still on screen after 2, 3, 5, 6
     * and 2 passes respectively.
     */
    public int passesLeft() {
        if (type == IMPACT || type == DISCHARGE) {
            return 3 - (counter & 3);
        }
        if (type == EXPLOSION) {
            return 7 - (counter & 7);
        }
        return 0;
    }

    public boolean isEffect() {
        return type == IMPACT || type == DISCHARGE || type == EXPLOSION;
    }

    public boolean inFlight() {
        return type == FIREBALL || type == ELECTRIC || type == ROCKET;
    }

    public int cellX() {
        return (x / Level.CELL_UNITS) & 63;
    }

    public int cellY() {
        return (y / Level.CELL_UNITS) & 63;
    }
}
