#!/usr/bin/env bash
set -euo pipefail

IDLE_SEC="${1:-5}"
echo "Running PaperPlay Desktop Motion Benchmark (idleSec=$IDLE_SEC)..."
./gradlew :desktopApp:run --no-daemon -q --args="--motion-bench --idle-sec=$IDLE_SEC"
