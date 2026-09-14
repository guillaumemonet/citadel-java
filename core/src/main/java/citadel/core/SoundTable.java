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
