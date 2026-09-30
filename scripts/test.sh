#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
xcrun swiftc Sources/Collection.swift Tests/main.swift -lsqlite3 -o build/CollectionTests
./build/CollectionTests
