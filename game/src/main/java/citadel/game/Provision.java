package citadel.game;

import java.awt.BorderLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

import citadel.core.disk.GameDisks;

/**
 * Makes sure the original floppy images are at hand before the game starts.
 *
 * The port ships no game data at all: it reads the five Citadel disks, as {@code .adf} images,
 * and decodes everything it needs from them as it goes. When they cannot be found the player is
 * asked whether to download them, or to point at a folder that holds them; downloads go into a
 * per-user folder, beside the save file. A development checkout is left alone: Gradle passes
 * {@code citadel.adf}, and that wins.
 */
final class Provision {

    /** An archive of the released disks: the five images in a zip. */
    static final String DISKS_URL = "https://www.amiga500archive.com/dl.php?id=JEIEHMMHMF";

    private Provision() {
    }

    /**
     * Resolves the folder of disk images, downloading as needed, and publishes it as the
     * {@code citadel.adf} property {@link Assets} reads.
     *
     * @return false when the player declined, and the game should not start
     */
    static boolean ensure() throws Exception {
        File home = userHome();
        File disks = existing(System.getProperty("citadel.adf"), "adf", "../adf", "../../adf",
                new File(home, "adf").getPath());
        if (disks == null) {
            disks = ask(new File(home, "adf"));
            if (disks == null) {
                return false;
            }
        }
        System.setProperty("citadel.adf", disks.getAbsolutePath());
        return true;
    }

    /** Where a packaged game keeps what it downloads and saves. */
    static File userHome() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
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
            if (c != null && isDisks(new File(c))) {
                return new File(c);
            }
        }
        return null;
    }

    private static boolean isDisks(File dir) {
        return dir.isDirectory() && GameDisks.present(dir.toPath());
    }

    // ------------------------------------------------------------ asking

    /** Offers to download the disks into {@code target}, or to use images already on disk. */
    private static File ask(File target) throws Exception {
        String[] options = {"Download", "Choose folder...", "Quit"};
        while (true) {
            int choice = JOptionPane.showOptionDialog(null,
                    "<html>Citadel needs the five original Amiga disks, as .adf images,<br>"
                            + "which are not included.<br><br>"
                            + "They can be downloaded from amiga500archive.com (about 4 MB) into<br>"
                            + target.getAbsolutePath() + "<br><br>"
                            + "Or point to a folder that already holds them.</html>",
                    "Citadel", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == 0) {
                runWithProgress("Downloading the game disks...", () -> download(target));
                return target;
            }
            if (choice != 1) {
                return null;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Folder holding the Citadel .adf images");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                File picked = chooser.getSelectedFile();
                if (isDisks(picked)) {
                    return picked;
                }
                JOptionPane.showMessageDialog(null, "The Citadel disks are not all in " + picked,
                        "Citadel", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    // ------------------------------------------------------------ download

    /** Fetches the archive and keeps only the disk images in it. */
    private static void download(File target) throws IOException {
        File part = new File(target.getPath() + ".part");
        deleteTree(part.toPath());
        Files.createDirectories(part.toPath());

        HttpURLConnection http = (HttpURLConnection) URI.create(DISKS_URL).toURL().openConnection();
        http.setInstanceFollowRedirects(true);
        http.setConnectTimeout(15000);
        http.setReadTimeout(30000);
        if (http.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("the server answered " + http.getResponseCode() + " for "
                    + DISKS_URL);
        }
        try (InputStream in = http.getInputStream(); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                name = name.substring(name.lastIndexOf('/') + 1);
                if (e.isDirectory() || !name.toLowerCase(Locale.ROOT).endsWith(".adf")) {
                    continue;
                }
                // flat names only, so an entry cannot reach outside the folder
                Path to = part.toPath().resolve(name.replaceAll("[\\\\:]", "_"));
                Files.copy(zip, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            http.disconnect();
        }
        if (!GameDisks.present(part.toPath())) {
            throw new IOException("the archive at " + DISKS_URL + " does not hold the Citadel disks");
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
