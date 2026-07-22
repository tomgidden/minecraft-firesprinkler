#!/usr/bin/env python3
"""
Not a test: a hand tool for poking at the server while diagnosing one.

Sends commands over RCON, one per argument.

    ./rcon.py "time query gametime" "setblock ~ ~ ~ fire"

Always prints the raw response, so an empty reply is distinguishable from a
command that produced no output -- `execute if block ...` says nothing when the
condition fails, which is how most of the checks here read the world.
"""
import sys

from functions import connect

if __name__ == "__main__":
    with connect() as mc:
        for cmd in sys.argv[1:]:
            print(f"$ {cmd}\n  [{mc.command(cmd).strip()}]")
