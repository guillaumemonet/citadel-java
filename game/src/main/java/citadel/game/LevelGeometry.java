package citadel.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import citadel.core.GfxLayout;
import citadel.core.Level;

import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.control.BillboardControl;
import com.jme3.scene.shape.Quad;
import com.jme3.util.BufferUtils;

/**
 * Turns a {@link Level} into jMonkeyEngine geometry.
 *
 * One grid cell is one world unit and walls are one unit tall, so the level spans 64 x 64 units
 * in the XZ plane with Y up. The engine's N/E/S/W map onto +Z/+X/-Z/-X, which follows from
 * rm_FixDoor stepping N to the cell at +512 bytes, i.e. the next row.
 *
 * Wall faces are emitted per cell and oriented to be seen from inside that cell, so the two sides
 * of a shared wall are two back to back quads and each cell keeps its own texture. Back face
 * culling means only one of the pair is ever drawn.
 *
 * Quads are batched by texture, which gives at most 27 wall draw calls for a whole level.
 */
public final class LevelGeometry {

    /** Edge of one cell, in world units. */
    public static final float CELL = 1f;
    /** Wall height, in world units. */
    public static final float WALL_H = 1f;

    private final Level level;
    private final Assets assets;
    private final int complex;

    public LevelGeometry(Level level, Assets assets, int complex) {
        this.level = level;
        this.assets = assets;
        this.complex = complex;
    }

    private final Node root = new Node("level");
    /** Door faces, keyed by {@link #faceKey}, animated rather than rebuilt. */
    private final Map<Integer, Geometry> doorFaces = new HashMap<Integer, Geometry>();
    /** The texture each door face had when shut, so a rebuild does not pick up its open value. */
    private final Map<Integer, Integer> doorTextures = new HashMap<Integer, Integer>();

    public static int faceKey(int x, int y, int dir) {
        return ((y * Level.GRID + x) << 2) | dir;
    }

    public Node build() {
        root.setName("level:" + level.name);
        root.detachAllChildren();
        doorFaces.clear();
        enemyViews.clear();
        enemyNode = null;
        root.attachChild(buildWalls());
        root.attachChild(buildFloorAndCeiling(reachableCells()));
        root.attachChild(buildSprites());
        return root;
    }

    /** Re-runs the whole build in place, after a trigger has changed a wall, item or column. */
    public void rebuild() {
        build();
    }

    /**
     * Slides each moving door up into the ceiling. The original redraws a partially open door in
     * place (oc_DrawOpen); a vertical slide is the natural 3D equivalent and needs no new mesh.
     */
    public void syncDoors(citadel.core.GameState game) {
        for (citadel.core.GameState.DoorSlot slot : game.doorSlots()) {
            if (!slot.isActive()) {
                continue;
            }
            float lift = slot.openFraction() * WALL_H;
            raise(slot.cellX(), slot.cellY(), slot.dir(), lift);
            int nx = slot.cellX() + Level.DX[slot.dir()];
            int ny = slot.cellY() + Level.DY[slot.dir()];
            if (Level.inside(nx, ny)) {
                raise(nx, ny, Level.OPPOSITE[slot.dir()], lift);
            }
        }
    }

    private void raise(int x, int y, int dir, float lift) {
        Geometry g = doorFaces.get(faceKey(x, y, dir));
        if (g != null) {
            g.setLocalTranslation(0f, lift, 0f);
        }
    }

    // --------------------------------------------------------------- walls

    private Node buildWalls() {
        Node node = new Node("walls");
        Map<Integer, QuadBatch> byTexture = new HashMap<Integer, QuadBatch>();
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                for (int d = 0; d < 4; d++) {
                    int tex = level.wallSprite(x, y, d);
                    int key = faceKey(x, y, d);
                    Integer remembered = doorTextures.get(key);
                    if (remembered != null) {
                        // a known door: always drawn shut, then slid open by syncDoors
                        addDoorFace(node, x, y, d, remembered);
                        continue;
                    }
                    if (tex < 0) {
                        continue;
                    }
                    if (level.doorState(x, y, d) != Level.DoorState.NONE) {
                        doorTextures.put(key, tex);
                        addDoorFace(node, x, y, d, tex);
                        continue;
                    }
                    QuadBatch batch = byTexture.get(tex);
                    if (batch == null) {
                        batch = new QuadBatch();
                        byTexture.put(tex, batch);
                    }
                    addWallFace(batch, x, y, d, level.wallMirrored(x, y, d));
                }
            }
        }

        for (Map.Entry<Integer, QuadBatch> e : byTexture.entrySet()) {
            int tex = e.getKey();
            Material mat = assets.exists(complex, "wall", tex)
                    ? assets.material(complex, "wall", tex, false)
                    : assets.material(complex, "wall", 0, false);
            Geometry g = new Geometry("wall:" + tex, e.getValue().toMesh());
            g.setMaterial(mat);
            node.attachChild(g);
        }
        return node;
    }

    /** A door face gets its own geometry so it can slide without touching any batched mesh. */
    private void addDoorFace(Node node, int x, int y, int dir, int texture) {
        QuadBatch batch = new QuadBatch();
        addWallFace(batch, x, y, dir, level.wallMirrored(x, y, dir));
        Geometry g = new Geometry("door:" + x + "," + y + "," + dir, batch.toMesh());
        g.setMaterial(assets.exists(complex, "wall", texture)
                ? assets.material(complex, "wall", texture, false)
                : assets.material(complex, "wall", 0, false));
        node.attachChild(g);
        doorFaces.put(faceKey(x, y, dir), g);
    }

    /**
     * Emits the face of cell (cx, cy) in direction d, wound so its normal points back into the
     * cell. The two ground corners a and b run anticlockwise seen from above, which makes the
     * winding below produce the right normal for all four directions.
     */
    private void addWallFace(QuadBatch batch, int cx, int cy, int d, boolean mirrored) {
        float x0 = cx * CELL, x1 = (cx + 1) * CELL;
        float z0 = cy * CELL, z1 = (cy + 1) * CELL;
        float ax, az, bx, bz;
        switch (d) {
            case 0:  ax = x1; az = z1; bx = x0; bz = z1; break; // N, +Z
            case 1:  ax = x1; az = z0; bx = x1; bz = z1; break; // E, +X
            case 2:  ax = x0; az = z0; bx = x1; bz = z0; break; // S, -Z
            default: ax = x0; az = z1; bx = x0; bz = z0; break; // W, -X
        }
        batch.addQuad(
                ax, WALL_H, az,
                bx, WALL_H, bz,
                bx, 0f, bz,
                ax, 0f, az,
                mirrored);
    }

    // ---------------------------------------------------- floor and ceiling

    private Node buildFloorAndCeiling(boolean[] reachable) {
        QuadBatch floor = new QuadBatch();
        QuadBatch ceiling = new QuadBatch();
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                if (!reachable[x + y * Level.GRID]) {
                    continue;
                }
                float x0 = x * CELL, x1 = (x + 1) * CELL;
                float z0 = y * CELL, z1 = (y + 1) * CELL;
                // floor, normal +Y
                floor.addQuad(x0, 0f, z0, x1, 0f, z0, x1, 0f, z1, x0, 0f, z1, false);
                // ceiling, normal -Y: same corners, reversed
                ceiling.addQuad(x0, WALL_H, z1, x1, WALL_H, z1, x1, WALL_H, z0, x0, WALL_H, z0, false);
            }
        }

        Node node = new Node("floors");
        Geometry f = new Geometry("floor", floor.toMesh());
        f.setMaterial(assets.material(complex, "wall", Level.FLOOR_TEXTURE, false));
        node.attachChild(f);
        Geometry c = new Geometry("ceiling", ceiling.toMesh());
        c.setMaterial(assets.material(complex, "wall", Level.CEILING_TEXTURE, false));
        node.attachChild(c);
        return node;
    }

    /**
     * Flood fills the cells the player can stand in, so floors and ceilings are only built where
     * the level actually exists rather than across the whole 64 x 64 grid.
     *
     * Doors count as passable because they open during play. Seeds are the spawn, the exit, both
     * ends of every teleport and every enemy position, which between them cover the pockets that
     * are only reachable by teleport.
     */
    private boolean[] reachableCells() {
        boolean[] seen = new boolean[Level.GRID * Level.GRID];
        Deque<int[]> queue = new ArrayDeque<int[]>();
        seed(queue, seen, level.spawnCellX, level.spawnCellY);
        seed(queue, seen, level.exitCellX, level.exitCellY);
        for (Level.Teleport t : level.teleports()) {
            seed(queue, seen, t.fromX, t.fromY);
            seed(queue, seen, t.toX, t.toY);
        }
        for (Level.EnemySpawn e : level.enemySpawns()) {
            seed(queue, seen, e.cellX(), e.cellY());
        }
        for (Level.Trigger t : level.triggers()) {
            seed(queue, seen, t.cellX, t.cellY);
        }

        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int d = 0; d < 4; d++) {
                if (level.hasWall(c[0], c[1], d)
                        && level.doorState(c[0], c[1], d) == Level.DoorState.NONE) {
                    continue;
                }
                seed(queue, seen, c[0] + Level.DX[d], c[1] + Level.DY[d]);
            }
        }
        return seen;
    }

    private static void seed(Deque<int[]> queue, boolean[] seen, int x, int y) {
        if (!Level.inside(x, y) || seen[x + y * Level.GRID]) {
            return;
        }
        seen[x + y * Level.GRID] = true;
        queue.add(new int[]{x, y});
    }

    // ------------------------------------------------------------- sprites

    /**
     * Columns, items, corpses and enemies, as camera facing quads. The engine draws these as
     * scaled sprites rather than geometry, so a billboard is the faithful equivalent.
     */
    private Node buildSprites() {
        Node node = new Node("sprites");
        for (int y = 0; y < Level.GRID; y++) {
            for (int x = 0; x < Level.GRID; x++) {
                int col = level.column(x, y);
                if (col != 0) {
                    addColumn(node, x, y, col);
                }
                int item = level.item(x, y);
                if (item != 0) {
                    addSprite(node, "item", GfxLayout.spriteIndexFor(item),
                            x, y, 0.5f, 0.5f, 0.25f, "item");
                }
            }
        }
        return node;
    }

    /** Live enemies, rebuilt from the simulation each frame rather than baked into the level. */
    private final Map<Integer, Geometry> enemyViews = new HashMap<Integer, Geometry>();
    private Node enemyNode;

    /**
     * Positions one billboard per living enemy and picks its frame from sv_EnemyDirTab, so an
     * enemy walking away shows its back.
     */
    public void syncEnemies(citadel.core.GameState game) {
        if (enemyNode == null) {
            enemyNode = new Node("enemies");
            root.attachChild(enemyNode);
        }
        for (citadel.core.Enemy e : game.enemies()) {
            Geometry g = enemyViews.get(e.slot);
            if (e.isDead()) {
                if (g != null) {
                    g.getParent().removeFromParent();
                    enemyViews.remove(e.slot);
                }
                continue;
            }
            String group = (e.species == 0) ? "enemy1" : "enemy2";
            int frame = e.frame(game.posX, game.posY);
            if (!assets.exists(complex, group, frame)) {
                frame = 0;
            }
            if (g == null) {
                g = new Geometry("enemy" + e.slot, new Quad(0.5f, 0.9f));
                g.setLocalTranslation(-0.25f, -0.45f, 0f);
                g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
                Node pivot = new Node("enemy" + e.slot);
                pivot.attachChild(g);
                pivot.addControl(new BillboardControl());
                enemyNode.attachChild(pivot);
                enemyViews.put(e.slot, g);
            }
            g.setMaterial(assets.material(complex, group, frame, true));
            g.getParent().setLocalTranslation(
                    e.x / (float) Level.CELL_UNITS, 0.45f, e.y / (float) Level.CELL_UNITS);
        }
    }

    private void addColumn(Node node, int x, int y, int cellValue) {
        int sprite = GfxLayout.spriteIndexFor(cellValue);
        GfxLayout.ColumnKind kind = GfxLayout.columnKind(cellValue);
        switch (kind) {
            case FULL:
                addSprite(node, "column", sprite, x, y, 0.6f, WALL_H, WALL_H / 2f, "col");
                break;
            case RAISED:
                addSprite(node, "column", sprite, x, y, 0.6f, WALL_H / 2f, WALL_H * 0.75f, "col");
                break;
            default:
                addSprite(node, "column", sprite, x, y, 0.6f, WALL_H / 2f, WALL_H * 0.25f, "col");
                break;
        }
    }

    private void addSprite(Node node, String group, int sprite, int cx, int cy,
                           float w, float h, float centreY, String name) {
        addSpriteAt(node, group, sprite, cx + 0.5f, cy + 0.5f, w, h, centreY, name);
    }

    private void addSpriteAt(Node node, String group, int sprite, float wx, float wz,
                             float w, float h, float centreY, String name) {
        if (sprite < 0 || !assets.exists(complex, group, sprite)) {
            return;
        }
        Geometry g = new Geometry(name, new Quad(w, h));
        g.setMaterial(assets.material(complex, group, sprite, true));
        g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
        // Quad spans (0,0)..(w,h), so shift it so the anchor is its centre.
        Node pivot = new Node(name);
        g.setLocalTranslation(-w / 2f, -h / 2f, 0f);
        pivot.attachChild(g);
        pivot.addControl(new BillboardControl());
        pivot.setLocalTranslation(new Vector3f(wx, centreY, wz));
        node.attachChild(pivot);
    }

    // ------------------------------------------------------------ batching

    /** Accumulates quads into one mesh. */
    private static final class QuadBatch {
        private final List<Float> pos = new ArrayList<Float>();
        private final List<Float> uv = new ArrayList<Float>();
        private final List<Float> norm = new ArrayList<Float>();
        private final List<Integer> idx = new ArrayList<Integer>();

        /** Corners in order: top-a, top-b, bottom-b, bottom-a. */
        void addQuad(float ax, float ay, float az,
                     float bx, float by, float bz,
                     float cx, float cy, float cz,
                     float dx, float dy, float dz,
                     boolean mirrored) {
            int base = pos.size() / 3;
            push(ax, ay, az);
            push(bx, by, bz);
            push(cx, cy, cz);
            push(dx, dy, dz);

            float u0 = mirrored ? 1f : 0f;
            float u1 = mirrored ? 0f : 1f;
            uv.add(u0); uv.add(0f);
            uv.add(u1); uv.add(0f);
            uv.add(u1); uv.add(1f);
            uv.add(u0); uv.add(1f);

            // face normal from the winding used below
            float e1x = dx - ax, e1y = dy - ay, e1z = dz - az;
            float e2x = cx - dx, e2y = cy - dy, e2z = cz - dz;
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0) {
                nx /= len; ny /= len; nz /= len;
            }
            for (int i = 0; i < 4; i++) {
                norm.add(nx); norm.add(ny); norm.add(nz);
            }

            idx.add(base); idx.add(base + 3); idx.add(base + 2);
            idx.add(base); idx.add(base + 2); idx.add(base + 1);
        }

        private void push(float x, float y, float z) {
            pos.add(x); pos.add(y); pos.add(z);
        }

        Mesh toMesh() {
            Mesh m = new Mesh();
            m.setBuffer(Type.Position, 3, toFloats(pos));
            m.setBuffer(Type.TexCoord, 2, toFloats(uv));
            m.setBuffer(Type.Normal, 3, toFloats(norm));
            int[] indices = new int[idx.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = idx.get(i);
            }
            m.setBuffer(Type.Index, 3, BufferUtils.createIntBuffer(indices));
            m.updateBound();
            return m;
        }

        private static java.nio.FloatBuffer toFloats(List<Float> src) {
            float[] a = new float[src.size()];
            for (int i = 0; i < a.length; i++) {
                a[i] = src.get(i);
            }
            return BufferUtils.createFloatBuffer(a);
        }
    }
}
