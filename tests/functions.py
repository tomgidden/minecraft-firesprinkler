#!/usr/bin/env python3
"""
Shared machinery for the spray geometry tests.

Everything that touches the world lives here, because most of the false
failures during development came from the harness rather than the mod: a
sprinkler left standing by a previous case, an obstruction overwriting a
support block, fire expiring on its own. Keeping the build sequence in one
place means those fixes apply to every suite rather than to whichever one they
were noticed in.
"""
import sys
import time

from mcrcon import MCRcon

try:
    from config import (
        RCON_HOST, RCON_PASSWORD, RCON_PORT,
        SX, SY, SZ, FLOOR_Y,
        MAX_RADIUS, BASE_RADIUS,
        SUPPORT_BLOCK, BUTTON_BLOCK, OBSTRUCTION_BLOCK, FLOOR_BLOCK,
        ROOF_BLOCK,
    )
except ModuleNotFoundError:
    sys.exit("No config.py -- copy config.py.dist to config.py and edit it.")
except ImportError as e:
    # config.py predates a setting added to config.py.dist since it was copied.
    sys.exit(f"config.py needs updating: {e}. Compare it against config.py.dist.")

FIRE_Y = FLOOR_Y + 1
PAD = 7                              # tested plane is (2*PAD+1) square
CLEAR = PAD + 12                     # wiped volume, much wider than we test

# How far above the head level to wipe. A sprinkler only reaches maxDepth down,
# but its *water supply* sits above it, and anything else up there can shadow a
# cone or feed a stray head -- so clear well past both.
CLEAR_ABOVE = 8

# The roof sits above everything the cone can use, so it cannot shadow a test.
ROOF_Y_OFFSET = CLEAR_ABOVE + 2

# Sensible default: one sprinkler at the configured spot.
DEFAULT_HEADS = [(SX, SY, SZ)]


# --- the model ---------------------------------------------------------------

def radius_at_depth(d):
    """Chebyshev radius of the cone d levels below a head."""
    return min(MAX_RADIUS, BASE_RADIUS + d)


def model_wet(solid, hd, hx, hz, d, x, z):
    """
    Does the head at (hx, hd, hz) wet the cell (x, z) at depth d?

    Two segments, matching what the water does: the head throws it outward one
    block per level until it is over the target column, then it falls straight
    down. `solid(x, depth, z)` reports obstructions, with depth measured from
    the reference head level (SY), not from this head.
    """
    rel = d - hd

    if rel < 1:        return False

    ox, oz = x - hx, z - hz
    
    # `cheb` is the "Chebyshev" distance from the head to the target column, in blocks.
    cheb = max(abs(ox), abs(oz))

    if cheb > radius_at_depth(rel): return False

    # The head already covers BASE_RADIUS on its own level, so the first rings
    # cost no travel and ring k lands at depth k - BASE_RADIUS.
    throw_depth = max(0, cheb - BASE_RADIUS)

    cx = cz = 0
    for i in range(1, throw_depth + 1):
        nx = cx + (0 if ox == cx else (1 if ox > cx else -1))
        nz = cz + (0 if oz == cz else (1 if oz > cz else -1))
        if solid(hx + nx, hd + i, hz + nz):
            return False

        # A step moving in both axes must not squeeze between two blocks set
        # corner to corner.
        if nx != cx and nz != cz:
            if solid(hx + nx, hd + i, hz + cz) and solid(hx + cx, hd + i, hz + nz):
                return False
        cx, cz = nx, nz

    for e in range(hd + throw_depth + 1, d + 1):
        if solid(x, e, z):
            return False
    return True


# --- the world ---------------------------------------------------------------

def connect():
    """An RCON session against the configured server."""
    return MCRcon(RCON_HOST, RCON_PASSWORD, port=RCON_PORT)


def reset_area(mc):
    """
    Wipe the working volume back to a known state: open space above a floor
    that fire cannot burn out on.

    Clears far beyond the tested plane, and above the head level as well as
    below it. Leftovers from a previous case have shown up as phantom failures
    more than once, and anything overhead can shadow a cone or feed a stray
    head. Cheap to overdo, expensive to get wrong.
    """
    mc.command(f"fill {SX-CLEAR} {FIRE_Y} {SZ-CLEAR} "
               f"{SX+CLEAR} {SY+CLEAR_ABOVE} {SZ+CLEAR} air")
    mc.command(f"fill {SX-CLEAR} {FLOOR_Y} {SZ-CLEAR} "
               f"{SX+CLEAR} {FLOOR_Y} {SZ+CLEAR} {FLOOR_BLOCK}")
    # Roof the lot. Rain puts fire out on its own, which is indistinguishable
    # from the sprinkler doing it, and we should not depend on the server
    # happening to have weather disabled.
    mc.command(f"fill {SX-CLEAR} {SY+ROOF_Y_OFFSET} {SZ-CLEAR} "
               f"{SX+CLEAR} {SY+ROOF_Y_OFFSET} {SZ+CLEAR} {ROOF_BLOCK}")


def clear_heads(mc):
    """
    Sweep away every button in the working area, whatever its height.

    A stray head left by an earlier case adds its own cone and silently widens
    the sprayed area, which reads as the geometry being too generous rather
    than as contamination -- the nastiest of the harness traps. `reset_area`
    already wipes this volume, so this is belt and braces for cases that build
    heads outside it.
    """
    mc.command(f"fill {SX-CLEAR} {FIRE_Y} {SZ-CLEAR} "
               f"{SX+CLEAR} {SY+CLEAR_ABOVE} {SZ+CLEAR} air replace #minecraft:buttons")


def build_obstructions(mc, obstructions):
    """
    Place the case's obstructions.

    Always call before building heads: an obstruction at a head's support level
    would overwrite it, silently disarming that sprinkler.
    """
    for (x, y, z) in obstructions:
        mc.command(f"setblock {x} {y} {z} {OBSTRUCTION_BLOCK}")


def build_sprinkler(mc, hx, hy, hz):
    """
    Build one sprinkler: a waterlogged support with a ceiling button under it.

    Raises if the button did not stick. A missing sprinkler reports "nothing
    was extinguished", which is exactly what a geometry bug looks like, so it
    is worth failing loudly instead.
    """
    mc.command(f"setblock {hx} {hy+1} {hz} {SUPPORT_BLOCK}")
    mc.command(f"setblock {hx} {hy} {hz} {BUTTON_BLOCK}")
    if not mc.command(f"execute if block {hx} {hy} {hz} #minecraft:buttons run seed").strip():
        raise SystemExit(f"could not build sprinkler at {hx},{hy},{hz}")


def build_sprinklers(mc, heads):
    for (hx, hy, hz) in heads:
        build_sprinkler(mc, hx, hy, hz)


def plane_cells():
    """Every (x, z) in the tested plane."""
    return [(SX+dx, SZ+dz) for dz in range(-PAD, PAD+1) for dx in range(-PAD, PAD+1)]


def light_fires(mc, cells):
    """Light a fire in each cell; returns those that did not catch."""
    for (x, z) in cells:
        mc.command(f"setblock {x} {FIRE_Y} {z} fire")
    return [(x, z) for (x, z) in cells
            if not mc.command(f"execute if block {x} {FIRE_Y} {z} fire run seed").strip()]


def read_fires(mc, cells):
    """Read back which fires are gone. '#' extinguished, '.' still burning."""
    return {(x, z): ('.' if mc.command(
        f"execute if block {x} {FIRE_Y} {z} fire run seed").strip() else '#')
        for (x, z) in cells}


def extinguish_all(mc):
    mc.command(f"fill {SX-CLEAR} {FIRE_Y} {SZ-CLEAR} {SX+CLEAR} {FIRE_Y} {SZ+CLEAR} air replace fire")


# --- running a case ----------------------------------------------------------

def run_case(name, obstructions=(), heads=None, settle=10):
    """
    Build a case, burn it, and compare the result against the model.

    `obstructions` are absolute (x, y, z). `heads` are absolute (x, y, z)
    button positions and may sit at different heights; the single sprinkler at
    the configured spot is used if omitted.
    """
    if heads is None:
        heads = DEFAULT_HEADS
    obs = set(obstructions)

    # Obstruction lookup in the model's coordinates: depth below SY.
    def solid(x, depth, z):
        return (x, SY - depth, z) in obs

    cells = plane_cells()

    with connect() as mc:
        reset_area(mc)
        build_obstructions(mc, obstructions)
        clear_heads(mc)
        build_sprinklers(mc, heads)
        unlit = light_fires(mc, cells)

    time.sleep(settle)

    with connect() as mc:
        actual = read_fires(mc, cells)
        extinguish_all(mc)

    depth = SY - FIRE_Y
    expect = {}
    for (x, z) in cells:
        if (x, FIRE_Y, z) in obs:
            expect[(x, z)] = 'X'
            continue
        expect[(x, z)] = '#' if any(
            model_wet(solid, SY - hy, hx, hz, depth, x, z) for (hx, hy, hz) in heads
        ) else '.'

    return report(name, cells, expect, actual, unlit)


def report(name, cells, expect, actual, unlit=()):
    """Print the comparison; return True if it matched."""
    # A cell holding an obstruction has no fire in it, so the readback always
    # says "extinguished". That is an artefact of how we read, not a result.
    diffs = [c for c in cells if expect[c] != 'X' and actual[c] != expect[c]]

    status = "OK      " if not diffs else "MISMATCH"
    note = f"   ({len(unlit)} failed to light)" if unlit else ""
    print(f"{status} {name}{note}")

    if diffs:
        print("        model                      actual")
        for dz in range(-PAD, PAD+1):
            row = [(SX+dx, SZ+dz) for dx in range(-PAD, PAD+1)]
            e = ''.join(expect[c] for c in row)
            a = ''.join(actual[c] for c in row)
            print(f"        {e}   {a}" + ("  <" if e != a else ""))
        print(f"        {len(diffs)} differing cells")
    print()
    return not diffs


def summarise(results):
    print(f"{sum(results)}/{len(results)} passed")
    return all(results)
