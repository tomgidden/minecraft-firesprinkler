#!/usr/bin/env python3
"""Several sprinklers at once: the sprayed area should be their union."""
from functions import run_case, summarise, SX, SY, SZ

def heads(*offsets):
    """Heads at (dx, dz) offsets, all at the configured button level."""
    return [(SX+dx, SY, SZ+dz) for (dx, dz) in offsets]

if __name__ == "__main__":
    summarise([
        run_case("two heads, 4 apart", heads=heads((-2, 0), (2, 0))),
        run_case("two heads, 10 apart (cones just meet)", heads=heads((-5, 0), (5, 0))),
        run_case("two heads diagonal", heads=heads((-3, -3), (3, 3))),
        run_case("four heads in a square", heads=heads((-3, -3), (3, -3), (-3, 3), (3, 3))),
    ])
