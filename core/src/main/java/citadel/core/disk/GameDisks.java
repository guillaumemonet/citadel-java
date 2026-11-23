package citadel.core.disk;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import citadel.core.DataSource;

/**
 * The five Citadel floppies, served as the files the Amiga-Citadel {@code Data} folder holds.
 *
 * <h2>The disks</h2>
 * The boot block names each disk, so the image file names do not matter:
 * <ul>
 * <li>disk 1, an AmigaDOS volume with the {@code CITADEL} loader, and SERVER — the front end
 *     between levels — as a packed block at cylinder 27;</li>
 * <li>disks 2 to 4, {@code CYT2} to {@code CYT4}, no file system: packed blocks at fixed
 *     cylinders, which SERVER loads with {@code dl_START_s} (start sector, sector count) and
 *     unpacks with {@code Decrunch};</li>
 * <li>disk 5, {@code CYT5} in the boot block over an ordinary OFS volume: one packed file per
 *     map, wall set, enemy set and palette, which SERVER's {@code LoadFiles} reads by name
 *     ({@code FileStructure1}: {@code W1A W1B E1A E1B C1A M1A}).</li>
 * </ul>
 *
 * <h2>Where each file is</h2>
 * Disk 5 is read by name. Everything else sits inside a block, and the table in
 * {@link #entries} gives its offset there. The blocks are the ones SERVER loads:
 * cylinder 18 of disk 4 is the hangar picture ({@code move #18*2*11,d1}), 21 the engine's item
 * graphics, panel and messages, 24 the sample bank {@code ST.DAT} with the tables that follow it,
 * 1 and 12 the server's two songs; sectors 1 and 2 are the save file. Disk 5's {@code MOD} is
 * MAPA's song and {@code DET} the death sequence, whose code is followed by its module and its two
 * pictures.
 * The offsets inside them were found by matching against the Amiga-Citadel {@code Data} folder,
 * and {@code GameDisksTest} checks every one of them against it.
 *
 * <p>The disks are the released game, and two files differ from the repository: one wall tile in
 * {@code WALLS1A.VIR} and a few glyphs of {@code KANE.fnt}. The disks win.
 */
public final class GameDisks implements DataSource {

    /** Disks the game cannot do without; disk 2 only carries the attract-mode intro. */
    private static final int[] REQUIRED = {1, 3, 4, 5};

    private final Adf[] disks = new Adf[6];
    private final Map<String, byte[]> blocks = new HashMap<String, byte[]>();

    private GameDisks() {
    }

    /** Opens the images in a folder, whatever they are called. */
    public static GameDisks open(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("no folder " + dir);
        }
        List<Path> images = new ArrayList<Path>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".adf")
                        && Files.size(p) == Adf.SIZE) {
                    images.add(p);
                }
            }
        }
        GameDisks g = new GameDisks();
        for (Path p : images) {
            Adf adf = Adf.load(p);
            int n = number(adf);
            if (n > 0 && g.disks[n] == null) {
                g.disks[n] = adf;
            }
        }
        List<Integer> missing = g.missing();
        if (!missing.isEmpty()) {
            throw new IOException("Citadel disk" + (missing.size() > 1 ? "s " : " ") + missing
                    + " not found among the .adf images in " + dir);
        }
        return g;
    }

    /** True when the folder holds every disk the game needs. */
    public static boolean present(Path dir) {
        try {
            open(dir);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Which disk this is, 1 to 5, or 0 when it is not a Citadel disk. */
    static int number(Adf adf) throws IOException {
        String id = adf.bootId();
        if (id.startsWith("CYT") && id.charAt(3) >= '2' && id.charAt(3) <= '5') {
            return id.charAt(3) - '0';
        }
        if (id.startsWith("DOS") && adf.file("CITADEL") != null) {
            return 1;
        }
        return 0;
    }

    private List<Integer> missing() {
        List<Integer> out = new ArrayList<Integer>();
        for (int n : REQUIRED) {
            if (disks[n] == null) {
                out.add(n);
            }
        }
        return out;
    }

    public Adf disk(int n) {
        return disks[n];
    }

    /** Every path this source serves, in catalogue order then disk 5's files. */
    public List<String> paths() {
        List<String> out = new ArrayList<String>(ENTRIES.keySet());
        if (disks[5] != null) {
            for (String f : disks[5].list()) {
                String p = dataPath(f);
                if (p != null) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    @Override
    public boolean has(String path) {
        try {
            return find(path) != null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public byte[] read(String path) throws IOException {
        byte[] b = find(path);
        if (b == null) {
            throw new IOException(path + " is not on the Citadel disks");
        }
        return b;
    }

    private byte[] find(String path) throws IOException {
        String key = path.replace('\\', '/');
        Entry e = BY_KEY.get(key.toUpperCase(Locale.ROOT));
        if (e != null) {
            return e.read(this);
        }
        String onDisk = diskName(key);
        if (onDisk == null || disks[5] == null) {
            return null;
        }
        byte[] raw = disks[5].file(onDisk);
        if (raw == null) {
            return null;
        }
        return PowerPacker.isFile(raw) ? PowerPacker.file(raw) : raw;
    }

    @Override
    public String toString() {
        return "Citadel disks " + Arrays.toString(Arrays.stream(disks).skip(1)
                .map(d -> d == null ? "-" : d.name()).toArray());
    }

    // ------------------------------------------------------------ disk 5

    private static final Pattern MAP = Pattern.compile("GAME/MAPS/LEVEL0([1-8])([A-Z])\\.MAP");
    private static final Pattern WALLS = Pattern.compile("GAME/GFX_VIR/WALLS([1-8])([AB])\\.VIR");
    private static final Pattern ENEMY = Pattern.compile("GAME/GFX_VIR/ENEMY([1-8])([ABT])\\.VIR");
    private static final Pattern COLS = Pattern.compile("GAME/GFX_VIR/COLS0([1-8])\\.DAT");

    /** The disk 5 name of a Data path: LEVEL03B.MAP is M3B, WALLS3A.VIR W3A, COLS03.DAT C3A. */
    static String diskName(String path) {
        String p = path.toUpperCase(Locale.ROOT);
        Matcher m;
        if ((m = MAP.matcher(p)).matches()) {
            return "M" + m.group(1) + m.group(2);
        }
        if ((m = WALLS.matcher(p)).matches()) {
            return "W" + m.group(1) + m.group(2);
        }
        if ((m = ENEMY.matcher(p)).matches()) {
            return "E" + m.group(1) + m.group(2);
        }
        if ((m = COLS.matcher(p)).matches()) {
            return "C" + m.group(1) + "A";
        }
        return null;
    }

    /** The inverse of {@link #diskName}, or null for the files that are not data in their own right. */
    static String dataPath(String diskName) {
        Matcher m = Pattern.compile("([MWEC])([1-8])([A-Z])").matcher(diskName);
        if (!m.matches()) {
            return null;
        }
        String n = m.group(2);
        String x = m.group(3);
        switch (m.group(1)) {
            case "M":
                return "GAME/MAPS/LEVEL0" + n + x + ".MAP";
            case "W":
                return "GAME/Gfx_VIR/WALLS" + n + x + ".VIR";
            case "E":
                return "GAME/Gfx_VIR/ENEMY" + n + x + ".VIR";
            default:
                return "A".equals(x) ? "GAME/Gfx_VIR/COLS0" + n + ".DAT" : null;
        }
    }

    // ------------------------------------------------------------ blocks

    /** A packed block SERVER track-loads: disk and first sector. */
    private enum Block {
        /** SERVER itself, with the files it {@code >extern}s at the end of its source. */
        SERVER(1, 27 * Adf.CYLINDER),
        /** The cover picture, {@code CITADEL_ENG.rawb.pp} in the repository. */
        COVER(3, 72 * Adf.CYLINDER),
        /** The hangar, {@code Hangar_ENG.rawb}. */
        HANGAR(4, 18 * Adf.CYLINDER),
        /** {@code ITEMS01.VIR}, {@code WINDOW1.RAW} and {@code TEXT.ENG}, back to back. */
        ENGINE_GFX(4, 21 * Adf.CYLINDER),
        /** {@code ST.DAT}: the samples, then the bomb, the tables and the panel fonts. */
        SAMPLES(4, 24 * Adf.CYLINDER),
        /**
         * {@code mod.aconcagua.pro}, the server's main music: {@code lea mt_data-$200,a0} /
         * {@code moveq #1*2*11,d1} at the top of SERVER, unpacked with {@code decrunch}.
         */
        MAIN_MUSIC(4, 1 * Adf.CYLINDER),
        /** {@code mod.dirranbandi.pro}, END_PART2's "good end music": {@code move #12*2*11,d1}. */
        END_MUSIC(4, 12 * Adf.CYLINDER);

        final int disk;
        final int sector;

        Block(int disk, int sector) {
            this.disk = disk;
            this.sector = sector;
        }
    }

    private byte[] block(Block b) throws IOException {
        String key = b.name();
        byte[] d = blocks.get(key);
        if (d == null) {
            d = PowerPacker.block(image(b.disk), b.sector * Adf.SECTOR);
            blocks.put(key, d);
        }
        return d;
    }

    /** A packed block's bytes as they are on the disk, length word included. */
    private byte[] packed(Block b) throws IOException {
        byte[] img = image(b.disk);
        int at = b.sector * Adf.SECTOR;
        if (!PowerPacker.isBlock(img, at)) {
            throw new IOException("no packed block on disk " + b.disk + " at sector " + b.sector);
        }
        return Arrays.copyOfRange(img, at, at + PowerPacker.u32(img, at));
    }

    private byte[] image(int disk) throws IOException {
        if (disks[disk] == null) {
            throw new IOException("Citadel disk " + disk + " is missing");
        }
        return disks[disk].image();
    }

    /**
     * DET unpacked: the death sequence, Death14.ss assembled at {@code $52000}, which SERVER's
     * DEATHSEQ loads into DeathMEM and calls. Its {@code >extern}s are the module and the two
     * pictures, back to back after the code.
     */
    private byte[] death() throws IOException {
        byte[] d = blocks.get("DET");
        if (d == null) {
            d = PowerPacker.block(fileOnDisk5("DET"), 0);
            blocks.put("DET", d);
        }
        return d;
    }

    /** A run of bytes out of DET. */
    private static Entry inDeath(int off, int len) {
        return g -> Arrays.copyOfRange(g.death(), off, off + len);
    }

    /**
     * One of the texts SERVER assembles with {@code TEXT} / {@code ETEXT}: lines ended by a zero
     * byte, the whole ended by {@code dc.b -1}. Served up to and including that -1.
     */
    private static Entry serverText(int off) {
        return g -> {
            byte[] srv = g.block(Block.SERVER);
            int end = off;
            while (end < srv.length && srv[end] != (byte) -1) {
                end++;
            }
            return Arrays.copyOfRange(srv, off, Math.min(srv.length, end + 1));
        };
    }

    private byte[] fileOnDisk5(String name) throws IOException {
        byte[] raw = disks[5] == null ? null : disks[5].file(name);
        if (raw == null) {
            throw new IOException(name + " is not on Citadel disk 5");
        }
        return raw;
    }

    // ------------------------------------------------------------ the table

    private interface Entry {
        byte[] read(GameDisks g) throws IOException;
    }

    private static Entry slice(Block b, int off, int len) {
        return g -> Arrays.copyOfRange(g.block(b), off, off + len);
    }

    private static Entry raw(int disk, int offset, int len) {
        return g -> Arrays.copyOfRange(g.image(disk), offset, offset + len);
    }

    private static Map<String, Entry> entries() {
        Map<String, Entry> m = new LinkedHashMap<String, Entry>();

        m.put("GAME/Gfx_VIR/ITEMS01.VIR", slice(Block.ENGINE_GFX, 0x0000, 28080));
        m.put("GAME/Gfx_VIR/WINDOW1.RAW", slice(Block.ENGINE_GFX, 0x6db0, 40000));
        m.put("GAME/Store/TEXT.ENG", slice(Block.ENGINE_GFX, 0x109f0, 5775));

        // ST.DAT is served packed, as the repository has it; SoundTable unpacks it
        m.put("GAME/Store/ST.DAT", g -> g.packed(Block.SAMPLES));
        m.put("GAME/Store/Bomba.rawb", slice(Block.SAMPLES, 0x18edc, 5580));
        m.put("GAME/Store/CHAOSTAB.DAT", slice(Block.SAMPLES, 0x1e69a, 1728));
        m.put("GAME/Store/CHAOSTAC.DAT", slice(Block.SAMPLES, 0x1ed5a, 2048));
        m.put("GAME/Gfx_VIR/NUMBERS.RAW", slice(Block.SAMPLES, 0x1f55a, 430));
        m.put("GAME/Gfx_VIR/FONTS01.FNT", slice(Block.SAMPLES, 0x1f708, 768));

        m.put("INTRO/STORE/KANE.fnt", slice(Block.SERVER, 0xb61a, 0xc00));
        m.put("INTRO/GFX/strzalka.rawb", slice(Block.SERVER, 0x167cc, 132));
        m.put("INTRO/GFX/MT.raw", g -> PowerPacker.block(g.block(Block.SERVER), 0x82e2));
        // MT_endlev = KaneFont+$c00, still packed inside SERVER: ENDLEVEL_PART decrunches it
        m.put("INTRO/MODS/mod.MiedzyEtapy.pro",
                g -> PowerPacker.block(g.block(Block.SERVER), 0xc21a));
        // HangTab = NLoad+$364, the order DrukTekst reveals the hangar panel in
        m.put("GAME/Store/HangarTab.dat", slice(Block.SERVER, 0x13fb2, 1632));
        // kreditsy and Koncowka, CREDIT_PART's and END_PART2's scrolling texts
        m.put("SERVER/kreditsy", serverText(0x5978));
        m.put("SERVER/Koncowka", serverText(0x66f4));
        m.put("INTRO/GFX/Hangar_ENG.rawb", g -> g.block(Block.HANGAR));
        m.put("INTRO/GFX/cytadela.rawb", g -> g.block(Block.COVER));

        // MAP is MAPKA.rawb.pp as it is in the repository: a length-prefixed block, not a PP20 file
        m.put("INTRO/GFX/MAPKA.rawb",
                g -> PowerPacker.block(g.fileOnDisk5("MAP"), 0));
        // the death sequence, DET, which SERVER unpacks to DeathMEM and runs
        // mt_data, zabity = mt_data+$12f50, gameover = zabity+40064 (Death14.ss)
        m.put("INTRO/STORE/mod.death.pro", inDeath(0x1618, 77648));
        m.put("INTRO/STORE/zabity.rawb", inDeath(0x14568, 40064));
        m.put("INTRO/STORE/GameOver.rawb", inDeath(0x1e1e8, 40000));

        // the music SERVER track-loads from disk 4, and MAPA's from disk 5 (MUSname "DF0:MOD")
        m.put("INTRO/MODS/mod.aconcagua.pro", g -> g.block(Block.MAIN_MUSIC));
        m.put("INTRO/MODS/mod.dirranbandi.pro", g -> g.block(Block.END_MUSIC));
        m.put("INTRO/MODS/mod.brief1.pro", g -> PowerPacker.block(g.fileOnDisk5("MOD"), 0));

        // the save file: disk 4 from sector 1, which ENDLEVEL_PART writes back with
        // dl_START_s2 ($8001: save, sector 1, two sectors). Head block, then five slots.
        m.put("GAME/Store/Savegame.DAT", raw(4, 512, 768));

        // the intro animations, stored unpacked on disk 4 one after the other
        m.put("INTRO/ANIM2/start.k", raw(4, 0x76400, 135858));
        m.put("INTRO/ANIM2/kabina.k", raw(4, 0x976b2, 140924));
        m.put("INTRO/ANIM2/explozycja.k", raw(4, 0xb9d2e, 111502));

        return m;
    }

    /** Catalogued paths, spelt as in the repository. */
    private static final Map<String, Entry> ENTRIES = entries();
    private static final Map<String, Entry> BY_KEY = new HashMap<String, Entry>();

    static {
        for (Map.Entry<String, Entry> e : ENTRIES.entrySet()) {
            BY_KEY.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
        }
    }
}
