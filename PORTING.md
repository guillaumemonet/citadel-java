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
- Collision clamps the in-cell position *after* the move, which is what produces the sliding along
  walls. Corners are handled as `br2_UP` / `br2_DOWN` do: push back along the axis with less
  penetration, so you cannot cut through a sealed corner.
- A face is passable when its value masked with 62 is 0, 32, 38 or 54 — nothing, either door type
  open, or a broken door (`br_ChkDoors`).
- Enemy steps are refused against the cell the step began in, one axis at a time. Testing only
  the destination cell's own faces lets a diagonal step slip past a sealed corner, which in play
  looks exactly like an enemy walking through a wall.
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

`gradle :core:test` runs headless against the shipped maps: 107 tests covering parsing, movement,
doors, switches, pickups, line of sight, enemy movement, the animation tables, the interference
burst, exhaustion, the cheat codes, and the four data tables whose exhaustiveness the transitions
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
  which the death card now shows, and the hangar, which the save slot picker sits in
- front end: the intro animation plays, then a title screen, with death and victory cards
- the five-slot save menu, reading and writing the original file
- playable viewer, plus a screenshot path for verification

Not started:

- the server's flow proper: its menu tree in the hangar, and the map screen between complexes,
  where MAPA lets you pick one of seven targets and marks off the ones already taken
- the pushes that go with a hit: `sv_AddMove` and the `sv_LastMove` rebound, see below

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

## Animation

`sv_DoAnims` runs once per pass of `MAIN_LOOP` and animates by rewriting offset tables in place,
so the same cell value draws a different sprite from one pass to the next. It advances four
counters at once:

| table entry | frames | what it is |
|---|---|---|
| `sv_WallOffsets[23]` | tiles 23, 24 | the one animated wall texture |
| `sv_BloodOffsets[5]` | two windows in tile 38 | a plaque; not drawn yet, see below |
| `sv_CollumnOffsets[4]`, `[12]`, `[20]` | four sprites each | three animated columns |
| `sv_WalkState` | 0, 2, 4, 6 | the enemy walk phase |

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

**Not ported:** the three point-blank cases also cost energy and shove the player, and the shove
is left out for the same reason as the others.

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
the sequence is over. The port does the same job by staying in its PLAYING mode.

## Fading out

`p_FadeColors` multiplies every four bit palette component by `d0 / 16`, counting d0 down from 16,
two VBLANK frames a step. Scaling a colour towards zero is the same thing as blending it towards
black, so a black plate whose alpha climbs in the same seventeen steps is exact rather than an
approximation.

It runs at the end of both ways out of a level: after `TELEPORT` when the exit is reached, and
after `sv_Death`. So a level change happens behind a black screen rather than as a cut, and the
port now does that too.

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

The engine's head-on angles are 0 east, 256 west, 128 north and 378 south. The first two are the
other way round in this port because its X axis is mirrored against the engine's; the south one is
straightened from 378 to 384, a six unit skew that is either a slip or a hand-tuned fudge and is
four degrees either way.

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

`oc_PlayerHit` also turns the player by a random `-8..7`, which is ported. The pushes that go with
both events are not: `br_BumpWall` writes a rebound into `sv_LastMove+4/+6`, and `oc_PlayerHit`
adds four times the shot's velocity to `sv_AddMove` - which `sv_DoRotIt` then reads back under
`andi #$ff`, destroying the sign and sending a negative push the wrong way. That one is a bug in
the original, and reproducing it would only feel broken.

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
none is on a door. Plaques are set far more liberally by the editor — 754 faces carry the bits —
but `dr_AddTables` is called from the wall drawing path, so the **81** with an actual wall are the
only ones that were ever drawn.

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

**Bit 0 set means the *first* species**, which is the opposite of what it looks like. Both
`EnemyHitServe` (`beq.s .me01` falls through to `sv_LevelData+4`) and `dr_DrawEnemy`
(`bne.s de_enemy2` skips the `lea sv_Enemy2Offsets`) take the set bit to mean species 1, so
`Level.EnemySpawn.species` inverts it. Getting this backwards silently swaps both the sprite sheet
and the whole stat block, including the vulnerability mask.

Which of the six views an enemy presents comes from `de_WALK`, and the sectors are deliberately
uneven: `sv_EDirSub: dc.w 128,64+32,64,64,64,64+32`. It is measured against the **camera angle**,
not the bearing from the enemy to the player — the sprite is screen aligned, so an enemy at the
edge of the view shows the same side as one dead ahead.

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
