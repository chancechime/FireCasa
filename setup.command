#!/bin/sh
# Double-click me on a Mac: opens FireCasa setup in your browser. No Terminal typing needed.
cd "$(dirname "$0")" && exec python3 setup/server.py
