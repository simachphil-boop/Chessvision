#!/usr/bin/env bash
# Downloads the official Linux Stockfish build (used by CI for engine tests and puzzle generation).
set -euo pipefail
OUT="${1:-$RUNNER_TEMP/sf}"
mkdir -p "$OUT"
cd "$OUT"
gh release download -R official-stockfish/Stockfish -p 'stockfish-ubuntu-x86-64-avx2.tar' --clobber \
  || gh release download -R official-stockfish/Stockfish -p 'stockfish-ubuntu-x86-64.tar' --clobber
tar -xf stockfish-ubuntu-*.tar
BIN=$(find . -type f -name 'stockfish-ubuntu*' ! -name '*.tar' | head -n1)
chmod +x "$BIN"
cp "$BIN" "$OUT/stockfish-bin"
echo "uci" | timeout 10 "$OUT/stockfish-bin" | grep -m1 "id name"
echo "STOCKFISH=$OUT/stockfish-bin" >> "${GITHUB_ENV:-/dev/null}"
