package citadel.core;

/**
 * Renders a {@link ProModule} to 16 bit mono samples.
 *
 * <p>Taken from the author's own port of Breathless ({@code fov.breathless.gld.sound
 * .ProTrackerPlayer} in breathless-java), translated and adapted to what Citadel's replayer does:
 *
 * <ul>
 * <li>{@code mt_music} runs from the level 3 interrupt, so a tick is one VBLANK, 1/50 s, and
 *     {@code Fxx} only ever sets the speed ({@code mt_SetSpeed}: any non zero argument, no
 *     tempo). The module format has no CIA tempo to honour.</li>
 * <li>Notes come per voice per position ({@link ProModule#cell}) rather than per pattern.</li>
 * <li>A looping sample plays its first {@code repeat + replen} words, then loops over the
 *     {@code replen} words from {@code repeat}, which is what {@code mt_DoVoice} hands Paula.</li>
 * <li>Finetune bends the period by the eighth of a semitone ProTracker's tables step by,
 *     rather than looking the note up in those tables.</li>
 * </ul>
 *
 * <p>Arpeggio, the pitch slides, tone portamento, vibrato, sample offset, position jump, pattern
 * break, volume, speed and the useful extended commands are played; the rest are ignored rather
 * than played wrong. Rendering is offline: the whole song once, which the caller then loops.
 */
public final class ProTrackerPlayer {

    /** The Amiga's PAL clock, which a period divides into a sample rate. */
    private static final double PAL_CLOCK = 3_546_895.0;

    /** One tick per VBLANK. */
    public static final int TICKS_PER_SECOND = 50;
    private static final int DEFAULT_SPEED = 6;
    private static final int MAX_VOLUME = 64;
    private static final int MIN_PERIOD = 113;
    private static final int MAX_PERIOD = 856;

    /** mt_VibratoTable: a quarter of a sine. */
    private static final int[] SINE = {
        0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224, 235, 244, 250, 253,
        255, 253, 250, 244, 235, 224, 212, 197, 180, 161, 141, 120, 97, 74, 49, 24,
    };

    private static final class Channel {
        int sample;             // 1..31, 0 for none
        int period;
        int volume;
        double position;
        double step;
        boolean playing;
        boolean looping;        // past the first play, now inside the loop

        int portamentoTarget;
        int portamentoSpeed;
        int vibratoSpeed;
        int vibratoDepth;
        int vibratoPosition;
        int lastOffset;
        int retrigCount;
        int noteCutTick;
    }

    private final ProModule module;
    private final int sampleRate;
    private final byte[][] pcm = new byte[ProModule.SAMPLES][];

    private ProTrackerPlayer(ProModule module, int sampleRate) {
        this.module = module;
        this.sampleRate = sampleRate;
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = module.sampleData(i);
        }
    }

    /**
     * Renders the song once through, stopping where it would come round to a position already
     * played, or at {@code maxSeconds}.
     */
    public static short[] render(ProModule module, int sampleRate, int maxSeconds) {
        return new ProTrackerPlayer(module, sampleRate).run(maxSeconds);
    }

    private short[] run(int maxSeconds) {
        Channel[] channels = new Channel[ProModule.CHANNELS];
        for (int i = 0; i < channels.length; i++) {
            channels[i] = new Channel();
        }
        int speed = DEFAULT_SPEED;
        int limit = sampleRate * maxSeconds;
        short[] out = new short[limit];
        int written = 0;
        int perTick = sampleRate / TICKS_PER_SECOND;

        int position = 0;
        int row = 0;
        boolean[] visited = new boolean[ProModule.ROWS * 128];

        while (position < module.length() && written < limit) {
            // a row already played means the song has come round: the caller loops from there
            int key = position * ProModule.ROWS + row;
            if (visited[key]) {
                break;
            }
            visited[key] = true;
            int nextPosition = position;
            int nextRow = row + 1;
            boolean jumped = false;
            int patternDelay = 0;

            for (int repeat = 0; repeat <= patternDelay && written < limit; repeat++) {
                for (int tick = 0; tick < speed && written < limit; tick++) {
                    if (tick == 0 && repeat == 0) {
                        int[] control = startRow(channels, position, row);
                        if (control[0] > 0) {
                            speed = control[0];
                        }
                        if (control[1] >= 0) {
                            nextPosition = control[1];
                            nextRow = 0;
                            jumped = true;
                        }
                        if (control[2] >= 0) {
                            if (!jumped) {
                                nextPosition = position + 1;
                            }
                            nextRow = control[2];
                            jumped = true;
                        }
                        patternDelay = control[3];
                    } else {
                        applyTickEffects(channels, position, row, tick);
                    }
                    written += mix(out, written, limit, channels, perTick);
                }
            }

            if (!jumped && nextRow >= ProModule.ROWS) {
                nextRow = 0;
                nextPosition = position + 1;
            }
            if (nextPosition >= module.length()) {
                nextPosition = 0;               // mt_NextPosition wraps at the song length
            }
            if (nextRow >= ProModule.ROWS) {
                nextRow = 0;
            }
            position = nextPosition;
            row = nextRow;
        }

        short[] trimmed = new short[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    /**
     * The first tick of a row: notes start and effects are armed.
     *
     * @return {speed, position jump, pattern break row, pattern delay}, -1 (0 for the speed
     *         and the delay) meaning nothing to change
     */
    private int[] startRow(Channel[] channels, int position, int row) {
        int speed = 0;
        int positionJump = -1;
        int patternBreak = -1;
        int patternDelay = 0;

        for (int c = 0; c < channels.length; c++) {
            Channel channel = channels[c];
            int[] cell = module.cell(position, row, c);
            int period = cell[0];
            int sample = cell[1];
            int command = (cell[2] >> 8) & 0xF;
            int data = cell[2] & 0xFF;

            if (sample > 0 && sample <= ProModule.SAMPLES) {
                channel.sample = sample;
                channel.volume = Math.min(MAX_VOLUME, module.sample(sample - 1).volume());
            }

            boolean tonePortamento = command == 0x3 || command == 0x5;
            if (period > 0 && channel.sample > 0) {
                period = fineTuned(period, module.sample(channel.sample - 1).fineTune());
                if (tonePortamento) {
                    channel.portamentoTarget = period;
                } else {
                    channel.period = period;
                    channel.position = 0;
                    if (command == 0x9) {
                        if (data != 0) {
                            channel.lastOffset = data * 256;
                        }
                        channel.position = channel.lastOffset;
                    }
                    channel.playing = true;
                    channel.looping = false;
                    channel.vibratoPosition = 0;
                }
            }

            channel.retrigCount = 0;
            channel.noteCutTick = -1;

            switch (command) {
                case 0x3 -> {
                    if (data != 0) {
                        channel.portamentoSpeed = data;
                    }
                }
                case 0x4 -> {
                    if ((data >> 4) != 0) {
                        channel.vibratoSpeed = data >> 4;
                    }
                    if ((data & 0xF) != 0) {
                        channel.vibratoDepth = data & 0xF;
                    }
                }
                case 0x9 -> {
                    if (data != 0) {
                        channel.lastOffset = data * 256;
                    }
                }
                case 0xB -> positionJump = data;
                case 0xC -> channel.volume = Math.min(MAX_VOLUME, data);
                case 0xD -> patternBreak = (data >> 4) * 10 + (data & 0xF);
                case 0xE -> {
                    int sub = data >> 4;
                    int value = data & 0xF;
                    switch (sub) {
                        case 0x1 -> channel.period = clampPeriod(channel.period - value);
                        case 0x2 -> channel.period = clampPeriod(channel.period + value);
                        case 0x9 -> channel.retrigCount = value;
                        case 0xA -> channel.volume = Math.min(MAX_VOLUME, channel.volume + value);
                        case 0xB -> channel.volume = Math.max(0, channel.volume - value);
                        case 0xC -> channel.noteCutTick = value;
                        case 0xE -> patternDelay = Math.max(patternDelay, value);
                        default -> {
                            // the other extended commands do not matter here
                        }
                    }
                }
                case 0xF -> {
                    // mt_SetSpeed: a VBLANK replayer, so any non zero value is a speed
                    if (data != 0) {
                        speed = data;
                    }
                }
                default -> {
                    // the per tick effects are handled below
                }
            }
            refreshStep(channel, channel.period);
        }
        return new int[]{speed, positionJump, patternBreak, patternDelay};
    }

    private void applyTickEffects(Channel[] channels, int position, int row, int tick) {
        for (int c = 0; c < channels.length; c++) {
            Channel channel = channels[c];
            int effect = module.cell(position, row, c)[2];
            int command = (effect >> 8) & 0xF;
            int data = effect & 0xFF;
            int period = channel.period;

            switch (command) {
                case 0x0 -> {
                    if (data != 0) {
                        int step = switch (tick % 3) {
                            case 1 -> data >> 4;
                            case 2 -> data & 0xF;
                            default -> 0;
                        };
                        period = transpose(channel.period, step);
                    }
                }
                case 0x1 -> {
                    channel.period = clampPeriod(channel.period - data);
                    period = channel.period;
                }
                case 0x2 -> {
                    channel.period = clampPeriod(channel.period + data);
                    period = channel.period;
                }
                case 0x3 -> {
                    slideToNote(channel);
                    period = channel.period;
                }
                case 0x4 -> period = vibrato(channel);
                case 0x5 -> {
                    slideToNote(channel);
                    period = channel.period;
                    volumeSlide(channel, data);
                }
                case 0x6 -> {
                    period = vibrato(channel);
                    volumeSlide(channel, data);
                }
                case 0xA -> volumeSlide(channel, data);
                case 0xE -> {
                    int sub = data >> 4;
                    int value = data & 0xF;
                    if (sub == 0x9 && value > 0 && tick % value == 0) {
                        channel.position = 0;
                        channel.looping = false;
                    } else if (sub == 0xC && tick == channel.noteCutTick) {
                        channel.volume = 0;
                    }
                }
                default -> {
                    // nothing to do on this tick
                }
            }
            refreshStep(channel, period);
        }
    }

    private static void slideToNote(Channel channel) {
        if (channel.portamentoTarget == 0) {
            return;
        }
        if (channel.period < channel.portamentoTarget) {
            channel.period = Math.min(channel.portamentoTarget,
                    channel.period + channel.portamentoSpeed);
        } else if (channel.period > channel.portamentoTarget) {
            channel.period = Math.max(channel.portamentoTarget,
                    channel.period - channel.portamentoSpeed);
        }
    }

    /** Vibrato bends the period for this tick without changing it for good. */
    private static int vibrato(Channel channel) {
        int index = (channel.vibratoPosition >> 2) & 0x1F;
        int amplitude = SINE[index] * channel.vibratoDepth / 128;
        int period = (channel.vibratoPosition < 128)
                ? channel.period + amplitude : channel.period - amplitude;
        channel.vibratoPosition = (channel.vibratoPosition + channel.vibratoSpeed * 4) & 0xFF;
        return clampPeriod(period);
    }

    private static void volumeSlide(Channel channel, int data) {
        int up = data >> 4;
        int down = data & 0xF;
        if (up > 0) {
            channel.volume = Math.min(MAX_VOLUME, channel.volume + up);
        } else if (down > 0) {
            channel.volume = Math.max(0, channel.volume - down);
        }
    }

    private static int transpose(int period, int semitones) {
        if (semitones == 0 || period <= 0) {
            return period;
        }
        return clampPeriod((int) Math.round(period / Math.pow(2.0, semitones / 12.0)));
    }

    /** Finetune is a signed nibble, -8..7, each step an eighth of a semitone. */
    private static int fineTuned(int period, int fineTune) {
        int ft = fineTune >= 8 ? fineTune - 16 : fineTune;
        if (ft == 0) {
            return period;
        }
        return (int) Math.round(period / Math.pow(2.0, ft / 96.0));
    }

    private static int clampPeriod(int period) {
        return Math.max(MIN_PERIOD, Math.min(MAX_PERIOD, period));
    }

    private void refreshStep(Channel channel, int period) {
        channel.step = (period <= 0) ? 0 : (PAL_CLOCK / period) / sampleRate;
    }

    /** Mixes one tick of sound and returns how many samples it wrote. */
    private int mix(short[] out, int at, int limit, Channel[] channels, int count) {
        int written = 0;
        for (int i = 0; i < count && at + written < limit; i++) {
            int total = 0;
            for (Channel channel : channels) {
                if (!channel.playing || channel.sample <= 0 || channel.step <= 0) {
                    continue;
                }
                byte[] data = pcm[channel.sample - 1];
                ProModule.Sample info = module.sample(channel.sample - 1);
                int loopStart = info.repeatWords() * 2;
                int loopEnd = Math.min(data.length, loopStart + info.replenWords() * 2);
                int end = info.loops() ? loopEnd : data.length;
                if ((int) channel.position >= end) {
                    if (info.loops() && loopEnd > loopStart) {
                        channel.looping = true;
                        channel.position = loopStart
                                + (channel.position - end) % (loopEnd - loopStart);
                    } else {
                        channel.playing = false;
                        continue;
                    }
                }
                int index = (int) channel.position;
                if (index >= data.length) {
                    channel.playing = false;
                    continue;
                }
                total += data[index] * channel.volume;
                channel.position += channel.step;
            }
            // four voices at full volume fit in sixteen bits at this scale
            int value = total * 2;
            out[at + written] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, value));
            written++;
        }
        return written;
    }
}
