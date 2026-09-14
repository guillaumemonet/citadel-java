package citadel.game;

import java.io.File;
import java.util.List;

import citadel.core.GameState;
import citadel.core.Level;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.system.AppSettings;

/**
 * Playable viewer for a converted Citadel level: walk around with the original engine's movement
 * and collision rules, open doors, press switches, take teleports.
 *
 * <pre>
 *   gradle :game:run                      loads LEVEL01A
 *   gradle :game:run --args=LEVEL05B
 * </pre>
 *
 * Controls: the mouse turns, W/S walk, A/D strafe, left mouse or Ctrl fires, E or right mouse
 * uses the wall you are facing, 1..6 pick a weapon and 0 goes back to the bare hand.
 */
public final class CitadelApp extends SimpleApplication implements ActionListener, AnalogListener {

    private static final float EYE_HEIGHT = LevelGeometry.WALL_H * 0.55f;
    private static final float MOUSE_TURN = 30f;
    private static final float MAX_PITCH = FastMath.QUARTER_PI;

    private final String requested;

    private Level level;
    private GameState game;
    private LevelGeometry geometry;
    private BitmapText hud;

    private boolean forward, back, left, right, turnLeft, turnRight;
    private float mouseTurn;
    private float pitch;

    private ScreenshotAppState shots;
    private int frame;

    public CitadelApp(String requested) {
        this.requested = requested;
    }

    public static void main(String[] args) {
        String want = (args.length > 0) ? args[0] : "LEVEL01A";
        CitadelApp app = new CitadelApp(want);

        AppSettings settings = new AppSettings(true);
        settings.setTitle("Citadel - " + want);
        settings.setResolution(1280, 800);
        settings.setVSync(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        Assets assets = new Assets(assetManager);
        File map = findMap(assets.mapsDir(), requested);
        if (map == null) {
            throw new IllegalStateException("no map matching '" + requested
                    + "' under " + assets.mapsDir());
        }
        try {
            level = Level.load(map);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + map, e);
        }
        int complex = Assets.complexOf(map.getName());
        System.out.println("loaded " + level + "  (complex " + complex + ")");

        game = new GameState(level);
        String at = System.getProperty("citadel.at");
        if (at != null) {
            String[] parts = at.split(",");
            game.posX = Integer.parseInt(parts[0].trim()) * Level.CELL_UNITS + Level.CELL_UNITS / 2;
            game.posY = Integer.parseInt(parts[1].trim()) * Level.CELL_UNITS + Level.CELL_UNITS / 2;
            if (parts.length > 2) {
                game.angle = Integer.parseInt(parts[2].trim()) & 511;
            }
        }
        geometry = new LevelGeometry(level, assets, complex);
        rootNode.attachChild(geometry.build());

        viewPort.setBackgroundColor(ColorRGBA.Black);
        cam.setFrustumPerspective(70f, (float) cam.getWidth() / cam.getHeight(), 0.01f, 200f);
        flyCam.setEnabled(false);
        setupInput();
        setupHud();
        syncCamera();

        String shotDir = System.getProperty("citadel.screenshot");
        if (shotDir != null) {
            shots = new ScreenshotAppState(shotDir, requested, 0);
            stateManager.attach(shots);
            setDisplayStatView(false);
            setDisplayFps(false);
            if ("top".equals(System.getProperty("citadel.cam"))) {
                topDownCamera();
            }
        }
    }

    // --------------------------------------------------------------- input

    private void setupInput() {
        inputManager.setCursorVisible(false);
        inputManager.addMapping("fwd", new KeyTrigger(KeyInput.KEY_W), new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping("back", new KeyTrigger(KeyInput.KEY_S), new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addMapping("left", new KeyTrigger(KeyInput.KEY_A));
        inputManager.addMapping("right", new KeyTrigger(KeyInput.KEY_D));
        inputManager.addMapping("turnL", new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping("turnR", new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping("use", new KeyTrigger(KeyInput.KEY_E),
                new KeyTrigger(KeyInput.KEY_SPACE),
                new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        inputManager.addMapping("fire", new KeyTrigger(KeyInput.KEY_LCONTROL),
                new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addListener(this,
                "fwd", "back", "left", "right", "turnL", "turnR", "use", "fire");

        // 1..6 select a weapon, 0 goes back to the bare hand, as F1..F0 do in the original.
        int[] keys = {KeyInput.KEY_0, KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3,
                KeyInput.KEY_4, KeyInput.KEY_5, KeyInput.KEY_6};
        for (int i = 0; i < keys.length; i++) {
            inputManager.addMapping("weapon" + i, new KeyTrigger(keys[i]));
            inputManager.addListener(this, "weapon" + i);
        }

        inputManager.addMapping("mouseL", new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping("mouseR", new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping("mouseU", new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping("mouseD", new MouseAxisTrigger(MouseInput.AXIS_Y, true));
        inputManager.addListener(this, "mouseL", "mouseR", "mouseU", "mouseD");
    }

    @Override
    public void onAction(String name, boolean pressed, float tpf) {
        if ("fwd".equals(name)) {
            forward = pressed;
        } else if ("back".equals(name)) {
            back = pressed;
        } else if ("left".equals(name)) {
            left = pressed;
        } else if ("right".equals(name)) {
            right = pressed;
        } else if ("turnL".equals(name)) {
            turnLeft = pressed;
        } else if ("turnR".equals(name)) {
            turnRight = pressed;
        } else if ("use".equals(name) && pressed) {
            game.use();
        } else if ("fire".equals(name)) {
            firing = pressed;
            if (pressed) {
                game.fire();
            }
        } else if (name.startsWith("weapon") && pressed) {
            int slot = name.charAt(name.length() - 1) - '0';
            if (!game.inventory.select(slot)) {
                game.note("you have no " + citadel.core.Inventory.WEAPON_NAMES[slot]);
            }
        }
    }

    /**
     * The machinegun keeps firing while the button is held; every other weapon needs the trigger
     * released, which is what the cc_RequestTab dance in Check_RMB amounts to.
     */
    private boolean firing;
    private static final int MACHINEGUN = 3;

    private void serveAutoFire() {
        if (firing && game.inventory.current == MACHINEGUN) {
            game.fire();
        }
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        if ("mouseL".equals(name)) {
            mouseTurn -= value * MOUSE_TURN;
        } else if ("mouseR".equals(name)) {
            mouseTurn += value * MOUSE_TURN;
        } else if ("mouseU".equals(name)) {
            pitch = Math.min(MAX_PITCH, pitch + value * 2f);
        } else if ("mouseD".equals(name)) {
            pitch = Math.max(-MAX_PITCH, pitch - value * 2f);
        }
    }

    // -------------------------------------------------------------- update

    @Override
    public void simpleUpdate(float tpf) {
        float fwd = (forward ? 1f : 0f) - (back ? 1f : 0f);
        float strafe = (right ? 1f : 0f) - (left ? 1f : 0f);
        float turn = (turnRight ? 1f : 0f) - (turnLeft ? 1f : 0f) + mouseTurn;
        mouseTurn = 0f;

        if (shots != null) {
            // verification path: no input, but the world still ticks so doors finish opening
            fwd = strafe = turn = 0f;
            frame++;
            if (frame == 20) {
                shots.takeScreenshot();
            } else if (frame > 25) {
                stop();
            }
        }

        serveAutoFire();
        game.update(fwd, strafe, turn, Math.min(tpf, 0.1f));

        if (game.consumeGeometryDirty()) {
            geometry.rebuild();
        }
        geometry.syncDoors(game);
        geometry.syncEnemies(game);
        syncCamera();
        updateHud();
    }

    private void syncCamera() {
        float scale = LevelGeometry.CELL / Level.CELL_UNITS;
        cam.setLocation(new Vector3f(game.posX * scale, EYE_HEIGHT, game.posY * scale));
        float a = game.angleRadians();
        Vector3f look = new Vector3f(
                FastMath.cos(a) * FastMath.cos(pitch),
                FastMath.sin(pitch),
                FastMath.sin(a) * FastMath.cos(pitch));
        cam.lookAtDirection(look, Vector3f.UNIT_Y);
    }

    private void topDownCamera() {
        float mid = Level.GRID * LevelGeometry.CELL / 2f;
        cam.setLocation(new Vector3f(mid, 70f, mid));
        cam.lookAtDirection(new Vector3f(0f, -1f, 0.0001f), Vector3f.UNIT_Y);
        cam.setFrustumPerspective(60f, (float) cam.getWidth() / cam.getHeight(), 1f, 300f);
    }

    // ----------------------------------------------------------------- hud

    private void setupHud() {
        hud = new BitmapText(assetManager.loadFont("Interface/Fonts/Default.fnt"));
        hud.setSize(16f);
        hud.setColor(ColorRGBA.White);
        hud.setLocalTranslation(10f, cam.getHeight() - 10f, 0f);
        guiNode.attachChild(hud);
    }

    private String message = "";
    private float messageTimer;

    private void updateHud() {
        List<String> events = game.drainLog();
        if (!events.isEmpty()) {
            message = events.get(events.size() - 1);
            messageTimer = 3f;
        } else if (messageTimer > 0f) {
            messageTimer -= 1f / 60f;
            if (messageTimer <= 0f) {
                message = "";
            }
        }
        citadel.core.Inventory inv = game.inventory;
        StringBuilder cards = new StringBuilder();
        for (int c = 0; c < 3; c++) {
            if (inv.hasCard(c)) {
                cards.append("RGB".charAt(c));
            }
        }
        hud.setText(String.format(
                "energy %d   %s %d   %s%s   cell (%d,%d) facing %s%n%s%s",
                inv.energyReading(),
                citadel.core.Inventory.WEAPON_NAMES[inv.current], inv.ammoReading(),
                cards.length() > 0 ? "cards " + cards : "",
                inv.speedBoost > 0 ? "  [fast]" : "",
                game.cellX(), game.cellY(),
                citadel.core.MapFile.DIR_NAMES[game.facingDir()],
                game.isLevelComplete() ? "[EXIT REACHED]  " : "",
                message));
    }

    private static File findMap(File dir, String want) {
        File exact = new File(dir, want);
        if (exact.isFile()) {
            return exact;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        String needle = want.toUpperCase();
        for (File f : files) {
            if (f.isFile() && f.getName().toUpperCase().startsWith(needle)) {
                return f;
            }
        }
        return null;
    }
}
