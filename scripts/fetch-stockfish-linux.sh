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
NAME=$(grep -E '^stockfish-linux-x86-64-universal\.tar(\.gz)?$' assets.txt | head -n1 || true)
[ -n "$NAME" ] || NAME=$(grep -E '^stockfish-(linux|ubuntu)-x86-64-avx2\.tar(\.gz)?$' assets.txt | head -n1 || true)
[ -n "$NAME" ] || NAME=$(grep -E '^stockfish-(linux|ubuntu)-x86-64.*\.tar(\.gz)?$' assets.txt | head -n1 || true)
echo "Using asset: $NAME"
gh release download "$TAG" -R official-stockfish/Stockfish -p "$NAME" --clobber
tar -tvf "$NAME" | head -n 40
tar -xf "$NAME"
BIN=$(find . -type f -name 'stockfish*' ! -name '*.tar*' ! -name '*.txt' -size +1M -printf '%s %p\n' | sort -nr | head -n1 | cut -d' ' -f2-)
echo "Binary: $BIN"
file "$BIN" || true
chmod +x "$BIN"
cp "$BIN" "$OUT/stockfish-bin"
printf 'uci\nquit\n' | "$OUT/stockfish-bin" > uci.txt || true
head -n 2 uci.txt
echo "STOCKFISH=$OUT/stockfish-bin" >> "${GITHUB_ENV:-/dev/null}"
