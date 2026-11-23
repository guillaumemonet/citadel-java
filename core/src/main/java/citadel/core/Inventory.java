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
    /**
     * Slots 7, 8 and 9 hold the red, green and blue cards, sv_Cards following sv_GUNS in
     * sv_ITEMS. A card is taken in hand like a weapon and fired at a card reader (rm_CardUsed).
     */
    public static final int FIRST_CARD_SLOT = 7;
    public static final int SLOT_COUNT = 10;
    public static final String[] WEAPON_NAMES = {
            "hand", "handgun", "shotgun", "machinegun", "flamer", "bolter", "launcher",
            "red card", "green card", "blue card",
    };

    /** Ammunition a pickup grants per weapon, from the middle word of each sv_GUNS entry. */
    private static final int[] AMMO_PER_PICKUP = {0, -10, -5, -10, -5, -5, -1};

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
    /**
     * Negative, clamped at LIMIT. A new game starts at -150: the engine's own -225 is overwritten
     * from STRUCTURE, which SERVER loads from the head of Savegame.DAT.
     */
    public int energy = -150;
    /** sv_DIFFICULT, for the +70 a weapon picked up on the easy setting gets. */
    public boolean easy;
    /** sv_WalkSpeed+6: passes of MAIN_LOOP the speed potion has left. */
    public int speedBoost;
    /** sv_WalkSpeed+2: the base walk speed, 250, or 300 while a potion runs. */
    public int baseWalk = GameState.WALK_SPEED;
    /** sv_WalkSpeed+4: the base speed ti_power saved, put back when the potion runs out. */
    public int savedWalk = GameState.WALK_SPEED;
    /** sv_Vodka: passes of MAIN_LOOP of drunkenness left; sv_DoMMove counts it down. */
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

    /** The counter under the weapon: ammunition, or for a card in hand, how many of them. */
    public int ammoReading() {
        return isCard(current) ? -cards[current - FIRST_CARD_SLOT] : -ammo[current];
    }

    public static boolean isCard(int slot) {
        return slot >= FIRST_CARD_SLOT && slot < SLOT_COUNT;
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

    /**
     * sv_WalkSpeed+2, the base walk speed in units per pass: 250, and 300 while a potion runs.
     * Two potions in a row leave it at 300 for good, because the second saves the first's 300 as
     * the speed to go back to.
     */
    public int walkSpeed() {
        return baseWalk;
    }

    /** True while a speed potion is running, which takes the walk speed out of the heart's hands. */
    public boolean isSpeedBoosted() {
        return speedBoost > 0;
    }

    /**
     * sv_BlitUoK, once per pass of MAIN_LOOP: {@code tst sv_WalkSpeed+6 / subi #1 / bne}, and
     * when it reaches 0 {@code move sv_WalkSpeed+4,sv_WalkSpeed+2}.
     */
    public void tickSpeed() {
        if (speedBoost > 0 && --speedBoost == 0) {
            baseWalk = savedWalk;
        }
    }

    /**
     * The engine's own variables, which a new level starts from afresh: the potion and the vodka
     * are not part of STRUCTURE, so neither survives the end of a level.
     */
    public void resetLevelState() {
        speedBoost = 0;
        baseWalk = GameState.WALK_SPEED;
        savedWalk = GameState.WALK_SPEED;
        vodka = 0;
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
            // Take_Items only writes the condition: the weapon in hand does not change. It is
            // (random & 63) + 90, plus 70 on the easy setting.
            condition[slot] = 90 + (int) (Math.random() * 64) + (easy ? 70 : 0);
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
                // ti_power: move sv_WalkSpeed+2,sv_WalkSpeed+4 / move #300,+2 / move #300,+6
                savedWalk = baseWalk;
                baseWalk = 300;
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

    /**
     * ci_NewWeapon: takes a slot in hand if its first word is not zero. For a weapon that word is
     * its condition, so a weapon not picked up, or worn out, cannot be selected; the hand keeps
     * its 671. The cards' first words are constants ($9de0, $0b4a, $73e1), so a card can always
     * be taken in hand, even with none left - rm_CardUsed then says so.
     */
    public boolean select(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            return false;
        }
        if (!isCard(slot) && (!owned[slot] || condition[slot] == 0)) {
            return false;
        }
        current = slot;
        return true;
    }

    /** Damage taken; energy moves towards zero and the player dies when it reaches it. */
    public void hurt(int amount) {
        energy = Math.min(0, energy + amount);
    }
}
