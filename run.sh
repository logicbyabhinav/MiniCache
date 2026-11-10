#!/usr/bin/env bash
set -e
echo "========================================================"
echo "  Starting MiniCache Daemon (Port: 6379)"
echo "  Built from first principles in Java 21"
echo "========================================================"
mkdir -p bin
javac -d bin -sourcepath src src/com/minicache/*.java src/com/minicache/engine/*.java src/com/minicache/command/*.java src/com/minicache/server/*.java
java -cp bin com.minicache.MiniCache "$@"
