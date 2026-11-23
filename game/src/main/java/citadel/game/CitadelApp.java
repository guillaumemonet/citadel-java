package citadel.game;

import java.io.File;
import java.util.List;

import citadel.core.GameState;
import citadel.core.Level;
import citadel.core.SaveGame;
import citadel.core.Structure;

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
import com.jme3.scene.Node;
import com.jme3.system.AppSettings;

/**
 * The game: the server's front end, and play.
 *
 * The original is three programs chained by a loader — an intro player, SERVER with the menus,
 * and the engine. Here {@link Server} runs SERVER's flow as a script of its own, and hands a
 * level over to this class to play; the engine's answer (finished, killed, or ESC) goes back to
 * it, as {@code do_JakiKoniec} does.
 *
 * <pre>
 *   gradle :game:run                      the front end
 *   gradle :game:run --args=LEVEL05B      straight into a level, then on through the campaign
 * </pre>
 *
 * Controls: the mouse turns, W/S walk, A/D strafe, left mouse or Ctrl fires, E or right mouse
 * uses the wall you are facing, 1..6 pick a weapon, 0 the bare hand, M toggles the automap, P
 * pauses, ESC leaves the level for the hangar, F5 and F9 save and load. In the menus the arrows
 * (or W/S/A/D) move and ENTER, SPACE or a mouse button picks.
 */
public final class CitadelApp extends SimpleApplication
        implements ActionListener, AnalogListener, Server.Host {

    private static final float EYE_HEIGHT = LevelGeometry.WALL_H * 0.55f;
    private static final float MOUSE_TURN = 30f;
    private static final float MAX_PITCH = FastMath.QUARTER_PI;

    /** A map to go straight into, or null for the front end. */
    private final String requested;

    private Level level;
    private GameState game;
    private LevelGeometry geometry;
    /** The world, under the root, so a level change leaves the sound nodes alone. */
    private final Node world = new Node("world");
    private SoundSystem sound;
    private BitmapText hud;
    private Automap automap;
    private Compass compass;
    private HeartMonitor heartMonitor;
    private BombGauge bombGauge;
    /** The original status panel, drawn over the bottom of the view. */
    private HudPanel hudPanelView;
    /** sv_Death, the blood that runs down the view before the death sequence. */
    private BloodWipe bloodWipe;
    /** p_FadeColors, which takes the screen to black after it, or after leaving a level. */
    private Fade fade;

    private boolean forward, back, left, right, turnLeft, turnRight;
    private float mouseTurn;
    private float pitch;

    private ScreenshotAppState shots;
    private int frame;

    private Display display;
    private Music music;
    private Server server;
    private Screens screens;

    public CitadelApp(String requested) {
        this.requested = requested;
    }

    public static void main(String[] args) {
        // exit explicitly: once a Swing dialog has been shown, the AWT thread keeps the JVM alive
        try {
            if (!Provision.ensure()) {
                System.exit(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        String want = (args.length > 0) ? args[0] : null;
        CitadelApp app = new CitadelApp(want);

        AppSettings settings = new AppSettings(true);
        settings.setTitle("Citadel");
        settings.setResolution(1280, 800);
        settings.setVSync(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        try {
            assets = new Assets(assetManager);
        } catch (Exception e) {
            throw new IllegalStateException("cannot open the Citadel disks", e);
        }
        String paceProperty = System.getProperty("citadel.pace");
        if (paceProperty != null) {
            pace = Float.parseFloat(paceProperty);
        }
        try {
            texts = citadel.core.TextTable.loadFrom(assets.data());
        } catch (Exception e) {
            System.out.println("no TEXT file found, using plain wording");
        }
        rootNode.attachChild(world);
        sound = new SoundSystem(assetManager, rootNode, assets);

        viewPort.setBackgroundColor(ColorRGBA.Black);
        cam.setFrustumPerspective(70f, (float) cam.getWidth() / cam.getHeight(), 0.01f, 200f);
        flyCam.setEnabled(false);
        setupInput();
        setupHud();
        interference = new Interference(assetManager, cam.getWidth(), cam.getHeight());
        try {
            dissolve = new Dissolve(assetManager,
                    citadel.core.ChaosTable.loadFrom(assets.data()),
                    cam.getWidth(), cam.getHeight());
            viewPort.addProcessor(dissolve);
        } catch (Exception e) {
            System.out.println("no CHAOSTAC.DAT, teleports will cut straight over");
        }
        automap = new Automap(assetManager, cam.getWidth(), cam.getHeight());
        guiNode.attachChild(automap.picture());
        compass = new Compass(assetManager, cam.getWidth(), cam.getHeight(), automap.side());
        guiNode.attachChild(compass.picture());
        try {
            weaponDisplay = new WeaponDisplay(assetManager, assets,
                    citadel.core.WeaponChange.loadFrom(assets.data()));
        } catch (Exception e) {
            System.out.println("no CHAOSTAB.DAT, weapons will appear at once");
        }
        bloodWipe = new BloodWipe(assetManager, cam.getWidth(), cam.getHeight());
        fade = new Fade(assetManager, cam.getWidth(), cam.getHeight());
        heartMonitor = new HeartMonitor(assetManager);
        guiNode.attachChild(heartMonitor.picture());
        bombGauge = new BombGauge(assetManager, assets, 0f, 0f);
        try {
            hudPanelView = new HudPanel(assetManager, assets, 1, cam.getWidth());
            guiNode.attachChild(hudPanelView.strip());
            guiNode.attachChild(hudPanelView.overlay());
            hudPanelView.place(compass.picture(),
                    HudPanel.COMPASS_X, HudPanel.COMPASS_ROW, 32, 27);
            hudPanelView.place(heartMonitor.picture(),
                    HudPanel.HEART_X, HudPanel.HEART_ROW,
                    citadel.core.Heart.WIDTH, citadel.core.Heart.HEIGHT);
            hudPanelView.place(bombGauge.picture(),
                    HudPanel.BOMB_X, HudPanel.BOMB_ROW,
                    citadel.core.BombGfx.WIDTH, citadel.core.BombGfx.HEIGHT);
        } catch (Exception e) {
            System.out.println("no status panel artwork, keeping the plain readout: " + e);
        }
        layer(automap.picture(), Z_FURNITURE);

        screens = new Screens(assetManager, guiNode,
                assetManager.loadFont("Interface/Fonts/Default.fnt"),
                assets, cam.getWidth(), cam.getHeight());
        display = new Display(assetManager, cam.getWidth(), cam.getHeight());
        music = new Music(rootNode, assets.data(), this::enqueue);

        String screen = System.getProperty("citadel.screen");
        String shotDir = System.getProperty("citadel.screenshot");
        String start = screen;
        if (screen == null && requested != null) {
            String map = assets.findMap(requested);
            if (map == null) {
                throw new IllegalStateException("no map matching '" + requested + "' on "
                        + assets.data());
            }
            start = "play:" + map;
        }
        try {
            server = new Server(this, assets.data(), display,
                    citadel.core.KaneFont.loadFrom(assets.data()),
                    hangarView(), mapView(), assets.cover().argb, start);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the server's screens", e);
        }
        mode = Mode.FRONT;
        setHudVisible(false);
        display.attach(guiNode);
        server.start();

        if (shotDir != null) {
            shots = new ScreenshotAppState(shotDir, screen != null ? screen
                    : requested != null ? requested : "front", 0);
            stateManager.attach(shots);
            setDisplayStatView(false);
            setDisplayFps(false);
        }
    }

    private HangarView hangarView() throws Exception {
        citadel.core.RawScreen h = assets.raw("INTRO/GFX/Hangar_ENG.rawb", 320, 6, 128);
        return new HangarView(display, citadel.core.KaneFont.loadFrom(assets.data()),
                h.indices(), palette12(h.palette, 32), assets.data().read("GAME/Store/HangarTab.dat"));
    }

    private MapView mapView() throws Exception {
        citadel.core.RawScreen m = assets.raw("INTRO/GFX/MAPKA.rawb", 320, 6, 128);
        return new MapView(display, citadel.core.HudGfx.loadFrom(assets.data()),
                m.indices(), palette12(m.palette, 32), assets.arrowIndices(), 11);
    }

    private static int[] palette12(int[] argb, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = Display.rgb12(argb[i]);
        }
        return out;
    }

    // --------------------------------------------------------------- the host

    @Override
    public void playMusic(String path) {
        music.play(path);
    }

    @Override
    public void stopMusic() {
        music.stop();
    }

    /**
     * Saves go where {@code citadel.save} says, or into the per-user folder. The disks are never
     * written to; the first time round the file is read from disk 4 instead.
     */
    @Override
    public File saveFile() {
        String wanted = System.getProperty("citadel.save");
        return wanted != null ? new File(wanted) : new File(Provision.userHome(), "Savegame.DAT");
    }

    @Override
    public void quit() {
        stop();
    }

    /**
     * The engine's start: STRUCTURE into the level's state (energy, Glowica, the guns, the
     * difficulty), then what the engine does with them.
     */
    @Override
    public void startLevel(String mapName, Structure structure, boolean training) {
        try {
            level = assets.level(mapName);
        } catch (Exception e) {
            System.out.println("cannot load " + mapName + ": " + e);
            server.levelEnded(Server.Result.ESCAPE, 0);
            return;
        }
        currentMap = mapName;
        levelStructure = structure;
        levelStart = structure.copy();
        this.training = training;
        game = new GameState(level);
        game.setTexts(texts);
        game.setLoopHz(pace);
        structure.toGame(game);
        game.beginLevel();
        String at = System.getProperty("citadel.at");
        if (at != null) {
            String[] parts = at.split(",");
            game.posX = Integer.parseInt(parts[0].trim()) * Level.CELL_UNITS + Level.CELL_UNITS / 2;
            game.posY = Integer.parseInt(parts[1].trim()) * Level.CELL_UNITS + Level.CELL_UNITS / 2;
            if (parts.length > 2) {
                game.angle = Integer.parseInt(parts[2].trim()) & 511;
            }
        }
        int complex = Assets.complexOf(mapName);
        world.detachAllChildren();
        geometry = new LevelGeometry(level, assets, complex);
        world.attachChild(geometry.build());
        try {
            if (hudPanelView != null) {
                hudPanelView.setComplex(complex);
            }
            compass.setColour(assets.panelColour(complex, 1));
        } catch (Exception e) {
            System.out.println("no palette for complex " + complex + ": " + e);
        }
        if (dissolve != null) {
            dissolve.clearBackdrop();
        }
        fade.clear();
        bloodWipe.clear();
        exit = Exit.NONE;
        paused = false;
        pitch = 0f;
        forward = back = left = right = turnLeft = turnRight = false;
        firing = false;
        mouseTurn = 0f;
        display.detach();
        mode = Mode.PLAYING;
        setHudVisible(true);
        inputManager.setCursorVisible(false);
        mouseSettle = MOUSE_SETTLE;
        if ("top".equals(System.getProperty("citadel.cam"))) {
            topDownCamera();
        } else {
            syncCamera();
        }
        System.out.println("loaded " + level + "  (complex " + complex + ")");
    }

    /**
     * The engine's way out: STRUCTURE gets energy, Glowica, the kills, the time and the guns
     * back, and the server carries on with what the engine returned.
     */
    private void finishLevel(Server.Result r, int slot) {
        levelStructure.fromGame(game);
        if (dissolve != null) {
            dissolve.clearBackdrop();
        }
        fade.clear();
        bloodWipe.clear();
        interference.update(guiNode, false, 0);
        mode = Mode.FRONT;
        setHudVisible(false);
        screens.hide();
        display.black();
        display.attach(guiNode);
        inputManager.setCursorVisible(true);
        server.levelEnded(r, slot);
    }

    // --------------------------------------------------------------- input

    /**
     * CheckCodes watches every key the player presses, so the cheats need the raw stream rather
     * than the named actions. The engine stores movement keys in the same ring, which is why the
     * overlap with W, A, S, D and E does not matter. The front end counts keys held for the
     * scrollers, which stop while one is down.
     */
    private void setupRawKeys() {
        inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
            @Override
            public void onKeyEvent(com.jme3.input.event.KeyInputEvent evt) {
                if (evt.isRepeating()) {
                    return;
                }
                if (mode == Mode.FRONT && server != null) {
                    FrontScript.Input in = server.input;
                    if (evt.isPressed()) {
                        in.keysHeld++;
                        in.key = true;
                    } else {
                        in.keysHeld = Math.max(0, in.keysHeld - 1);
                    }
                    return;
                }
                if (mode != Mode.PLAYING || !evt.isPressed() || paused) {
                    return;
                }
                char c = evt.getKeyChar();
                if (c == 0 || game == null) {
                    return;
                }
                // cc_z: Z turns the interference burst on and off, as in the original
                if (c == 'z' || c == 'Z') {
                    game.toggleSzum();
                }
                game.typeLetter(c);
            }

            @Override
            public void beginInput() {
            }

            @Override
            public void endInput() {
            }

            @Override
            public void onJoyAxisEvent(com.jme3.input.event.JoyAxisEvent evt) {
            }

            @Override
            public void onJoyButtonEvent(com.jme3.input.event.JoyButtonEvent evt) {
            }

            @Override
            public void onMouseMotionEvent(com.jme3.input.event.MouseMotionEvent evt) {
            }

            @Override
            public void onMouseButtonEvent(com.jme3.input.event.MouseButtonEvent evt) {
            }

            @Override
            public void onTouchEvent(com.jme3.input.event.TouchEvent evt) {
            }
        });
    }

    private void setupInput() {
        // ESC is the game's own: out of a level, or a fire key in the menus
        if (inputManager.hasMapping(INPUT_MAPPING_EXIT)) {
            inputManager.deleteMapping(INPUT_MAPPING_EXIT);
        }
        inputManager.setCursorVisible(false);
        setupRawKeys();
        inputManager.addMapping("fwd", new KeyTrigger(KeyInput.KEY_W), new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping("back", new KeyTrigger(KeyInput.KEY_S), new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addMapping("left", new KeyTrigger(KeyInput.KEY_A));
        inputManager.addMapping("right", new KeyTrigger(KeyInput.KEY_D));
        inputManager.addMapping("turnL", new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping("turnR", new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping("use", new KeyTrigger(KeyInput.KEY_E),
                new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        inputManager.addMapping("space", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("fire", new KeyTrigger(KeyInput.KEY_LCONTROL),
                new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addMapping("map", new KeyTrigger(KeyInput.KEY_M), new KeyTrigger(KeyInput.KEY_TAB));
        inputManager.addMapping("pause", new KeyTrigger(KeyInput.KEY_P));
        inputManager.addMapping("escape", new KeyTrigger(KeyInput.KEY_ESCAPE));
        inputManager.addMapping("save", new KeyTrigger(KeyInput.KEY_F5));
        inputManager.addMapping("load", new KeyTrigger(KeyInput.KEY_F9));
        inputManager.addMapping("confirm", new KeyTrigger(KeyInput.KEY_RETURN),
                new KeyTrigger(KeyInput.KEY_NUMPADENTER));
        inputManager.addMapping("slower", new KeyTrigger(KeyInput.KEY_MINUS),
                new KeyTrigger(KeyInput.KEY_SUBTRACT));
        inputManager.addMapping("faster", new KeyTrigger(KeyInput.KEY_EQUALS),
                new KeyTrigger(KeyInput.KEY_ADD));
        inputManager.addListener(this,
                "fwd", "back", "left", "right", "turnL", "turnR", "use", "space", "fire", "map",
                "pause", "escape", "save", "load", "confirm", "slower", "faster");

        // 1..6 select a weapon, 7..9 the red, green and blue cards, 0 goes back to the bare
        // hand, as F1..F0 do in the original.
        int[] keys = {KeyInput.KEY_0, KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3,
                KeyInput.KEY_4, KeyInput.KEY_5, KeyInput.KEY_6, KeyInput.KEY_7,
                KeyInput.KEY_8, KeyInput.KEY_9};
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
        if (mode == Mode.FRONT) {
            serveFrontKey(name, pressed);
            return;
        }
        if (mode == Mode.SLOTS) {
            if (pressed) {
                serveSlotKey(name);
            }
            return;
        }
        if (exit != Exit.NONE || game.isPlayerDead()) {
            return;                                 // tst sv_EndLevel / bmi cc_NoKey
        }
        if ("pause".equals(name) && pressed) {
            togglePause();
            return;
        }
        if ("map".equals(name) && pressed) {
            toggleMap();
            return;
        }
        if ("escape".equals(name) && pressed) {
            finishLevel(Server.Result.ESCAPE, 0);   // cc_esc: back to s
            return;
        }
        if (paused) {
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
        } else if (("use".equals(name) || "space".equals(name)) && pressed) {
            game.use();
        } else if ("slower".equals(name) && pressed) {
            setPace(game.loopHz() - 2f);
        } else if ("faster".equals(name) && pressed) {
            setPace(game.loopHz() + 2f);
        } else if ("save".equals(name) && pressed && !training) {
            openSlots(true);
        } else if ("load".equals(name) && pressed) {
            openSlots(false);
        } else if ("fire".equals(name)) {
            firing = pressed;
            game.holdTrigger(pressed);
            // the machinegun fires from the loop, once a pass, while the button is held
            if (pressed && game.inventory.current != MACHINEGUN) {
                game.fire();
            }
        } else if (name.startsWith("weapon") && pressed) {
            int slot = name.charAt(name.length() - 1) - '0';
            // ci_NewWeapon refuses silently
            game.inventory.select(slot);
        }
    }

    /**
     * The server's keys (NewLev2) and GetDirs: the cursor keys latch el_DIR, RETURN, ENTER,
     * SPACE and ESC are el_DIR+8 in KeyMode 0 and 3, and so is a button.
     */
    private void serveFrontKey(String name, boolean pressed) {
        FrontScript.Input in = server.input;
        boolean mouse = "fire".equals(name) || "use".equals(name);
        if (mouse) {
            in.buttonHeld = pressed;
        }
        if ("space".equals(name)) {
            in.spaceHeld = pressed;
        }
        if (!pressed) {
            return;
        }
        if ("escape".equals(name) && server.onTitle) {
            stop();                                 // the port's way out of the game
            return;
        }
        switch (name) {
            case "fwd" -> in.up = true;
            case "back" -> in.down = true;
            case "left", "turnL" -> in.left = true;
            case "right", "turnR" -> in.right = true;
            case "confirm", "escape" -> {
                in.fire = true;
                in.quitKey = true;
            }
            case "space" -> in.fire = true;
            case "fire", "use" -> {
                in.fire = true;
                in.button = true;
            }
            default -> {
                // nothing the server reads
            }
        }
    }

    /**
     * The machinegun keeps firing while the button is held; every other weapon needs the trigger
     * released, which is what the cc_RequestTab dance in Check_RMB amounts to.
     */
    private boolean firing;
    private static final int MACHINEGUN = 3;

    private Assets assets;
    private String currentMap;
    /** STRUCTURE, which the level writes back into when it is over. */
    private Structure levelStructure;
    /** STRUCTURE as the level began, which the port's quick save keeps. */
    private Structure levelStart;
    private boolean training;

    private citadel.core.TextTable texts;

    /** What the application is doing: the server's screens, play, or the quick save picker. */
    private enum Mode { FRONT, PLAYING, SLOTS }

    private Mode mode = Mode.FRONT;

    // ------------------------------------------------- the port's quick save and load

    private boolean slotSave;
    private int slotIndex;

    /**
     * F5 and F9, which the original does not have: it saves only in ENDLEVEL_PART and loads only
     * from the hangar. F5 writes the slot that ENDLEVEL_PART would have written after the level
     * before this one, so loading it starts this level over as it began; F9 loads a slot the way
     * LOAD GAME does.
     */
    private void openSlots(boolean save) {
        slotSave = save;
        slotIndex = 0;
        mode = Mode.SLOTS;
        setHudVisible(false);
        inputManager.setCursorVisible(true);
        paintSlots();
    }

    private SaveGame slots() {
        File f = saveFile();
        try {
            if (f.isFile()) {
                return SaveGame.load(f);
            }
            return SaveGame.of(assets.data().read("GAME/Store/Savegame.DAT"));
        } catch (Exception e) {
            return SaveGame.empty();
        }
    }

    private void paintSlots() {
        SaveGame save = slots();
        List<String> lines = new java.util.ArrayList<String>();
        for (int i = 0; i < SaveGame.SLOT_COUNT; i++) {
            lines.add(String.format("%d.  %s", i + 1, save.isEmpty(i) ? "- free -" : save.name(i)));
        }
        screens.showList(slotSave ? "SAVE GAME" : "LOAD GAME", lines, slotIndex,
                "W/S or arrows to choose    ENTER to confirm    ESC to go back");
    }

    private void serveSlotKey(String name) {
        if ("fwd".equals(name)) {
            slotIndex = (slotIndex + SaveGame.SLOT_COUNT - 1) % SaveGame.SLOT_COUNT;
            paintSlots();
        } else if ("back".equals(name)) {
            slotIndex = (slotIndex + 1) % SaveGame.SLOT_COUNT;
            paintSlots();
        } else if ("escape".equals(name) || "use".equals(name)) {
            backToPlay();
        } else if ("confirm".equals(name)) {
            SaveGame save = slots();
            if (slotSave) {
                Structure s = levelStart.copy();
                s.setLevel(s.level() - 1);
                save.store(slotIndex, s, SaveGame.slotName(s.complex(), s.level()), null);
                try {
                    save.save(saveFile());
                } catch (Exception e) {
                    System.out.println("cannot save: " + e.getMessage());
                }
                backToPlay();
            } else if (save.isOccupied(slotIndex)) {
                screens.hide();
                mode = Mode.PLAYING;
                finishLevel(Server.Result.LOAD, slotIndex);
            }
        }
    }

    private void backToPlay() {
        screens.hide();
        mode = Mode.PLAYING;
        setHudVisible(true);
        inputManager.setCursorVisible(false);
        mouseSettle = MOUSE_SETTLE;
    }

    /** The in-game overlays only belong on screen while the world is up. */
    private void setHudVisible(boolean visible) {
        com.jme3.scene.Spatial.CullHint hint = visible
                ? com.jme3.scene.Spatial.CullHint.Never
                : com.jme3.scene.Spatial.CullHint.Always;
        world.setCullHint(visible ? com.jme3.scene.Spatial.CullHint.Inherit : hint);
        if (hud != null) {
            hud.setCullHint(hint);
        }
        if (hudPanel != null) {
            hudPanel.setCullHint(hint);
        }
        if (hudPanelView != null) {
            hudPanelView.strip().setCullHint(hint);
            hudPanelView.overlay().setCullHint(hint);
        }
        if (weaponDisplay != null && weaponDisplay.picture() != null) {
            weaponDisplay.picture().setCullHint(hint);
        }
        if (heartMonitor != null) {
            heartMonitor.picture().setCullHint(hint);
        }
        if (bombGauge != null) {
            bombGauge.picture().setCullHint(hint);
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

    // ---------------------------------------------------------------- pause

    /**
     * True while GameState holds MAIN_LOOP ({@link GameState#isHalted}): sv_Pause from P, or the
     * map. The rules keep the pause, its SCROLL 37, 38 and 41 and the level clock; this only
     * mirrors it so input is ignored meanwhile.
     */
    private boolean paused;

    private void togglePause() {
        game.togglePause();
        releaseControls();
    }

    /** cc_m: M or TAB, paused or not. The map holds the game for as long as it is up (ServeMap). */
    private void toggleMap() {
        game.toggleMap();
        if (automap.isVisible() != game.isMapOn()) {
            automap.toggle();
        }
        releaseControls();
    }

    private void releaseControls() {
        paused = game.isHalted();
        forward = back = left = right = turnLeft = turnRight = false;
        firing = false;
        game.holdTrigger(false);
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

    private void serveAutoFire() {
        game.holdTrigger(firing);
    }

    /**
     * Frames to ignore the mouse for after the cursor is hidden. Hiding it warps the pointer to
     * the middle of the window, and that warp arrives as one enormous relative delta - enough to
     * drive the pitch straight to its clamp, so the game starts looking at the floor.
     */
    private static final int MOUSE_SETTLE = 3;
    private int mouseSettle;

    @Override
    public void onAnalog(String name, float value, float tpf) {
        if (mode != Mode.PLAYING || mouseSettle > 0 || paused) {
            return;
        }
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

    /** VBLANKs owed to the server's script. */
    private float vblanks;

    @Override
    public void simpleUpdate(float tpf) {
        if (shots != null) {
            serveShot();
        }
        if (mode == Mode.FRONT) {
            serveFront(tpf);
            return;
        }
        if (mode != Mode.PLAYING) {
            return;
        }
        if (paused) {
            game.update(0f, 0f, 0f, Math.min(tpf, 0.1f));   // only the level clock runs
            updateHud();
            return;
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

        if (exit == Exit.NONE) {
            serveAutoFire();
            game.update(fwd, strafe, turn, Math.min(tpf, 0.1f));
        }

        if (game.isPlayerDead()) {
            serveDying(tpf);
            if (mode != Mode.PLAYING) {
                return;
            }
        } else if (game.isLevelComplete() || exit != Exit.NONE) {
            serveExit(tpf);
            if (mode != Mode.PLAYING) {
                return;
            }
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
        bombGauge.update(guiNode, game, Assets.complexOf(currentMap));
        interference.update(guiNode, game.szumActive(), game.szumFrame());
        if (weaponDisplay != null) {
            weaponDisplay.sync(guiNode, game, Assets.complexOf(currentMap));
            weaponDisplay.update(tpf);
            if (hudPanelView != null && weaponDisplay.picture() != null) {
                hudPanelView.place(weaponDisplay.picture(),
                        HudPanel.WEAPON_X, HudPanel.WEAPON_ROW, 32, 27);
            }
        }
        sound.serve(game);
        if (exit == Exit.NONE) {
            serveTeleport(tpf);
        }
        if (!holdingCamera && !"top".equals(System.getProperty("citadel.cam"))) {
            syncCamera();
        }
        if (mouseSettle > 0) {
            mouseSettle--;
        }
        lastTpf = tpf;
        updateHud();
    }

    /** The server's script, one VBLANK at a time, and whatever it asked of this thread. */
    private void serveFront(float tpf) {
        int owed;
        if (shots != null) {
            owed = Integer.getInteger("citadel.vblanks", 4);
        } else {
            vblanks += tpf * GameState.FRAMES_PER_SECOND;
            owed = (int) Math.min(vblanks, 6f);
            vblanks -= (int) vblanks;
        }
        for (int i = 0; i < owed && mode == Mode.FRONT; i++) {
            server.tick();
            for (Runnable r : server.drain()) {
                r.run();
            }
        }
        if (server.isFinished()) {
            stop();
        }
        display.upload();
    }

    /** The verification path: render a few frames, save one, and quit whatever the mode. */
    private void serveShot() {
        frame++;
        // -Pcheat=ALIBABA types a code on the verification path, since the shot harness sends
        // no keys of its own; -Phurt=999 kills the player, so the death can be looked at
        String cheat = System.getProperty("citadel.cheat");
        if (cheat != null && frame == 3 && mode == Mode.PLAYING) {
            for (char ch : cheat.toCharArray()) {
                game.typeLetter(ch);
            }
        }
        String hurt = System.getProperty("citadel.hurt");
        if (hurt != null && frame == 3 && mode == Mode.PLAYING) {
            game.inventory.hurt(Integer.parseInt(hurt));
        }
        String walk = System.getProperty("citadel.walk");
        forward = walk != null && frame < Integer.parseInt(walk);
        // -Pscreen=gameover presses fire once zabity is up, to get past it
        if ("gameover".equals(System.getProperty("citadel.screen")) && frame == 16) {
            server.input.fire = true;
        }
        // -Pframe=N moves the shot later, which is how a transition gets caught mid-flight
        int shotAt = Integer.getInteger("citadel.frame", 20);
        if (frame == shotAt) {
            shots.takeScreenshot();
        } else if (frame > shotAt + 5) {
            stop();
        }
    }

    // ------------------------------------------------------- leaving a level

    /** Where the way out of a level has got to. */
    private enum Exit { NONE, CAPTURE, DISSOLVE, FADE }

    private Exit exit = Exit.NONE;

    /**
     * EndLevel on reaching the exit: the window cleared in the hidden screen, TELEPORT dissolving
     * the view into it, p_FadeColors, and back to the server with a good end.
     */
    private void serveExit(float tpf) {
        switch (exit) {
            case NONE -> {
                if (dissolve != null) {
                    dissolve.arm();
                    exit = Exit.CAPTURE;
                } else {
                    fade.start(guiNode);
                    exit = Exit.FADE;
                }
            }
            case CAPTURE -> {
                if (dissolve.isCaptured()) {
                    dissolve.startIntoBlack(guiNode);
                    exit = Exit.DISSOLVE;
                }
            }
            case DISSOLVE -> {
                dissolve.update(tpf);
                if (!dissolve.isRunning()) {
                    fade.start(guiNode);
                    exit = Exit.FADE;
                }
            }
            case FADE -> {
                fade.update(tpf);
                if (fade.isDone()) {
                    finishLevel(training ? Server.Result.ESCAPE : Server.Result.GOOD, 0);
                }
            }
        }
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

    /**
     * sv_Death then p_FadeColors: blood runs down the view for about four and a half seconds,
     * then the screen goes to black, and only then does the server run the death sequence.
     *
     * EndLevel returns 0 while this is running, which is how the engine holds the game open; here
     * the same job is done by staying in PLAYING until both stages have finished.
     */
    private void serveDying(float tpf) {
        if (!bloodWipe.isRunning() && !bloodWipe.isDone()) {
            bloodWipe.start(guiNode);
        }
        bloodWipe.update(tpf);
        if (!bloodWipe.isDone()) {
            return;
        }
        if (!fade.isRunning() && !fade.isDone()) {
            fade.start(guiNode);
        }
        fade.update(tpf);
        if (fade.isDone()) {
            finishLevel(Server.Result.DEAD, 0);
        }
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
     * buffer, then COMPASS, UpdateMap and the readout are drawn over the result. The server's
     * screen covers everything.
     */
    static final float Z_DISSOLVE = 1f;
    static final float Z_INTERFERENCE = 2f;
    /** The status panel artwork, which the widgets sit in. */
    static final float Z_PANEL = 3f;
    static final float Z_FURNITURE = 4f;
    static final float Z_TEXT = 5f;
    static final float Z_DISPLAY = 10f;

    private static void layer(com.jme3.scene.Spatial s, float z) {
        com.jme3.math.Vector3f t = s.getLocalTranslation();
        s.setLocalTranslation(t.x, t.y, z);
    }

    /** Dark band the readout sits on, since white text on a lit ceiling is barely there. */
    private com.jme3.ui.Picture hudPanel;

    /** The weapon in hand, and ci_DrawWeapon's reveal when it changes. */
    private WeaponDisplay weaponDisplay;

    private void setupHud() {
        float bandHeight = 30f;
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

    /** tpf of the frame being served, for the readouts that run on the VBLANK clock. */
    private float lastTpf;

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
        if (hudPanelView != null) {
            hudPanelView.update(game, message.toUpperCase(), lastTpf);
        }
        // the panel carries energy, ammunition, the cards and the message now, so this line is
        // only the things the original never showed: the clock, where you are, and what is in hand
        hud.setText(String.format("%s%s   %d:%02d   cell (%d,%d) facing %s%s",
                citadel.core.Inventory.WEAPON_NAMES[inv.current],
                inv.speedBoost > 0 ? "  [fast]" : "",
                game.levelSeconds() / 60, game.levelSeconds() % 60,
                game.cellX(), game.cellY(),
                citadel.core.MapFile.DIR_NAMES[game.facingDir()],
                paused ? "   [PAUSED]"
                        : game.isPlayerDead() ? "   [YOU ARE DEAD]"
                        : game.isLevelComplete() ? "   [EXIT REACHED]" : ""));
    }
}
