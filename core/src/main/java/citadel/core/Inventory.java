package citadel.core;

/**
 * What the player carries, following Take_Items and the sv_ITEMS / sv_GUNS / sv_Cards tables.
 *
 * The engine stores energy and ammunition as negative numbers and clamps them at -999, so the
 * counters can be drawn straight from the value. This class keeps the same convention internally
 * and exposes positive readings, which is what a HUD wants.
 */
public final class Inventory {

    /** Slot 0 is the bare hand; 1..6 are the weapons in sv_GUNS order. */
    public static final int WEAPON_COUNT = 7;
    public static final int HAND = 0;
    public static final String[] WEAPON_NAMES = {
            "hand", "handgun", "shotgun", "machinegun", "flamer", "bolter", "launcher",
    };

    /** Ammunition a pickup grants per weapon, from the middle word of each sv_GUNS entry. */
    private static final int[] AMMO_PER_PICKUP = {0, -10, -5, -15, -5, -5, -1};

    /** The engine's floor for every counter it clamps. */
    public static final int LIMIT = -999;

    /** Cell item values, from the dispatch chain in Take_Items. */
    public static final int FIRST_WEAPON = 2;
    public static final int FIRST_AMMO = 8;
    public static final int FIRST_CARD = 14;
    public static final int MEDIKIT = 17;
    public static final int POWER = 18;
    public static final int VODKA = 19;
    public static final int WARHEAD = 20;

    public final boolean[] owned = new boolean[WEAPON_COUNT];
    /** Condition of each weapon, rolled on pickup; the hand starts at the engine's 671. */
    public final int[] condition = new int[WEAPON_COUNT];
    /** Negative, like the engine. */
    public final int[] ammo = new int[WEAPON_COUNT];
    /** Red, green and blue keycards; negative counts, like the engine. */
    public final int[] cards = new int[3];

    public int current = HAND;
    /** Negative, clamped at LIMIT. Starts at the engine's -225. */
    public int energy = -225;
    /** Frames of boosted walk speed remaining, from the "power" pickup. */
    public int speedBoost;
    /** Frames of drunkenness remaining. */
    public int vodka;
    /** Warhead pieces collected; the engine counts down in steps of 2 from 0 to -12. */
    public int warhead;

    public Inventory() {
        owned[HAND] = true;
        condition[HAND] = 671;
        ammo[HAND] = -1111;
    }

    public int energyReading() {
        return -energy;
    }

    public int ammoReading() {
        return -ammo[current];
    }

    public int cardReading(int colour) {
        return -cards[colour];
    }

    public boolean hasCard(int colour) {
        return colour >= 0 && colour < 3 && cards[colour] < 0;
    }

    public boolean isAlive() {
        return energy < 0;
    }

    /** Walk speed in engine units per frame, 250 normally and 300 while boosted. */
    public int walkSpeed() {
        return speedBoost > 0 ? 300 : GameState.WALK_SPEED;
    }

    public void tick() {
        if (speedBoost > 0) {
            speedBoost--;
        }
        if (vodka > 0) {
            vodka--;
        }
    }

    private static int clamp(int v) {
        return Math.max(LIMIT, v);
    }

    /**
     * Applies one pickup.
     *
     * @param itemValue the value stored in the cell, 1..20
     * @return a short description for the HUD, or null when the value means nothing
     */
    public String take(int itemValue) {
        if (itemValue >= FIRST_WEAPON && itemValue < FIRST_AMMO) {
            int slot = itemValue - 1;
            owned[slot] = true;
            // Take_Items rolls (random & 63) + 90, plus 70 more on the easy setting.
            condition[slot] = 90 + (int) (Math.random() * 64);
            current = slot;
            return "picked up the " + WEAPON_NAMES[slot];
        }
        if (itemValue >= FIRST_AMMO && itemValue < FIRST_CARD) {
            int slot = itemValue - 7;
            ammo[slot] = clamp(ammo[slot] + AMMO_PER_PICKUP[slot]);
            return "ammunition for the " + WEAPON_NAMES[slot];
        }
        if (itemValue >= FIRST_CARD && itemValue < MEDIKIT) {
            int colour = itemValue - FIRST_CARD;
            cards[colour]--;
            return "picked up the " + "RGB".charAt(colour) + " card";
        }
        switch (itemValue) {
            case MEDIKIT:
                energy = clamp(energy - 30);
                return "first aid";
            case POWER:
                speedBoost = 300;
                return "speed boost";
            case VODKA:
                vodka += 190;
                return "vodka";
            case WARHEAD:
                warhead -= 2;
                return "warhead section";
            default:
                return null;
        }
    }

    /** Damage taken; energy moves towards zero and the player dies when it reaches it. */
    public void hurt(int amount) {
        energy = Math.min(0, energy + amount);
    }
}
