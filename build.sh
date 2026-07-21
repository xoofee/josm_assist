#!/usr/bin/env sh

# Build the plugin on macOS or Linux.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
cd "$SCRIPT_DIR"

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java is required. Install a JDK (Java 8 or newer) and try again." >&2
    exit 1
fi

if [ ! -f libs/josm.jar ]; then
    echo "Local JOSM dependency not found; downloading the tested JOSM JAR..."
    ./download-josm.sh
fi

echo "Building JOSM Assist Plugin..."
./gradlew build

JAR_PATH="build/libs/josmassist-1.0.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: Build completed but expected JAR was not created: $JAR_PATH" >&2
    exit 1
fi

echo "Build successful: $JAR_PATH"

if [ "${1:-}" = "--install" ]; then
    exec ./install.sh
fi
