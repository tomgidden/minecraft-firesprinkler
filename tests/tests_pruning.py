#!/usr/bin/env python3
"""
Cases that exercise the heightmap pruning in computeSprayedAt.

The search skips columns whose ceiling is too low to hang a button from, and
gives up once every column in range is capped. Both are easy to get subtly
wrong: an over-eager skip loses a valid sprinkler, and the give-up is only
sound once the cone has stopped widening -- until then a taller column can
still be waiting just outside the footprint looked at so far.
"""
from functions import run_case, summarise, SX, SY, SZ, FIRE_Y

if __name__ == "__main__":
    summarise([
        # The head sits at the cone's edge, with nothing tall nearer it. An
        # abort that fired while the radius was still widening would miss it.
        run_case("head at radius 5, nothing tall nearer",
                 heads=[(SX+5, SY, SZ)]),
        run_case("head at radius 5 diagonal",
                 heads=[(SX+5, SY, SZ+5)]),

        # A head well below the surrounding terrain: its column's heightmap
        # reports far above the button, so the skip must not reject it.
        run_case("head under a taller stack",
                 [(SX, SY+3, SZ), (SX, SY+4, SZ)]),

        # Head low down, with the fire close beneath it.
        run_case("head 2 above the fire only",
                 heads=[(SX, FIRE_Y+2, SZ)]),
    ])
