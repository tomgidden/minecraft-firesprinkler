#!/usr/bin/env python3
"""
The join between the throw and the fall.

A block at the target offset, at each level from the head down: the throw
covers the diagonal approach and the fall covers the destination column, and
the two have to meet exactly.
"""
from functions import run_case, summarise, SX, SY, SZ

OX = 3   # target offset; with baseRadius 1 the throw ends at depth 2

if __name__ == "__main__":
    summarise([
        run_case(f"block at offset +{OX}, {lvl} below head", [(SX+OX, SY-lvl, SZ)])
            for lvl in range(1, 6)
    ])
