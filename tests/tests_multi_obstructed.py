#!/usr/bin/env python3
"""
Several sprinklers with obstructions.

The case that matters: a block shadowing one head, where another head's cone
reaches the same spot around it. That is the "except if another sprinkler's
cone gets around it" clause in the README.
"""
from functions import run_case, summarise, SX, SY, SZ, FIRE_Y

FLOOR = [(SX+dx, FIRE_Y+2, SZ+dz) for dx in range(-9, 10) for dz in range(-9, 10)]

def heads(*offsets):
    return [(SX+dx, SY, SZ+dz) for (dx, dz) in offsets]

if __name__ == "__main__":
    summarise([
        run_case("shadowed head + clear head, same target", [(SX, FIRE_Y+2, SZ)], heads((0, 0), (4, 0))),
        run_case("floor with gap at (+3,0), heads at -4 and +3", [p for p in FLOOR if p != (SX+3, FIRE_Y+2, SZ)], heads((-4, 0), (3, 0))),
        run_case("one head blocked high, one blocked low", [(SX-3, SY-1, SZ), (SX+3, FIRE_Y+2, SZ)], heads((-3, 0), (3, 0))),
    ])
