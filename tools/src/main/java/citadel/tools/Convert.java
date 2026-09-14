package citadel.tools;

import citadel.core.GfxLayout;
import citadel.core.Io;
import citadel.core.MapFile;
import citadel.core.Palette;
import citadel.core.SoundTable;
import citadel.core.VirReader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Converts the original Amiga Citadel data files into formats a jMonkeyEngine port can consume:
 * PNG textures and sprite sheets, WAV sound effects, and readable dumps of the level data.
 *
 * Usage:
 * <pre>
 *   java -cp out citadel.tools.Convert &lt;dataDir&gt; &lt;outDir&gt; [--gfx] [--maps] [--sounds] [--raw]
 * </pre>
 * where dataDir is either the repository Data folder or Data/GAME directly. With no flag, every
 * stage runs except --raw.
 */
public final class Convert {

    private File gfxDir;
    private File mapsDir;
    private File soundsDir;
    private File storeDir;
    private File dataRoot;
    private File out;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: Convert <dataDir> <outDir>"
                    + " [--gfx] [--maps] [--sounds] [--intro] [--raw]");
            System.exit(2);
        }
        List<String> flags = Arrays.asList(args).subList(2, args.length);
        boolean any = flags.contains("--gfx") || flags.contains("--maps")
                || flags.contains("--sounds") || flags.contains("--intro")
                || flags.contains("--raw");

        Convert c = new Convert();
        c.resolve(new File(args[0]), new File(args[1]));
        if (!any || flags.contains("--gfx")) {
            c.convertGraphics();
        }
        if (!any || flags.contains("--maps")) {
            c.convertMaps();
        }
        if (!any || flags.contains("--sounds")) {
            c.convertSounds();
        }
        if (!any || flags.contains("--intro")) {
            c.convertIntro(c.dataRoot);
        }
        if (flags.contains("--raw")) {
            c.dumpRawTiles();
        }
        System.out.println("done -> " + c.out.getAbsolutePath());
    }

    private void resolve(File dataDir, File outDir) throws IOException {
        File root = new File(dataDir, "GAME").isDirectory() ? new File(dataDir, "GAME") : dataDir;
        gfxDir = firstExisting(new File(root, "Gfx_VIR"), new File(root, "MAP_GFX"));
        mapsDir = new File(root, "MAPS");
        soundsDir = new File(root, "Sounds");
        storeDir = new File(root, "Store");
        // the intro lives beside GAME, not inside it
        dataRoot = "GAME".equals(root.getName()) && root.getParentFile() != null
                ? root.getParentFile() : root;
        out = outDir;
        Io.mkdirs(out);
        System.out.println("data : " + root.getAbsolutePath());
    }

    private static File firstExisting(File... candidates) {
        for (File f : candidates) {
            if (f.isDirectory()) {
                return f;
            }
        }
        return candidates[0];
    }

    // ------------------------------------------------------------ graphics

    /**
     * Rebuilds, for each of the 8 complexes, the contiguous graphics block the engine builds in
     * memory, then slices it up using the engine's own offset tables.
     */
    private void convertGraphics() throws IOException {
        StringBuilder log = new StringBuilder();
        for (int complex = 1; complex <= 8; complex++) {
            File wa = new File(gfxDir, "WALLS" + complex + "A.VIR");
            File wb = new File(gfxDir, "WALLS" + complex + "B.VIR");
            File ea = new File(gfxDir, "ENEMY" + complex + "A.VIR");
            File eb = new File(gfxDir, "ENEMY" + complex + "B.VIR");
            File items = new File(gfxDir, "ITEMS01.VIR");
            File cols = new File(gfxDir, String.format("COLS%02d.DAT", complex));
            if (!wa.isFile() || !wb.isFile()) {
                System.out.println("complex " + complex + ": no wall set, skipped");
                continue;
            }

            byte[] block = new byte[GfxLayout.BLOCK_SIZE];
            place(block, GfxLayout.WALLS_A, wa);
            place(block, GfxLayout.WALLS_B, wb);
            place(block, GfxLayout.ENEMY_A, ea);
            place(block, GfxLayout.ENEMY_B, eb);
            place(block, GfxLayout.ITEMS, items);

            Palette pal = Palette.load(cols.isFile() ? cols : new File(gfxDir, "COLS01.DAT"));
            VirReader vir = new VirReader(block, true);

            File dir = new File(out, String.format("gfx/complex%d", complex));
            Io.mkdirs(dir);
            Io.writeText(new File(dir, "palette.txt"), pal.toText());
            ImageIO.write(paletteStrip(pal), "png", new File(dir, "palette.png"));

            int written = 0, blank = 0;
            log.append("# complex ").append(complex).append("  palette ").append(pal.name).append('\n');
            log.append("file                  offset  size   note\n");
            for (GfxLayout.Entry e : GfxLayout.all()) {
                boolean sprite = !"wall".equals(e.group);
                if (sprite && vir.isBlank(e.offset, e.width, e.height)) {
                    blank++;
                    continue;
                }
                BufferedImage img = vir.extract(e.offset, e.width, e.height, pal.lut(sprite));
                File f = new File(dir, e.fileName());
                ImageIO.write(img, "png", f);
                written++;
                log.append(String.format("%-20s %7d  %2dx%-3d %s%n",
                        e.fileName(), e.offset, e.width, e.height, e.note));
            }
            log.append('\n');
            System.out.printf("complex %d: %d graphics written, %d empty slots skipped%n",
                    complex, written, blank);
        }
        Io.writeText(new File(out, "gfx/INDEX.txt"), log.toString());
    }

    private void place(byte[] block, int at, File f) throws IOException {
        if (!f.isFile()) {
            System.out.println("  missing " + f.getName() + ", left blank");
            return;
        }
        byte[] d = Io.readAll(f);
        System.arraycopy(d, 0, block, at, Math.min(d.length, block.length - at));
    }

    private BufferedImage paletteStrip(Palette pal) {
        int sw = 24;
        BufferedImage img = new BufferedImage(sw * 16, sw * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int i = 0; i < Palette.SIZE; i++) {
            g.setColor(new Color(pal.opaque[i]));
            g.fillRect((i % 16) * sw, (i / 16) * sw, sw, sw);
        }
        g.dispose();
        return img;
    }

    /**
     * Dumps the whole graphics block as plain 64x65 tiles, in both possible byte orders, so the
     * column major assumption can be confirmed by looking at the result.
     */
    private void dumpRawTiles() throws IOException {
        File wa = new File(gfxDir, "WALLS1A.VIR");
        byte[] block = new byte[GfxLayout.BLOCK_SIZE];
        place(block, GfxLayout.WALLS_A, wa);
        place(block, GfxLayout.WALLS_B, new File(gfxDir, "WALLS1B.VIR"));
        place(block, GfxLayout.ENEMY_A, new File(gfxDir, "ENEMY1A.VIR"));
        place(block, GfxLayout.ENEMY_B, new File(gfxDir, "ENEMY1B.VIR"));
        place(block, GfxLayout.ITEMS, new File(gfxDir, "ITEMS01.VIR"));
        Palette pal = Palette.load(new File(gfxDir, "COLS01.DAT"));

        File dir = new File(out, "raw");
        Io.mkdirs(dir);
        for (boolean colMajor : new boolean[]{true, false}) {
            VirReader vir = new VirReader(block, colMajor);
            int tiles = GfxLayout.BLOCK_SIZE / VirReader.TILE + 1;
            int cols = 8;
            int rows = (tiles + cols - 1) / cols;
            BufferedImage atlas = new BufferedImage(cols * 66, rows * 80, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = atlas.createGraphics();
            g.setColor(new Color(0x101010));
            g.fillRect(0, 0, atlas.getWidth(), atlas.getHeight());
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            for (int t = 0; t < tiles; t++) {
                BufferedImage img = vir.extract(t * VirReader.TILE, 64, 64, pal.opaque);
                int px = (t % cols) * 66 + 1;
                int py = (t / cols) * 80 + 1;
                g.drawImage(img, px, py, null);
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("tile " + t, px, py + 76);
            }
            g.dispose();
            ImageIO.write(atlas, "png",
                    new File(dir, colMajor ? "tiles_columnmajor.png" : "tiles_rowmajor.png"));
        }
        System.out.println("raw tile atlases written to " + dir);
    }

    // ---------------------------------------------------------------- maps

    private void convertMaps() throws IOException {
        File[] files = mapsDir.listFiles();
        if (files == null) {
            System.out.println("no MAPS directory at " + mapsDir);
            return;
        }
        Arrays.sort(files);
        File dir = new File(out, "maps");
        Io.mkdirs(dir);
        int n = 0;
        List<String> summary = new ArrayList<String>();
        for (File f : files) {
            if (!f.isFile() || f.length() < MapFile.EVENTS_OFF) {
                continue;
            }
            try {
                MapFile map = MapFile.load(f);
                String base = f.getName().replaceAll("[^A-Za-z0-9._-]+", "_");
                ImageIO.write(MapRenderer.render(map), "png", new File(dir, base + ".png"));
                Io.writeText(new File(dir, base + ".txt"), map.toText());
                summary.add(String.format("%-28s spawn=(%2d,%2d) exit=(%2d,%2d) enemies=%d events=%d",
                        f.getName(), map.spawnX(), map.spawnY(), map.exitX(), map.exitY(),
                        map.enemies().size(), map.events().size()));
                n++;
            } catch (IOException e) {
                System.out.println("  " + f.getName() + ": " + e.getMessage());
            }
        }
        Io.writeText(new File(dir, "INDEX.txt"), join(summary));
        System.out.println("maps: " + n + " converted");
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- intro

    /**
     * Decodes the intro sequences and the loose IFF artwork.
     *
     * Worth saying plainly: the repository README states the intro animations are gone. They are
     * not. Data/INTRO/ANIM2 holds three ordinary IFF ANIMs, and they decode.
     */
    private void convertIntro(File dataRoot) throws IOException {
        File animDir = new File(dataRoot, "INTRO/ANIM2");
        File outDir = new File(out, "intro");
        Io.mkdirs(outDir);
        StringBuilder log = new StringBuilder("name        size      planes  frames\n");

        File[] anims = animDir.listFiles();
        if (anims != null) {
            Arrays.sort(anims);
            for (File f : anims) {
                if (!f.isFile() || f.getName().endsWith(".k")) {
                    continue; // .k files are the engine's own repacked form, not IFF
                }
                try {
                    AnimReader anim = AnimReader.load(f);
                    File frames = new File(outDir, f.getName());
                    Io.mkdirs(frames);
                    for (int i = 0; i < anim.frameCount(); i++) {
                        ImageIO.write(anim.frame(i), "png",
                                new File(frames, String.format("%03d.png", i)));
                    }
                    log.append(String.format("%-10s  %4dx%-4d  %6d  %6d%n",
                            f.getName(), anim.width, anim.height, anim.planes, anim.frameCount()));
                    System.out.printf("intro %s: %d frames%n", f.getName(), anim.frameCount());
                } catch (IOException e) {
                    System.out.println("  " + f.getName() + ": " + e.getMessage());
                }
            }
        }

        // loose artwork, which is plain ILBM
        int art = 0;
        for (String dir : new String[]{"INTRO/ART_INNE", "GAME/Gfx_IFF", "GAME/ScreenShots"}) {
            File src = new File(dataRoot, dir);
            File[] files = src.listFiles();
            if (files == null) {
                continue;
            }
            Arrays.sort(files);
            File dst = new File(outDir, "art");
            Io.mkdirs(dst);
            for (File f : files) {
                if (!f.isFile()) {
                    continue;
                }
                try {
                    IffReader iff = IffReader.load(f);
                    String base = f.getName().replaceAll("[^A-Za-z0-9._-]+", "_");
                    ImageIO.write(iff.toImage(), "png", new File(dst, base + ".png"));
                    art++;
                } catch (IOException e) {
                    // not an ILBM, skip quietly: these folders hold raw data too
                }
            }
        }
        Io.writeText(new File(outDir, "INDEX.txt"), log.toString());
        System.out.println("intro: " + art + " artwork images");
    }

    // -------------------------------------------------------------- sounds

    private void convertSounds() throws IOException {
        File st = new File(storeDir, "ST.DAT");
        File dir = new File(out, "sounds");
        Io.mkdirs(dir);
        StringBuilder log = new StringBuilder("name       offset  length  period   rate\n");

        byte[] samples = st.isFile() ? Io.readAll(st) : null;
        int n = 0;
        for (SoundTable.Sfx s : SoundTable.ALL) {
            byte[] pcm;
            int off;
            if (samples != null && s.offset + s.length <= samples.length) {
                pcm = samples;
                off = s.offset;
            } else {
                File alt = new File(soundsDir, s.name + ".snd");
                if (!alt.isFile()) {
                    System.out.println("  no source for " + s.name);
                    continue;
                }
                pcm = Io.readAll(alt);
                off = 0;
            }
            Io.write(new File(dir, s.name + ".wav"),
                    SoundTable.toWav(pcm, off, s.length, s.sampleRate()));
            log.append(String.format("%-10s %6d  %6d  0x%04x  %5d%n",
                    s.name, s.offset, s.length, s.period, s.sampleRate()));
            n++;
        }
        Io.writeText(new File(dir, "INDEX.txt"), log.toString());
        System.out.println("sounds: " + n + " WAV files written"
                + (samples == null ? " (from Sounds/*.snd, ST.DAT not found)" : " (from ST.DAT)"));
    }
}
