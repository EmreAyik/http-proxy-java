#!/usr/bin/env bash
# Local build helper - compiles all sources into ./out
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/*.java
echo "Built. Run with:  java -cp out Main           (GUI)"
echo "             or:  java -cp out Main --headless"
