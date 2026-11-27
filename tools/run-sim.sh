#!/usr/bin/env bash
# 難易度シミュレータを実行する。Minecraft/Forge は不要(JDKのみ)。
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${TMPDIR:-/tmp}/zapoc-sim"
rm -rf "$OUT"; mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" src/main/java/com/example/claudeganbate/difficulty/*.java tools/DifficultySim.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT" DifficultySim "$@"
