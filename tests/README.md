# Spray geometry tests

These check the mod's actual in-game behaviour against a Python model of the
intended geometry. They are not unit tests: they drive a running dev server
over RCON, build structures, light fires, and read back which ones went out.

That is deliberate. The spray rules are easy to state and surprisingly easy to
implement subtly wrong — the bug these were written to catch (`reaches()`
ignoring `baseRadius`) was invisible at the depth the manual testing happened
to use, and only showed up once a sprinkler sat low enough for the fire to be
in the widening part of the cone.

## Requirements

A dev server with the mod installed and RCON enabled (`server.properties`:
`enable-rcon=true`, `rcon.password=…`, `rcon.port=25575`), and `pip install
mcrcon`.

## Setup

```sh
cp config.py.dist config.py
```

Then edit `config.py`: the RCON password, and the coordinates to build at.
`config.py` is gitignored, so your password and your own test location stay out
of the repo.

**The area around those coordinates is destroyed and rebuilt on every run.**
Point this at a disposable world, well away from anything you care about.
and set the configuration values to point to a flat area near the ocean, or
possibly over the ocean.

Build a working sprinkler at the configured spot — a waterlogged block at
`SY+1`, a ceiling-mounted button at `SY` — and leave the space below it clear
down to `FLOOR_Y`.

`MAX_RADIUS` and `BASE_RADIUS` in `config.py` must match
`config/firesprinkler.properties` on the server. The model predicts from them,
so a mismatch shows up as every case failing at once.

## Layout

`functions.py` holds everything shared: the model, the RCON session, and the
build sequence (reset the area, place obstructions, sweep stray heads, build
sprinklers, light fires, read back, compare). Each suite is then just its list
of cases.

```sh
python3 tests_basic.py             # clear cone, single obstructions
python3 tests_floors.py            # floors with gaps, diagonal pinches
python3 tests_offaxis.py 0 15      # off-cardinal gaps (slice by index)
python3 tests_seam.py              # the join between the throw and the fall
python3 tests_multi.py             # several sprinklers, unobstructed
python3 tests_multi_obstructed.py  # several sprinklers with obstructions
python3 tests_multi_heights.py     # sprinklers at differing heights
python3 tests_pruning.py           # the heightmap column pruning
```

A case is one call:

```python
run_case("floor with a gap on axis", obstructions, heads)
```

`obstructions` and `heads` are absolute `(x, y, z)`; heads may sit at different
heights, and default to the single sprinkler from `config.py`.

Each case prints `OK` or `MISMATCH` with the model and actual planes side by
side. Expect roughly 15 s per case: most of it is waiting for fires to be
extinguished on vanilla's 30–39 tick cadence.

`rcon.py` is not a test — it is a hand tool for poking at the server while
diagnosing one:

```sh
python3 rcon.py "time query gametime" "setblock 927 64 2306 fire"
```

## Reading a failure

```
MISMATCH floor with gap at +3
        model            actual
        ...#########...   ...............  <
        ...#########...   ....#######....  <
```

`#` is a cell where the fire was extinguished, `.` one where it survived, `X`
an obstruction. The model column is what the specified geometry predicts.

## Things that will bite you

Every one of these produced a false failure at some point, and a harness that
can invent a failure can equally hide a real one:

- **Fire needs a floor it burns on forever.** On stone it burns out by itself,
  so an extinguished cell is indistinguishable from one that simply expired.
  `FLOOR_BLOCK` defaults to netherrack; change it and this comes straight back.
- **Rain puts fire out too.** Each run roofs the area over (`ROOF_BLOCK`,
  glass by default) rather than trusting the server to have weather disabled —
  a shower during a run would read as the sprinkler covering everything.
- **Obstructions are placed before sprinklers.** An obstruction at the same
  level as a head's support block would otherwise overwrite it, silently
  disarming that sprinkler and looking exactly like a geometry bug.
- **Leftovers bleed between cases.** The cleared volume is deliberately wider
  than the tested plane. One phantom failure was traced to a previous case's
  floor still standing; it passed on an isolated rerun.
- **Stray sprinkler heads are the worst of these.** The multi-head suites build
  extra sprinklers; if one survives into the next run its cone silently widens
  the sprayed area, which looks exactly like the geometry being too generous.
  Each run now clears the head level and rebuilds its own sprinkler, and aborts
  if it cannot.
- **A block placed at fire level reads as "extinguished"**, because there is no
  fire there to survive. Cells the model marks `X` are excluded from the
  comparison for that reason.
- **"failed to light" counts are unreliable.** They are collected before the
  settle, by which time the earliest fires may already be out; treat them as a
  hint, not a result.
