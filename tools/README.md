| `maps/` | one PNG overview and one text dump per level (walls, items, enemies, events), plus `INDEX.txt` || `gfx/complexN/` | 158 PNGs per complex# Citadel asset converter

Converts the original 1995 Amiga data files into formats a jMonkeyEngine port can load: PNG
textures and sprites, WAV sound effects, and readable dumps of the level data.

The format readers live in `:core` and have no dependencies at all; this module adds the PNG and
WAV writing on top.

```sh
gradle :tools:convert                                       # Data -> build/assets
gradle :tools:inspect -Pargs="../Data/GAME/MAPS/LEVEL01A.MAP 24 7"
```

`Convert` also takes flags directly: `--gfx`, `--maps`, `--sounds` run one stage only, and `--raw`
dumps the whole graphics block as plain 64x64 tiles in both byte orders, which is how the storage
order was confirmed in the first place.

Output:

| path | contents |
|---|---|
| `gfx/complexN/` | 146 PNGs per complex (walls, columns, items, objects, blood, both enemy species) plus the palette |
| `maps/` | one PNG overview and one text dump per level, plus `INDEX.txt` |
| `sounds/` | the 25 effects as WAV, at their original Paula rates |
| `intro/` | the three intro animations as PNG frames, plus 46 loose IFF artwork images |
| `raw/` | tile atlases, for eyeballing the decode |

Everything below was derived from the assembly sources in this repository and verified against
the shipped data; where something is still a guess it says so.

## Palette: `COLS0n.DAT`

64 bytes, 32 entries of one big endian word in `0RGB` form, 4 bits per component. Nibbles are
replicated to 8 bits (`0xA` becomes `0xAA`).

Index 0 is the transparent key for every sprite. Wall textures are opaque and never use it.
Per `Editor/edytor_notes.txt` the first 20 entries are fixed engine colours and the last 12 are
the level designer's.

Confirmed against the `CMAP` chunk of the authoring source `SCIANY_01A_podziemia.iff`: identical
for all 32 entries.

## Graphics: `.VIR`

Headerless 8 bit paletted pixel data. The server loads five files back to back into one block at
`MEMORY + co_Walls` and then addresses every graphic through the offset tables at label
`st_Offsets` in `Code/MAIN1/CytadelaHD_134.ss`. Offsets in those tables are relative to that base.

Block layout, per `LoadFiles` in the server:

| offset | file | size |
|---|---|---|
| 0 | `WALLSnA.VIR` | 83 200 |
| 83 200 | `WALLSnB.VIR` | 83 200 |
| 166 400 | `ENEMYnA.VIR` | 52 000 |
| 218 400 | `ENEMYnB.VIR` | 52 000 |
| 270 400 | `ITEMS01.VIR` | 28 080 |

Cross check: the server copies `ITEMS01.VIR` to `MEMORY + 0x52c40`, and `0x52c40 - 0x10c00` is
exactly `65 * 4160`, the first entry of `sv_ItemOffsets`.

**Storage is column major** with a stride of 65 bytes per column: 64 pixels plus one guard byte
the engine forces to zero (`mk_coll1`, `mk_fixblood`). A 64x64 tile is therefore 4160 bytes, and
reading a texture column is a contiguous byte run, which is what the wall rasteriser wants
(`shZ_DownPix: move.b (a1)+,d0`).

```
pixelByte = block[base + x * 65 + y]
```

**The colour index is stored bit-reversed in bits 3..7** of that byte:

```java
index = reverse5((pixelByte >> 3) & 31)   // equivalently: reverse8(pixelByte) & 31
```

This falls out of the chunky to planar converters. `sv2_copy` and friends run each byte through
the `ConvBits` macro, which does `add.b d0,d0` and rolls the bit that fell out of bit 7 into the
current bitplane, so bit 7 feeds bitplane 0, bit 6 feeds bitplane 1, and so on. Storing the index
pre-shifted and reversed means the converter never shuffles anything. Every value in the shipped
files is a multiple of 8, and the mapping matches the authoring IFF at 100% over 20 480 sampled
pixels (`citadel.tools.ProbeMapping`).

Sizes per group, from the offset tables:

- walls, 27 textures of 64x64 (`sv_WallOffsets`; a 28th slot is a scratch buffer)
- columns, 8 of 32x64 then 16 of 32x32, then 4 corpse sprites (`sv_CollumnOffsets`)
- items, 20 of 32x32 (`sv_ItemOffsets`)
- in-flight objects, 32 of 16x16: a 4 by 4 grid in each of tiles 70 and 71
- blood, 3 decals of 64x64 then 4 plaque entries (`sv_BloodOffsets`); the plaque entries are
  extracted as 32x32 quadrants of tile 38, which the compositor says is **wrong** - see below
- enemy species 1, 24 frames of 32x64; species 2, 25 frames (`sv_Enemy1Offsets`, `sv_Enemy2Offsets`)

`sv_EnemyDirTab` maps (view direction, walk phase) onto enemy frames: 6 directions of 4 phases.

The in-flight objects are 16x16, packed four by four into tiles 70 and 71. The +/-16 adjustments
in the offset table are the row steps of that grid, and extracting on it yields clean isolated
sprites: flames, sparks and impact marks. Which of the 32 belongs to which projectile type has
not been worked out from the table, so the renderer pairs them by eye.

### Decals: `sv_BloodOffsets`

Seven entries: three 64x64 blood decals in tiles 35..37, then four 32x32 plaques packed as the
quadrants of tile 38 — a card reader with a red lamp, the same with a green one, and a KEEP OUT
sign in two frames.

The two routines that draw them look alike and are not. `dr_AddBlood` copies the wall tile into
the scratch buffer at `sv_WallOffsets[27]` and overlays every non-zero decal byte on the way past
(`AB_loop1`, 260 iterations of four longwords, so a full 4160-byte tile). `dr_AddTables` has its
own pair of loops: `AT_loop1` copies the tile, then `AT_loop2` writes **32 columns of 32 bytes**
starting at offset 1056, which is `16*65 + 16` — dead centre of the 64x64 face. The two `rept 4`
passes per column come from the `eori #$8000,d4 / bmi` trick, which runs the block twice.

Selection: blood is bits 6..7 of the wall byte less one, so 1..3 pick entries 0..2. Plaques are
the 2-bit field in cell byte 4, and `dr_AddTables` does `addq #2,d5` first, so 1..3 pick entries
3..5. Both preserve the wall's mirror flag, since the composite is drawn as wall id 28 — the
scratch buffer — with the flag or-ed back in.

Worth knowing when reading a map dump: the editor sets plaque bits freely, and only **81** of the
754 plaque faces in the shipped campaign have a wall to be drawn on.

### Animation: `sv_AnimOffsets`

`sv_DoAnims` rewrites table entries once per pass of `MAIN_LOOP`:

| rewritten | cycle |
|---|---|
| `sv_WallOffsets[23]` | tile 23, tile 24 |
| `sv_BloodOffsets[5]` | two windows in tile 38 |
| `sv_CollumnOffsets[4]` | sprites 4, 5, 6, 7 |
| `sv_CollumnOffsets[12]` | sprites 12, 13, 14, 15 |
| `sv_CollumnOffsets[20]` | sprites 20, 21, 22, 23 |
| `sv_WalkState` | 0, 2, 4, 6, the enemy walk phase shared by every enemy |

### `Store/CHAOSTAC.DAT`

2048 bytes, 1024 big endian words, and a **permutation of 0..1023**. `TELEPORT` reads it in
order, 32 entries per VBLANK frame for 32 frames, reusing each entry at five offsets 1024 apart,
which comes to 5120 cells of four pixels by two rows: the whole 320 by 128 view exactly once.
`citadel.core.ChaosTable` reads it and hands out the cells frame by frame.

### `Store/CHAOSTAB.DAT`

Its sibling: 1728 bytes, 864 words, a permutation of 0..863 — which is 32 by 27, the weapon
window. `ci_DrawWeapon` walks it backwards from the last entry, 32 pixels per VBLANK frame, to
copy a newly selected weapon into place over 27 frames. `citadel.core.WeaponChange` reads it.

## Levels: `LEVELnnX.MAP`

About 37 KB. Addresses inside a map are `y * 512 + x * 8 + sub`, i.e. the raw byte offset of the
cell plus a sub index.

| offset | contents |
|---|---|
| `0x0000` | grid, 64 x 64 cells of 8 bytes |
| `0x8000` | header, 40 bytes |
| `0x8028` | enemy table, 256 entries of 16 bytes |
| `0x9028` | event stream, words, terminated by `-1 -1` |

### Cell, 8 bytes

| byte | meaning |
|---|---|
| 0..3 | wall for N, E, S, W: bit 0 mirror, bits 1..5 texture index, bits 6..7 blood stain |
| 4 | plaque or card slot, 2 bits per direction |
| 5 | low 5 bits column index, top 3 bits corpse sprite |
| 6 | low 6 bits item index, bit 6 marks the cell impassable (and keeps nearby doors shut) |
| 7 | index into the enemy table |

Direction order confirmed by `du_S` / `du_E` / `du_W` in `DrawUserMap`.

Wall values worth knowing, tested as `value & 0x3e` (so twice the texture index):
30/32/34 closed, open and animating door type 1; 36/38/40 the same for type 2; 54 broken door;
44 and 46 switch out and in.

### Header, 40 bytes

| offset | meaning |
|---|---|
| 4 | enemy species 1: **vulnerability mask**, 4 bits |
| 5 | enemy species 1: weapon |
| 6 | enemy species 1: energy |
| 8 | enemy species 1: aggression (always a multiple of 50) |
| 10, 11, 12, 14 | the same for species 2 |
| 16, 18 | player spawn cell |
| 20 | spawn facing (unconfirmed) |
| 22, 24 | level exit cell |

Byte 4 is where the editor shows two 2-bit "hits" fields, but `EnemyHitServe` reads it as a mask,
one bit per damage type: bit 0 bullets, bit 1 fireballs, bit 2 electricity, bit 3 rockets. A
species with the bit clear takes no damage at all from that type.

### Enemy entry, 16 bytes

Fully accounted for: `+1` flags (**bit 0** selects the species, per the `btst #0` in Move_Enemy;
bit 7 is unrelated and only asks the renderer to draw that one behind a column), `+2` energy,
`+4` world X, `+6` world Y (1024 units per cell), `+8` facing, `+10` walk speed in units per
frame, `+12` state (0 walking, 1 hit, 2 killed, 3 burning, 4 shooting), `+13` state counter.
Bytes 0, 14 and 15 are still unidentified, so the text dump prints all 16.

Only 24x24 cells around the player are simulated per frame (`Move_Enemy`).

### Event stream

`-1, record, -1, record, ..., -1, -1`. Each record opens with its trigger location, whose low
3 bits discriminate:

- `0..3` a button on that wall direction, followed by command pairs
- `4` a teleport, followed by its destination location
- `5` a card reader, followed by a colour word (0 none, 1 red, 2 green, 3 blue), then commands

Commands are `(command, location)` word pairs. The command word is `(param << 8) | code`; a word
of `-2` separates the on-press actions from the on-release actions, and the list ends when the low
byte goes negative. Codes, matching the `"ODCDSBRB W I C"` table in the editor:

| code | action |
|---|---|
| 0 | open door |
| 1 | close door |
| 2 | set block |
| 3 | remove block |
| 4 | set wall, `param` is the new value |
| 5 | set item |
| 6 | set column |

## Sounds

`Store/ST.DAT` is the raw sample bank. The `sound_list` table in the engine gives, for each of the
25 effects, its offset, its length in words and its Paula period; playback rate is
`3546895 / period` on PAL. Samples are signed 8 bit mono.

The last entry ends at byte 102 108, exactly the size the source comments give for the block, and
every length matches a file in `Data/GAME/Sounds`, which is what fixes the names.

The `SOUND` macro's argument is **1-based**: `play_sound` does `subq #1,d0` before scaling by the
8-byte entry size. Reading every call site pins each sound to what it is actually played for, and
twelve of them agree with the file names — but **handgun and launcher are swapped**:
`rm_HandGunUsed` plays `SOUND 7`, whose sample ships as `launcher.snd`, and `rm_LauncherUsed`
plays `SOUND 3`, shipped as `handgun.snd`. The indices are the authority; `SoundTable` exposes
them as named constants for that reason.

## Intro animations: `Data/INTRO/ANIM2`

The repository README says the intro animations are missing. **They are not.** `start`, `kabina`
and `explozycja` are ordinary IFF `FORM ANIM` files, 320x200 in 6 bitplanes, and they decode: 27,
20 and 27 frames respectively.

Structure is the standard one: a container of `FORM ILBM`s where the first carries `BMHD`, `CMAP`
and a full `BODY`, and each one after that carries an `ANHD` plus a `DLTA` to apply to the frame
before it. All three use `ANHD` operation 5, byte vertical delta.

Two things bite:

- **Hold-and-Modify.** `CAMG` bit 0x800 is set on all of this artwork. A HAM6 pixel is six bits
  whose top two select what to do: 00 takes a colour from the first 16 CMAP entries, and 01, 10
  and 11 *hold* the previous pixel and *modify* its blue, red or green with the low four bits.
  Decoding one of these as a plain 64-colour image gives dense red and green speckle, because
  every modify pixel is read as an index. The game's own wall and sprite artwork has `CAMG` 0,
  which is why that decoded correctly from the start.
- The `.k` files beside them are *not* IFF. They start with a literal `ANIM`, a palette of 12-bit
  RGB words and a `BODY`; they are the engine's own repacked form, produced by
  `Code/IntroPlayer/AnimConvert01.ss`. Not decoded yet, and not needed while the IFF originals
  are present.

**Still unconfirmed:** all three decode with content confined to roughly the top-left 200x105 of
the 320x200 frame, the rest solid black. Fixing the HAM decode cleaned up the colours but did not
change the framing, so it really is in the data: the `BODY` is consumed exactly, the deltas
animate correctly, and the same reader now decodes the title screens full-frame and pixel-correct.
Most likely a window the intro player composites, with `Napisy.a`/`Napisy.g` (subtitles) filling
the rest. Worth confirming against `IntroReplayer_125.ss` before building on it.

## Not converted yet

- the `.k` repacked animations beside the IFF ones
- `Store/ROTTABLE.DAT`, 64 pre-sorted cell visibility lists, one per view angle. The software
  renderer walks these back to front; a jMonkeyEngine port does its own culling and will not need
  them.
- `Store/IWT.DAT`, `TABLES.DAT`, `SINTABLE.DAT`: engine lookup tables and screen furniture.
- `Store/TEXT.POL` / `TEXT.ENG` / `TEXT.GER`: in-game strings, indexed by `sv_TextOffsets`.
- The IFF artwork under `Data/GAME/Gfx_IFF` and `Data/INTRO`. `citadel.tools.IffReader` decodes
  ILBM already and can be run standalone on any of those files.
