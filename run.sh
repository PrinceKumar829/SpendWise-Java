#!/bin/sh
# Compiles the backend and starts SpendWise (macOS / Linux). Needs JDK 17+ and lib/mysql-connector-j-*.jar
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out $(find backend/src/main -name "*.java")
java -cp "out:lib/*" spendwise.Server
