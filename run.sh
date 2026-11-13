#!/usr/bin/env bash
# Compile and start the Auto Workshop website (needs Java 21+)
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/com/autoshop/*.java || exit 1
echo "Starting Auto Workshop at http://localhost:8080  (press Ctrl+C to stop)"
java -cp out com.autoshop.AutoShopServer
