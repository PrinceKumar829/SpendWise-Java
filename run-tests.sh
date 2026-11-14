#!/bin/sh
# Runs all tests (no MySQL needed). macOS / Linux.
set -e
cd "$(dirname "$0")"
rm -rf out testout && mkdir -p out testout
javac -encoding UTF-8 -d out $(find backend/src/main -name "*.java")
javac -encoding UTF-8 -cp out -d testout $(find backend/src/test -name "*.java")
for t in SplitServiceTest InsightServiceTest JsonTest FlowTest HttpSmokeTest; do
  java -cp out:testout spendwise.$t
done
