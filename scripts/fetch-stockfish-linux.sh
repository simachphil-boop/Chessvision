#!/usr/bin/env bash
# Downloads the official Linux Stockfish build (used by CI for engine tests and puzzle generation).
set -eu
OUT="${1:-$RUNNER_TEMP/sf}"
mkdir -p "$OUT"
cd "$OUT"
TAG=$(gh release view -R official-stockfish/Stockfish --json tagName -q .tagName)
echo "Stockfish release: $TAG"
gh release view "$TAG" -R official-stockfish/Stockfish --json assets -q '.assets[].name' > assets.txt
cat assets.txt
NAME=$(grep -E '^stockfish-ubuntu-x86-64-avx2\.tar$' assets.txt || grep -E '^stockfish-ubuntu-x86-64.*\.tar$' assets.txt | head -n1)
echo "Using asset: $NAME"
gh release download "$TAG" -R official-stockfish/Stockfish -p "$NAME" --clobber
tar -xf "$NAME"
BIN=$(find . -type f -name 'stockfish-ubuntu*' ! -name '*.tar' | head -n1)
chmod +x "$BIN"
cp "$BIN" "$OUT/stockfish-bin"
printf 'uci\nquit\n' | "$OUT/stockfish-bin" > uci.txt || true
head -n 2 uci.txt
echo "STOCKFISH=$OUT/stockfish-bin" >> "${GITHUB_ENV:-/dev/null}"
