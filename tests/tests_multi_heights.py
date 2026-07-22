#!/usr/bin/env python3
"""
Sprinklers at differing heights.

This is the group that caught reaches() ignoring baseRadius: a head low enough
for the fire to sit in the widening part of its cone, rather than the flat part
where the radius has already capped.
"""
from functions import run_case, summarise, SX, SY, SZ, FIRE_Y

FLOOR = [(SX+dx, FIRE_Y+4, SZ+dz)
         for dx in range(-9, 10) for dz in range(-9, 10)]

if __name__ == "__main__":
    summarise([
        run_case("two heads, 3 levels apart", heads=[(SX-3, SY, SZ), (SX+3, SY-3, SZ)]),
        run_case("floor with gap at +3, extra head below floor", [p for p in FLOOR if p != (SX+3, FIRE_Y+4, SZ)], [(SX, SY, SZ), (SX-3, FIRE_Y+3, SZ)]),
        run_case("low head only, high one fully sealed", FLOOR, [(SX, SY, SZ), (SX, FIRE_Y+3, SZ)]),
    ])
