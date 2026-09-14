# Citadel asset converter

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
- items, 20 of 32x32; in-flight objects, 16 more (`sv_ItemOffsets`)
- blood, 3 decals of 64x64 then 4 wall plaques of 32x32 (`sv_BloodOffsets`)
- enemy species 1, 24 frames of 32x64; species 2, 25 frames (`sv_Enemy1Offsets`, `sv_Enemy2Offsets`)

`sv_EnemyDirTab` maps (view direction, walk phase) onto enemy frames: 6 directions of 4 phases.

The in-flight object sizes are still provisional; the tables adjust those offsets by ±16 bytes,
which is vertical, so they are stacked sub-sprites inside tiles 70 and 71.

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
| 4 | enemy species 1: hits, two 2-bit fields |
| 5 | enemy species 1: weapon |
| 6 | enemy species 1: energy |
| 8 | enemy species 1: aggression (always a multiple of 50) |
| 10, 11, 12, 14 | the same for species 2 |
| 16, 18 | player spawn cell |
| 20 | spawn facing (unconfirmed) |
| 22, 24 | level exit cell |

### Enemy entry, 16 bytes

Confirmed: `+1` flags (bit 7 selects species 2), `+4` world X, `+6` world Y (1024 units per cell),
`+12` state (0 walking, 1 hit, 2 killed, 3 burning, 4 shooting). `+2` and `+10` are both counters
that look like energy; the rest is not yet identified, so the text dump prints all 16 raw bytes.

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

## Not converted yet

- `Store/ROTTABLE.DAT`, 64 pre-sorted cell visibility lists, one per view angle. The software
  renderer walks these back to front; a jMonkeyEngine port does its own culling and will not need
  them.
- `Store/IWT.DAT`, `TABLES.DAT`, `CHAOSTAB/TAC.DAT`, `SINTABLE.DAT`: engine lookup tables and
  screen furniture.
- `Store/TEXT.POL` / `TEXT.ENG` / `TEXT.GER`: in-game strings, indexed by `sv_TextOffsets`.
- The IFF artwork under `Data/GAME/Gfx_IFF` and `Data/INTRO`. `citadel.tools.IffReader` decodes
  ILBM already and can be run standalone on any of those files.
