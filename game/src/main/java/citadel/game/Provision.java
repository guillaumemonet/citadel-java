package citadel.game;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import citadel.tools.Convert;

/**
 * Makes sure the original data and the converted assets exist before the game starts.
 *
 * The original data is not shipped with the port: it lives in the Amiga-Citadel repository. When
 * it cannot be found the player is asked whether to download it, and it goes, together with the
 * assets converted from it and the save file, into a per-user folder. A development checkout is
 * left alone: Gradle passes {@code citadel.data} and {@code citadel.assets}, and those win.
 */
final class Provision {

    /** The Amiga-Citadel commit the port was checked against. */
    static final String SOURCE_COMMIT = "64364663a779e638c5e3e4e5fa78ab9873f7a742";
    static final String SOURCE_URL =
            "https://github.com/pawelmat/Amiga-Citadel/archive/" + SOURCE_COMMIT + ".zip";

    private Provision() {
    }

    /**
     * Resolves the data and asset folders, downloading and converting as needed, and publishes
     * them as the {@code citadel.data} and {@code citadel.assets} properties {@link Assets} reads.
     *
     * @return false when the player declined, and the game should not start
     */
    static boolean ensure() throws Exception {
        File home = userHome();
        File data = existing(System.getProperty("citadel.data"), "../Amiga-Citadel/Data",
                "../../Amiga-Citadel/Data");
        if (data == null) {
            data = new File(home, "Data");
            if (!isData(data)) {
                data = ask(data);
                if (data == null) {
                    return false;
                }
            }
        }

        String wanted = System.getProperty("citadel.assets");
        File assets = (wanted != null) ? new File(wanted)
                : new File("build/assets").isDirectory() ? new File("build/assets")
                : new File(home, "assets");
        if (!new File(assets, "gfx").isDirectory()) {
            convert(data, assets);
        }

        System.setProperty("citadel.data", data.getAbsolutePath());
        System.setProperty("citadel.assets", assets.getAbsolutePath());
        return true;
    }

    /** Where a packaged game keeps what it downloads, converts and saves. */
    static File userHome() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String user = System.getProperty("user.home");
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            return new File(local != null ? local : user, "Citadel");
        }
        if (os.contains("mac")) {
            return new File(user, "Library/Application Support/Citadel");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return new File(xdg != null && !xdg.isEmpty() ? xdg : user + "/.local/share", "citadel");
    }

    private static File existing(String... candidates) {
        for (String c : candidates) {
            if (c != null && isData(new File(c))) {
                return new File(c);
            }
        }
        return null;
    }

    private static boolean isData(File dir) {
        return new File(dir, "GAME/MAPS").isDirectory();
    }

    // ------------------------------------------------------------ asking

    /** Offers to download the data into {@code target}, or to use a copy already on disk. */
    private static File ask(File target) throws Exception {
        String[] options = {"Download", "Choose folder...", "Quit"};
        while (true) {
            int choice = JOptionPane.showOptionDialog(null,
                    "<html>Citadel needs the original Amiga game data, which is not included.<br><br>"
                            + "It can be downloaded from the Amiga-Citadel repository on GitHub<br>"
                            + "(github.com/pawelmat/Amiga-Citadel, about 20 MB) into<br>"
                            + target.getAbsolutePath() + "<br><br>"
                            + "Or point to an Amiga-Citadel checkout you already have.</html>",
                    "Citadel", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == 0) {
                runWithProgress("Downloading the original data...", () -> download(target));
                return target;
            }
            if (choice != 1) {
                return null;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Amiga-Citadel folder, or its Data folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                File picked = chooser.getSelectedFile();
                for (File f : new File[]{picked, new File(picked, "Data")}) {
                    if (isData(f)) {
                        return f;
                    }
                }
                JOptionPane.showMessageDialog(null, "No Data/GAME/MAPS under " + picked,
                        "Citadel", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private static void convert(File data, File assets) throws Exception {
        runWithProgress("Converting the game data...", () -> {
            File part = new File(assets.getPath() + ".part");
            deleteTree(part.toPath());
            // the converter reports every file it writes; nobody is reading
            PrintStream out = System.out;
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            try {
                Convert.main(new String[]{data.getAbsolutePath(), part.getAbsolutePath()});
            } finally {
                System.setOut(out);
            }
            deleteTree(assets.toPath());
            Files.move(part.toPath(), assets.toPath());
        });
    }

    // ------------------------------------------------------------ download

    /** Fetches the repository archive and keeps only its Data folder. */
    private static void download(File target) throws IOException {
        File part = new File(target.getPath() + ".part");
        deleteTree(part.toPath());
        Files.createDirectories(part.toPath());

        HttpURLConnection http = (HttpURLConnection) new URL(SOURCE_URL).openConnection();
        http.setInstanceFollowRedirects(true);
        http.setConnectTimeout(15000);
        http.setReadTimeout(30000);
        if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("GitHub answered " + http.getResponseCode() + " for " + SOURCE_URL);
        }
        int files = 0;
        Path root = part.toPath().toAbsolutePath().normalize();
        try (InputStream in = http.getInputStream(); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                // entries are Amiga-Citadel-<commit>/Data/...; everything else is the sources
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0 || !name.startsWith("Data/", slash + 1)) {
                    continue;
                }
                Path to = root.resolve(name.substring(slash + "/Data/".length())).normalize();
                if (!to.startsWith(root)) {
                    throw new IOException("archive entry escapes the target: " + name);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(to);
                } else {
                    Files.createDirectories(to.getParent());
                    Files.copy(zip, to);
                    files++;
                }
            }
        } finally {
            http.disconnect();
        }
        if (files == 0 || !isData(part)) {
            throw new IOException("the archive at " + SOURCE_URL + " has no Data/GAME/MAPS");
        }
        deleteTree(target.toPath());
        Files.move(part.toPath(), target.toPath());
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(p)) {
            all = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        // deepest first, so every folder is empty by the time it is reached
        for (Path q : all) {
            Files.delete(q);
        }
    }

    // ------------------------------------------------------------ progress

    interface Job {
        void run() throws Exception;
    }

    /** Runs a job off the event thread behind an indeterminate progress window. */
    private static void runWithProgress(String message, Job job) throws Exception {
        JDialog[] dialog = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            JDialog d = new JDialog((java.awt.Frame) null, "Citadel", false);
            JLabel label = new JLabel(message);
            label.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            bar.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
            d.add(label, BorderLayout.NORTH);
            d.add(bar, BorderLayout.CENTER);
            d.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            d.pack();
            d.setLocationRelativeTo(null);
            d.setVisible(true);
            dialog[0] = d;
        });
        try {
            job.run();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, message.replace("...", " failed:\n") + e,
                    "Citadel", JOptionPane.ERROR_MESSAGE);
            throw e;
        } finally {
            try {
                SwingUtilities.invokeAndWait(() -> dialog[0].dispose());
            } catch (InvocationTargetException ignored) {
                // the window is going away either way
            }
        }
    }
}
