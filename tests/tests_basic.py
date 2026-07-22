#!/usr/bin/env python3
"""Base cases: a clear cone, and single obstructions at various depths."""
from functions import run_case, summarise, SX, SY, SZ, FIRE_Y

if __name__ == "__main__":
    summarise([
        run_case("clear (no obstruction)"),
        run_case("block immediately below button", [(SX, SY-1, SZ)]),
        run_case("block at d=2 on axis", [(SX, SY-2, SZ)]),
        run_case("single shelf on axis, 2 above fire", [(SX, FIRE_Y+2, SZ)]),
        run_case("single shelf offset +2", [(SX+2, FIRE_Y+2, SZ)]),
    ])
