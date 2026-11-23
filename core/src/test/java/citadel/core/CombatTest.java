package citadel.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combat rules against the values in the engine source, on synthetic maps so that every
 * position is exact. Labels refer to MAIN1.
 */
class CombatTest {

    /** One pass of MAIN_LOOP. */
    private static final float TICK = 1f / GameState.DEFAULT_LOOP_HZ;

    private static void arm(GameState game, int slot) {
        game.inventory.owned[slot] = true;
        game.inventory.condition[slot] = 500;
        game.inventory.ammo[slot] = -99;
        game.inventory.current = slot;
    }

    private static void place(GameState game, int x, int y, int angle) {
        game.posX = x;
        game.posY = y;
        game.angle = angle;
    }

    private static void pass(GameState game, int n) {
        for (int i = 0; i < n; i++) {
            game.update(0, 0, 0, TICK);
        }
    }

    private static List<int[]> marks(GameState game) {
        List<int[]> out = new ArrayList<int[]>();
        for (Projectile p : game.projectiles()) {
            out.add(new int[]{p.type, p.x, p.y, p.counter});
        }
        return out;
    }

    private static boolean hasMark(List<int[]> marks, int type, int x, int y, int counter) {
        for (int[] m : marks) {
            if (m[0] == type && m[1] == x && m[2] == y && m[3] == counter) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ ro_SetExplode

    @Test
    @DisplayName("ro_SetExplode resolves twelve fireball probes on the spot, nothing flies")
    void explosionProbes() {
        GameState game = new GameState(new SyntheticMap().level());
        place(game, SyntheticMap.at(2), SyntheticMap.at(2), 0);
        int bx = SyntheticMap.at(30), by = SyntheticMap.at(30);
        game.detonate(bx, by, 0, 0);
        List<int[]> m = marks(game);
        assertEquals(13, m.size(), "the rocket's own mark and twelve probes");
        for (Projectile p : game.projectiles()) {
            assertEquals(Projectile.EXPLOSION, p.type, "nothing left in flight");
        }
        int E = Projectile.EXPLOSION;
        assertTrue(hasMark(m, E, bx, by, 3), "$0603 where it went off");
        // first ring, orthogonal: (75|1, 0|1) for eight sub-steps, ending in $0601
        assertTrue(hasMark(m, E, bx + 600, by + 8, 1));
        assertTrue(hasMark(m, E, bx - 600, by + 8, 1));
        assertTrue(hasMark(m, E, bx + 8, by + 600, 1));
        assertTrue(hasMark(m, E, bx + 8, by - 600, 1));
        // first ring, diagonal: 700/8 = 87 per axis, ending in $0600
        assertTrue(hasMark(m, E, bx + 696, by + 696, 0));
        assertTrue(hasMark(m, E, bx + 696, by - 696, 0));
        assertTrue(hasMark(m, E, bx - 696, by - 696, 0));
        assertTrue(hasMark(m, E, bx - 696, by + 696, 0));
        // second ring: the orthogonal vectors again, two collision calls, $0600
        assertTrue(hasMark(m, E, bx + 1200, by + 16, 0));
        assertTrue(hasMark(m, E, bx - 1200, by + 16, 0));
        assertTrue(hasMark(m, E, bx + 16, by + 1200, 0));
        assertTrue(hasMark(m, E, bx + 16, by - 1200, 0));
    }

    @Test
    @DisplayName("the blast backs off an eighth of the rocket's step, and a probe that hits is freed")
    void explosionBacksOffAndProbesStopAtWalls() {
        GameState game = new GameState(new SyntheticMap().wall(30, 30, Level.EAST_DIR, 2).level());
        place(game, SyntheticMap.at(2), SyntheticMap.at(2), 0);
        int bx = SyntheticMap.at(30), by = SyntheticMap.at(30);
        game.detonate(bx, by, 75, 1);
        Projectile base = game.projectiles().get(0);
        assertEquals(bx - (75 >> 3), base.x, "asr #3 of the vector");
        // the two east probes run into the wall and leave nothing; the diagonal ones reach the
        // boundary beyond the wall's end and slip round it, as they do in the engine
        for (Projectile p : game.projectiles()) {
            assertFalse(p.cellX() == 31 && p.cellY() == 30, "a probe passed through the wall");
        }
        assertEquals(13 - 2, game.projectiles().size());
    }

    @Test
    @DisplayName("probes hurt the player for 25 each unless sv_HitFlag is set")
    void probesHurtThePlayer() {
        GameState game = new GameState(new SyntheticMap().level());
        int bx = SyntheticMap.at(30), by = SyntheticMap.at(30);
        place(game, bx - 600, by, 0);
        int before = game.inventory.energyReading();
        game.detonate(bx, by, 0, 0);
        // the west probe of each orthogonal ring reaches him
        assertEquals(before - 50, game.inventory.energyReading());
    }

    @Test
    @DisplayName("a point blank rocket costs the player 5 and its probes nothing")
    void pointBlankRocket() {
        GameState game = new GameState(new SyntheticMap().wall(10, 10, Level.EAST_DIR, 2).level());
        place(game, SyntheticMap.at(10), SyntheticMap.at(10), 256);   // facing east
        arm(game, 6);
        int before = game.inventory.energyReading();
        assertTrue(game.fire());
        assertEquals(before - 5, game.inventory.energyReading(),
                "+5 from rm_LauncherUsed, and sv_HitFlag keeps the probes off him");
        assertTrue(game.szumActive());
        for (Projectile p : game.projectiles()) {
            assertFalse(p.inFlight(), "the blast is resolved at once");
        }
    }

    // ----------------------------------------------------------- the launcher

    @Test
    @DisplayName("rm_LauncherUsed: sv_Flag+8 allows a rocket every fifth pass, SCROLL 68 between")
    void launcherReload() {
        GameState game = new GameState(new SyntheticMap().level());
        place(game, SyntheticMap.at(10), SyntheticMap.at(10), 256);
        arm(game, 6);
        assertTrue(game.fire());
        int ammo = game.inventory.ammo[6];
        for (int i = 0; i < 4; i++) {
            pass(game, 1);
            game.drainLog();
            assertFalse(game.fire(), "a rocket " + (i + 1) + " passes after the last");
            assertTrue(game.drainLog().contains("reloading"));
        }
        assertEquals(ammo, game.inventory.ammo[6], "reloading spends nothing");
        pass(game, 1);
        assertTrue(game.fire(), "the fifth pass reloads it");
    }

    // ----------------------------------------------------------- hit boxes

    /** The handgun eastwards is (49, 1) a sub-step: 50 * 32767 * 2 >> 16, and 0 | 1. */
    @Test
    @DisplayName("Object_Collision hits an enemy within 200 on both axes, not 300")
    void enemyHitBox() {
        int ex = SyntheticMap.at(13);
        for (int offset : new int[]{250, 270}) {
            int ey = SyntheticMap.at(10) + offset;
            GameState game = new GameState(new SyntheticMap()
                    .enemy(1, 1, 100, ex, ey, 0, 0).level());
            place(game, SyntheticMap.at(10), SyntheticMap.at(10), 256);
            arm(game, 1);
            Enemy e = game.enemies().get(0);
            game.fire();
            // by x = 13*1024 the bullet has drifted about 60 units north
            if (offset == 250) {
                assertEquals(95, e.energy, "190 off the line is inside the box");
            } else {
                assertEquals(100, e.energy, "210 off the line is outside it");
            }
        }
    }

    @Test
    @DisplayName("columns 1, 2 and 18 stop shots in a 190 box, column 3 does not")
    void columnsStopShots() {
        for (int col : new int[]{1, 2, 3}) {
            GameState game = new GameState(new SyntheticMap()
                    .column(12, 10, col)
                    .enemy(1, 1, 100, SyntheticMap.at(14), SyntheticMap.at(10), 0, 0).level());
            place(game, SyntheticMap.at(10), SyntheticMap.at(10), 256);
            arm(game, 1);
            Enemy e = game.enemies().get(0);
            game.fire();
            assertEquals(col == 3 ? 95 : 100, e.energy, "column " + col);
        }
    }

    // --------------------------------------------------------------- barrels

    @Test
    @DisplayName("a barrel takes six bullets, then leaves the map and goes off")
    void barrelBullets() {
        Level level = new SyntheticMap().column(12, 10, 18).level();
        GameState game = new GameState(level);
        place(game, SyntheticMap.at(10), SyntheticMap.at(10), 256);
        arm(game, 1);
        for (int i = 0; i < 5; i++) {
            game.fire();
            assertEquals(18, level.column(12, 10), "shot " + (i + 1) + " is +5 of 30");
        }
        game.projectiles().clear();
        game.fire();
        assertEquals(0, level.column(12, 10), "the sixth reaches 30");
        assertTrue(game.consumeGeometryDirty());
        int explosions = 0;
        for (Projectile p : game.projectiles()) {
            explosions += p.type == Projectile.EXPLOSION ? 1 : 0;
        }
        assertTrue(explosions >= 9, "ro_SetExplode where the barrel stood");
    }

    @Test
    @DisplayName("a fireball destroys a barrel at once; electricity bounces off")
    void barrelFireAndElectricity() {
        Level level = new SyntheticMap().column(12, 10, 18).level();
        GameState game = new GameState(level);
        place(game, SyntheticMap.at(10), SyntheticMap.at(10), 256);
        arm(game, 5);
        for (int i = 0; i < 6; i++) {
            game.fire();
            pass(game, 3);
        }
        assertEquals(18, level.column(12, 10), "a bolt is a wall hit");
        arm(game, 4);
        game.fire();
        pass(game, 4);
        assertEquals(0, level.column(12, 10), "a fireball adds 30");
    }

    // ------------------------------------------------------- enemy weapons

    @Test
    @DisplayName("es_HandGun: real bullets from 300 ahead, which can hit another enemy")
    void enemyFriendlyFire() {
        // the shooter faces east, a second enemy stands 500 ahead of it, the player far behind
        int sx = SyntheticMap.at(20), sy = SyntheticMap.at(20);
        Level level = new SyntheticMap().species(0, 0xf, 1, 0, 0)
                .enemy(1, 1, 100, sx, sy, 256, 0)
                .enemy(2, 1, 100, sx + 600, sy, 256, 0).level();
        GameState game = new GameState(level);
        place(game, SyntheticMap.at(12), SyntheticMap.at(20), 256);
        Enemy shooter = game.enemies().get(0);
        Enemy victim = game.enemies().get(1);
        shooter.state = Enemy.SHOOTING;
        shooter.counter = 64;
        victim.state = Enemy.HIT;      // keeps it busy and in place
        victim.counter = 40;
        int energy = game.inventory.energyReading();
        pass(game, 1);
        assertEquals(68, shooter.counter, "es_EnemyShoot at 68");
        assertEquals(95, victim.energy, "five from one bullet");
        assertEquals(100, shooter.energy, "the bullet started 300 ahead, clear of its shooter");
        assertEquals(energy, game.inventory.energyReading());
    }

    @Test
    @DisplayName("an enemy whose weapon is 0 fires nothing")
    void weaponZeroFiresNothing() {
        int sx = SyntheticMap.at(20), sy = SyntheticMap.at(20);
        GameState game = new GameState(new SyntheticMap().species(0, 0xf, 0, 0, 0)
                .enemy(1, 1, 100, sx, sy, 0, 0).level());
        place(game, SyntheticMap.at(16), SyntheticMap.at(20), 256);
        Enemy e = game.enemies().get(0);
        e.state = Enemy.SHOOTING;
        e.counter = 64;
        game.drainSounds();
        int energy = game.inventory.energyReading();
        pass(game, 1);
        assertTrue(game.projectiles().isEmpty());
        assertTrue(game.drainSounds().isEmpty(), "es_SND[0] is 0");
        assertEquals(energy, game.inventory.energyReading());
    }

    @Test
    @DisplayName("me_shootON at 72 falls through into me_hited: counter 64 and a knockback step")
    void shootWindUpQuirk() {
        int sx = SyntheticMap.at(20), sy = SyntheticMap.at(20);
        GameState game = new GameState(new SyntheticMap().enemy(1, 1, 100, sx, sy, 0, 0).level());
        place(game, SyntheticMap.at(10), SyntheticMap.at(10), 0);
        Enemy e = game.enemies().get(0);
        e.state = Enemy.SHOOTING;
        e.counter = 68;
        e.knockX = 10;
        pass(game, 1);
        assertEquals(Enemy.WALKING, e.state);
        assertEquals(64, e.counter);
        assertEquals(sx + 20, e.x, "twice the knockback byte");
    }

    // --------------------------------------------------------------- enemies

    @Test
    @DisplayName("Move_Enemy serves the 24 x 24 cells from 12 behind to 11 ahead")
    void simulationWindow() {
        for (int dx : new int[]{-12, 11, 12}) {
            int ex = SyntheticMap.at(30 + dx), ey = SyntheticMap.at(30);
            GameState game = new GameState(new SyntheticMap().enemy(1, 1, 100, ex, ey, 0, 0).level());
            place(game, SyntheticMap.at(30), SyntheticMap.at(30), 0);
            Enemy e = game.enemies().get(0);
            e.state = Enemy.HIT;
            e.counter = 72;
            pass(game, 1);
            assertEquals(dx == 12 ? 72 : 76, e.counter, "enemy " + dx + " cells away");
        }
    }

    @Test
    @DisplayName("a killed enemy falls for three passes in its cell, then leaves a corpse")
    void deathAnimation() {
        for (int flags : new int[]{1, 2}) {
            int ex = SyntheticMap.at(20), ey = SyntheticMap.at(20);
            Level level = new SyntheticMap().enemy(1, flags, 100, ex, ey, 0, 0).level();
            GameState game = new GameState(level);
            place(game, SyntheticMap.at(14), SyntheticMap.at(20), 0);
            Enemy e = game.enemies().get(0);
            game.damageEnemy(e, 101);
            assertEquals(Enemy.KILLED, e.state);
            assertEquals(18, e.frame(0, 0), "counter 72");
            pass(game, 1);
            assertEquals(22, e.frame(0, 0), "76 is bumped to 88");
            assertEquals(1, level.enemyRef(20, 20), "still holding its cell");
            pass(game, 1);
            assertEquals(23, e.frame(0, 0));
            assertFalse(e.isGone());
            assertEquals(0, game.enemiesKilled());
            pass(game, 1);
            assertTrue(e.isGone());
            assertEquals(0, level.enemyRef(20, 20));
            assertEquals(flags == 2 ? 3 : 1, level.corpse(20, 20), "32, or 96 for species 2");
            assertEquals(1, game.enemiesKilled());
        }
    }

    @Test
    @DisplayName("a dying enemy still stops shots")
    void deadEnemyAbsorbsShots() {
        int ex = SyntheticMap.at(13), ey = SyntheticMap.at(10);
        GameState game = new GameState(new SyntheticMap()
                .enemy(1, 1, 100, ex, ey, 0, 0)
                .enemy(2, 1, 100, ex + 1024, ey, 0, 0).level());
        place(game, SyntheticMap.at(10), SyntheticMap.at(10), 256);
        arm(game, 1);
        Enemy front = game.enemies().get(0);
        Enemy behind = game.enemies().get(1);
        game.damageEnemy(front, 101);
        game.fire();
        assertEquals(100, behind.energy, "the falling one took the bullet");
    }

    @Test
    @DisplayName("blood: $40 or $80 on the face the shot was going to, only on walls")
    void bloodOnKill() {
        int ex = SyntheticMap.at(10), ey = SyntheticMap.at(13);
        Level level = new SyntheticMap()
                .wall(10, 13, Level.NORTH_DIR, 2)
                .wall(10, 13, Level.EAST_DIR, 30)    // a door: no blood
                .enemy(1, 1, 3, ex, ey, 0, 0).level();
        GameState game = new GameState(level);
        place(game, SyntheticMap.at(10), SyntheticMap.at(10), 128);   // facing north
        arm(game, 1);
        game.fire();
        Enemy e = game.enemies().get(0);
        assertEquals(Enemy.KILLED, e.state);
        int stain = level.wallBlood(10, 13, Level.NORTH_DIR);
        assertTrue(stain == 1 || stain == 2, "struck face " + stain);
        assertEquals(0, level.wallBlood(10, 13, Level.EAST_DIR), "doors take none");
        assertEquals(0, level.wallBlood(10, 13, Level.WEST_DIR), "nor open faces");
        assertTrue(game.consumeGeometryDirty());
    }

    @Test
    @DisplayName("eh_Burning: 56 passes of staggering, a point a pass on contact, a burnt corpse")
    void burning() {
        int ex = SyntheticMap.at(20), ey = SyntheticMap.at(20);
        Level level = new SyntheticMap().enemy(1, 1, 10, ex, ey, 0, 0).level();
        GameState game = new GameState(level);
        // 280 units off: the flamer hits at once, and the speed-0 stagger keeps touching him
        place(game, ex - 280, ey, 256);
        arm(game, 4);
        int before = game.inventory.energyReading();
        assertTrue(game.fire());
        Enemy e = game.enemies().get(0);
        assertEquals(Enemy.BURNING, e.state);
        assertEquals(55, e.energy);
        assertEquals(20, e.frame(0, 0), "counter 80");
        assertEquals(before - 5, game.inventory.energyReading(), "the point-blank flamer");
        assertTrue(game.fireLoopVolume() >= 0, "the fire sample is looping");
        game.drainLog();
        // the point-blank recoil: sv_LastMove+4 = 190 at -sv_Angle, spent 130, 70 and 10
        pass(game, 1);
        assertEquals(ex - 280 - 130, game.posX, "the first rebound is 190 less 60");
        assertEquals(21, e.frame(0, 0), "counter 84");
        pass(game, 2);
        assertEquals(ex - 280 - 210, game.posX, "then 70 and 10");
        assertEquals(before - 5, game.inventory.energyReading(), "thrown out of reach");

        // back within 300 on both axes, in the enemy's own square
        place(game, ex - 280, ey, 256);
        pass(game, 1);
        assertEquals(before - 7, game.inventory.energyReading(),
                "br2_COLUMN and me_PLAYER each take a point from a burning enemy");
        List<String> said = game.drainLog();
        assertTrue(said.contains("you are burning"), "me_PLAYER's SCROLL 71");
        assertEquals(ex - 280, game.posX, "br2_COLUMN put him back on sv_LastPos");
        pass(game, 51);
        assertFalse(e.isGone(), "55 down to 0 is still burning");
        pass(game, 1);
        assertTrue(e.isGone());
        assertEquals(2, level.corpse(20, 20), "64: the burnt corpse");
        assertEquals(-1, game.fireLoopVolume(), "the owner's death stops the loop");
    }

    @Test
    @DisplayName("me_RepTurn retries with the saved vector, so a blocked enemy only turns")
    void blockedEnemyOnlyTurns() {
        int ex = 20 * 1024 + 700, ey = SyntheticMap.at(20);
        // the enemy faces an east wall 324 away, and the player is due east beyond it and more
        // than 8191 off, so me_walk can only aim it east again and CanShoot is refused
        Level level = new SyntheticMap()
                .wall(20, 20, Level.EAST_DIR, 2)
                .enemy(1, 1, 100, ex, ey, 256, 100).level();
        GameState game = new GameState(level);
        place(game, ex + 8300, ey, 0);
        Enemy e = game.enemies().get(0);
        pass(game, 1);
        assertEquals(ex, e.x, "the retries reuse the blocked vector");
        assertEquals(ey, e.y);
        assertNotEquals(256, e.angle, "but it has turned");
        assertEquals(0, e.angle & 1, "andi #$1fe");
    }

    @Test
    @DisplayName("me_angletab: the aim at the player is looked up, not computed")
    void angleTable() {
        assertEquals(0, GameState.aimAt(1000, 0, 1000), "player to the west");
        assertEquals(256, GameState.aimAt(-1000, 0, 1000), "to the east");
        assertEquals(128, GameState.aimAt(0, -1000, 1000), "to the north");
        assertEquals(384, GameState.aimAt(0, 1000, 1000), "to the south");
        // dx*16/dist = 8: entry 24 is 170, and 256 + 170 wraps to 426 & $1fe
        assertEquals((256 + 170) & 0x1fe, GameState.aimAt(500, 0, 1000));
    }

    // -------------------------------------------------------------- CanShoot

    @Test
    @DisplayName("CanShoot: wall 52 is transparent, a corner needs two shut faces")
    void lineOfSight() {
        Level level = new SyntheticMap()
                .wall(10, 10, Level.EAST_DIR, 52)
                .wall(20, 20, Level.EAST_DIR, 2)
                .wall(30, 30, Level.EAST_DIR, 2)
                .wall(30, 30, Level.NORTH_DIR, 2).level();
        GameState game = new GameState(level);
        assertTrue(game.canShoot(SyntheticMap.at(10), SyntheticMap.at(10),
                SyntheticMap.at(11), SyntheticMap.at(10)), "przezroczysta");
        // diagonally out of (20,20) past its one shut face
        assertTrue(game.canShoot(SyntheticMap.at(20), SyntheticMap.at(20),
                SyntheticMap.at(21), SyntheticMap.at(21)), "one shut face of four");
        assertFalse(game.canShoot(SyntheticMap.at(30), SyntheticMap.at(30),
                SyntheticMap.at(31), SyntheticMap.at(31)), "two shut faces");
    }

    // ------------------------------------------------------------- the rest

    @Test
    @DisplayName("mark lifetimes: impact 3 calls, $0603 4, $0601 6, $0600 7, $0604 3")
    void markLifetimes() {
        assertEquals(3, Projectile.mark(Projectile.IMPACT, 0, 0, 0).passesLeft());
        assertEquals(3, Projectile.mark(Projectile.DISCHARGE, 0, 0, 0).passesLeft());
        assertEquals(4, Projectile.mark(Projectile.EXPLOSION, 0, 0, 3).passesLeft());
        assertEquals(6, Projectile.mark(Projectile.EXPLOSION, 0, 0, 1).passesLeft());
        assertEquals(7, Projectile.mark(Projectile.EXPLOSION, 0, 0, 0).passesLeft());
        assertEquals(3, Projectile.mark(Projectile.EXPLOSION, 0, 0, 4).passesLeft());

        GameState game = new GameState(new SyntheticMap().level());
        game.projectiles().add(Projectile.mark(Projectile.EXPLOSION, 5000, 5000, 3));
        pass(game, 3);
        assertEquals(1, game.projectiles().size());
        pass(game, 1);
        assertTrue(game.projectiles().isEmpty());
    }

    @Test
    @DisplayName("dr_DrawEnemy draws counter / 4 for every state but walking")
    void framesFromTheCounter() {
        Enemy e = new GameState(new SyntheticMap()
                .enemy(1, 1, 100, 5000, 5000, 0, 0).level()).enemies().get(0);
        int[][] cases = {{Enemy.SHOOTING, 60, 15}, {Enemy.SHOOTING, 68, 17}, {Enemy.HIT, 76, 19},
                {Enemy.BURNING, 84, 21}, {Enemy.KILLED, 92, 23}};
        for (int[] c : cases) {
            e.state = c[0];
            e.counter = c[1];
            assertEquals(c[2], e.frame(0, 0));
        }
    }

    @Test
    @DisplayName("sv_rotate: 50 at index 0 is 49 across, the forward vector of angle 256")
    void rotate() {
        int[] v = GameState.rotate(50, (256 - 256) & 0x1fe);
        assertEquals(49, v[0]);
        assertEquals(0, v[1]);
        int[] w = GameState.rotate(50, (256 - 0) & 0x1fe);
        assertEquals(-50, w[0], "facing west, floor of -49.99");
        int[] n = GameState.rotate(75, (256 - 128) & 0x1fe);
        assertEquals(74, n[1], "north");
    }
}
