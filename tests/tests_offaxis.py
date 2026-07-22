#!/usr/bin/env python3
"""
Gap positions off the cardinal axes, where the diagonal throw is exercised.

Slow to run in one go, so it takes a slice:
`tests_offaxis.py 0 5` runs the first five.
"""
import sys

from functions import run_case, summarise, SX, SZ, FIRE_Y

FLOOR = [(SX+dx, FIRE_Y+2, SZ+dz) for dx in range(-8, 9) for dz in range(-8, 9)]

CASES = [(1, 1), (2, 2), (3, 3), (4, 4), (5, 5),      # pure diagonals
         (3, 1), (1, 3), (4, 2), (2, 4), (5, 3),      # mixed offsets
         (-3, -1), (-2, 3), (4, -4), (-5, -5), (5, 1)]  # other quadrants

if __name__ == "__main__":
    lo = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    hi = int(sys.argv[2]) if len(sys.argv) > 2 else len(CASES)
    summarise([
        run_case(f"floor, gap at ({gx:+d},{gz:+d})", [p for p in FLOOR if p != (SX+gx, FIRE_Y+2, SZ+gz)])
            for (gx, gz) in CASES[lo:hi]
    ])
