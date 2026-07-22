#!/usr/bin/env python3
"""Floors with gaps, diagonal pinches, and partial coverage."""
from functions import run_case, summarise, SX, SY, SZ, FIRE_Y

FLOOR = [(SX+dx, FIRE_Y+2, SZ+dz)
         for dx in range(-8, 9) for dz in range(-8, 9)]

def floor_with_gap(*gaps):
    """The full floor minus the given (dx, dz) offsets."""
    holes = {(SX+dx, FIRE_Y+2, SZ+dz) for (dx, dz) in gaps}
    return [p for p in FLOOR if p not in holes]

if __name__ == "__main__":
    summarise([
        run_case("floor, gap on axis", floor_with_gap((0, 0))),
        run_case("floor, gap at (+2,+2)", floor_with_gap((2, 2))),
        run_case("floor, gap at (+5,0) cone edge", floor_with_gap((5, 0))),
        run_case("floor, gap at (+6,0) outside cone", floor_with_gap((6, 0))),
        run_case("diagonal pinch at d=1", [(SX+1, SY-1, SZ), (SX, SY-1, SZ+1)]),         # Two blocks corner to corner: water must not squeeze through.
        run_case("one side only at d=1", [(SX+1, SY-1, SZ)]),
        run_case("half floor (x >= SX)", [(SX+dx, FIRE_Y+2, SZ+dz) for dx in range(0, 9) for dz in range(-8, 9)]),
    ])
