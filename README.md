# Fire Sprinkler

**Minecraft 26.1.2 - 26.x; Fabric and NeoForge.**

This small server-side-only mod turns a button into a working fire sprinkler.
Place a button on the **underside** of a block that is waterlogged (or is a
water source), and any fire that starts in the spray cone beneath it is put out
just like rain — with a hiss and steam — and burning mobs walking through are
extinguished too.

## Installation

This is a **server-side mod** — it goes in the server's `mods/` folder only.
Players connect with a completely unmodified vanilla client.

### Fabric servers

Requires the correct [fabric-api jar](https://modrinth.com/mod/fabric-api) on
the server.

Copy [`firesprinkler-fabric-….jar`](https://github.com/tomgidden/minecraft-firesprinkler/releases) to the server's `mods/` folder.

### NeoForge servers

Requires [NeoForge](https://neoforged.net/) on the server. No other mods needed.

Copy [`firesprinkler-neoforge-….jar`](https://github.com/tomgidden/minecraft-firesprinkler/releases) to the server's `mods/` folder.

## Usage

1. Place a block and make it a **water supply**: either waterlog it (e.g. a
   waterlogged slab, stairs, fence) or put water above it[^1]
2. Place a **button on the underside** of that block, so the button hangs from
   the ceiling. (Any button type works.)

That's it. The sprinkler is now armed. It stays invisible and inert until a
fire actually starts (or a burning mob wanders) within its spray cone, at which
point it "rains" on just that spot — putting the fire out with the vanilla
fire-extinguish hiss and a puff of steam.

Because it is *fire-triggered*, an armed sprinkler does *not* hydrate
farmland, drip from leaves, fill cauldrons, or boost fishing the way standing
rain would. It only acts when there is something to put out.

[^1]: actually you can put the water above the solid block above *that* instead,
ie. two solid blocks above the button, with water above those.  That way,
client-side water drips can be avoided without resorting to using glass blocks.

### The spray cone

The spray widens as it falls, like a real sprinkler head, then is confined:

| Level below the button | Covered area (centred on the button) |
| ---------------------- | ------------------------------------ |
| Button's own level     | 3×3                                  |
| 1 below                | 5×5                                  |
| 2 below                | 7×7                                  |
| 3 below                | 9×9                                  |
| 4 or more below        | 11×11 (until the depth limit)        |

The spray falls **straight down**: a fire is only reached if there is no solid
"floor" directly above it, up to the sprinkler's level. A floor is anything
that stops falling water the way a roof stops rain — full blocks, slabs,
stairs, fences, closed trapdoors. Open trapdoors, string, torches, carpets,
flowers, and water/waterlogged blocks all let the spray through.

### Configuration

On first launch the mod writes `config/firesprinkler.properties`:

```properties
# Chebyshev radius on the button's own level (0 = just the button cell; 1 = 3x3).
base_radius=1
# Radius the cone widens to before it stops widening (5 = 11x11).
max_radius=5
# Deepest level below the button the spray can reach, in blocks.
max_depth=16
# Probability (0.0-1.0) a sprayed fire is put out per check. 1.0 = instant.
# Set to 0.0 to fall back to vanilla rain's own rate (0.2 + age*0.03 per check).
extinguish_chance=0.8
# Game ticks between extinguish checks on a sprayed fire (20 = 1 second).
check_interval=10
```

Edit and restart the server to change the cone's size, reach, and speed.

### Extinguish speed

Left to vanilla's rain behaviour, fire is slow to go out: a fire only rolls its
extinguish chance on its own tick (every 30–39 game ticks, ~1.5–2 s), the chance
starts at only 20%, and a fire being rained on never ages so it never climbs.
That averages ~8–9 seconds with a long tail.

A sprinkler is meant to be brisker, so by default it checks a sprayed fire every
`check_interval` ticks (twice a second) with a flat `extinguish_chance` (80%),
putting most fires out in a second or two. Turn it up to `extinguish_chance=1.0`
for an instant douse, or set `extinguish_chance=0.0` to fall back to the exact
vanilla rain rate.

## Single-player

If you drop the mod jar into a singleplayer client's `mods/` folder, it works
in normal singleplayer and via "Open to LAN". If the world is later moved to a
server without the mod, sprinklers simply stop working and buttons behave as
plain vanilla buttons again.

## How it works

Vanilla Minecraft already knows how to put fire out with rain, but only when a
storm is actually overhead: `FireBlock.tick` gates its rain-extinguish branch
behind `level.isRaining()`, and `Entity.isInRain()` clears a burning entity's
fire (playing the extinguish sound). A sprinkler needs to work under a roof with
clear skies, so this mod supplies the extinguishing itself:

* **`FireBlock.tick`** — for a fire block sitting inside an active sprinkler's
  cone, the mod runs its own extinguish check, independent of the weather. It
  uses a configurable flat chance (`extinguish_chance`), reschedules the check at
  its own faster cadence (`check_interval`) rather than the slow vanilla fire
  tick, plays the fire-extinguish sound and a puff of white steam so the
  extinguish is seen and heard, and — like rain — stops that fire from aging
  or spreading while it is being sprayed. (Setting `extinguish_chance=0.0` falls
  back to vanilla rain's own `0.2 + age × 0.03` rate.)

* **`Entity.isInRain`** — for a **burning** entity inside the cone it returns
  `true`, reusing vanilla's rain extinguishing (and its hiss) for mobs and
  players, with an added puff of steam. This one works regardless of weather
  because the check runs before vanilla's own rain gate.

An "active sprinkler" is detected by scanning up from the queried position for a
ceiling-mounted button whose supporting block above supplies water, then
checking the cone geometry and that the column above is unobstructed. The scan
is bounded by `max_depth`, and the expensive check only runs for positions that
are fire or burning entities.

### Troubleshooting

If a sprinkler isn't putting fire out, set `debug=true` in
`config/firesprinkler.properties` and restart the server. The mod then logs, at
`INFO` under the logger name `firesprinkler`, every fire tick it inspects: which
ceiling buttons it found and whether they had a water supply, whether the column
above the fire was clear or blocked (and at what depth), and each extinguish. The
trace shows exactly why a given fire is or isn't being reached.

## License and stuff

This mod is covered by the [MIT License](LICENSE.txt).

In short, you may freely use this mod in any modpack, but just don't claim you
made it. No promises, no warranties, so don't blame me if it breaks anything or
disadvantages you in some way, or you believe it did.

[Comments and improvements welcome.](https://github.com/tomgidden/minecraft-firesprinkler)

-- `_gid`

![_gid](https://api.mineatar.io/face/afa08bc643414b0f9ff9c1f2ce3ddfcc?scale=8)
