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
 * The game: intro, title screen, play, and the end cards.
 *
 * The original is three programs chained by a loader -- an intro player, a server holding the
 * menus, and the engine. Here they are modes of one application.
 *
 * <pre>
 *   gradle :game:run                      loads LEVEL01A
 *   gradle :game:run --args=LEVEL05B
 * </pre>
 *
 * Controls: the mouse turns, W/S walk, A/D strafe, left mouse or Ctrl fires, E or right mouse
 * uses the wall you are facing, 1..6 pick a weapon, 0 the bare hand, M toggles the automap,
 * F5 and F9 save and load, ENTER answers the front-end screens.
 */
public final class CitadelApp extends SimpleApplication implements ActionListener, AnalogListener {

    private static final float EYE_HEIGHT = LevelGeometry.WALL_H * 0.55f;
    private static final float MOUSE_TURN = 30f;
    private static final float MAX_PITCH = FastMath.QUARTER_PI;

    private final String requested;

    private Level level;
    private GameState game;
    private LevelGeometry geometry;
    private SoundSystem sound;
    private BitmapText hud;
    private Automap automap;
    private Compass compass;
    private HeartMonitor heartMonitor;

    private boolean forward, back, left, right, turnLeft, turnRight;
    private float mouseTurn;
    private float pitch;

    private ScreenshotAppState shots;
    private int frame;
    private boolean autoStart;

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
        assets = new Assets(assetManager);
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

        currentMap = map.getName();
        game = new GameState(level);
        String paceProperty = System.getProperty("citadel.pace");
        if (paceProperty != null) {
            pace = Float.parseFloat(paceProperty);
        }
        game.setLoopHz(pace);
        try {
            texts = citadel.core.TextTable.loadFrom(assets.dataRoot());
            game.setTexts(texts);
        } catch (Exception e) {
            System.out.println("no TEXT file found, using plain wording");
        }
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
        sound = new SoundSystem(assetManager, rootNode, assets);
        rootNode.attachChild(geometry.build());

        viewPort.setBackgroundColor(ColorRGBA.Black);
        cam.setFrustumPerspective(70f, (float) cam.getWidth() / cam.getHeight(), 0.01f, 200f);
        flyCam.setEnabled(false);
        setupInput();
        setupHud();
        interference = new Interference(assetManager, cam.getWidth(), cam.getHeight());
        try {
            dissolve = new Dissolve(assetManager,
                    citadel.core.ChaosTable.loadFrom(assets.dataRoot()),
                    cam.getWidth(), cam.getHeight());
            viewPort.addProcessor(dissolve);
        } catch (Exception e) {
            System.out.println("no CHAOSTAC.DAT, teleports will cut straight over");
        }
        automap = new Automap(assetManager, cam.getWidth(), cam.getHeight());
        guiNode.attachChild(automap.picture());
        compass = new Compass(assetManager, cam.getWidth(), cam.getHeight(),
                automap.side());
        guiNode.attachChild(compass.picture());
        // the burst goes over the view but under the furniture: sv_MAKE_SZUM paints the chunky
        // buffer before sv_copy, and COMPASS and UpdateMap run after it
        try {
            weaponDisplay = new WeaponDisplay(assetManager, assets,
                    citadel.core.WeaponChange.loadFrom(assets.dataRoot()));
        } catch (Exception e) {
            System.out.println("no CHAOSTAB.DAT, weapons will appear at once");
        }
        heartMonitor = new HeartMonitor(assetManager);
        guiNode.attachChild(heartMonitor.picture());
        layer(automap.picture(), Z_FURNITURE);
        layer(compass.picture(), Z_FURNITURE);
        layer(heartMonitor.picture(), Z_FURNITURE);
        syncCamera();

        screens = new Screens(assetManager, guiNode,
                assetManager.loadFont("Interface/Fonts/Default.fnt"),
                assets.assetRoot(), cam.getWidth(), cam.getHeight());
        // the verification paths want the world straight away, not the front end
        String want = System.getProperty("citadel.screen");
        boolean skipFrontEnd = (System.getProperty("citadel.screenshot") != null && want == null)
                || System.getProperty("citadel.at") != null;
        enter(skipFrontEnd ? Mode.PLAYING
                : "title".equals(want) ? Mode.TITLE
                : "dead".equals(want) ? Mode.DEAD
                : "victory".equals(want) ? Mode.VICTORY
                : (screens.hasIntro() ? Mode.INTRO : Mode.TITLE));
        autoStart = "play".equals(want);
        if ("slots".equals(want)) {
            openSlots(SlotAction.LOAD, Mode.TITLE);
        }

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
        inputManager.addMapping("map", new KeyTrigger(KeyInput.KEY_M));
        inputManager.addMapping("save", new KeyTrigger(KeyInput.KEY_F5));
        inputManager.addMapping("load", new KeyTrigger(KeyInput.KEY_F9));
        inputManager.addMapping("confirm", new KeyTrigger(KeyInput.KEY_RETURN),
                new KeyTrigger(KeyInput.KEY_NUMPADENTER));
        inputManager.addMapping("slower", new KeyTrigger(KeyInput.KEY_MINUS),
                new KeyTrigger(KeyInput.KEY_SUBTRACT));
        inputManager.addMapping("faster", new KeyTrigger(KeyInput.KEY_EQUALS),
                new KeyTrigger(KeyInput.KEY_ADD));
        inputManager.addListener(this,
                "fwd", "back", "left", "right", "turnL", "turnR", "use", "fire", "map",
                "save", "load", "confirm", "slower", "faster");

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
        if (mode != Mode.PLAYING) {
            if (pressed) {
                handleFrontEndKey(name);
            }
            return;
        }
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
        } else if ("slower".equals(name) && pressed) {
            setPace(game.loopHz() - 2f);
        } else if ("faster".equals(name) && pressed) {
            setPace(game.loopHz() + 2f);
        } else if ("map".equals(name) && pressed) {
            automap.toggle();
        } else if ("save".equals(name) && pressed) {
            openSlots(SlotAction.SAVE, Mode.PLAYING);
        } else if ("load".equals(name) && pressed) {
            openSlots(SlotAction.LOAD, Mode.PLAYING);
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

    private final citadel.core.Campaign campaign = new citadel.core.Campaign();
    private Assets assets;

    private String currentMap;
    private boolean advancing;

    /**
     * Walks the ETAPY sequence: reaching the exit cell loads the next map and carries the player's
     * inventory across, which is what the server does by keeping it in STRUCTURE between levels.
     */
    private void advanceToNextLevel() {
        citadel.core.Campaign.Stage next = campaign.next(currentMap);
        if (next == null) {
            advancing = false;
            enter(Mode.VICTORY);
            return;
        }
        File f = campaign.file(assets.mapsDir(), next);
        if (f == null) {
            game.note("missing map " + next.mapName());
            return;
        }
        try {
            citadel.core.Inventory carried = game.inventory;
            level = Level.load(f);
            currentMap = f.getName();
            GameState fresh = new GameState(level);
            fresh.setTexts(texts);
            fresh.setLoopHz(pace);
            copyInventory(carried, fresh.inventory);
            game = fresh;

            rootNode.detachAllChildren();
            geometry = new LevelGeometry(level, assets, Assets.complexOf(currentMap));
            rootNode.attachChild(geometry.build());
            syncCamera();
            game.note("ENTERING " + currentMap);
            System.out.println("advanced to " + level);
        } catch (Exception e) {
            game.note("cannot load " + next.mapName() + ": " + e.getMessage());
        } finally {
            advancing = false;
        }
    }

    /** Weapons, ammunition, cards and energy survive a level change; level state does not. */
    private static void copyInventory(citadel.core.Inventory from, citadel.core.Inventory to) {
        System.arraycopy(from.owned, 0, to.owned, 0, from.owned.length);
        System.arraycopy(from.condition, 0, to.condition, 0, from.condition.length);
        System.arraycopy(from.ammo, 0, to.ammo, 0, from.ammo.length);
        System.arraycopy(from.cards, 0, to.cards, 0, from.cards.length);
        to.current = from.current;
        to.energy = from.energy;
        to.warhead = from.warhead;
    }

    private citadel.core.TextTable texts;

    /**
     * What the application is doing. The original is three programs — an intro player, a server
     * with the menus, and the game engine — chained by the loader; here they are states.
     */
    private enum Mode { INTRO, TITLE, PLAYING, DEAD, VICTORY, SLOTS }

    /** Which way the slot picker is pointing. */
    private enum SlotAction { SAVE, LOAD }

    private SlotAction slotAction = SlotAction.LOAD;
    private int slotIndex;
    /** The mode to fall back to when the picker is dismissed. */
    private Mode slotReturn = Mode.TITLE;

    private void openSlots(SlotAction action, Mode back) {
        slotAction = action;
        slotReturn = back;
        slotIndex = 0;
        mode = Mode.SLOTS;
        setHudVisible(false);
        inputManager.setCursorVisible(true);
        paintSlots();
    }

    /** Reads the file fresh each time, so the list always reflects what is on disk. */
    private citadel.core.SaveGame slots() {
        File f = saveFile();
        try {
            return f.isFile() ? citadel.core.SaveGame.load(f) : citadel.core.SaveGame.empty();
        } catch (Exception e) {
            return citadel.core.SaveGame.empty();
        }
    }

    private void paintSlots() {
        citadel.core.SaveGame save = slots();
        List<String> lines = new java.util.ArrayList<String>();
        for (int i = 0; i < citadel.core.SaveGame.SLOT_COUNT; i++) {
            if (save.isEmpty(i)) {
                lines.add(String.format("%d.  - free -", i + 1));
            } else {
                lines.add(String.format("%d.  %-16s  complex %d-%c   energy %d",
                        i + 1, save.name(i), save.complex(i),
                        (char) ('A' + save.levelIndex(i)), -save.energy(i)));
            }
        }
        screens.showList(
                slotAction == SlotAction.SAVE ? "SAVE GAME" : "LOAD GAME",
                lines, slotIndex,
                "W/S or arrows to choose    ENTER to confirm    E to go back");
    }

    private void confirmSlot() {
        citadel.core.SaveGame save = slots();
        if (slotAction == SlotAction.SAVE) {
            try {
                save.store(slotIndex, game, campaign, campaign.indexOf(currentMap),
                        currentMap.replace(".MAP", ""));
                save.save(saveFile());
            } catch (Exception e) {
                System.out.println("cannot save: " + e.getMessage());
            }
            enter(slotReturn);
            return;
        }
        if (save.isEmpty(slotIndex)) {
            return;   // nothing to load; stay on the picker
        }
        loadFromSlot(save, slotIndex);
    }

    private Mode mode = Mode.PLAYING;
    private Screens screens;

    private void enter(Mode next) {
        mode = next;
        switch (next) {
            case INTRO:
                screens.startIntro();
                break;
            case TITLE:
                screens.showTitle("ENTER to begin    F9 to load a saved game    ESC to quit");
                break;
            case DEAD:
                screens.showMessage(textOr(39, "YOU ARE DEAD")
                        + "\n\nENTER to restart the level    F9 to load");
                break;
            case VICTORY:
                screens.showMessage("THE CITADEL HAS FALLEN\n\nENTER to return to the title");
                break;
            default:
                screens.hide();
                break;
        }
        inputManager.setCursorVisible(next != Mode.PLAYING);
        boolean inGame = next == Mode.PLAYING;
        setHudVisible(inGame);
    }

    /** The in-game overlays only belong on screen while the world is up. */
    private void setHudVisible(boolean visible) {
        com.jme3.scene.Spatial.CullHint hint = visible
                ? com.jme3.scene.Spatial.CullHint.Never
                : com.jme3.scene.Spatial.CullHint.Always;
        if (hud != null) {
            hud.setCullHint(hint);
        }
        if (hudPanel != null) {
            hudPanel.setCullHint(hint);
        }
        if (weaponDisplay != null && weaponDisplay.picture() != null) {
            weaponDisplay.picture().setCullHint(hint);
        }
        if (heartMonitor != null) {
            heartMonitor.picture().setCullHint(hint);
        }
        if (compass != null) {
            compass.picture().setCullHint(hint);
        }
        if (automap != null && (!visible || automap.isVisible())) {
            automap.picture().setCullHint(hint);
        }
    }

    private String textOr(int index, String fallback) {
        return (texts != null && !texts.get(index).isEmpty()) ? texts.get(index) : fallback;
    }

    /** Keys the front-end screens answer to. Everything else is ignored while they are up. */
    private void handleFrontEndKey(String name) {
        if (mode == Mode.SLOTS) {
            if ("fwd".equals(name)) {
                slotIndex = (slotIndex + citadel.core.SaveGame.SLOT_COUNT - 1)
                        % citadel.core.SaveGame.SLOT_COUNT;
                paintSlots();
            } else if ("back".equals(name)) {
                slotIndex = (slotIndex + 1) % citadel.core.SaveGame.SLOT_COUNT;
                paintSlots();
            } else if ("confirm".equals(name)) {
                confirmSlot();
            } else if ("use".equals(name)) {
                enter(slotReturn);
            }
            return;
        }

        if ("confirm".equals(name)) {
            switch (mode) {
                case INTRO:
                    enter(Mode.TITLE);      // let the player skip the animation
                    break;
                case DEAD:
                    restartLevel();
                    break;
                case VICTORY:
                    enter(Mode.TITLE);
                    break;
                default:
                    enter(Mode.PLAYING);
                    break;
            }
        } else if ("load".equals(name)) {
            openSlots(SlotAction.LOAD, mode);
        }
    }

    /**
     * Game pace, in passes of MAIN_LOOP per second. The original is bounded by how fast the
     * software renderer could go, which is not recorded anywhere, so this is exposed as a setting:
     * it governs walking, turning, doors and how quickly enemies notice you, all at once.
     */
    private void setPace(float hz) {
        game.setLoopHz(hz);
        pace = game.loopHz();
        game.note(String.format("pace %.0f loops/s", pace));
    }

    private float pace = GameState.DEFAULT_LOOP_HZ;

    /** Restarts the current map from its spawn, keeping nothing but the campaign position. */
    private void restartLevel() {
        try {
            level = Level.load(new File(assets.mapsDir(), currentMap));
            game = new GameState(level);
            game.setTexts(texts);
            game.setLoopHz(pace);
            rootNode.detachAllChildren();
            geometry = new LevelGeometry(level, assets, Assets.complexOf(currentMap));
            rootNode.attachChild(geometry.build());
            syncCamera();
        } catch (Exception e) {
            System.out.println("cannot restart: " + e.getMessage());
        }
        enter(Mode.PLAYING);
    }

    /**
     * Quick save and load, in the original 768 byte format so the file is the one the Amiga game
     * would read. It is written beside the converted assets rather than into Data/, which is the
     * pristine original and lives under version control.
     */
    private File saveFile() {
        return new File(assets.assetRoot().getParentFile(), "Savegame.DAT");
    }

    /** Restores one slot: loads its map, rebuilds the world, and puts the inventory back. */
    private void loadFromSlot(citadel.core.SaveGame save, int slot) {
        try {
            GameState probe = new GameState(level);
            citadel.core.Campaign.Stage stage = save.restore(slot, probe, campaign);
            File map = campaign.file(assets.mapsDir(), stage);
            if (map == null) {
                game.note("missing map " + stage.mapName());
                return;
            }
            level = Level.load(map);
            currentMap = map.getName();
            game = new GameState(level);
            game.setTexts(texts);
            game.setLoopHz(pace);
            save.restore(slot, game, campaign);

            rootNode.detachAllChildren();
            geometry = new LevelGeometry(level, assets, Assets.complexOf(currentMap));
            rootNode.attachChild(geometry.build());
            syncCamera();
            enter(Mode.PLAYING);
            game.note("LOADED " + currentMap);
        } catch (Exception e) {
            game.note("cannot load: " + e.getMessage());
        }
    }

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
        if (shots != null) {
            // verification path: render a few frames, save one, and quit whatever the mode
            frame++;
            // walk the real route through the front end, which is where the HUD is switched
            if (autoStart && mode != Mode.PLAYING && (frame == 4 || frame == 8 || frame == 12)) {
                handleFrontEndKey("confirm");
            }
            // -Pwalk=N drives the player forward for N frames, so a shot can show where
            // walking forward actually takes you rather than only where the camera points
            String walk = System.getProperty("citadel.walk");
            forward = walk != null && frame < Integer.parseInt(walk);
            // -Pframe=N moves the shot later, which is how a transition gets caught mid-flight
            int shotAt = Integer.getInteger("citadel.frame", 20);
            if (frame == shotAt) {
                shots.takeScreenshot();
            } else if (frame > shotAt + 5) {
                stop();
            }
        }
        if (mode == Mode.INTRO) {
            if (screens.advanceIntro(tpf)) {
                enter(Mode.TITLE);
            }
            return;
        }
        if (mode != Mode.PLAYING) {
            return;   // title and the end cards wait on a key
        }

        float fwd = (forward ? 1f : 0f) - (back ? 1f : 0f);
        float strafe = (right ? 1f : 0f) - (left ? 1f : 0f);
        // The engine's plane is (X, Y) with Y north; jME's is (X, Z) with Z coming towards the
        // viewer, which is its mirror image. So an angle that sweeps clockwise in the engine
        // sweeps anticlockwise on screen, and the turn input is negated here — at the boundary,
        // where the handedness actually changes — rather than in the rules, which are tested
        // against the engine's own compass.
        float turn = -((turnRight ? 1f : 0f) - (turnLeft ? 1f : 0f) + mouseTurn);
        mouseTurn = 0f;

        if (shots != null && System.getProperty("citadel.walk") == null) {
            fwd = strafe = turn = 0f;   // no input on the verification path
        }

        serveAutoFire();
        game.update(fwd, strafe, turn, Math.min(tpf, 0.1f));

        if (game.isPlayerDead()) {
            enter(Mode.DEAD);
            return;
        }
        if (game.isLevelComplete() && !advancing) {
            advancing = true;
            advanceToNextLevel();
        }

        if (game.consumeGeometryDirty()) {
            geometry.rebuild();
        }
        geometry.syncAnimations(game);
        geometry.syncDoors(game);
        geometry.syncEnemies(game);
        geometry.syncProjectiles(game);
        automap.update(game);
        compass.update(game);
        heartMonitor.update(game);
        interference.update(guiNode, game.szumActive(), game.szumFrame());
        if (weaponDisplay != null) {
            weaponDisplay.sync(guiNode, game, Assets.complexOf(currentMap),
                    cam.getWidth(), cam.getHeight());
            weaponDisplay.update(tpf);
        }
        sound.serve(game);
        serveTeleport(tpf);
        if (!holdingCamera) {
            syncCamera();
        }
        updateHud();
    }

    /**
     * TELEPORT: keep the old view on screen for one frame so it can be read back, then dissolve
     * it away over the new one.
     */
    private void serveTeleport(float tpf) {
        if (dissolve == null) {
            return;
        }
        dissolve.update(tpf);
        if (holdingCamera) {
            holdingCamera = false;
            if (dissolve.isCaptured()) {
                dissolve.start(guiNode);
            }
        } else if (game.consumeTeleported()) {
            holdingCamera = true;
            dissolve.arm();
        }
    }

    /**
     * Points the camera along exactly the vector the player walks down.
     *
     * These must come from the same place. The camera used to build its own {@code (cos a, sin a)}
     * while movement used the engine's {@code (-cos a, sin a)}; they agree on Z and differ on X,
     * so facing north or south felt fine and facing east or west walked you backwards.
     */
    private void syncCamera() {
        float scale = LevelGeometry.CELL / Level.CELL_UNITS;
        cam.setLocation(new Vector3f(game.posX * scale, EYE_HEIGHT, game.posY * scale));
        float flat = FastMath.cos(pitch);
        Vector3f look = new Vector3f(
                (float) GameState.forwardX(game.angle) * flat,
                FastMath.sin(pitch),
                (float) GameState.forwardY(game.angle) * flat);
        cam.lookAtDirection(look, Vector3f.UNIT_Y);
    }

    private void topDownCamera() {
        float mid = Level.GRID * LevelGeometry.CELL / 2f;
        cam.setLocation(new Vector3f(mid, 70f, mid));
        cam.lookAtDirection(new Vector3f(0f, -1f, 0.0001f), Vector3f.UNIT_Y);
        cam.setFrustumPerspective(60f, (float) cam.getWidth() / cam.getHeight(), 1f, 300f);
    }

    // ----------------------------------------------------------------- hud

    /** sv_MAKE_SZUM, the static burst when a shot lands or the player walks into a wall. */
    private Interference interference;

    /** TELEPORT, the chaos-table dissolve from the view being left to the one arrived in. */
    private Dissolve dissolve;
    /**
     * True for the single frame between a teleport landing and the camera following it. The old
     * view has to still be on screen when the framebuffer is read, so the camera waits one frame.
     */
    private boolean holdingCamera;

    /**
     * Layers in the gui bucket, bottom to top. They have to be whole numbers apart: jME's
     * GuiComparator subtracts the two Z values and casts the result to int, so anything closer
     * together than 1 compares equal and the order falls back on whatever was attached first.
     *
     * The order follows the engine's own: the TELEPORT dissolve and sv_MAKE_SZUM paint the view
     * buffer, then COMPASS, UpdateMap and the readout are drawn over the result.
     */
    static final float Z_DISSOLVE = 1f;
    static final float Z_INTERFERENCE = 2f;
    static final float Z_FURNITURE = 3f;
    static final float Z_TEXT = 4f;

    private static void layer(com.jme3.scene.Spatial s, float z) {
        com.jme3.math.Vector3f t = s.getLocalTranslation();
        s.setLocalTranslation(t.x, t.y, z);
    }

    /** Dark band the readout sits on, since white text on a lit ceiling is barely there. */
    private com.jme3.ui.Picture hudPanel;

    /** The weapon in hand, and ci_DrawWeapon's reveal when it changes. */
    private WeaponDisplay weaponDisplay;

    private void setupHud() {
        float bandHeight = 56f;
        com.jme3.texture.Image px = new com.jme3.texture.Image(
                com.jme3.texture.Image.Format.RGBA8, 1, 1,
                com.jme3.util.BufferUtils.createByteBuffer(new byte[]{0, 0, 0, (byte) 0xb0}),
                com.jme3.texture.image.ColorSpace.sRGB);
        hudPanel = new com.jme3.ui.Picture("hudPanel");
        hudPanel.setTexture(assetManager, new com.jme3.texture.Texture2D(px), true);
        hudPanel.getMaterial().getAdditionalRenderState()
                .setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        hudPanel.setWidth(cam.getWidth());
        hudPanel.setHeight(bandHeight);
        hudPanel.setPosition(0, cam.getHeight() - bandHeight);
        layer(hudPanel, Z_FURNITURE);
        guiNode.attachChild(hudPanel);

        hud = new BitmapText(assetManager.loadFont("Interface/Fonts/Default.fnt"));
        hud.setSize(19f);
        hud.setColor(ColorRGBA.White);
        hud.setLocalTranslation(12f, cam.getHeight() - 8f, Z_TEXT);
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
                "energy %d%s   %s %d   %s%s   %d:%02d   cell (%d,%d) facing %s%n%s%s",
                inv.energyReading(),
                game.isEnergyCritical() ? " !" : "",
                citadel.core.Inventory.WEAPON_NAMES[inv.current], inv.ammoReading(),
                cards.length() > 0 ? "cards " + cards : "",
                inv.speedBoost > 0 ? "  [fast]" : "",
                game.levelSeconds() / 60, game.levelSeconds() % 60,
                game.cellX(), game.cellY(),
                citadel.core.MapFile.DIR_NAMES[game.facingDir()],
                game.isPlayerDead() ? "[YOU ARE DEAD]  "
                        : game.isLevelComplete() ? "[EXIT REACHED]  " : "",
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
