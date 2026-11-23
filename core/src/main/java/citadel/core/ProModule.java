package citadel.core;

import java.io.IOException;

/**
 * The game's music: ProTracker songs repacked into the ".pro" layout its replayer reads
 * ({@code mt_init} / {@code mt_DoVoice} in SERVER_032_HD_ENG.ss and Death14.ss).
 *
 * <h2>Layout</h2>
 * <pre>
 *   0    31 sample headers of 8 bytes: length in words, finetune, volume, repeat, replen
 *        -- a ProTracker header without the 22 byte name
 *   248  song length                      (cmp.b 248(a0),d1 in mt_NextPosition)
 *   250  four position tables, one per voice, 128 bytes each: a TRACK number per position
 *        (mt_DoVoice: lea 122(a0),a2, then lea 128(a2),a2 before each voice)
 *   762  the tracks, 128 bytes each: 64 rows of one word, an index into the note table
 *        (lsl #7,d1 picks the track, PatternPos steps 4 a row and is halved)
 *   ...  a long, the size of the note table, then the table itself: the distinct 4 byte
 *        ProTracker notes the song uses (mt_LWTPtr = 766 + tracks*128)
 *   ...  the samples, back to back (mt_SampleStarts)
 * </pre>
 * The number of tracks is not stored: {@code mt_init} scans the 512 bytes of position tables for
 * the largest entry, as a ProTracker player scans its pattern table.
 *
 * So where a ProTracker module stores a pattern of four channels per position, this one stores a
 * track per voice per position, and shares identical tracks and identical notes. The notes
 * themselves are ProTracker's: 12 bits of period, the instrument split across the high nibbles
 * of the first and third bytes, and 12 bits of effect.
 */
public final class ProModule {

    public static final int CHANNELS = 4;
    public static final int ROWS = 64;
    public static final int SAMPLES = 31;

    private static final int HEADER = 8;
    private static final int SONG_LENGTH = 248;
    private static final int POSITIONS = 250;
    private static final int POSITION_TABLE = 128;
    private static final int TRACKS = 762;
    private static final int TRACK_BYTES = ROWS * 2;

    /** One instrument, as the header gives it. */
    public record Sample(int lengthWords, int fineTune, int volume, int repeatWords,
                         int replenWords) {

        /** A sample loops when its repeat length is more than one word, as Paula sees it. */
        public boolean loops() {
            return replenWords > 1;
        }
    }

    private final byte[] data;
    private final Sample[] samples = new Sample[SAMPLES];
    private final int[] sampleStart = new int[SAMPLES];
    private final int songLength;
    private final int trackCount;
    private final int noteTable;

    private ProModule(byte[] data) throws IOException {
        this.data = data;
        if (data.length < TRACKS + 4) {
            throw new IOException("only " + data.length + " bytes, not a .pro module");
        }
        songLength = data[SONG_LENGTH] & 0xff;
        if (songLength == 0 || songLength > POSITION_TABLE) {
            throw new IOException("song length " + songLength);
        }
        int max = 0;
        for (int i = POSITIONS; i < TRACKS; i++) {
            max = Math.max(max, data[i] & 0xff);
        }
        trackCount = max + 1;
        int sizeAt = TRACKS + trackCount * TRACK_BYTES;
        if (sizeAt + 4 > data.length) {
            throw new IOException(trackCount + " tracks do not fit in " + data.length + " bytes");
        }
        noteTable = sizeAt + 4;
        int at = noteTable + ((Io.u16(data, sizeAt) << 16) | Io.u16(data, sizeAt + 2));
        for (int i = 0; i < SAMPLES; i++) {
            int h = i * HEADER;
            samples[i] = new Sample(Io.u16(data, h), data[h + 2] & 0x0f, data[h + 3] & 0xff,
                    Io.u16(data, h + 4), Io.u16(data, h + 6));
            sampleStart[i] = at;
            at += samples[i].lengthWords() * 2;
        }
        if (at > data.length) {
            throw new IOException("samples run " + (at - data.length) + " bytes past the end");
        }
    }

    public static ProModule read(byte[] data) throws IOException {
        return new ProModule(data);
    }

    /** Positions in the song, {@code 248(a0)}. */
    public int length() {
        return songLength;
    }

    public int trackCount() {
        return trackCount;
    }

    public Sample sample(int index) {
        return samples[index];
    }

    /** A sample's signed 8 bit data, its whole stored length. */
    public byte[] sampleData(int index) {
        int len = samples[index].lengthWords() * 2;
        byte[] out = new byte[len];
        System.arraycopy(data, sampleStart[index], out, 0, len);
        return out;
    }

    /** The track a voice plays at a position. */
    public int track(int position, int channel) {
        return data[POSITIONS + channel * POSITION_TABLE + position] & 0xff;
    }

    /**
     * One note: {@code {period, instrument 1..31 or 0, effect}}, the effect being the command in
     * bits 8..11 and its argument in bits 0..7.
     */
    public int[] cell(int position, int row, int channel) {
        int index = Io.u16(data, TRACKS + track(position, channel) * TRACK_BYTES + row * 2);
        int n = noteTable + index * 4;
        int b0 = data[n] & 0xff;
        int b1 = data[n + 1] & 0xff;
        int b2 = data[n + 2] & 0xff;
        int b3 = data[n + 3] & 0xff;
        return new int[]{((b0 & 0x0f) << 8) | b1, (b0 & 0xf0) | (b2 >> 4), ((b2 & 0x0f) << 8) | b3};
    }
}
