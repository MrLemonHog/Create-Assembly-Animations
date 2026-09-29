# Custom animations

An assembly animation is a resource pack: a fragment shader that paints the ship, and a JSON next to it for the name,
sounds, settings and effects.

**Contents:** [1. The pack](#1-the-pack) · [2. The shader](#2-the-shader) · [3. The JSON](#3-the-json) ·
[4. Effects](#4-effects)

## 1. The pack

```
my-animations/
  pack.mcmeta
  pack.png                                        optional, shown in the config screen
  assets/my_pack/assembly_animations/wave.fsh     the shader
  assets/my_pack/assembly_animations/wave.json    optional
  assets/my_pack/textures/particle/...            optional, pictures for effects
  assets/my_pack/lang/en_us.json                  optional, translations
```

`my_pack` is the namespace: lowercase letters, digits and `_`. The animation's id is `my_pack:wave`;
`assembly_animations/lasers/blue.fsh` in a subfolder becomes `my_pack:lasers/blue`.

**`pack.mcmeta`** (`34` is the format of Minecraft 1.21.1):

```json
{
  "pack": {
    "pack_format": 34,
    "description": "My assembly animations"
  }
}
```

**`wave.fsh`** — a band of light running up the ship:

```glsl
vec4 animate(Pixel pixel) {
    float front = Stage == ASSEMBLY ? Progress : 1.0 - Progress;
    float band = 1.0 - smoothstep(0.0, 0.1, abs(pixel.bounds.y - front));
    return vec4(pixel.color + vec3(0.4, 0.9, 1.0) * band, 1.0);
}
```

Put the folder in `resourcepacks` and turn the pack on — *Wave* shows up in the mod's config screen.

Open the animation's page in the config screen and press `F3 + T` after every edit: the shader recompiles and the
preview updates. Compile errors with line numbers go to `latest.log`.

GLSL also reserves some unobvious words: `packed`, `input`, `output`, `sample`, `filter`, `active`, `flat`. A variable
with one of these names gives a syntax error that doesn't point at the cause.

## 2. The shader

### The file

The `.fsh` holds only the `animate` function. The uniforms, the `Pixel` struct and the functions below are already
declared in `assembly_animation.glsl`: no `main`, `#version` or declarations of your own
are needed.

`#moj_import <create_assembly_animation:effects.glsl>` adds `latticeHash`, `valueNoise`, `fbm`, `faceEdge` (distance
from a point on a block face to the face's edge) and `desaturate`. Your own files: `#moj_import <my_pack:common.glsl>`
reads `assets/my_pack/shaders/include/common.glsl`.

### The pixel

`animate` runs for every pixel of the build and returns `vec4(colour, alpha)`: alpha `0` leaves the pixel as it is,
`1` replaces it. The fields of `pixel`:

| Field | |
| --- | --- |
| `color` | what the game drew there, lit as in the world |
| `pos` | position in blocks, relative to the physics assembler |
| `block` | the pixel's block, whole numbers |
| `inBlock` | position inside the block, `0..1` on each axis |
| `bounds` | position inside the build's box, `0..1` on each axis (`.y` is height) |
| `distance` | `0` at the assembler, `1` at the farthest block |
| `random` | `0..1`, the same for every pixel of a block |
| `screen` | position on screen, `0..1` |
| `normal` | the face normal, imprecise along edges |

Because `normal` is imprecise, a texture laid on faces by it comes out smeared. Find the pixel's face from `inBlock`:
`min(pixel.inBlock, 1.0 - pixel.inBlock)` gives the distance to each face, and the smallest one is the face. If even
that is over about `0.06`, the pixel isn't on a cube face but on a lever, a propeller or a chest lid.

The simplest is `onCubeFace(pixel, cube)`: true when the pixel lies on the outside face of a full cube, and puts that
cube's cell in `cube`; it holds on edges and skips levers, propellers and other shapes. `fullCubeAt(cell)` and
`occupiedAt(cell)` tell whether a full cube, or any block, stands in a cell.

### Uniforms

| Uniform | |
| --- | --- |
| `Progress` | `0..1` through the animation |
| `Time` | seconds since the start |
| `Duration` | the length from the JSON, in seconds |
| `Stage` | `ASSEMBLY`, `ALIGNMENT` or `DISASSEMBLY` |
| `BoundsMin`, `BoundsMax` | the build's box, in the space of `pixel.pos` |
| `Reach` | distance from the assembler to the farthest block |
| `Daylight` | `0` at night, `1` at noon |
| `ScreenSize` | window size in pixels |
| `StartRight` | the direction to the right of the screen when the animation started, in block space |
| `SceneColor`, `SceneDepth` | the game's frame, to look at other pixels |
| `Brightness` | the player's brightness setting |

The mod applies the player's speed and brightness itself: speed is already in `Progress` and `Time`.

Declaring these uniforms again breaks the shader. These names are taken too: `Mask`, `Heights`, `Solids`,
`SolidsGrid`, `Fade`, `Area`, `Margin`, `InvViewProj`, `SceneToLocal`, `LocalToClip`.

### The three stages

- `ASSEMBLY` — the blocks become a ship. `Progress` goes `0 → 1`.
- `ALIGNMENT` — the lever's been pulled and the ship lines up before it's taken apart. It can take up to a minute and
  `Progress` stays `0`: drive it with `Time` and make it loop.
- `DISASSEMBLY` — the ship turns back into blocks. `Progress` goes `0 → 1`.

Disassembly starts right after alignment, so its first frame has to match the alignment loop, or the switch shows as
a jump.

### Drawing around the build

With `"area": "screen"` in the JSON the shader also runs around the ship, up to `margin` blocks away (`3` by default,
`16` at most). You need this for anything that sticks out of the hull.

More fields of `pixel`:

| Field | |
| --- | --- |
| `inside` | `1` on the build, `0` off it |
| `eye`, `ray` | the view ray, in block space |
| `depth` | distance along the ray to what the game has already drawn; anything farther is hidden |

Off the build, `pos`, `block` and the rest describe the ground or the sky, so check `inside`. Return alpha `0` where
there is nothing to draw.

Functions:

- `along(pixel, y)` — distance along the ray to a flat plane at height `y`, or `-1` if something's in the way. The
  point hit is `pixel.eye + pixel.ray * along(pixel, y)`.
- `topAt(vec2(x, z))` — how high the build goes in a column, or a big negative number if it's empty. Use it to
  stand something on the deck, like flames.
- `buildAt(uv)` and `blockSpaceAt(uv)` — whether another spot on screen is part of the build, and what's there in
  block space.
- `toScreen(point)` — where a block-space point ends up on screen. The result is corrected for the aspect ratio, so
  compare it with `pixel.screen * vec2(ScreenSize.x / ScreenSize.y, 1.0)`.

Don't look for blocks near an air pixel with `buildAt`: neighbouring pixels get different answers and the drawing
comes out ragged. Use `topAt` or particles.

### See-through and vanishing

`behind(pixel.screen)` is a blur of what's around the build at that spot. `mix(behind(pixel.screen), pixel.color, 0.4)`
makes the ship see-through.

Switches, at the top of the file:

```glsl
#define RAW_OUTPUT
#define WRITES_DEPTH

vec4 animate(Pixel pixel) { ... }
```

- `RAW_OUTPUT` — your colour goes out as it is, not mixed by the player's brightness. Scale your glow by `Brightness`.
- `WRITES_DEPTH` — adds `float vanished`. Set `1.0` where the build is gone, and effects behind the ship stop being
  hidden by it.

Also: `hash(vec3)` for a random number that stays the same between frames, `luminance(vec3)`, and `frame(...)` for
texture strips.

## 3. The JSON

`wave.json` sits next to `wave.fsh`. Every field is optional; the blocks below are pieces of that one file.

### Name, length, glow

```json
{
  "name": "Wave",
  "description": "A band of light runs up the ship.",
  "author": "You",
  "duration": 2.5,
  "charge_color": [0.4, 0.9, 1.0]
}
```

- `name`, `description`, `author` — text or a translation key from the pack's `lang`. Without `name` the mod uses the
  key `<namespace>.style.<file>`, and without that the file name: `wave.fsh` becomes *Wave*.
- `duration` — seconds for assembly and for disassembly, `3` by default, `0.25` to `60`. Different lengths:
  `{ "assembly": 5, "disassembly": 7.5 }`; a formula works too (see [Effects](#4-effects)).
- `charge_color` — `[r, g, b]` from `0` to `1`: the glow on the assembler while the lever is held.

### Textures

Any texture works: from the game, your pack or another mod. Vanilla paths follow the player's resource pack.

```json
"textures": {
  "TntSide": "minecraft:textures/block/tnt_side.png",
  "Blast": [
    "minecraft:textures/particle/explosion_0.png",
    "minecraft:textures/particle/explosion_1.png"
  ]
}
```

Declare them in the shader before `animate`:

```glsl
uniform sampler2D TntSide;
uniform sampler2D Blast;
uniform float BlastFrames;
```

Names are letters, digits and `_`, starting with a letter. A list is glued into one vertical strip (frames of one
size) and gets `<Name>Frames`; `frame(Blast, BlastFrames, index, uv)` picks a frame.

Animated vanilla textures like `fire_0.png` are already a strip (16×512, 32 frames); pass the frame count yourself:
`frame(Fire, 32.0, mod(floor(Time * 20.0), 32.0), uv)` plays it at the game's speed.

For a block texture on faces, pick the face from `inBlock` as above and flip the texture on the opposite sides so it
isn't mirrored.

### Sounds

```json
"sounds": [
  {
    "sound": "minecraft:entity.tnt.primed",
    "at": 0.0,
    "volume": 0.9
  },
  {
    "sound": "minecraft:entity.generic.explode",
    "time": 1.5,
    "pitch": 0.9
  },
  {
    "sound": "minecraft:block.portal.ambient",
    "every": 1.5,
    "stages": "alignment"
  }
]
```

| Field | |
| --- | --- |
| `sound` | a sound id, including your pack's own |
| `at` | when to play, `0..1` through the animation |
| `time` | or when to play, in seconds from the start |
| `every` | repeat this many seconds apart |
| `from`, `to` | for `every`: the seconds it repeats between, the whole time by default |
| `jitter` | for `every`: up to this many more seconds between two, at random |
| `volume`, `pitch` | `0.8` and `1.0` by default |
| `pitch_spread` | up to this much higher, at random |
| `stages` | a stage or a list: `assembly`, `alignment`, `disassembly` |

`time`, `every`, `from`, `to` and `jitter` can be [formulas](#formulas), like `"build_start / 20"`.

### Particles

```json
"particles": [
  {
    "type": "minecraft:large_smoke",
    "from": 0.6,
    "to": 1.0,
    "rate": 25,
    "speed": 0.08
  },
  {
    "type": "minecraft:explosion",
    "where": "wave",
    "from": 0.5,
    "to": 0.8
  }
]
```

| Field | |
| --- | --- |
| `type` | a vanilla particle with no settings of its own, like `minecraft:smoke` |
| `where` | `shell` (outside of the build, the default), `volume` (inside it) or `wave` |
| `from`, `to` | the part of the animation, `0..1` |
| `rate` | particles per second, `10` by default, at most `200` |
| `speed` | how fast they fly off, `0.02` by default |
| `chance` | for `wave`: the share of blocks that get one, all by default |
| `stages` | a stage or a list |

`wave` spawns one particle on each outside block, spreading out from the assembler between `from` and `to`, for example
explosions.

- By default sounds and particles play only on assembly and disassembly; add `"alignment"` to `stages` for the loop.
  `Progress` doesn't move there, so `at`, `from` and `to` do nothing: use `every` for sounds, particles run the whole
  time.
- The preview in the config screen plays no sounds and spawns no particles.
- The player's Particles and Sounds switches apply as usual.

### Settings of your own

```json
"options": [
  {
    "id": "sparks",
    "type": "toggle",
    "default": true
  },
  {
    "id": "palette",
    "type": "choice",
    "values": ["gold", "ice", "ash"],
    "default": "gold"
  },
  {
    "id": "amount",
    "type": "slider",
    "min": 0.25,
    "max": 2,
    "step": 0.05,
    "default": 1,
    "format": "percent",
    "uniform": "Amount"
  }
]
```

- `id` — the name [formulas](#formulas) read the setting by.
- `type` — `toggle`, `choice` or `slider`. A slider's `format` is `percent`, `multiplier` or `number`.
- `uniform` — optional: the shader gets the setting as a `float` of that name (`0`/`1` for a toggle, the position in
  `values` for a choice, the number for a slider). Declare it: `uniform float Amount;`.
- `name` and `tooltip` — text or a translation key. Without them the keys are `<namespace>.style.<file>.option.<id>`
  and `.tooltip`, and `...option.<id>.<value>` for the values of a choice (or `value_names`).

## 4. Effects

Pictures around the ship, its items and timings that depend on its size go in the JSON as formulas, and the mod draws
them. Effects show in the preview too.

### Formulas

A formula is a string in the JSON: `"0.5 + 0.5 * cos(tau * time / 13)"`. It has `+ - * / %`, comparisons, `&& || !`,
`a ? b : c` and these functions:

`sin cos tan asin acos atan atan2 pow exp log sqrt abs sign floor ceil round fract mod min max clamp mix step
smoothstep smooth outback outcubic length hash noise fbm rand pick`, and `pi`, `tau`.

- `mix(a, b, t)` and `smoothstep` work as in GLSL; `smooth(t)` is `smoothstep(0, 1, t)`.
- `pick(i, a, b, c...)` — the `i`-th value, counting from `0`.
- `length(x, y, z)` takes exactly three values; for a flat distance pass `0` as one of them.
- `hash(x, y, z)`, `noise` and `fbm` give the same numbers as `latticeHash`, `valueNoise` and `fbm` of `effects.glsl`.

Every formula can read:

| Name | |
| --- | --- |
| `time` | ticks since the start (20 a second), the player's speed included |
| `stage` | `0` assembly, `1` alignment, `2` disassembly |
| `reach`, `size`, `faces` | distance to the farthest block, number of blocks, number of outside faces |
| `lo_x`, `lo_y`, `lo_z`, `hi_x`, `hi_y`, `hi_z` | the build's box, in block space |
| `center_x`, `center_y`, `center_z` | its middle |
| `right_x`, `right_y`, `right_z` | `StartRight` |
| the `id` of each setting | its value |

### Values of the ship

```json
"vars": {
  "build_start": "16",
  "spread": "clamp(12 + reach * 0.7, 14, 36)"
},
"uniforms": {
  "BuildStart": "build_start / 20"
},
"duration": "(build_start + spread + 50) / 20"
```

- `vars` — your own names, worked out in order; each sees the ones before, and so does every formula after them.
- `uniforms` — values for the shader. Declare them there: `uniform float BuildStart;`.
- `duration` and the `time`, `every`, `from`, `to`, `jitter` of sounds take formulas (in seconds).

### Points and drawings

`effects` is a list. Each effect puts points on the ship and draws something at every point.

```json
"effects": [
  {
    "on": "faces",
    "per_face": "2 * amount",
    "vars": {
      "life": "30 + 20 * rand(1)",
      "age": "time - 20 * rand(2)"
    },
    "when": "age >= 0 && age <= life",
    "draw": [
      {
        "sprite": "minecraft:glow",
        "glow": true,
        "x": "spot_x + normal_x * age * 0.02",
        "y": "spot_y + age * 0.01",
        "z": "spot_z + normal_z * age * 0.02",
        "size": 0.08,
        "alpha": "1 - age / life"
      }
    ]
  }
]
```

**Where the points are** — `on`:

| `on` | |
| --- | --- |
| `faces` | random points on the outside faces, `per_face` per face on average |
| `blocks` | the middles of blocks, a `share` of them from `0` to `1` (the same blocks for the same share); `"items": true` keeps only blocks that have an item |
| `top` | one point: the top block nearest the middle |
| `center` | one point: the middle of the build |

**What a point knows** — its formulas can also read:

| Name | |
| --- | --- |
| `spot_x`, `spot_y`, `spot_z` | where the point is |
| `normal_x`, `normal_y`, `normal_z` | which way its face looks (`faces` only) |
| `block_x`, `block_y`, `block_z` | its block |
| `color_r`, `color_g`, `color_b` | the block's average colour, tint included |
| `seed` | `0..1`, its own random number |
| `rand(n)` | the `n`-th random number of the point |

The effect's `vars` are worked out for every point; the ones that don't depend on `time` or the settings only once.
`when` hides the point while it is `0`.

**What is drawn** — `draw` is a list, and every entry is drawn at every point:

| Field | |
| --- | --- |
| `sprite` | a picture, or a list of them, from `textures/particle/` of a pack: `"leaf"` is your own `leaf.png`, `"minecraft:glow"` a vanilla one |
| `item` | `true` to draw the block's own item, like a dropped one |
| `x`, `y`, `z` | where to draw it |
| `size` | a picture's half width, in blocks |
| `roll`, `flip` | turn a picture, and squash it sideways (`1` flat on, `0` edge on) like a falling leaf |
| `r`, `g`, `b`, `alpha` | its colour, `1` by default |
| `picture` | which picture of the list |
| `glow` | `true` adds light instead of covering |
| `scale`, `yaw`, `pitch` | an item's size and turn (radians) |
| `copies` | draw the entry this many times, with `copy` from `0`: trails, layers, several sparks per point |
| `vars`, `when` | the entry's own |
