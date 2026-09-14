package citadel.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * The 25 sound effects, transcribed from the sound_list table in
 * Code/MAIN1/CytadelaHD_134.ss.
 *
 * The table holds (address, length in words, Paula period) per entry, all relative to sv_SAMPLES,
 * which is the start of Store/ST.DAT. The last entry ends at byte 102108, exactly the size the
 * source comments give for the sample block, and every length matches a file in Data/GAME/Sounds,
 * which is what fixes the names below.
 *
 * Samples are signed 8 bit mono. Playback rate is the PAL Paula clock divided by the period.
 */
public final class SoundTable {

    /** PAL Paula clock in Hz. */
    public static final double PAL_CLOCK = 3_546_895.0;

    public static final class Sfx {
        public final String name;
        public final int offset;
        public final int length;
        public final int period;

        Sfx(String name, int offset, int length, int period) {
            this.name = name;
            this.offset = offset;
            this.length = length;
            this.period = period;
        }

        public int sampleRate() {
            return (int) Math.round(PAL_CLOCK / period);
        }
    }

    public static final Sfx[] ALL = {
            new Sfx("arrgh2",   0,      2680, 0x200),
            new Sfx("switch",   2680,    750, 300),
            new Sfx("handgun",  3430,   5270, 0x240),
            new Sfx("machine",  8700,    902, 500),
            new Sfx("flamer",   9602,   6416, 0x110),
            new Sfx("bolter",   16018,  1794, 500),
            new Sfx("launcher", 17812,  4964, 0x160),
            new Sfx("noammo",   22776,   488, 400),
            new Sfx("door1",    23264,  4842, 0x340),
            new Sfx("door2",    28106,  7028, 0x340),
            new Sfx("explode",  35134,  4164, 0x240),
            new Sfx("ammotake", 39298,  2806, 0x240),
            new Sfx("arrgh",    42104,  3336, 0x200),
            new Sfx("fire",     45440,  8870, 0x240),
            new Sfx("apteczka", 54310,  3570, 0x340),
            new Sfx("door3",    57880,  7028, 0x1e0),
            new Sfx("en1",      64908,  3746, 0x340),
            new Sfx("en2",      68654,  4798, 0x340),
            new Sfx("en3",      73452,  3268, 0x340),
            new Sfx("en4",      76720,  5306, 0x340),
            new Sfx("karta",    82026,  5218, 0x230),
            new Sfx("pik",      87244,   610, 0x210),
            new Sfx("ooo",      87954,  1406, 0x380),
            new Sfx("machine2", 89360,  8176, 0x180),
            new Sfx("shotgun",  97536,  4572, 0x290),
    };

    /**
     * Indices by what the engine actually plays them for, taken from every {@code SOUND} macro
     * call site. The macro argument is 1-based: play_sound does {@code subq #1,d0} before scaling
     * by the 8-byte entry size, so entry n in the source is index n-1 here.
     *
     * These are the authority, not the file names. Twelve of them agree with the names in
     * Data/GAME/Sounds, but <b>handgun and launcher are swapped</b>: rm_HandGunUsed plays SOUND 7,
     * whose sample is the one shipped as launcher.snd, and rm_LauncherUsed plays SOUND 3, shipped
     * as handgun.snd. The names in {@link #ALL} stay as they are so they remain traceable to the
     * files on disk; use the constants below to pick a sound.
     */
    public static final int SWITCH = 1;          // SOUND 2,  rm_ServeSwitch
    public static final int NO_AMMO = 7;         // SOUND 8,  every weapon's empty and broken case
    public static final int FIRE_HANDGUN = 6;    // SOUND 7,  rm_HandGunUsed
    public static final int FIRE_SHOTGUN = 24;   // SOUND 25, rm_ShotGunUsed
    public static final int FIRE_MACHINEGUN = 23; // SOUND 24, rm_MachineGun
    public static final int FIRE_FLAMER = 4;     // SOUND 5,  rm_FlamerUsed
    public static final int FIRE_BOLTER = 5;     // SOUND 6,  rm_BolterUsed
    public static final int FIRE_LAUNCHER = 2;   // SOUND 3,  rm_LauncherUsed
    public static final int EXPLODE = 10;        // SOUND2 11, ro_SetExplode
    public static final int DOOR1 = 8;           // SOUND2 9,  OpenCloseDoors
    public static final int DOOR2 = 9;           // SOUND2 10, OpenCloseDoors
    public static final int DOOR3 = 15;          // SOUND2 16, OpenCloseDoors
    public static final int TAKE_AMMO = 11;      // SOUND 12, ti_ammo
    public static final int TAKE_ITEM = 22;      // SOUND 23, weapon and card pickups
    public static final int TAKE_AID = 14;       // SOUND 15, medikit, power and vodka
    public static final int PLAYER_HURT = 12;    // SOUND 13, oc_PlayerHit
    public static final int BUMP = 0;            // SOUND 1,  walking into a wall
    public static final int CARD = 20;           // SOUND 21, using a card
    public static final int BEEP = 21;           // SOUND 22, do_pikaj low-energy warning
    /** Enemy roars, picked at random from this run of four (SOUND4 with d2 in 18..21). */
    public static final int ENEMY_ROAR_FIRST = 17;
    public static final int ENEMY_ROAR_COUNT = 4;

    /** Fire sound for a weapon slot, 0 being the bare hand. */
    public static int fireSound(int weaponSlot) {
        switch (weaponSlot) {
            case 1: return FIRE_HANDGUN;
            case 2: return FIRE_SHOTGUN;
            case 3: return FIRE_MACHINEGUN;
            case 4: return FIRE_FLAMER;
            case 5: return FIRE_BOLTER;
            case 6: return FIRE_LAUNCHER;
            default: return -1;
        }
    }

    private SoundTable() {
    }

    /** Wraps signed 8 bit mono PCM into a WAV container (unsigned 8 bit, as WAV requires). */
    public static byte[] toWav(byte[] pcmSigned, int off, int len, int rate) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 44);
        int dataLen = Math.max(0, Math.min(len, pcmSigned.length - off));
        writeAscii(out, "RIFF");
        writeLe32(out, 36 + dataLen);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);          // PCM
        writeLe16(out, 1);          // mono
        writeLe32(out, rate);
        writeLe32(out, rate);       // byte rate = rate * 1 channel * 1 byte
        writeLe16(out, 1);          // block align
        writeLe16(out, 8);          // bits per sample
        writeAscii(out, "data");
        writeLe32(out, dataLen);
        for (int i = 0; i < dataLen; i++) {
            out.write((pcmSigned[off + i] & 0xff) ^ 0x80);
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) {
            o.write(s.charAt(i));
        }
    }

    private static void writeLe16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static void writeLe32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }
}
