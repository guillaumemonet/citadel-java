# Porting Citadel to jMonkeyEngine

A Java port of the 1995 Amiga game whose original 68000 sources and data are
in the [Amiga-Citadel](https://github.com/pawelmat/Amiga-Citadel) repository, expected as a sibling
checkout in `../Amiga-Citadel` (or wherever `-Poriginal=<path>` points). Paths such as `Code/` and
`Data/` below are relative to it.

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
| `game` | jMonkeyEngine front end and game | `core`, jME 3.9 |

Requires a JDK 21 toolchain. `build/` and `tools/out/` are gitignored.

## Getting a level on screen

```sh
gradle :game:run                   # the cover picture, then the server's menu
gradle :game:run --args=LEVEL05B   # straight into a level, then on through the campaign
```

The game reads everything from the five original disk images in `../adf` (or `-Padf=<path>`) and
decodes it in memory; there is no conversion step. `gradle :tools:convert` still exports the
repository's `Data/` to PNG, WAV and text dumps, for looking at.

### The disks

Disk 1 and disk 5 are AmigaDOS volumes; disks 2 to 4 have no file system and SERVER reads them
by sector with its own track loader. Everything on them is PowerPacker 2.0, either as `PP20`
files (disk 5) or as blocks whose first long word is their packed length (the track-loaded ones).
`citadel.core.disk.GameDisks` serves them under their `Data/` paths:

| what | where |
|---|---|
| maps, wall and enemy sets, palettes | disk 5 files `M3B`, `W3A`, `E3A`, `C3A`... |
| `ITEMS01.VIR`, `WINDOW1.RAW`, `TEXT.ENG` | disk 4, block at cylinder 21 |
| `ST.DAT` samples, `Bomba.rawb`, `CHAOSTAB/C.DAT`, `NUMBERS.RAW`, `FONTS01.FNT` | disk 4, block at cylinder 24 |
| hangar | disk 4, block at cylinder 18 |
| `mod.aconcagua.pro`, the server's music | disk 4, block at cylinder 1 |
| `mod.dirranbandi.pro`, the end's music | disk 4, block at cylinder 12 |
| `Savegame.DAT` | disk 4, unpacked, sectors 1 and 2 |
| the end sequence's animations (`.k`) | disk 4, unpacked, from byte `0x76400` (cylinder 43) |
| cover picture | disk 3, block at cylinder 72 |
| `MT.raw` (+`0x82e2`), `KANE.fnt` (+`0xb61a`), `mod.MiedzyEtapy.pro` (+`0xc21a`, still packed), `HangarTab.dat` (+`0x13fb2`), `strzalka.rawb` (+`0x167cc`), the credits (+`0x5978`) and the epilogue (+`0x66f4`) | inside SERVER, disk 1 block at cylinder 27 |
| map screen, MAPA's music `mod.brief1.pro` | disk 5 files `MAP` and `MOD`, length-prefixed blocks |
| `mod.death.pro` (+`0x1618`), `zabity.rawb` (+`0x14568`), `GameOver.rawb` (+`0x1e1e8`) | disk 5 file `DET`, the death sequence, unpacked |

`GameDisksTest` checks every one against Amiga-Citadel's `Data/`. They all match, except that the
released disks carry one different wall tile in `WALLS1A.VIR`, a few different glyphs in
`KANE.fnt`, and a save file whose free slots are called `EMPTY` rather than `WOLNE`. The disks
are used as they are. The offsets inside SERVER follow from its `>extern` chain: `MT_pic`, then
`KaneFont = MT_PIC+$3338`, `MT_endlev = KaneFont+$c00`, and so on down to `Strzalka`.

Useful while debugging:

```sh
gradle :game:shot -Plevel=LEVEL03C            # render one frame to build/shots
gradle :game:shot -Pcam=top                   # top-down, to compare against maps/*.png
gradle :game:shot -Pscreen=endlevel           # a front-end screen: title, hangar, slots,
                                              # credits, map, endlevel, dead, gameover,
                                              # victory, epilogue; -Pframe=N to wait longer
gradle :tools:inspect -Pargs="../../Amiga-Citadel/Data/GAME/MAPS/LEVEL01A.MAP 24 7"
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

## Controls

The angle convention and the direction a control turns you are independent, and the port shipped
once with a correct forward vector and every control mirrored. A rising angle sweeps west, north,
east, south — clockwise seen from above — so turning right must **increase** it. `ControlsTest`
checks each control against the compass rather than against the others.

## Handedness

The engine's plane is (X, Y) with Y north. jME's is (X, Z) with Z coming towards the viewer, which
is its mirror image, so an angle that sweeps clockwise in the engine sweeps anticlockwise on
screen. The turn input is negated at the boundary in `CitadelApp`, not in the rules, which stay
tested against the engine's own compass.

The camera must take its direction from `GameState.forwardX/forwardY` and nothing else. It once
built its own `(cos a, sin a)` while movement used `(-cos a, sin a)`: they agree on Z and differ
on X, so facing north or south felt fine and facing east or west walked you backwards.

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
- A pass of `sv_DoMMove` is one vector of `sv_WalkSpeed` along the view, turned by 0, ±64, ±128,
  192, 256 or 320 for the eight key combinations, so a diagonal is as long as a straight line.
  `sv_rotate` floors (`add.l / swap`): 250 due north is 249 units.
- Collision clamps the in-cell position *after* the move (`sv_Border`), which is what produces the
  sliding along walls: within 307 of a shut face, X first (west, else east), then Y (south, else
  north), the far side tested with `>= 717`.
- Corners are `br2_UP` / `br2_DOWN`: in a corner zone, if either of the two faces meeting the
  corner from the neighbouring squares holds *anything* (`tst.b`, open doors included), push back
  along the axis that went in **less**, X on a tie. At `LEVEL01A` (14,5) an in-cell (200, 740)
  comes out (200, 717).
- The enemy whose number is in the square's byte 7 blocks the player when within 300 on both
  axes: `sv_LastPos` goes back into `sv_PosX` and the walls' clamping is discarded with it. A
  burning one costs a point, SOUND 13 and SCROLL 71 (`br2_COLUMN`).
- A face is passable when its value masked with 62 is 0, 32, 38 or 54 — nothing, either door type
  open, or a broken door (`br_ChkDoors`).
- Enemy steps are refused against the cell the step began in, one axis at a time. Testing only
  the destination cell's own faces lets a diagonal step slip past a sealed corner, which in play
  looks exactly like an enemy walking through a wall.
- Columns 1, 2, 4 and 18 have a box of 304..719 on both axes (`.br2_COLUMN2`); column 3 is
  decorative and column 4 is a teleport pad. Entering one pushes back to 302 or 722 on *each* axis
  the last pass's position was outside the box on. The barrel (18) jumps to `.br2p` past the low X
  test, landing on its `bmi` with the flags of `cmpi #18` — never minus — so its box runs to the
  west edge of the square, and walking north into it from west of 303 puts you at x 302.
- `br_BumpWall` counts a bump within 62 of head on and on a stride of 120 or more, and stores a
  rebound: `sv_LastMove+4` the stride, `+6` the direction reflected off the face. The next passes
  spend it 60 shorter each time and it *replaces* the player's own step — 250 into a wall comes
  back as 190, 130, 70 and 10 — so pushing into a wall bumps about once every six passes (a little
  more often as `EXCITE` shortens the stride). The point-blank flamer and bolter (190) and
  launcher (250) recoil through the same field, at `-sv_Angle`.
- `sv_AddMove` is what a shot (`oc_PlayerHit`, four times its vector) or an enemy walking into you
  (`me_PLAYER`, a quarter of its step) adds. The next `sv_DoMMove` replaces the step with it.
  The engine tests only the X word and reads both words under `andi #$ff`, which throws the sign
  away, so a shove west or south lands east or north. **Deliberate departure:** the port applies
  the shove in its own direction, at most 255 units on each axis, which is what the code meant.
- Vodka (`sv_Vodka`, 190 passes a bottle): each pass two random seven-bit signed numbers, -64..63,
  are added to the sideways and length components, the second `asr #3` skews the direction and
  turns the view back by as much. Standing still the drift goes backwards: with no key pressed
  `sv_MouseMove` still calls `sv_DoMMove`, with d3 = 1.
- Angles are always even (`andi #$1fe`).

Doors (`br_Chk_OPEN`, `OpenCloseDoors`, `ServePriorDoor`):

- Each of the two channels, `sv_DoorFlag1` and `2`, is ported field for field
  (`GameState.DoorSlot`). Every pass `br_Chk_OPEN` clears the channel's flag and, unless the square
  carries the block flag, lists every face of its type in the square with its far side, sets the
  flag, and sets the counter from the face: 6 if shut, -1 if open, unchanged if moving — so a door
  caught half way keeps its count when you come back. The list is only rewritten when the square
  has such a door, so a door left behind for another square's stays as it was, open or half way.
- Door 1 counts 6 → 5 (clamped) → 4 … 0 showing 34 and is open on the **sixth** pass, and takes six
  to shut; door 2 counts 5 … 0 and is open on the **seventh**, seven to shut. The door sound plays
  whenever the flag differs from the one remembered at +44, so starting to open and starting to
  close; door 1 plays SOUND2 16 instead of 9 on a level whose header word 26 is set
  (`Level.doorSound16`); door 2's SOUND2 10 comes from a point a square east and half a square
  south of the door, `addi #512,d0` being written twice.
- `oc_FixMap` writes the whole byte, so a door that moves loses any blood on it; and since
  `br_Chk_OPEN` compares the whole byte, a bloodied door never opens by itself.
- A command door (`rm_OpenDoor` / `rm_CloseDoor` from a switch or a card) is skipped if already
  there or moving, and otherwise queued on its channel's priority table at +24, appended to
  whatever is queued, with the last command's flag. `ServePriorDoor`, after `Check_RMB`, hands it
  to the channel once the channel is not in use — so it waits while the player keeps an automatic
  door of that type open — and it then animates with its sound like any other. While it runs,
  `br_Chk_OPEN` skips that channel. `rm_ServeSwitch` refuses with SCROLL 54, leaving the switch
  alone, while either channel has a priority door queued or running.
- The renderer asks `GameState.doorOpenFraction(x, y, dir)` per face: 1 for 32/38, the channel's
  counter for 34/40 (`oc_DrawOpen` draws every face holding that value from one shared texture),
  0 otherwise.

One departure from the original, deliberate: a move that crosses into another square through a
shut face is refused against the square it started in (`blockCrossing`). The original has no such
test; sober it never needs one (at most 250 or 300 units against a 307 clamp, and an
`sv_AddMove` shove at most 255), but a drunk stride of up to 313 takes
a player clamped against a wall six units through it, and `sv_Border` then clamps him on the far
side.

The keyboard maps onto `sv_joystick`'s eight directions; an analogue input shorter than 1 scales
the length, and its direction is still taken as one of the eight.

## Tests

`gradle :core:test` runs headless against the shipped maps and disks: about 190 tests covering parsing,
movement, doors, switches, pickups, line of sight, enemy movement, the animation tables, the
interference burst, exhaustion, the cheat codes, the save file, LevelLoop and MAPA, the music,
and the four data tables whose exhaustiveness the transitions
depend on. The one that earns its keep is a random walk of 20 000 steps across five levels
asserting the player never ends up on the far side of a shut face. It found both departures
listed above.

## State

Done:

- every data format read and converted, all 28 levels and all 8 complexes
- runtime level model: grid, walls, doors, columns, items, enemies, triggers, teleports
- jME geometry: walls, sliding doors, floors, ceilings, billboards for columns, items and enemies
- movement, collision, automatic doors, switches and their commands, teleports, level exit
- item pickup and a working inventory (weapons, ammo, cards, first aid, boost, vodka, warhead)
- enemy simulation: roaming, closing in, line of sight, shooting, recoil, burning, the death
  fall, corpses, blood and ammunition drops
- player weapons: bullets resolved at once, ammunition, weapon wear, the launcher's reload, the
  point-blank costs, per-species vulnerability mask
- barrels: damage, destruction and the explosion they set off
- sound: 25 effects, positioned in the world for doors and enemies
- the end sequence's animations decoded (Kane's `.k` repacking of IFF ANIM op 5, Hold-and-Modify)
- the ETAPY level sequence, with the inventory carried across levels
- the original in-game messages, with their variants taken in turn
- switches both ways: going in runs a record's first command list, coming out the list after its
  `-2` (`rm_ServeSwitch`); and everything `rm_HandUsed` says about what the hand touches
- card readers: a card is taken in hand with 7, 8 or 9 like a weapon and fired at the slot, which
  must be empty and want that colour (`rm_CardUsed`)
- items standing on tables: bit 5 of the item byte is their height, not part of the item
- the level clock, low-energy warning, death, and the automap
- projectiles in flight: fireballs, electricity, rockets, and `ro_SetExplode`'s twelve probes
- Savegame.DAT byte for byte, saved where SERVER saves (ENDLEVEL_PART) and loaded where it loads
- the compass needle, and in-flight object sprites resolved to their real 16x16 grid
- texture animation: the animated wall, the three animated columns, and the shared enemy walk phase
- `sv_MAKE_SZUM`, the interference burst when a shot lands or the player walks into a wall
- `TELEPORT`, the chaos-table dissolve from the view being left to the one arrived in
- the heart monitor, and the exhaustion behind it that scales the walk speed
- `ci_DrawWeapon`, the pixel-by-pixel reveal when the weapon in hand changes
- blood stains on walls (`dr_AddBlood`) and plaques on them (`dr_AddTables`)
- `DrawBomb`, the warhead assembly gauge, and the six messages that go with it
- `CheckCodes`, all seven cheats
- `sv_Death`, the blood that runs down the screen, and `p_FadeColors` after it
- the original status panel, with every widget where the engine puts it, and `sc_DoScroll`
- the server's own screens decoded: hangar, map, cover art, disk prompts, and the death picture,
  which the death card now shows, and the hangar, which the menu and the save slot picker sit in
- the server's menu itself: MainServer's four screens, in the panel, in its own font, revealed
  through HangarTab, with the cursor flashing through FlashTab
- SERVER's flow: `s`, StartGame, LevelLoop, LoadTrening, and what each of the engine's three
  answers leads to
- MAPA, the map between complexes, with the choice of three it gives and the centre's conditions
- ENDLEVEL_PART, the report after each level, and the save it offers
- DEATHSEQ, the death sequence, and GAME OVER
- END_PART2: kabina, start and explozycja, then the scrolling epilogue
- CREDIT_PART, the scrolling credits behind INFO
- the music: a ProTracker player for the game's ".pro" modules, and the five songs where SERVER
  plays them
- `TELEPORT` into an empty window on the way out of a level, and the pause key
- playable viewer, plus a screenshot path for verification

Not started:

- the intro, see [The intro](#the-intro)

## Saves

`Store/Savegame.DAT` is 768 bytes: a head block, then five slots, each the engine's
`STRUCTURE`, the 128 byte record the server keeps at `$7f800` and hands to the game between
levels. On the released disks it is sectors 1 and 2 of disk 4, which SERVER reads at boot and
writes back with `dl_START_s2` (`$8001`: save). `citadel.core.Structure` names its fields:

| offset | what |
|---|---|
| +0, +2, +4 | screen size, floors, details — the renderer's options |
| +6, +8 | energy (negative) and `sv_Glowica` |
| +10, +12 | enemies killed and seconds spent on the level just played |
| +14 | `sv_GUNS`, 18 words: per weapon condition, ammunition a pickup, ammunition |
| +50 | difficulty, 1 for easy |
| +60, +62 | the complex, and how many of its levels are behind you |
| +64 | one byte a complex, set once it has fallen |
| +72 | the slot's name, ended by `@` |
| +88 | occupied: `WczytajStan` will not load a slot with 0 here |
| +90 | enemies killed in all, which GoodExit adds +10 to |
| +92 | `KANE` on the last level |
| +98 | complexes taken, which the centre wants at seven |
| +100 | the interference switch |

The engine reads +0, +2, +4, +6, +8, +50, +100 and the guns when a level starts and writes them
back, with the kills and the time, when it ends. Everything else is SERVER's:

- **`s`** copies the head block to STRUCTURE, which is how a new game starts at complex 1 with
  150 energy, and keeps a pristine copy of it at `STRUCTURE+$300`. RememberOpt carries size,
  floors and details across a return to `s`; the difficulty is not carried, so it goes back to
  HARD.
- **LevelLoop** adds one to +62 before it looks the level up in ETAPY. So +62 counts levels
  finished, a slot saved after a level resumes at the next one, and a slot saved after the last
  level of a complex resumes in MAPA.
- **ENDLEVEL_PART** is the only place a game is saved. The slot gets a copy of STRUCTURE — +88
  comes along with it, since the head block has a 1 there — then its name: `NazwyPoziomow2` for
  the complex up to its `@`, and the level digit, so `STORES 2`. The file is written as the
  pristine head followed by the five slots.
- **LOAD GAME** copies the slot to STRUCTURE and goes straight into StartGame.

The port does the same with the same file. It reads it from disk 4 the first time and writes it
to the per-user folder, never to the disks. F5 and F9 in play are its own addition: F5 writes
the slot ENDLEVEL_PART would have written after the level before this one — STRUCTURE as this
level began, with +62 one less — so loading it starts this level over as it began; F9 loads a
slot the way LOAD GAME does. Saves made by earlier versions of the port did not set +88, so
their slots read as free.

## Hold-and-Modify

The cover picture and the animations are HAM6, which `CAMG` bit 0x800 announces. A HAM pixel's top two
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

Reaching the exit cell loads the next map and carries weapons, ammunition and energy across,
which is what the server does by keeping them in `STRUCTURE` between levels. Then the engine's
own start runs (`GameState.beginLevel`): energy is capped at 500, cards are cleared — every level
starts without them — and on easy the energy is topped up and the enemies thinned, see the
server's menu below. A new game starts from the head of `Savegame.DAT`: 150 energy, and 10
machinegun rounds a pickup, which override the engine's own -225 and -15.

`TextTable` reads `Store/TEXT.ENG` (or `.POL`, `.GER`). The file is split by `@`, one block per
message numbered from zero, and that numbering is exactly the `SCROLL` macro's argument. A block
whose first line starts with `^` holds lines that take turns: `.sc_ChooseText` keeps a read offset
in the two bytes after the `^` and moves it on with each use, so the variants come in order and
wrap. Walking into a plain wall prints message 1, the insults, in turn; a shut door gets 40 and a
door still opening 42 (`br_BumpWall`).

Message numbers are verified against their call sites at 40, 41, 44, 52, 53, 54, 65 and 76. Pickup
messages need no table of their own: every branch of `Take_Items` computes an index that works out
to the item value itself.

## Animation

`sv_DoAnims` runs once per pass of `MAIN_LOOP` and animates by rewriting offset tables in place,
so the same cell value draws a different sprite from one pass to the next. It advances four
counters at once:

| table entry | frames | what it is |
|---|---|---|
| `sv_WallOffsets[23]` | tiles 23, 24 | the one animated wall texture |
| `sv_BloodOffsets[5]` | two windows in tile 38 | a plaque; not drawn yet, see below |
| `sv_CollumnOffsets[4]`, `[12]`, `[20]` | four sprites each | three animated columns |
| `sv_WalkState` | 0, 6, 4, 2 | the enemy walk phase: +2 in each of the three `sv_DAdo` calls |

`sv_WalkState` is a single global, so every enemy on screen steps in time; the port keeps one
counter on `GameState` and feeds it to all four. `LEVEL01A` alone has 48 animated wall faces and
121 animated columns, so this is not a subtle effect.

The animated wall faces are batched into one mesh with a swappable material rather than one
geometry each; the columns are already billboards, so they only change material.

## Exhaustion, and the heart monitor

`DRAW_HEART` looks like a decoration and is not. The word at `sc_PosTab+2` is the length of the
trace cycle, so a smaller value repeats the spike sooner and the heart visibly races — and
`sc_NormWalk`, at the bottom of the same routine, reads that same word to set the walk speed:

```asm
sc_NormWalk:    lea     sc_PosTab(pc),a1        ;speed & exhaust
                move    2(a1),d0
                addi    #288,d0                 ;max 512
                move    sv_WalkSpeed+2,d1
                mulu    d0,d1
                lsr.l   #8,d1
                lsr     d1                      ;/512
                move    d1,sv_WalkSpeed
```

`EXCITE` knocks `11*4` off the word, down to a floor of `11*4`; `DRAW_HEART` adds 4 back whenever
its own countdown expires. So the beat runs between 44 and 224, the speed between `332/512` and
`512/512` of base, and a fully winded player walks at **65%** until they get their breath back.

Recovery is 4 every 25 VBLANK frames standing still and every 50 while moving — `sv_Flag+2`, set
by `sv_DoMMove` and cleared by `sv_joystick` each pass. From the floor back to rest is 45 steps,
so about 22 seconds of standing still or 45 of walking.

`EXCITE` fires when the player is hurt (`oc_PlayerHit`), walks into a wall (`br_BumpWall` — see
**Bumping into things** for what counts as walking into one), or
sets off their own fireball, bolt or rocket at point blank range. A speed potion takes the base
speed straight and ignores all of this, which is the `tst sv_WalkSpeed+6` at the top of the block.

The trace itself is a 29 column window onto a 32 entry ring, one vertical run per column. The
`bset/bset/bclr/bclr/bset` pattern over five interleaved bitplanes spells out index 19, the same
green in every palette.

The three point-blank cases also cost energy — 5 for the flamer and the launcher, 1 for the
bolter — and that is ported. They also throw the player back through `sv_LastMove+4/+6`, 190 or
250 at `-sv_Angle`, spent like a wall's rebound (see **Movement rules**).

## The status panel

Three files beside the `.VIR` data had never been read, and each one's size says what it is:
`WINDOW1.RAW` is 40 000 bytes, which is 320 by 200 in five interleaved bitplanes and matches the
`($9c40 razem)` on the `screen` equ; `NUMBERS.RAW` is 430, the `$1ae` of `sv_Numbers`; and
`FONTS01.FNT` is 768, the `$300` of `sv_Fonts`.

So the original HUD is a whole screen with a hole in it. The hole is at `sv_Leftoffset * 8 = 64`
across and `sv_Upoffset = 16` down, 192 by 128, and the artwork clears two extra pixels all round
for the bevel.

`NUMBERS.RAW` holds two sets of digits, not one. `tc_DrawCounter` steps `lea 20(a5),a5` through
ten digits of 16 by 18; the 70 bytes after them are indexed by `tc_DrawCardCnt` with the count
itself and stepped `addi #10,d0`, which makes ten more of 8 by 7 for the card readouts. They look
like a bar graph until you draw them.

Both are one bitplane, and the engine ORs the set bits in with a colour while leaving the cleared
ones showing the panel behind — so a set bit is the plate and the digit is the hole in it. That is
why the readout looks like a segment display going dark rather than lighting up: the artwork has
`888` printed in every cell and the counter paints over the segments it does not want.

`FONTS01.FNT` is an ordinary 8 by 8 font: 96 glyphs of 8 bytes, ASCII from space to DEL.

### Where everything goes

Every widget is addressed as an offset from `screen+$7d00`, and $7d00 is 32000, which is 160 rows
of 200 bytes — so the panel is the bottom 40 rows and the offsets read straight off as a layout,
one row being five bitplanes of 40 bytes:

| label | offset | row | x | what |
|---|---|---|---|---|
| `sv_BombPos` | `+$320` | 164 | 0 | the warhead gauge |
| `sv_Counter1` | `+$640+7` | 168 | 56 | NRG |
| `sv_Heart` | `+$640+13` | 168 | 104 | the HI TECH trace |
| `sv_Compas` | `+200+18` | 161 | 144 | |
| `sv_Weapon` | `+200+23` | 161 | 184 | the weapon in hand |
| `sv_Counter2` | `+$640+28` | 168 | 224 | AMMO |
| `sv_CardCnt` | `+600+39` | 163 | 312 | the three ID CARD counts |
| `sv_ScrollArea` | `+$1770+40` | 190 | 0 | the message line |

Two of those are worth stating plainly because the port had them elsewhere. **The weapon in hand
is a 32 by 27 icon in the panel**, not a sprite over the view — `ci_DrawWeapon` writes to
`sv_Weapon`, which is in the strip. And **the compass and the heart monitor have slots waiting for
them** in the artwork: the grid box, and the one labelled HI TECH.

The counters are white and turn red when the reading is low (`moveq #16,d1` against
`moveq #9,d1`), and `ci_NewWeapon` prints no ammunition counter at all for the bare hand — which
is just as well, since its 1111 would send `divu #100` off the end of the digit table.

The port draws the strip across the bottom of a full screen view rather than framing the 192 by
128 window the panel was built around.

The hangar picture has the same trick played on it. `MainServer` copies from `SERoff`, which is
`iff_screen+[53*40*6]+12`, and walks 612 plane rows of 16 bytes — six planes deep that is x 96 to
223 and rows 53 to 154, the panel the artwork leaves for the server's menus. The save slot picker
goes in there.

### The message line

`sc_DoScroll` has two speeds, and that is what makes it readable. With a message still to print it
shifts a whole byte and pokes the next glyph into the rightmost column:

```asm
.sc_MoveText:  lea sv_ScrollArea-34,a2
               REPT 33
               move.b 1(a2),(a2)+      ;eight pixels at a time
               ENDR
```

With nothing left to say it shifts a single pixel instead, rolling each row through the carry:

```asm
.sc_DS1:  move #%00000,CCR
          REPT 17
          roxl -(a1)                   ;one pixel
          ENDR
```

So a message is pushed in at a character a frame and then drifts out at a pixel a frame, taking
five and a half seconds to clear the window. `sv_ScrollArea` is the address one past the right
hand end, and it lands in bitplane 1 — so subtracting the 34 bytes puts the window at byte 6 of
bitplane 0: x 48 to 319 on row 190, drawn in palette index 1.

## The warhead gauge

`sv_Glowica` starts at 0 and `ti_Head` takes 2 off it for each section picked up, down to -12.
`DrawBomb` turns that into one of six stages with `subq #2,d0 / lsr d0 / mulu #6` and blits a
48 by 31 picture, drawing nothing at all until the first section is in hand.

The picture comes from `Store/Bomba.rawb`, which the repository has and which nothing had read
yet. Its layout falls straight out of the blit:

```asm
move #[31*5]-1,d1
db_2:   move.l  (a1),(a2)       ;4 bytes
        move    4(a1),4(a2)     ;and 2 more, so 6 bytes = 48 pixels
        lea     36(a1),a1       ;source stride 36, six stages of six
        lea     40(a2),a2       ;destination stride 40, one bitplane row
```

155 steps of 36 bytes is 5580, exactly the file size and exactly the `$15cc` the `equ` declares.
The destination walks one bitplane row at a time through a five plane interleaved screen, so step
i is row i / 5 and plane i % 5 — 31 rows of 48 pixels. The six stages are a circuit board filling
up with components, ending in the finished casing.

The section messages are the exception to the rule that a pickup's message index is its item
value: `ti_Head` has no `SCROLL` of its own, and `DrawBomb` prints one at `77 + stage`, so the six
sections run from "FOUND THE FIRST PART OF THE BOMB!" to "YOU FOUND THE LAST PART OF THE BOMB!!!"
instead of all sharing one line.

## The cheat codes

`CheckCodes` watches a sixteen byte ring of recent keystrokes and compares it against seven
tables. The tables are stored obfuscated and getting a plaintext out takes two steps, not one.
`cco_check` does `neg.b d3` to undo the negation — and what it compares against is **not ASCII**.
`cc_NoKey` saves the byte the CIA handed over, untouched, and the Amiga keyboard sends a key code
rotated left one and inverted:

```
stored = -( ~ROL(rawkey, 1) )
```

Three labels in version 1.30 still carry the author's own comments — `;kiss`, `;kicaj`, `;kotek` —
and the codes there decode to exactly KISS, KICAJ and KOTEK, which is what confirms the scheme.
By 1.34 the codes had been changed and the comments had not, so in the version this port follows
they are stale and point at the wrong words.

| code | label | effect |
|---|---|---|
| `KBBMORMS` | `EnergyCode` | energy to 666 |
| `HOTKISS` | `AmmoCode` | every weapon, 666 rounds each, all three cards |
| `IDAHO` | `WallCode` | a 1024 unit hop along the view, walls included |
| `HOTKIWI` | `DeathCode` | toggles `sv_NieUmieraj`: energy stops draining |
| `LORIEN` | `MapCode` | fills in the whole automap |
| `KITIARA` | `LevelCode` | ends the level |
| `ALIBABA` | `BombCode` | all six warhead sections |

`KBBMORMS` is the odd one out and is not a transcription slip: it is byte for byte identical in
1.30 and 1.34, where every other code changed.

Immortality is not a flag anyone checks before doing damage. `TEST_COUNTERS` compares the energy
reading with the one it kept last pass and, while `sv_NieUmieraj` is set, simply puts the old one
back — so the hit lands and is then undone. The port does the same.

Every key goes into the ring, movement included, which is why the codes sharing letters with WASD
cost nothing.

## The server's menu

MainServer keeps its menus as six lines of sixteen characters with a flag byte in front of each,
and the port takes them verbatim:

```
s_scrMain1:  1,"     START      "   s_scrTRENING: 0,"    DUNGEONS    "
             1,"   LOAD GAME    "                 0,"    STORES 1    "
             1,"    OPTIONS     "                 0,"    STORES 2    "
             0,"    TRAINING    "                 0,"   LABORATORY   "
             0,"                "                 0,"     SEWERS     "
             0,"      INFO      "                 0,"      MENU      "
```

DrukTekst puts the block at `SERscr+8*16*6`, eight rows into the panel, and steps `15*16*6` a
line — so the lines are at rows 61, 76, 91 and so on, sixteen characters from x 96. A glyph's set
bits go into planes 1 and 4 and out of the others, which is colour 18 of the hangar's palette.
PostawKropa marks the chosen line with an eight pixel circle at `iff_screen+[63*40*6]+11`
stepped by `kropa*40*15*6`, a dot either side at x 88 and x 224, in plane 0 alone: colour 1.

The flag byte picks between two glyph routines. The second, `m_CopFont2`, rotates the glyph's
byte four bits and writes the two halves into neighbouring bytes, so the glyph lands four pixels
further right — not wider. The flagged lines are the ones with an odd number of spaces round
them, so the shift centres them.

The text is not drawn on the screen but into a copy of the panel, `SERscr`, and then copied over
a byte column at a time in the order `Store/HangarTab.dat` gives: 816 entries, each a byte and
its twin 51 rows down, 32 a VBLANK. That is the panel dissolving into the next menu. While the
menu is up NewLev3 writes the next of FlashTab's sixteen greens into colour 1 every other VBLANK
(`move 2(a1,d0.w),Maincopper+6`); colour 1 is a register, so anything else in the hangar drawn in
colour 1 flashes with the dots. Leaving LOAD GAME, the options or TRAINING puts the cursor back
on the line that opened it (`move #1,Kropa`, `#2`, `#3`).

LOAD GAME is the same panel: WczytajStan copies the five slot names in, left aligned, with MENU
under them, and refuses a slot whose +88 is 0.

All five training entries turn out to ship. `FileStructure2` names a map for each — M1T, M3T,
M3K, M5T, M6T — and `LEVEL03K.MAP` is there beside the other four, so STORES 2 works too. None of
those five maps was reachable from the port before. The complex numbers double as the naming: 1 is
the dungeons, 3 the stores, 5 the laboratory and 6 the sewers.

The options screen shows the original's four, as `u_Poustawiaj` writes them into
`s_scrOPCJE` (typo in SCREEEN included), and they change STRUCTURE the way `UstawOpcje` does.
Three of them are the renderer's — screen size, detail level, floors on or off — and mean nothing
to a GPU, so they are kept, saved and ignored. The difficulty, STRUCTURE+50, is the one the
engine reads, in five places: at
the start of a level it raises an energy reading under 90 to 90 plus a random 0..63, empties
every fourth slot of the enemy table and cuts both species' aggression to 4/5; `Take_Items` gives
a weapon picked up 70 more condition; and `br_BumpWall` skips the point of energy, so walking into
walls is free.

### KANE.fnt

The menu font, 3072 bytes, and the index arithmetic in DrukTekst gives its shape away:

```asm
subi #32,d0
cmpi #32,d0
bmi.s .m_d3
subq #1,d0          ;there is no glyph for '@'
.m_d3:
move d0,d1
andi #1,d1          ;which of the pair
lsr  d0             ;which pair
lsl  #5,d0          ;32 bytes to a pair
or   d1,d0
```

So a glyph lives at `(index >> 1) * 32 + (index & 1)` with its thirteen rows two bytes apart, the
odd bytes belonging to its neighbour. The `subq #1` is the part to remember: the font has no '@',
so everything above it shifts down one and 'A' lands at index 32.

## The map between complexes

MAPA is where the next complex is chosen, and the order is not fixed. `Kolejnosc` lists the seven
in the order they are meant to fall — 2, 5, 6, 3, 4, 7, 8 — and Printnij walks it skipping the ones
already taken:

```asm
moveq #3,d3
lea   Kolejnosc,a1
ma_p2: move (a1)+,d2
      tst.b 64-1(a2,d2.w)      ;already done? then it does not use up the budget
      bne.s ma_p2
      cmp   d1,d2
      beq.s ma_p1              ;this is the one asked about, so it is open
      subi  #1,d3
      bne.s ma_p2
      ;fell through: no access
```

So the first **three** still standing are open at once, and finishing one lets the next in rather
than simply moving a pointer along. That is the whole structure of the campaign, and the port had
been walking ETAPY straight through instead.

`Kon2Tab` says which complex each of the seven targets is — 7, 4, 2, 3, 5, 6, 8 for PRISON,
HANGAR, POWER PLANT, STORES, LABORATORY, SEWERS and CENTER — and `Kon1Tab` is the way back, giving
the target the cursor lands on once a complex has fallen. Complex 1 is not on the map at all: it
is where the game begins.

The centre is the exception. `ma_wybrany` wants `STRUCTURE+98` at seven, so every complex behind
you, and `sv_Glowica` at -12, so the warhead complete; short of either it prints
"YOU MUST HAVE THE WHOLE BOMB TO ENTER!" and leaves you on the map.

### Drawing it

The picture is 320 by 192 and the screen is 200, so the eight rows left over are the status line —
`ma_draw` writes seven of them with `Font8`, which is the in-game 8 by 8 font, one bitplane, forty
characters. `KomplexTexts` is the template and `ma_CopIt` drops eleven characters into each of its
two fields.

`ArrowPos` holds the seven cursor positions as offsets into the six plane picture, so
`[row*40*6] + byte` unpacks each one into a row and a byte column. The arrow itself is
`strzalka.rawb`: 132 bytes, which is 2 by 11 in six planes, blitted into the picture and therefore
in its palette. `map_NEWLEV3` bobs it through `Asinus`, a 32 entry table of heights up to 9 — and
`ClrArrow` restores 20 rows behind it, which is 11 plus that 9.

The status line is not part of the picture. MAPAcopper turns the bitplanes off at line `$f6` and
on again, one deep, at `$f8` — row 194 — with a colour of its own, `ma_col`, on black. Every
change of the line goes through `ma_out` (that colour from `$eee` down by `$222` a VBLANK),
`ma_clr`, `ma_draw` and `ma_in` (back up). `Printnij` fills `KomplexTexts` at offsets 9 and 28.
A refusal stays up for 110 VBLANKs before the status comes back (`ma_uf`); a choice prints
`CHOSEN` and the name (`OKtext`, the name at 9 + 11), waits 150, sets STRUCTURE+60 from Kon2Tab
and +62 to 0, and fades the map out. MAPA marks the complex just left on the way in, so it is
STRUCTURE that says which have fallen, and loading a game reads it from there and nowhere else.

## The front end as a script

SERVER is straight-line code that waits for the vertical blank with its `VBLANK` macro and leaves
the per-frame work — the music, the cursor, the flashing, the arrow — to a level 3 interrupt.
`citadel.game.Server` runs it the same way: on a thread of its own, in lockstep with the render
thread, which lets it run up to its next `vblank()` once a frame and runs the current interrupt
first. A `move #110,d0 / VBLANK / dbf d0` is `vblanks(111)`, and the transcription follows the
assembly loop for loop:

```
s           STRUCTURE from the save file's head block, mod.aconcagua, the hangar
MainServer  START, LOAD GAME, OPTIONS, TRAINING, INFO (the credits)
StartGame   LoadFirstData (the hangar fades, the music stops), then
LevelLoop   +62 moves on and ETAPY says what next: a map, MAPA, or END_PART2
            the engine answers +   GoodExit: +90 += +10, ENDLEVEL_PART, LevelLoop
                               -   DEATHSEQ, then s
                               0   ESC: s
LoadTrening a training map; whatever happens, s (DEATHSEQ first if killed)
```

Playing a level is the one thing it hands over: the script asks for the level and waits, frame by
frame, until the engine's side says how it went. The screen it draws on, `Display`, is 320 by 200
palette indices and the colour registers that show them, with Extra Half-Brite, so the fades work
on the registers exactly as `se_setcolors` (each component one step towards the target, sixteen
steps), `fadecolors` (each component one step down, seventeen steps) and the death sequence's
`setcolors` (the target times d0 / 16) do. All of them are two VBLANKs a step, the death
sequence's three.

ESC is not a way out of the menus in the original: in KeyMode 0 the keyboard handler takes ESC,
RETURN, SPACE and ENTER all as fire. The port keeps that and lets ESC quit from the cover
picture, which the original never returns to.

## Music

The music is ProTracker, repacked by the authors into a ".pro" layout their replayer
(`mt_init`, `mt_music`) reads: 31 sample headers of 8 bytes — a ProTracker header without the
name — the song length at 248, then four position tables of 128 bytes, one per voice, each
naming a *track* of 64 one-word rows; the words index a table of the distinct 4 byte notes the
song uses, and the samples follow. Where a ProTracker module stores one pattern of four channels
per position, this stores a track per voice per position and shares identical tracks and notes.
The number of tracks is not stored: `mt_init` scans the position tables for the largest entry.
`citadel.core.ProModule` reads it; `MusicTest` checks the layout accounts for every byte.

`citadel.core.ProTrackerPlayer` renders a song to 16 bit mono. It comes from the author's
Breathless port and is adapted to this replayer: `mt_music` runs from the VBLANK interrupt, so a
tick is 1/50 s and `Fxx` is always a speed, never a tempo. A song is rendered whole on a worker
thread and looped, which is what `mt_music` does when the song position wraps. Where SERVER plays
what:

| song | where |
|---|---|
| `mod.aconcagua` | `s` to LoadFirstData: the hangar, its menus, the credits |
| `mod.brief1` | MAPA |
| `mod.MiedzyEtapy` | ENDLEVEL_PART |
| `mod.death` | the death sequence, from DET |
| `mod.dirranbandi` | END_PART2, the animations and the epilogue |

The engine has no music.

## Between levels

ENDLEVEL_PART puts `MT.raw` up — four planes stored one after the other, not interleaved, with
16 colours after them — and prints into a fifth plane whose 16 colours are all `$fe0`, so the
text is yellow whatever is under it. `el_text` is positioned text: a 0 byte, a byte column and a
row, then characters, sixteen rows of KANE.fnt each:

```
     DUNGEONS LEVEL 1 COMPLETED!           NazwyPoziomow, and +62
ENEMIES KILLED:              017          +10, three digits
ELAPSED TIME:                1:12:34      +12, the hours stop at 9
PARTS OF BOMB:               3            -Glowica / 2
      DO YOU WANT TO SAVE THE GAME?
             NO          YES
```

A frame `el_RamkaPos` says where is drawn round the choice by `EL_NewLev3`, which blinks it, 20
VBLANKs on and 20 off, while it stays put. Left and right move it between NO and YES. YES offers
the five slots and QUIT; a slot is saved as [Saves](#saves) describes, and `OK !` stays up for 80
VBLANKs. Everything fades out, the music stops, and LevelLoop goes on.

## The death sequence

After `sv_Death` and `p_FadeColors` the engine returns, and SERVER loads DET from disk 5 and runs
it (Death14.ss). zabity fades in to `mod.death` (`setcolors`, three VBLANKs a step), and stays up
to 40 seconds (`move #50*40,d0`) or until ESC, SPACE, RETURN, ENTER or fire. Then 49 passes of
`DorLoop1` close GAME OVER over it: the copper list shows `GameOver+20000` less two rows a pass
from the top of the screen down to a wait that moves down two lines, zabity below that, and
`GameOver+20000` again from a wait that moves up two lines from the bottom — the picture's two
halves sliding in from the edges. The finished GAME OVER, in zabity's colours, stays up to 44
seconds, fades, and SERVER goes back to `s` with a fresh STRUCTURE. `GameOver.rawb` has no
palette of its own; it is in DET after zabity, which is where the port reads both.

## The end

When LevelLoop finds the 1 in ETAPY after the centre, END_PART2 plays the three `.k`
animations — kabina, start, explozycja, in that order — to `mod.dirranbandi`, nine VBLANKs of
black between them. Each runs on a row of the `anims` table: (VBLANKs a tick, ticks) pairs. The
rows are labelled anim1 to anim3, but END_PART2 writes the address of the animation it is about
to play over the row `AnimsAdr` points at and ReplayPart moves on a row afterwards, so the rows go
to the animations in the order played: kabina gets the first. Then `ko_scroll` clears the screen,
waits 71 VBLANKs, and scrolls `Koncowka` up a row every three: one plane, KANE.fnt, a line every
16 rows, colour 1 graded by the copper list from `$222` at the top to `$fff` and back down at the
bottom. Holding a key or a button stops it; at the end it waits for one, and SERVER goes back to
`s`.

CREDIT_PART, behind INFO, is the same scroller over `MT.raw`, a row every two VBLANKs, in the
yellow plane; SPACE holds it, RETURN, ESC, ENTER or a button leaves.

## The intro

Not ported. The intro is a separate program, IntroReplayer, and its data is on disks 1 to 3:
there are `.k` animations unpacked at bytes `0x9cc00` (49 frames), `0xc65ae` (17) and `0xd1530`
of disk 1, at `0x94800` and five more places on disk 2, and at `0x73800`, `0x8814c` and
`0xa7e8e` on disk 3. But they are not whole files: the player track-loads overlapping ranges,
copies the start of one animation out of the tail of another (`anim1+215344`, 37794 bytes) and
single frames between them (`anim3b`, `anim6`), and the third animation on disk 1 runs off the
end of the disk. Rebuilding that, the HISTORIA text screens over `Panel_Hires` and
`mod.IntroDela` is a job of its own. The port starts on the cover picture the intro ends on.
`citadel.tools.AnimReader` still applies IFF ANIM deltas to the previous frame rather than to the
one two back, which matters only for the IFF originals; `KAnim` decodes the `.k` files right.

## Dying

`sv_Death` is blood running down the screen, and it is on its own clock: `EndLevel` only reaches
it every fourth call, with `subi #1,sv_EndLevel+2` against a reload of 4, and each of its 71
passes waits three VBLANK frames. That is about four and a half seconds.

Each pass walks 320 columns. `DeathTab` is a 320 word table holding how far down each column has
got, and each pass adds a fresh 1 to 4 taken from two bits of Amiga ROM, which is what gives the
leading edge its ragged look:

```asm
andi #3,d0
addq #1,d0          ;new Y
move (a1),d2        ;old Y
add  d0,(a1)+
```

The rows it fills use `and d4` on planes 0, 2 and 4 and `or d3` on planes 1 and 3, spelling out
index 2 + 8 = 10 — `#BB0000`, another of the fixed engine colours.

`EndLevel` returns 0 the whole time this runs, which is how the engine keeps the game open until
the sequence is over. The port does the same job by staying in its PLAYING mode. Then comes the
fade, and the server's [death sequence](#the-death-sequence).

## Fading out

`p_FadeColors` multiplies every four bit palette component by `d0 / 16`, counting d0 down from 16,
two VBLANK frames a step. Scaling a colour towards zero is the same thing as blending it towards
black, so a black plate whose alpha climbs in the same seventeen steps is exact rather than an
approximation.

It runs at the end of both ways out of a level: after `TELEPORT` when the exit is reached, and
after `sv_Death`. So a level change happens behind a black screen rather than as a cut, and the
port now does that too.

On the way out through the exit EndLevel first empties the window in the hidden screen (`.Clrscr`,
or the window background from `sv_WindowSav`) and then calls `TELEPORT`, so the view breaks up
into black before the fade. The port captures the frame as it does for a teleport and eats it
away over a black plate.

## Pausing

P toggles `sv_Pause` and prints message 37 or 38; while it is set MAIN_LOOP does nothing but
spin, one VBLANK a turn, counting down from 750 and printing message 41 each time the count runs
out — every 15 seconds. `NewLev3` keeps the level clock and the low-energy beep going and skips
`Draw_Heart`, so the heart neither races nor recovers.

The map is a pause too. M or TAB flips `sv_MapOn` whether paused or not, and `MAIN_LOOP` hands
over to `ServeMap`, which flips `sv_Pause` on the way in and again on the way out and prints
message 74 with the square written into it (`SetLocation`: "YOUR LOCATION: 07-42"). While the map
is up every key but the map's is ignored (`cc_IsMap`).

`GameState` owns all of this — `togglePause`, `toggleMap`, `isHalted`, and an `update` that only
runs the vertical blank while halted — and `CitadelApp` forwards the two keys and ignores the rest
while it is halted.

## Bumping into things

`br_BumpWall` opens with two tests, and the port had only the second of them:

```asm
br_BumpWall:    bmi.w   br_BW1
                cmpi    #124,d5
                bpl.w   br_BW1
                move    sv_LastMove,d5      ;bump into wall
                cmpi    #120,d5
                bmi.w   br_BW1
```

The second is the stride: the move has to be 120 units or more. The first is the **angle**, and it
is the one that matters. Every caller hands the routine `sv_LastMove+2` — the direction of the
move — less the angle that would take it straight into that face, plus 62; landing outside 124
throws the collision away. So a bump only counts within 62 units of head on, about 44 degrees, and
brushing a wall as you walk along it is free.

Without it, walking along any wall bumps every single pass: a point of energy, an insult, a jolt
to the heart and a burst of interference, over and over. That is what it looked like in play.

The engine's head-on directions, in `sv_rotate`'s terms (`256 - angle`), are 0 east, 256 west, 128
north and 378 south. The port tests them in those terms, as the engine does, so the south one's six
unit skew from 384 is kept, in the window and in the reflected rebound (`378 + 378`).

**Z** toggles the burst, as `cc_z` does — *zakłócenia* is Polish for interference — printing
DISTURBANCES: ON or OFF. `MAIN_LOOP` tests `cc_RequestTab2` before calling `sv_MAKE_SZUM` and
leaves the counter running underneath, so the port does the same.

## Interference

`sv_MAKE_SZUM` - *szum* is Polish for static - runs for two passes of `MAIN_LOOP` after a shot
lands on the player (`oc_PlayerHit`) or the player walks into a wall at speed (`br_BumpWall`). It
picks 81 rows out of the 128 row view, with repetition, by exclusive-oring pairs of words read
straight out of Amiga ROM, and fills each one across with the byte `$88`.

`$88` is a colour, not a dither pattern. The `ConvBits` macro rolls bit 7 into bitplane 0 and bit
3 into bitplane 4, so a byte with only those two bits set is index `1 + 16 = 17`, which every
complex palette holds as the same pale blue `#9999DD`. Solid full-width lines, in other words, so
the port draws them from a texture one pixel wide stretched across the window.

`oc_PlayerHit` also turns the player by a random `-8..7`. Both events push the player as well:
`br_BumpWall` writes a rebound into `sv_LastMove+4/+6`, and `oc_PlayerHit` adds four times the
shot's velocity to `sv_AddMove` - which `sv_DoRotIt` reads back under `andi #$ff`, destroying the
sign and sending a negative push the wrong way. The rebound is ported as the engine has it; the
shove keeps its direction in the port, see **Movement rules**.

## The teleport dissolve

`TELEPORT` is not a distortion, which is what the name suggests; it is a dissolve. Both views are
in hand — the new one is already drawn in the hidden buffer — and it copies the new over the old
four pixels and two rows at a time, in the order `Store/CHAOSTAC.DAT` gives, 32 VBLANK frames of
32 entries each.

The file is a **permutation of 0..1023**, and `te_loop3` reuses each entry at five offsets 1024
apart, so the walk visits 32 x 32 x 5 = 5120 cells. `divu d0,d5` with d0 = 80 splits an offset
into a nibble column and a row pair: 80 columns of four pixels is 320 across, and 5120 / 80 = 64
row pairs is 128 down. That is the whole view, each cell exactly once — which is why the
transition finishes clean rather than leaving patches behind. The narrow view setting takes
d0 = 48 and three repeats and works out the same way, at 192 by 128.

The port has only the new view, since that is whatever the scene renders, so it needs the old one
instead: the framebuffer is read once on the frame the teleport lands, held on top as a texture,
and eaten away by the same table. The camera is kept still for that single frame so the read
catches the view being left rather than the one arrived in.

## Changing weapon

`ci_DrawWeapon` runs every VBLANK but only does anything while `sv_ChaosAddr` is non-zero, and the
only thing that ever rearms it is `ci_NewWeapon`. **There is no firing animation in the original**
— the feedback for a shot is the projectile and the sound.

What it does is copy the new weapon into place a pixel at a time: 32 pixels a frame, in the order
`Store/CHAOSTAB.DAT` gives, which is a permutation of 0..863 — and 864 is 32 by 27, the weapon
window. `lsr #5` picks the row out of an entry and `andi #31` the column. It starts at the last
entry (`[32*27*2]-2`) and walks backwards with `subq #2,d0`, so a change takes 27 frames.

It copies rather than clears, so the weapon being put away is eaten by the one being drawn rather
than vanishing first; the port keeps the previous pixels in the buffer for the same reason. The
table addresses 27 rows and the converted sprites are square, so the rows are stretched to fit —
the same accommodation the teleport dissolve makes for the window size.

## Two things jME does quietly

**Layer order.** `GuiComparator` subtracts two Z values and **casts the result to int**, so
anything less than 1 apart compares equal and the draw order silently falls back to whichever was
attached first. The overlays are therefore whole numbers apart, in the engine's own order: the
dissolve and the interference paint the view, then the compass, the automap, the weapon and the
readout go over the result.

**Colour space.** Gamma correction is on by default, so a `Texture2D` built by hand has to declare
`ColorSpace.sRGB`. Declaring `Linear` means the sampler skips the conversion, and every
hand-generated overlay — the automap, the compass, the heart monitor, the interference, the held
weapon — comes out visibly washed out next to anything the asset manager loaded.

**And one of ours.** `Assets.material`'s last argument used to mean both "flip the texture" and
"blend it", which is right for a billboard and wrong for anything blended onto a wall face: those
carry hand-built UVs with v=0 at the top and must not flip. Blood spent a commit upside down
without anyone noticing, because a splatter looks like a splatter either way. A KEEP OUT sign does
not. `Assets.decal` is the third combination.

## Decals

`dr_AddBlood` and `dr_AddTables` both composite onto a scratch copy of the wall texture at
`sv_WallOffsets[27]`, and both hand it back as wall id 28 with the face's mirror flag or-ed in, so
a decal is mirrored along with the wall it is on. There the resemblance stops, and reading one for
the other is what kept the plaques a mystery here for so long:

- **Blood** uses `AB_loop1`: 260 iterations of four longwords, a full 4160-byte tile, every
  non-zero byte overlaid. So it covers the whole face. Bits 6..7 of the wall byte, less one, pick
  one of the three 64x64 decals in tiles 35..37.
- **Plaques** use `AT_loop2`: **32 columns of 32 bytes** from offset 1056, and 1056 is
  `16*65 + 16`, dead centre of the face. The 2-bit field in cell byte 4 picks `sv_BloodOffsets`
  entry n + 2 after the `addq #2,d5` — a card reader with a red lamp, the same with a green one,
  or a KEEP OUT sign. `sv_DoAnims` alternates the sign with a second frame.

What threw me first time was counting non-zero bytes per quadrant of tile 38, finding two pairs
with matching counts and reading that as a vertical period. The red and green lamps have the same
pixel count because they are the same picture in two colours.

The port draws both as their own quad two thousandths of a unit in front of the face, the plaque
covering the middle half in each direction. There are 200 stained faces across the 28 levels and
none is on a door. 754 faces carry plaque bits, and all but 12 of them have a wall. Byte 4 holds
north in its top pair and west in its bottom one: the renderer masks N with `$c0` and E with
`$30`, and `rm_CardUsed` shifts by `(~dir & 3) * 2`. Reading it the other way round, as the port
first did, puts most of them on open faces.

## Projectiles

`Anim_Objects` walks a table of 30 slots. Types 3, 4 and 5 are the fireball, electricity and
rocket; 1, 2 and 6 are the impact, discharge and explosion marks. Bullets are type 1 objects too,
but only for the length of the trigger pull: `rm_HandGunUsed`, `rm_AmmoLoop` and `es_HandGun` run
the whole collision loop at once and then either free the slot or turn it into an impact mark.

Speeds are written as `400/8` and `600/8` because the stored vector is per sub-step, and
`Object_Collision` takes eight of them a call. Before each sub-step it ORs 1 into both components
(`ori.l #$00010001`) so its divisions are safe, which is why a 50 really moves 51 and a probe
along an axis drifts 8 units sideways. A flying object gets one call when it is fired and one a
pass after that, until its counter runs out: 30 calls for a fireball, 20 for the other two, 12 000
units each.

`Object_Collision`, per sub-step: a crossing of a cell boundary is worked out to the point it
meets the boundary and refused on that cell's face (when both axes are refused the X one wins —
the comparison meant to choose reads `14(a1)`, the next slot); columns 1, 2 and 18 stop it in a
190 box; the player is hit within 250 on both axes unless `sv_HitFlag` is set; an enemy within
200, found through byte 7 of the nine surrounding cells, whatever its state. So a dying or burning
enemy still stops shots.

What a hit becomes: electricity a discharge mark; a fireball explosion mark `$0604` with SOUND2
11; a rocket goes off. Mark lifetimes follow from their counters: an impact or discharge lasts 3
calls of `Anim_Objects` (counter 0 to 3), an explosion `7 - counter` — 4 for `$0603`, 6 for
`$0601`, 7 for `$0600`, 3 for `$0604`.

**A rocket has no splash radius and throws nothing.** `ro_SetExplode` backs the rocket off by an
eighth of its sub-step vector, makes it mark `$0603`, and resolves twelve fireball probes on the
spot with `Object_Collision`: the four orthogonal `ro_DirTab` vectors for one call (600 units,
ending as mark `$0601` if they hit nothing), the four diagonals at `700/8` per axis for one call
(`$0600`), and the four orthogonal again for two calls (1200 units, `$0600`). A probe that hits
anything is freed; as fireballs they set alight what they kill. Barrels act as walls meanwhile
(`eh_FirePos+4`), so one blast cannot set off the next, and the player is spared when
`sv_HitFlag` is up: always for his own shot (`Check_RMB` holds it), and when the rocket itself
struck him (`ao_f2`, `es_Launcher`).

The second ring is a bug in the released engine: `lea ro_DirTab,a5` has no `(pc)`, and in the
shipped binary it resolves to the absolute address `$7020A8`, which is not the table, so its
vectors are whatever happens to be there. The port takes the source's evident intent and reuses
the four orthogonal entries.

### Barrels

Column 18 is a barrel, *beczka*. `Object_Collision` keeps the damage of up to sixteen of them in
`oc_beczkas` by map offset: a bullet adds 5, anything else 30, and at 30 the column and the item
in the cell go and the hit returns -1, which every caller answers with `ro_SetExplode` at the
barrel (`rm_beczka1/2`, `.rm_beczka3`, `es_beczka1`, `ao_f2`). Electricity bounces off. A volley
that sets one off stops there.

## Counters and the automap

`NewLev3` ticks the level clock once every 50 frames, `Test_Counters` turns the energy readout red
and beeps below 10, and zero energy ends the level as a death. The beep's countdown
(`do_pikaj+2`) starts at 50 and is reloaded with 55 only when it runs out, never when the warning
starts, so the first beep waits out what is left of it. `Test_Counters` only acts when the reading
changes; while HOTKIWI is on it puts any change back, the energy cheat's -666 included, since
`CheckCodes` writes `sv_Energy` alone. There is **no energy regeneration**
anywhere in the engine: energy only ever goes down, except for medikits.

Walking into a wall costs a point (`br_BumpWall`), but only on a real stride — the routine wants
the movement vector at 120 or more, so edging along a wall is free. Some weapons cost energy to
fire, too.

`UpdateMap` keeps one bit per cell and reveals the cell underfoot, then for each side whose face
byte is zero (`tst.b`, so an open door hides what is beyond) the neighbour through it, and from
that neighbour the two squares to either side whose faces towards it are clear: up to a 3 x 3
block. `Automap` paints that into a texture.

A speed potion saves the base speed and sets it to 300 for 300 passes (`ti_power`). Two in a row
save the first's 300 as the speed to go back to, so the base stays at 300 for the rest of the
level; a new level starts the potion and the vodka afresh.

## Enemies

The 16-byte enemy record is now fully accounted for: `+1` flags (**bit 0** selects the species —
bit 7 is unrelated, it only asks the renderer to draw that one behind a column), `+2` energy,
`+4`/`+6` world position, `+8` facing, `+10` walk speed, `+12` state, `+13` state counter, and
`+14`/`+15` the knockback: the low bytes of the vector of whatever last hit it.

`Move_Enemy` first lists byte 7 of the 24 x 24 cells from 12 behind the player to 11 ahead on each
axis, then serves only those, so distant enemies stand still. `me_MOVE` keeps an enemy 300 units
off a shut face and off the player, out of a blocking column's 210 box, out of any cell byte 7
already claims, and — `me_CORNERS` — away from a corner where either neighbouring face holds
anything at all. Stepping into the player shoves him (`sv_AddMove`, a quarter of the
step, see **Movement rules**), turns him by `asr.b #5` and, if the enemy is burning, costs him a point of
energy with SOUND 13 and SCROLL 71.

A blocked step is `me_RepTurn`: up to three random turns of `(r0 ^ r1) & $fe`, each retried — with
the vector saved before the first try, not one along the new facing. So a blocked enemy does not
move that pass, it only turns. A step that goes through turns one time in sixteen by up to 126.
Five GetRandom words in 65 536 make it roar.

`me_walk` aims through `me_angletab`, a 33-entry lookup by `(dx * 16) / dist`, rather than
computing the bearing; angles are kept even (`andi #$1fe`). The states and their counters, which
`dr_DrawEnemy` also draws as `counter / 4` for everything but walking:

| state | counter | what happens |
|---|---|---|
| shooting | 60, 64, 68, 72 | fires at 68; at 72 back to walking — and the `bne` after `move.b #0` falls into `me_hited`, so it ends on 64 after a knockback step |
| hit | 72, 76, 80 | two passes moved by twice the knockback |
| killed | 72, 88, 92, 96 | three passes falling, still in its cell and still stopping shots; then a corpse, 32 in byte 5 (96 for the second species), sv_NrKilled, and one time in 12.8 ammunition for its weapon, item `weapon + 7` |
| burning | 80, 84 | the energy word counts 55 down, so 56 passes of a random walk; then a burnt corpse, 64 or 128 |

A kill that is not by fire splashes blood: `$40` or `$80` at random on the face of the enemy's
cell the shot was going towards, and `$C0` on each face beside it three times in four, walls only.
The burning enemy last set alight owns the fire sample, looped on its own voice at the volume
`Sound_Distance2` gives each pass.

`me_killed`'s drop has a second roll "if you have this weapon", which tests the weapon's condition
word for a negative value. Conditions are never negative, so it never fires; the port keeps it as
written.

**Bit 0 set means the *first* species**, which is the opposite of what it looks like. Both
`EnemyHitServe` (`beq.s .me01` falls through to `sv_LevelData+4`) and `dr_DrawEnemy`
(`bne.s de_enemy2` skips the `lea sv_Enemy2Offsets`) take the set bit to mean species 1, so
`Level.EnemySpawn.species` inverts it. Getting this backwards silently swaps both the sprite sheet
and the whole stat block, including the vulnerability mask.

Which of the six views an enemy presents comes from `de_WALK`, and the sectors are deliberately
uneven: `sv_EDirSub: dc.w 128,64+32,64,64,64,64+32`. It is measured against the **camera angle**,
not the bearing from the enemy to the player — the sprite is screen aligned, so an enemy at the
edge of the view shows the same side as one dead ahead.

**Line of sight** is `CanShoot` transcribed: the line cut into `dist / 1024 + 1` equal steps, each
change of cell checked on the face it crosses, with wall 52 — the transparent one — letting the
look through. A diagonal step looks at the four faces meeting at the corner and is only refused
when two of them are shut.

**Randomness** uses a seeded PRNG for `GetRandom`. The original reads words out of Amiga ROM as its
entropy source, which cannot be reproduced and does not need to be. The sine table `sv_rotate`
reads is loaded from data at run time and is taken as `round(32767 sin)`.

One test is the port's own: `me_MOVE` checks only the destination cell's faces, and the port also
refuses a crossing against the cell the step began in, which only differs on a one-sided face.

## Weapons

The handgun, shotgun and machinegun fire **bullets resolved in the frame the trigger is pulled**:
real type 1 objects, 1, 3 and 2 of them, run through 20 or 25 `Object_Collision` calls at once.
The flamer, bolter and launcher put an object into `sv_ObjectTab`, after a first collision call
there and then; `PrepareStruct` turns them by a random 0..6. Landing within that first call is
point blank and costs the player 5, 1 or 5 energy with the interference and `EXCITE`. The launcher
then needs five passes to reload (`sv_Flag+8`), and asking sooner prints SCROLL 68.

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

Enemies fire through `es_EnemyShoot` with the same objects: `es_HandGun` 1, 3 or 2 bullets spread
by `(r ^ r) asr.b #3` (-16..15), the flamer, bolter and launcher by `asr.b #5`, all started 300
units ahead of the enemy, which is the only thing that keeps them off their shooter. They can
miss, hit another enemy, or set off a barrel. Weapons 0 and 7 fire nothing. There is no reload:
the wind-up and the roll in `me_walk` are all the pacing there is.

Known gaps:
- which of the 32 object sprites goes with which projectile type is paired by eye, not read from
  the offset table

- door 2's partly open picture follows `oc_MulD2Tab`, which is not transcribed; the renderer is
  given an even six steps instead
- `Test_Counters` runs at the start of each update as well as in each pass, so a change made
  between updates (a cheat, a pickup from the front end) is seen before the first pass
- an object `ro_SetExplode` or a shot puts into a later slot of `sv_ObjectTab` can be served by
  `Anim_Objects` in the same pass in the original; here new objects always wait for the next pass
- the impact mark's random height is kept (`Projectile.height`) but not drawn
- `header+20` is assumed to be the spawn facing
