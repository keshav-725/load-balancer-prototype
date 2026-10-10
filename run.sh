#!/usr/bin/env bash
# Compiles the prototype with plain javac (no Maven/Gradle, no dependencies) and runs the scripted demo.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac -d out $(find src/main/java -name '*.java')
java -Dstdout.encoding=UTF-8 -cp out lb.demo.Demo
