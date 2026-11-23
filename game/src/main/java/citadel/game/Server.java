package citadel.game;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import citadel.core.Campaign;
import citadel.core.DataSource;
import citadel.core.HangarMenu;
import citadel.core.KAnim;
import citadel.core.KaneFont;
import citadel.core.RawScreen;
import citadel.core.SaveGame;
import citadel.core.Structure;
import citadel.core.WorldMap;

/**
 * SERVER, the front end between levels (SERVER_032_HD_ENG.ss), run as the script it is.
 *
 * <pre>
 *   s           STRUCTURE from the save file's head, the main music, the hangar
 *   MainServer  START, LOAD GAME, OPTIONS, TRAINING, INFO
 *   StartGame   LoadFirstData, then LevelLoop:
 *   LevelLoop   +62 moves on; ETAPY says: play a map, go to MAPA, or END_PART2
 *               the engine returns + (GoodExit: ENDLEVEL_PART, LevelLoop again),
 *               - (DEATHSEQ, then s) or 0 (ESC: straight back to s)
 *   LoadTrening a training map; whatever happens, back to s (DEATHSEQ first if killed)
 * </pre>
 *
 * Playing a level is the one thing it hands over: {@link Host#startLevel} puts the world up and
 * the script waits, frame by frame, for {@link #levelEnded}.
 */
final class Server extends FrontScript {

    /** What the engine returned: do_JakiKoniec, and the port's own quick load. */
    enum Result { GOOD, DEAD, ESCAPE, LOAD }

    /** The render thread's side of things. */
    interface Host {
        void playMusic(String path);

        void stopMusic();

        /** Puts a level up and plays it; STRUCTURE goes in, and comes back changed. */
        void startLevel(String mapName, Structure structure, boolean training);

        File saveFile();

        void quit();
    }

    private static final int W = Display.W;
    private static final int H = Display.H;

    private final Host host;
    private final DataSource data;
    private final Display display;
    private final KaneFont kane;
    private final HangarView hangar;
    private final MapView mapa;
    private final HangarMenu menu = new HangarMenu();
    private final int[] cover;
    /** MT.raw: 4 planes and 16 colours, the picture behind ENDLEVEL_PART and CREDIT_PART. */
    private final int[] mtIndex;
    private final int[] mtPalette;
    private final String start;

    /** STRUCTURE. */
    private Structure structure = Structure.fresh();
    /** STRUCTURE+$300, the copy {@code s} keeps of the head block before anything changes it. */
    private Structure pristine = Structure.fresh();
    /** OptSav, which RememberOpt fills: size, floors and details survive a return to s. */
    private Structure optSav;

    private volatile Result result;
    private volatile int loadSlot;
    /** cp_Tsuw: 16 the first time, 18 after cp_GoAway resets it. */
    private int cpTsuw = 16;

    Server(Host host, DataSource data, Display display, KaneFont kane, HangarView hangar,
           MapView mapa, int[] cover, String start) throws IOException {
        this.host = host;
        this.data = data;
        this.display = display;
        this.kane = kane;
        this.hangar = hangar;
        this.mapa = mapa;
        this.cover = cover;
        this.start = start == null ? "" : start;
        byte[] mt = data.read("INTRO/GFX/MT.raw");
        mtIndex = planar(mt, 4);
        mtPalette = new int[16];
        for (int i = 0; i < 16; i++) {
            mtPalette[i] = ((mt[32000 + i * 2] & 0x0f) << 8) | (mt[32000 + i * 2 + 1] & 0xff);
        }
    }

    /** Planes one after the other, 8000 bytes each, as MT.raw has them (bitplane modulo 0). */
    static int[] planar(byte[] raw, int planes) {
        int[] out = new int[W * H];
        for (int p = 0; p < planes; p++) {
            for (int i = 0; i < W * H; i++) {
                if ((raw[p * 8000 + (i >> 3)] & (0x80 >> (i & 7))) != 0) {
                    out[i] |= 1 << p;
                }
            }
        }
        return out;
    }

    /** Called on the render thread when the level the script asked for is over. */
    void levelEnded(Result r, int slot) {
        loadSlot = slot;
        result = r;
    }

    // ================================================================ the flow

    @Override
    protected void run() {
        if (start.startsWith("play:")) {
            play(start.substring(5));
        }
        switch (start) {
            case "hangar", "slots", "credits" -> {
                // straight to s
            }
            case "map" -> {
                structure.setComplex(1);
                structure.setLevel(2);
                mapa();
            }
            case "endlevel" -> {
                structure.setComplex(3);
                structure.setLevel(2);
                structure.put(Structure.OFF_KILLED, 17);
                structure.put(Structure.OFF_TIME, 3600 + 12 * 60 + 34);
                structure.put(Structure.OFF_GLOWICA, -6);
                endLevelPart();
            }
            case "dead", "gameover" -> deathSeq();
            case "victory", "epilogue" -> endPart2();
            default -> title();
        }
        while (true) {
            s();
        }
    }

    /**
     * {@code gradle :game:run --args=LEVEL05B}: straight into a map. One of ETAPY's goes on
     * through the campaign from there, as if the levels before it had been played; any other is
     * played the way a training map is.
     */
    private void play(String map) {
        int complex = Assets.complexOf(map);
        char letter = map.length() > 7 ? map.charAt(7) : 'A';
        Structure probe = Structure.fresh();
        probe.setComplex(complex);
        probe.setLevel(letter - 'A');
        Campaign.Step step = Campaign.levelLoop(probe.copy());
        if (step.next() == Campaign.Next.PLAY && map.equalsIgnoreCase(step.mapName())) {
            structure.setComplex(complex);
            structure.setLevel(letter - 'A');
            levelLoop();
        } else {
            afterTraining(playLevel(map, true));
        }
    }

    /** What LoadTrening does with the engine's answer, and the port's quick load. */
    private void afterTraining(Result r) {
        if (r == Result.DEAD) {
            deathSeq();
        } else if (r == Result.LOAD) {
            structure = readSave().slot(loadSlot);
            menu.setStructure(structure);
            levelLoop();
        }
    }

    /**
     * The cover picture, cytadela.rawb from disk 3, which the intro leaves on screen when it
     * ends. The intro itself is not ported; see PORTING.md.
     */
    private void title() {
        onTitle = true;
        display.black();
        display.argb = cover;
        display.touch();
        input.clearAll();
        while (!input.fire && !input.key) {
            vblank();
        }
        input.clearAll();
        onTitle = false;
        display.black();
        vblank();
    }

    /** True while the cover is up, where the port lets ESC leave the game. */
    volatile boolean onTitle;

    /** {@code s}: a fresh STRUCTURE, the main music, the hangar, MainServer. */
    private void s() {
        setInterrupt(null);
        SaveGame save = readSave();
        structure = save.head();
        pristine = structure.copy();
        if (optSav != null) {
            structure.restoreOptions(optSav);
        }
        menu.setStructure(structure);
        later(() -> host.playMusic(Music.MAIN));
        hangar.load();
        vblank();
        mainServer();
    }

    /** MainServer and Main01. Returns to {@code s} when a game or a training level is over. */
    private void mainServer() {
        setInterrupt(() -> hangar.interrupt(input));
        hangar.kropa = 0;
        input.clearAll();
        seSetColors(hangar.palette, 32);
        hangar.keepPanel();
        if ("credits".equals(start)) {
            info();
        }
        boolean slots = "slots".equals(start);
        while (true) {
            // RysujMain
            menu.reset();
            if (!slots) {
                drukTekst();
            }
            input.fire = false;
            while (true) {
                int line;
                if (slots) {
                    slots = false;
                    line = 1;
                } else {
                    waitKlik();
                    line = hangar.kropa;
                }
                if (line == 4) {
                    continue;                       // the blank line: beq.s Main01
                }
                if (line == 5) {
                    info();
                    break;
                }
                if (line == 1) {
                    if (wczytajStan()) {
                        startGame();
                        return;
                    }
                    break;
                }
                if (line == 2) {
                    ustawOpcje();
                    break;
                }
                if (line == 0) {
                    startGame();
                    return;
                }
                int train = trening();
                if (train >= 0) {
                    loadTrening(train);
                    return;
                }
                break;
            }
        }
    }

    private void drukTekst() {
        String[] lines = menu.lines();
        boolean[] flags = new boolean[lines.length];
        for (int i = 0; i < lines.length; i++) {
            flags[i] = menu.isShifted(i);
        }
        hangar.drukTekst(this, lines, flags);
    }

    /** INFO: the hangar fades, the credits scroll, and the hangar comes back. */
    private void info() {
        hangar.doFlash = false;
        fadeColors(32);
        // CREDIT_PART draws into screens of its own; iff_screen is left as it was
        int[] screen = display.index.clone();
        creditPart();
        System.arraycopy(screen, 0, display.index, 0, screen.length);
        display.ehb = true;
        display.argb = null;
        hangar.czyscBoki();
        java.util.Arrays.fill(display.colours, 0);
        setInterrupt(() -> hangar.interrupt(input));
        seSetColors(hangar.palette, 32);
        input.fire = false;
    }

    /** WczytajStan: true when a game was loaded into STRUCTURE. */
    private boolean wczytajStan() {
        SaveGame save = readSave();
        String[] names = new String[SaveGame.SLOT_COUNT];
        for (int i = 0; i < names.length; i++) {
            names[i] = save.name(i);
        }
        menu.openLoad(names);
        hangar.kropa = 0;
        drukTekst();
        input.fire = false;
        while (true) {
            waitKlik();
            int k = hangar.kropa;
            if (k == 5) {
                hangar.kropa = 1;
                return false;
            }
            if (save.isOccupied(k)) {
                structure = save.slot(k);
                menu.setStructure(structure);
                hangar.kropa = 1;
                return true;
            }
        }
    }

    /** UstawOpcje, which comes back with the cursor on OPTIONS. */
    private void ustawOpcje() {
        menu.open(HangarMenu.Screen.OPTIONS);
        hangar.kropa = 0;
        while (true) {
            drukTekst();
            input.fire = false;
            HangarMenu.Action a;
            do {
                waitKlik();
                moveMenuTo(hangar.kropa);
                a = menu.select();
            } while (a == HangarMenu.Action.NONE);
            if (a == HangarMenu.Action.BACK) {
                hangar.kropa = 2;
                return;
            }
        }
    }

    /** Trening: the map index chosen, or -1 for MENU. Either way the cursor goes to TRAINING. */
    private int trening() {
        menu.open(HangarMenu.Screen.TRAINING);
        hangar.kropa = 0;
        drukTekst();
        input.fire = false;
        waitKlik();
        int k = hangar.kropa;
        hangar.kropa = 3;
        return k == 5 ? -1 : k;
    }

    private void moveMenuTo(int line) {
        menu.move(line - menu.line());
    }

    /** LoadFirstData: the hangar fades out and the music stops. */
    private void loadFirstData() {
        hangar.doFlash = false;
        setInterrupt(null);
        fadeColors(32);
        display.black();
        later(host::stopMusic);
        vblank();
    }

    /** LoadTrening: one of the five training maps, then back to {@code s} whatever happens. */
    private void loadTrening(int index) {
        loadFirstData();
        Result r = playLevel(HangarMenu.TRAINING_MAPS[index], true);
        rememberOpt();
        afterTraining(r);
    }

    /** StartGame: LoadFirstData, then LevelLoop. Returns to {@code s}. */
    private void startGame() {
        loadFirstData();
        levelLoop();
    }

    /** LevelLoop, until the game is over one way or another. */
    private void levelLoop() {
        while (true) {
            Campaign.Step step = Campaign.levelLoop(structure);
            if (step.next() == Campaign.Next.MAP) {
                mapa();
                continue;
            }
            if (step.next() == Campaign.Next.END) {
                endPart2();
                return;
            }
            Result r = playLevel(step.mapName(), false);
            rememberOpt();
            if (r == Result.LOAD) {
                structure = readSave().slot(loadSlot);
                menu.setStructure(structure);
                continue;
            }
            if (r == Result.ESCAPE) {
                return;
            }
            if (r == Result.DEAD) {
                deathSeq();
                return;
            }
            structure.addKillsToTotal();            // GoodExit
            endLevelPart();
        }
    }

    /** RememberOpt. */
    private void rememberOpt() {
        optSav = structure.copy();
    }

    /** Hands a level over to the engine and waits for it to come back. */
    private Result playLevel(String map, boolean training) {
        setInterrupt(null);
        result = null;
        Structure s = structure;
        later(() -> host.startLevel(map, s, training));
        while (result == null) {
            vblank();
        }
        input.clearAll();
        display.black();
        return result;
    }

    // ================================================================ MAPA

    /** MAPA: choose the next complex. Leaves STRUCTURE+60 on it and +62 at 0. */
    private void mapa() {
        mapa.czyArrow = true;
        display.black();
        vblank();
        later(() -> host.playMusic(Music.MAP));
        mapa.load();
        setInterrupt(mapa::interrupt);
        seSetColors(mapa.palette, 32);
        mapa.enter(structure);
        mapa.czyArrow = false;
        mapa.printnij(this);
        input.clearDirs();
        WorldMap world = mapa.world;
        while (true) {
            vblank();
            if (input.up || input.right) {
                world.move(-1);
            } else if (input.down || input.left) {
                world.move(1);
            } else if (input.fire) {
                input.fire = false;
                WorldMap.Pick pick = world.pick(structure.glowica());
                if (pick == WorldMap.Pick.CHOSEN) {
                    break;
                }
                mapa.say(this, switch (pick) {
                    case ALREADY_EXPLORED -> WorldMap.EXPLORED_TEXT;
                    case INACCESSIBLE -> WorldMap.NO_ACCESS_TEXT;
                    default -> WorldMap.NEED_BOMB_TEXT;
                });
                vblanks(WorldMap.REFUSAL_VBLANKS);
                mapa.printnij(this);
                input.clearDirs();
                continue;
            } else {
                continue;
            }
            // ma_sprawdz
            mapa.czyArrow = true;
            mapa.clrArrow();
            mapa.czyArrow = false;
            mapa.printnij(this);
            input.clearDirs();
        }
        mapa.say(this, world.chosenLine());
        vblanks(WorldMap.CHOSEN_VBLANKS);
        world.choose(structure);
        mapa.maOut(this);
        fadeColors(32);
        mapa.czyArrow = true;
        mapa.clrArrow();
        setInterrupt(null);
        later(host::stopMusic);
        display.black();
        vblank();
    }

    // ================================================================ ENDLEVEL_PART

    /** el_RamkaPos: x in bytes, y, width in bytes; and the last one drawn. */
    private final int[] ramka = new int[6];
    private int rFlag = 1;
    private int rFlag2;
    /** ENDLEVscr, the text plane. */
    private final boolean[] elText = new boolean[W * H];

    /** ENDLEVEL_PART: the level's figures, then the offer to save. */
    private void endLevelPart() {
        display.black();
        vblank();
        later(() -> host.playMusic(Music.END_OF_LEVEL));
        java.util.Arrays.fill(elText, false);
        for (int i = 16; i < 32; i++) {
            display.colours[i] = 0xfe0;
        }
        ramka[0] = -13;
        ramka[1] = 176;
        ramka[2] = 3;
        ramka[3] = 13;
        ramka[4] = 176;
        ramka[5] = 3;
        rFlag = 1;
        rFlag2 = 0;
        elDrukuj(elText());
        composeEndLevel();
        setInterrupt(this::elNewLev3);
        seSetColors(mtPalette, 16);
        ramka[0] = 13;
        input.clearDirs();

        boolean yes = false;
        while (true) {
            vblank();
            if (input.left || input.right) {
                yes = !yes;
                ramka[0] = yes ? 25 : 13;
            }
            input.left = input.right = input.up = input.down = false;
            if (input.fire) {
                input.fire = false;
                break;
            }
        }
        if (yes) {
            saveMenu();
        }
        // el_MYK
        fadeColors(32);
        setInterrupt(null);
        display.black();
        later(host::stopMusic);
        vblank();
    }

    /** el_text with el_Przelicz's figures in. */
    private String[][] elText() {
        String name = NAZWY_POZIOMOW[Math.max(0, Math.min(7, structure.complex() - 1))];
        int level = structure.level();
        int seconds = structure.seconds();
        int hours = Math.min(9, seconds / 3600);
        String time = (char) ('0' + hours) + ":" + two((seconds % 3600) / 60) + ":"
                + two(seconds % 60);
        int killed = structure.killed() & 0xffff;
        String bomb = String.valueOf((char) ('0' + ((-structure.glowica() & 0xffff) >> 1) % 10));
        return new String[][]{
            {"6", "25", " " + name + " LEVEL " + (char) ('0' + level) + " COMPLETED!"},
            {"1", "85", "ENEMIES KILLED:              " + three(killed)},
            {"1", "100", "ELAPSED TIME:                " + time},
            {"1", "115", "PARTS OF BOMB:               " + bomb},
            {"6", "160", "DO YOU WANT TO SAVE THE GAME?"},
            {"13", "178", "NO          YES"},
        };
    }

    private static String two(int v) {
        return "" + (char) ('0' + (v / 10) % 10) + (char) ('0' + v % 10);
    }

    private static String three(int v) {
        return (char) ('0' + (v / 100) % 10) + two(v);
    }

    /** NazwyPoziomow, twelve characters each. */
    private static final String[] NAZWY_POZIOMOW = {
        "    DUNGEONS", " POWER PLANT", "      STORES", "      HANGAR",
        "  LABORATORY", "      SEWERS", "      PRISON", "      CENTER",
    };

    /** el_Drukuj: sixteen rows of KANE.fnt a character, each byte written whole. */
    private void elDrukuj(String[][] text) {
        for (String[] t : text) {
            int x = Integer.parseInt(t[0]);
            int y = Integer.parseInt(t[1]);
            String s = t[2];
            for (int i = 0; i < s.length() && x + i < 40; i++) {
                for (int r = 0; r < 16 && y + r < H; r++) {
                    int b = kane.row(s.charAt(i), r);
                    for (int px = 0; px < 8; px++) {
                        elText[(y + r) * W + (x + i) * 8 + px] = ((b >> (7 - px)) & 1) != 0;
                    }
                }
            }
        }
    }

    /** ENDLEVcopper: MT.raw in planes 1 to 4, the text in plane 5. */
    private void composeEndLevel() {
        display.ehb = false;
        display.argb = null;
        for (int i = 0; i < W * H; i++) {
            display.index[i] = mtIndex[i] | (elText[i] ? 16 : 0);
        }
        display.touch();
    }

    /** EL_NewLev3: draws the frame where it has moved to, or blinks it where it is. */
    private void elNewLev3() {
        int x = ramka[0];
        int y = ramka[1];
        if (x < 0) {
            return;
        }
        if (x == ramka[3] && y == ramka[4]) {
            if (--rFlag != 0) {
                return;
            }
            rFlag = 20;
            rFlag2 ^= 1;
            frame(x, y, ramka[2], rFlag2 != 0);
        } else {
            frame(ramka[3], ramka[4], ramka[5], false);
            ramka[3] = x;
            ramka[4] = y;
            ramka[5] = ramka[2];
            frame(x, y, ramka[2], true);
            rFlag = 1;
            rFlag2 = 0;
        }
        composeEndLevel();
    }

    /**
     * el_nPostaw / el_nUsun: a line of {@code w} bytes along rows y and y+16, and a pixel down
     * each side, bit 0 of byte x-1 and bit 7 of byte x+w, for the fifteen rows between.
     */
    private void frame(int x, int y, int w, boolean on) {
        if (x < 1) {
            return;
        }
        for (int i = 0; i < w * 8; i++) {
            set(x * 8 + i, y, on);
            set(x * 8 + i, y + 16, on);
        }
        for (int r = 1; r <= 15; r++) {
            set(x * 8 - 1, y + r, on);
            set((x + w) * 8, y + r, on);
        }
    }

    private void set(int px, int row, boolean on) {
        if (px >= 0 && px < W && row >= 0 && row < H) {
            elText[row * W + px] = on;
        }
    }

    /** el_PosTab: the five slots and QUIT, as frame positions. */
    private static final int[][] EL_POSTAB = {
        {12, 48, 16}, {12, 68, 16}, {12, 88, 16}, {12, 108, 16}, {12, 128, 16}, {15, 158, 9},
    };

    /** ENDLEVEL_PART after YES: pick a slot, save into it, say OK. */
    private void saveMenu() {
        ramka[0] = 12;
        ramka[1] = 48;
        ramka[2] = 16;
        java.util.Arrays.fill(elText, false);
        SaveGame save = readSave();
        String[] titles = new String[SaveGame.SLOT_COUNT];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = save.name(i);
        }
        elDrukuj(saText(titles));
        composeEndLevel();
        vblanks(41);
        input.clearDirs();
        int pos = 0;
        while (true) {
            vblank();
            if (input.up) {
                pos = pos == 0 ? 5 : pos - 1;
            }
            if (input.down) {
                pos = pos == 5 ? 0 : pos + 1;
            }
            ramka[0] = EL_POSTAB[pos][0];
            ramka[1] = EL_POSTAB[pos][1];
            ramka[2] = EL_POSTAB[pos][2];
            input.up = input.down = input.left = input.right = false;
            if (input.fire) {
                input.fire = false;
                break;
            }
        }
        if (pos == 5) {
            return;                                 // 'PONIECHAJ'
        }
        String name = SaveGame.slotName(structure.complex(), structure.level());
        titles[pos] = name;
        save.store(pos, structure, name, pristine);
        elDrukuj(saText(titles));
        composeEndLevel();
        boolean ok;
        try {
            save.save(host.saveFile());
            ok = true;
        } catch (IOException e) {
            System.out.println("cannot save: " + e.getMessage());
            ok = false;
        }
        ramka[0] = -1;
        java.util.Arrays.fill(elText, false);
        elDrukuj(ok ? new String[][]{{"19", "80", "OK !"}}
                : new String[][]{{"10", "80", "UNPROTECT DISK 4 !!!"}});
        composeEndLevel();
        if (ok) {
            vblanks(81);
        } else {
            // the original retries the write after each press; here a write that failed once
            // will fail again, so one press goes on
            input.fire = false;
            waitKlik();
        }
    }

    /** el_saText, the slot titles copied in up to their '@'. */
    private static String[][] saText(String[] titles) {
        String[][] out = new String[7][];
        out[0] = new String[]{"8", "20", "  CHOOSE SAVE POSITION"};
        for (int i = 0; i < 5; i++) {
            out[1 + i] = new String[]{"12", String.valueOf(50 + i * 20), pad16(titles[i])};
        }
        out[6] = new String[]{"15", "160", "  QUIT"};
        return out;
    }

    private static String pad16(String s) {
        StringBuilder sb = new StringBuilder(s.length() > 16 ? s.substring(0, 16) : s);
        while (sb.length() < 16) {
            sb.append(' ');
        }
        return sb.toString();
    }

    // ================================================================ DEATHSEQ

    /**
     * DEATHSEQ, which runs DET (Death14.ss): zabity fades in to mod.death, stays up to 40
     * seconds, then GAME OVER closes over it from the top and the bottom in 49 frames and
     * stays up to 44 seconds more.
     */
    private void deathSeq() {
        display.black();
        vblank();
        RawScreen zabity;
        RawScreen gameOver;
        try {
            zabity = RawScreen.load("zabity.rawb", data.read("INTRO/STORE/zabity.rawb"), W, 5, 64);
            gameOver = RawScreen.load("GameOver.rawb", data.read("INTRO/STORE/GameOver.rawb"),
                    W, 5, 0);
        } catch (IOException e) {
            System.out.println("no death sequence: " + e.getMessage());
            return;
        }
        int[] z = zabity.indices();
        int[] g = gameOver.indices();
        int[] palette = new int[32];
        for (int i = 0; i < 32; i++) {
            palette[i] = Display.rgb12(zabity.palette[i]);
        }
        later(() -> host.playMusic(Music.DEATH));
        split(z, g, 0);
        // setcolors: d0 from 0 to 16, three VBLANKs a step
        for (int d0 = 0; d0 < 17; d0++) {
            display.scaled(palette, 32, d0);
            vblanks(3);
        }
        input.clearAll();
        for (int i = 0; i <= 50 * 40 && !input.fire; i++) {
            vblank();
        }
        // Dorysuj
        input.clearAll();
        for (int k = 1; k <= 49; k++) {
            vblank();
            split(z, g, k);
        }
        vblank();
        System.arraycopy(g, 0, display.index, 0, W * H);     // HIREScopper2
        display.touch();
        for (int i = 0; i <= 50 * 44 && !input.fire; i++) {
            vblank();
        }
        fadeColors(32);
        display.black();
        later(host::stopMusic);
        vblank();
        input.clearAll();
    }

    /**
     * HIREScopper after {@code k} passes of DorLoop1: the top 2k rows show GAME OVER's rows
     * 100-2k up to 99 ({@code screenG}, moved up two rows a pass), the bottom 2k its rows from 100
     * on ({@code dn}, the wait moved up two lines), and zabity in between, two rows lower than it
     * started once the first pass has moved {@code screenZ}.
     */
    private void split(int[] z, int[] g, int k) {
        for (int y = 0; y < H; y++) {
            int[] src;
            int row;
            if (y < 2 * k) {
                src = g;
                row = 100 - 2 * k + y;
            } else if (y >= H - 2 * k) {
                src = g;
                row = 100 + y - (H - 2 * k);
            } else {
                src = z;
                row = k == 0 ? y : y - 2;
            }
            row = Math.max(0, Math.min(H - 1, row));
            System.arraycopy(src, row * W, display.index, y * W, W);
        }
        display.ehb = false;
        display.argb = null;
        display.touch();
    }

    // ================================================================ CREDIT_PART

    /** SCROLL1scr: 218 rows of one plane, the text that scrolls up over MT.raw. */
    private final boolean[] scroll = new boolean[W * 218];

    /** CREDIT_PART, from INFO: the credits scroll over MT.raw until a button or a key. */
    private void creditPart() {
        setInterrupt(null);
        display.black();
        java.util.Arrays.fill(scroll, false);
        for (int i = 16; i < 32; i++) {
            display.colours[i] = 0xfe0;
        }
        composeScroll(false);
        seSetColors(mtPalette, 16);
        byte[] text = serverText("SERVER/kreditsy");
        int at = 0;
        input.clearAll();
        while (true) {
            vblanks(2);
            if (input.spaceHeld) {
                continue;                           // cp_Twait
            }
            if (input.quitKey || input.button) {
                break;                              // cp_GoAway
            }
            if (--cpTsuw == 0) {
                cpTsuw = 16;
                if (at >= text.length || text[at] == -1) {
                    at = 0;                         // -1: from the beginning
                }
                at = printLine(text, at);
            }
            scrollUp(false);
        }
        fadeColors(32);
        cpTsuw = 18;
        input.clearAll();
    }

    /**
     * cp_Dodrukuj / ko_Dodrukuj: rows 200 to 215 cleared, and the next line printed at row 200,
     * thirteen rows of KANE.fnt. Returns where the next line starts.
     */
    private int printLine(byte[] text, int at) {
        java.util.Arrays.fill(scroll, 200 * W, 216 * W, false);
        int col = 0;
        while (at < text.length && text[at] != 0 && text[at] != -1) {
            char c = (char) (text[at++] & 0xff);
            if (c != ' ' && col < 40) {
                for (int r = 0; r < KaneFont.GLYPH_H; r++) {
                    int b = kane.row(c, r);
                    for (int px = 0; px < 8; px++) {
                        scroll[(200 + r) * W + col * 8 + px] = ((b >> (7 - px)) & 1) != 0;
                    }
                }
            }
            col++;
        }
        if (at < text.length && text[at] == 0) {
            at++;
        }
        return at;
    }

    /** cp_Suwaj: the buffer up one row (the blitter's 215 rows), shown before it moves. */
    private void scrollUp(boolean gradient) {
        composeScroll(gradient);
        System.arraycopy(scroll, W, scroll, 0, 215 * W);
    }

    private void composeScroll(boolean gradient) {
        display.ehb = false;
        display.argb = null;
        for (int i = 0; i < W * H; i++) {
            if (gradient) {
                display.index[i] = scroll[i] ? GRADIENT + i / W : 0;
            } else {
                display.index[i] = mtIndex[i] | (scroll[i] ? 16 : 0);
            }
        }
        display.touch();
    }

    private byte[] serverText(String path) {
        try {
            return data.read(path);
        } catch (IOException e) {
            return "\0".getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    // ================================================================ END_PART2

    /**
     * END_PART2: kabina, start and explozycja to mod.dirranbandi, then ko_scroll, the epilogue,
     * and back to {@code s}.
     */
    private void endPart2() {
        setInterrupt(null);
        display.black();
        vblank();
        later(() -> host.playMusic(Music.GOOD_END));
        if (!"epilogue".equals(start)) {
            String[] order = {"kabina", "start", "explozycja"};
            for (int i = 0; i < order.length; i++) {
                replayPart(order[i], i);
            }
        }
        koScroll();
        later(host::stopMusic);
        display.black();
        vblank();
    }

    /** ReplayPart: one animation on its row of the anims table, then nine frames of black. */
    private void replayPart(String name, int row) {
        KAnim anim;
        try {
            anim = KAnim.load(data.read("INTRO/ANIM2/" + name + ".k"));
        } catch (IOException e) {
            System.out.println("cannot play " + name + ": " + e.getMessage());
            return;
        }
        int[] vblanks = KAnim.frameVblanks(KAnim.END_ROWS[row], anim.frameCount());
        int total = 0;
        int[] out = new int[W * H];
        for (int f = 0; f < anim.frameCount(); f++) {
            int[] px = anim.frame(f);
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    out[y * W + x] = px[(y >> 1) * KAnim.WIDTH + (x >> 1)];
                }
            }
            display.argb = out.clone();
            display.touch();
            vblanks(vblanks[f]);
            total += vblanks[f];
        }
        display.black();
        vblanks(9);
        // .dupa: the least it runs is the table's first word, licznik
        while (total < KAnim.END_MINIMUM[row]) {
            vblank();
            total++;
        }
    }

    /** Registers standing in for colour 1 on each of the 200 rows. */
    private static final int GRADIENT = Display.REGISTERS - H;

    /** KONC2copper's colour 1 for each row, $222 to $fff at the top and back down at the bottom. */
    private static int gradient(int row) {
        int line = 0x36 + row;
        int c = 0x111;
        for (int k = 0; k < 14; k++) {
            if (line >= 0x3a + 2 * k) {
                c = 0x222 + 0x111 * k;
            }
        }
        for (int k = 0; k < 14; k++) {
            if (line >= 0xe2 + 2 * k) {
                c = 0xeee - 0x111 * k;
            }
        }
        return c;
    }

    /** ko_scroll: Koncowka, three VBLANKs a row, held while a key or button is down. */
    private void koScroll() {
        display.black();
        java.util.Arrays.fill(scroll, false);
        vblanks(71);
        for (int y = 0; y < H; y++) {
            display.colours[GRADIENT + y] = gradient(y);
        }
        composeScroll(true);
        byte[] text = serverText("SERVER/Koncowka");
        int at = 0;
        int tsuw = 18;
        input.clearAll();
        while (true) {
            vblanks(3);
            if (input.buttonHeld || input.keysHeld > 0) {
                continue;                           // ko_Twait, and the buttons
            }
            if (--tsuw == 0) {
                tsuw = 16;
                if (at >= text.length || text[at] == -1) {
                    break;                          // ko_GoAway
                }
                at = printLine(text, at);
            }
            scrollUp(true);
        }
        input.clearAll();
        while (!input.button && !input.key && !input.fire) {
            vblank();
        }
        input.clearAll();
    }

    // ================================================================ helpers

    /** se_setcolors: sixteen steps from wherever the registers are to the target. */
    private void seSetColors(int[] target, int count) {
        for (int d0 = 15; d0 >= 0; d0--) {
            display.stepTowards(target, count);
            vblanks(2);
        }
    }

    /** fadecolors: seventeen steps down to black. */
    private void fadeColors(int count) {
        for (int d0 = 16; d0 >= 0; d0--) {
            display.stepDown(count);
            vblanks(2);
        }
    }

    private SaveGame readSave() {
        File f = host.saveFile();
        try {
            if (f.isFile()) {
                return SaveGame.load(f);
            }
            if (data.has("GAME/Store/Savegame.DAT")) {
                return SaveGame.of(data.read("GAME/Store/Savegame.DAT"));
            }
        } catch (IOException e) {
            System.out.println("cannot read the save file: " + e.getMessage());
        }
        return SaveGame.empty();
    }
}
