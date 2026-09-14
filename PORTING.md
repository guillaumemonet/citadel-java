# Porting Citadel to jMonkeyEngine

A Java port of the 1995 Amiga game whose original 68000 sources sit in `Code/` and `Data/`.

The original engine is a cell-based software renderer: a 64 x 64 grid of 1024-unit cells, walls
drawn as textured trapezoids back to front using a precomputed visibility table, all in integer
arithmetic, output as chunky pixels and converted to 5 Amiga bitplanes. A jMonkeyEngine port
throws away that whole rendering path (roughly 3000 lines of the 9600 in `MAIN1`) and keeps what
actually matters: the data formats and the game rules.

## Layout

| module | contents | dependencies |
|---|---|---|
| `core` | data formats and the runtime level model | none |
| `tools` | asset converter and inspectors | `core`, `java.desktop` |
| `game` | jMonkeyEngine viewer and, eventually, the game | `core`, jME 3.9 |

Requires a JDK 21 toolchain. `build/` and `tools/out/` are gitignored.

## Getting a level on screen

```sh
gradle :tools:convert          # Data -> build/assets (PNG, WAV, text dumps)
gradle :game:run               # walk around LEVEL01A
gradle :game:run --args=LEVEL05B
```

Conversion has to run once before the viewer, since the viewer loads its textures out of
`build/assets`. Level data is read straight from the original `.MAP` files.

Useful while debugging:

```sh
gradle :game:shot -Plevel=LEVEL03C            # render one frame to build/shots
gradle :game:shot -Pcam=top                   # top-down, to compare against maps/*.png
gradle :game:shot -Pscreen=title              # render a front-end screen instead of the world
gradle :tools:inspect -Pargs="../Data/GAME/MAPS/LEVEL01A.MAP 24 7"
```

## Formats

All decoded and documented in [tools/README.md](tools/README.md), with the source label that
proves each claim. The two findings that were not written down anywhere:

- `.VIR` graphics are stored **column major** with a 65-byte stride, one guard byte per column.
- The colour index is **bit-reversed and pre-shifted** into bits 3..7 of each byte, because the
  chunky-to-planar macro `ConvBits` consumes bit 7 first. Verified at 100% against the authoring
  IFF via `citadel.tools.ProbeMapping`.

Cell values are **1-based** indices into the engine's sprite tables: `ShowCollumns` does
`subq #1,d4` before scaling, and 0 means "nothing here". This applies to walls too, not just
columns and items: a shut door stores 30, which is wall id 15, which is sprite 14 — the barred
door. Sprite 15 is the black opening behind it.

## Coordinate system

The grid is 64 x 64 cells. One cell is one jME world unit in the XZ plane, walls are one unit
tall, Y is up. The engine's compass maps to **N = +Z, E = +X, S = -Z, W = -X**, which follows from
`rm_FixDoor` stepping N to the cell 512 bytes further on, i.e. the next grid row.

Cell faces are emitted once per cell and wound to be visible from inside it, so a shared wall is
two back-to-back quads and each side keeps its own texture. Quads are batched by texture: a whole
level is about 40 draw calls.

Floors and ceilings are only built where the player can actually stand, found by flood filling
from the spawn, the exit, both ends of every teleport and every enemy position, treating doors as
passable. This was cross-checked by rendering the level from above and comparing it against the
2D map dump.

## Angles

The angle runs 0..511 for a full turn, but **the forward vector is `(-cos a, sin a)`**, not the
naive `(cos a, sin a)`. Both the player (`sv_DoMMove`) and the enemies (`me_w4`) hand `sv_rotate`
the value `256 - angle` rather than the angle itself, which flips the X axis. So angle 0 faces
**west**, 128 north, 256 east, 384 south — and `rm_HandUsed` says as much in its own comment,
"W for 0 degrees".

Getting this backwards mirrors east and west: the player walks fine, but every "use" lands on the
wall behind the one they are facing. `facingAndForwardAgreeWithTheEngine` pins it down.

## Two clocks

The engine runs on two, and treating them as one makes the whole game run several times too fast.

`NewLev3` is the **level-3 interrupt**, fires on the vertical blank at a fixed 50 Hz, and is where
the level clock and the low-energy beep live. `MAIN_LOOP` is the **game loop**: it waits for *at
least* one blank (`VBLANK` then `dbf sv_Opoznienie`) and then takes as long as the software
renderer takes, which on real hardware is several frames.

So `sv_WalkSpeed`'s 250 units, `sv_RotSpeed`'s 12, a door's five steps and one call to `Move_Enemy`
are all **per pass of the loop**, not per video frame. Pacing them off the blank makes the player
sprint, the view spin, and the enemies notice and open fire about three times too quickly.

How fast that loop actually turned depends on the machine and the window size and is recorded
nowhere, so `DEFAULT_LOOP_HZ` is an estimate for an A1200 at the default window and is adjustable:
`-` and `+` in game, or `-Dcitadel.pace=` at launch. `movementIsPacedByTheLoopNotTheFrameRate` pins the
separation down.

## Movement rules

`GameState` reimplements the original's rules rather than substituting a physics engine, so the
feel carries over:

- The player is a point kept `min_distance - 60` = **307 units** from any face, out of 1024 per
  cell. `min_distance` is `500 * 256 / 350 + 2` with 68000 integer division.
- Collision clamps the in-cell position *after* the move, which is what produces the sliding along
  walls. Corners are handled as `br2_UP` / `br2_DOWN` do: push back along the axis with less
  penetration, so you cannot cut through a sealed corner.
- A face is passable when its value masked with 62 is 0, 32, 38 or 54 — nothing, either door type
  open, or a broken door (`br_ChkDoors`).
- Columns 1, 2 and 18 block a 208-unit box around the cell centre; column 3 is decorative and
  column 4 is a teleport pad (`.br2_COLUMN2`).
- Doors open by themselves when the player stands in an adjacent cell, unless that cell carries
  the block flag, and shut again on leaving. Only one door of each type moves at a time, over 5
  steps, exactly as `br_Chk_OPEN` and `OpenCloseDoors` do.

Two departures from the original, both deliberate:

- Movement is split into sub-steps and a crossing is rejected against the cell it started in. The
  original never needs this because it moves at most 250 units a frame against a 307 unit clamp,
  but a variable frame rate can ask for much more, and without it a door shutting on a straddling
  player lets them slip through.
- The combined forward/strafe input is normalised. The original moves along one vector capped by
  `sv_WalkSpeed`; keyboard strafing would otherwise make diagonals 41% faster.

## Tests

`gradle :core:test` runs headless against the shipped maps: 50 tests covering parsing, movement,
doors, switches, pickups, line of sight and enemy movement. The one that earns its keep is a
random walk of 20 000 steps across five levels asserting the player never ends up on the far side
of a shut face. It found both departures listed above.

## State

Done:

- every data format read and converted, all 28 levels and all 8 complexes
- runtime level model: grid, walls, doors, columns, items, enemies, triggers, teleports
- jME geometry: walls, sliding doors, floors, ceilings, billboards for columns, items and enemies
- movement, collision, automatic doors, switches and their commands, teleports, level exit
- item pickup and a working inventory (weapons, ammo, cards, first aid, boost, vodka, warhead)
- enemy simulation: roaming, closing in, line of sight, shooting, damage and death
- player weapons: hitscan fire, ammunition, weapon wear, per-species vulnerability mask
- sound: 25 effects, positioned in the world for doors and enemies
- intro animations and artwork decoded to PNG (IFF ANIM op 5, Hold-and-Modify)
- the ETAPY level sequence, with the inventory carried across levels
- the original in-game messages, with their random variants
- the level clock, low-energy warning, death, and the automap
- projectiles in flight: fireballs, electricity, rockets, and explosions that throw fireballs
- saving and loading in the original 768 byte Savegame.DAT format
- the compass needle, and in-flight object sprites resolved to their real 16x16 grid
- front end: the intro animation plays, then a title screen, with death and victory cards
- the five-slot save menu, reading and writing the original file
- playable viewer, plus a screenshot path for verification

Not started:

- the original HUD layout and the briefing screens (`SERVER`)

## Saves

`Store/Savegame.DAT` is 768 bytes: the live state in the first 128, then five slots of 128. Each
block is the engine's `STRUCTURE`, the record the server keeps at `$7f800` and hands to the game
between levels. A slot carries a 16 byte name at +72, set to `WOLNE@` — Polish for "free" — when
empty, with `@` terminating the string as it does in the text files.

Offsets come from the engine's own load, which reads size, floor, details, energy and Glowica
individually and then copies **18 words from +14** into `sv_GUNS`: six weapons of (condition,
ammunition per pickup, ammunition). That is cross-checked against the shipped file, where
-10, -5, -10, -5, -5 and -1 land exactly on those six per-pickup fields.

The front end drives it through a five-slot picker: `F5` saves, `F9` loads, W/S or the arrows
choose, ENTER confirms and E backs out. Each row shows the name, the complex and sub-level, and
the energy the save was made with.

`SaveGame` writes that layout byte for byte, so a save made by the port is a file the Amiga game
would read. The test suite loads the shipped `Savegame.DAT` and asserts its five slots read as
free and its head block starts at -150 energy.

## Hold-and-Modify

The intro artwork and animations are HAM6, which `CAMG` bit 0x800 announces. A HAM pixel's top two
bits say whether to take a colour from the palette or to hold the previous pixel and modify one of
its components. Decoded as a plain indexed image they come out buried in red and green speckle --
which is what the first version of this port did, and what a careful look at the title screen
caught.

The game's own wall and sprite artwork has `CAMG` 0 and is genuinely indexed, which is why that
half was pixel-correct from the start.

## Campaign and text

`Campaign` transcribes the server's `ETAPY` table: complex 1 is A and B, complexes 2 to 7 are A,
B and C, complex 8 is A alone and then the game ends. That is 21 levels. Several maps ship that
the sequence never visits — `LEVEL01T`, `LEVEL03K`, `LEVEL05D` and others — and they stay
playable on their own.

Reaching the exit cell loads the next map and carries weapons, ammunition, cards and energy
across, which is what the server does by keeping them in `STRUCTURE` between levels.

`TextTable` reads `Store/TEXT.ENG` (or `.POL`, `.GER`). The file is split by `@`, one block per
message numbered from zero, and that numbering is exactly the `SCROLL` macro's argument. A block
whose first line starts with `^` holds interchangeable lines the engine picks between at random,
which is why walking into a wall insults you differently every time.

Message numbers are verified against their call sites at 40, 41, 44, 52, 53, 54, 65 and 76. Pickup
messages need no table of their own: every branch of `Take_Items` computes an index that works out
to the item value itself.

## Projectiles

`Anim_Objects` walks a table of 30 slots. Types 3, 4 and 5 are the fireball, electricity and
rocket; 1, 2 and 6 are the brief impact, discharge and explosion marks. The handgun, shotgun and
machinegun are not in there at all — they resolve in the frame the trigger is pulled.

Speeds are written as `400/8` and `600/8` and an object advances eight steps a frame, so the real
figures are 400 and 600 units. That reading checks out at both ends: the handgun's 20-step hitscan
reaches the 8000 units its own comment claims, and all three projectile types come out with an
identical 12 cell range against their lifetimes (30, 20 and 20 frames).

A rocket has no splash radius. `ro_SetExplode` swaps it for an explosion mark and throws eight
fireballs outwards along `ro_DirTab`, and those are the splash damage.

## Counters and the automap

`NewLev3` ticks the level clock once every 50 frames, `Test_Counters` turns the energy readout red
and beeps below 10, and zero energy ends the level as a death. There is **no energy regeneration**
anywhere in the engine: energy only ever goes down, except for medikits.

Walking into a wall costs a point (`br_BumpWall`), but only on a real stride — the routine wants
the movement vector at 120 or more, so edging along a wall is free. Some weapons cost energy to
fire, too.

`UpdateMap` keeps one bit per cell and reveals the cell underfoot plus every neighbour with no wall
between, which is why a corridor fills in ahead of you. `Automap` paints that into a texture.

## Enemies

The 16-byte enemy record is now fully accounted for: `+1` flags (**bit 0** selects the species —
bit 7 is unrelated, it only asks the renderer to draw that one behind a column), `+2` energy,
`+4`/`+6` world position, `+8` facing, `+10` walk speed, `+12` state, `+13` state counter.

`Move_Enemy` only simulates the 24 × 24 cells around the player, so distant enemies stand still;
that is reproduced. Enemies have a 300-unit radius against the player's 307, refuse to walk onto
each other via the occupancy grid in cell byte 7, and turn at random up to three times when a step
is blocked.

Two places take a functional equivalent rather than a transcription, both noted in the code:

- **Line of sight** is a grid walk from shooter to target refusing the first shut face. `CanShoot`
  unrolls the same idea per quadrant; the observable result is the same and this is legible.
- **Randomness** uses a seeded PRNG. The original reads bytes out of Amiga ROM as its entropy
  source, which cannot be reproduced and does not need to be.

## Weapons

Shots are **hitscan, resolved in the frame the trigger is pulled**, because that is what the
original does: `rm_HandGunUsed` runs its whole collision loop immediately. Only slow objects
(flames, rockets in flight) persist in `sv_ObjectTab` across frames, and those are not modelled
yet.

`EnemyHitServe` fixes the damage table and, more importantly, reveals that the header field the
editor labels "hits" is really a **4-bit vulnerability mask**: bit 0 bullets, bit 1 fireballs,
bit 2 electricity, bit 3 rockets. A species with the bit clear takes no damage at all from that
type.

| type | engine name | damage | weapons |
|---|---|---|---|
| 1 | pocisk (bullet) | 5 | handgun, shotgun, machinegun |
| 3 | kula (fireball) | 25 | flamer |
| 4 | prad (electricity) | 20 | bolter |
| 5 | rakieta (rocket) | 40 | launcher |

A fireball that finishes an enemy leaves it burning rather than simply dead. Weapons wear out:
`DamageWeapon` knocks 0 or 1 off the condition per shot, and a weapon at 0 refuses to fire.

Known gaps:
- which of the 32 object sprites goes with which projectile type is paired by eye, not read from
  the offset table

- knockback is decoded (enemy bytes 14/15) but not applied
- enemy handguns are hitscan in the original too, so those enemies get a 25 frame reload to keep
  a roomful of them from emptying an energy bar in seconds
- wall plaques and blood decals are decoded but not yet drawn
- card readers are parsed and can be fired, but nothing checks whether you hold the card
- switch commands tagged "on release" are parsed and skipped; they need a held-button model
- `header+20` is assumed to be the spawn facing
