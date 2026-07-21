#!/usr/bin/env sh

# Check the prerequisites for building on macOS or Linux.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
cd "$SCRIPT_DIR"

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java was not found." >&2
    echo "Install a JDK (Java 8 or newer), set JAVA_HOME if needed, then retry." >&2
    exit 1
fi

echo "Java detected:"
java -version

if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then
    echo "ERROR: gradle/wrapper/gradle-wrapper.jar is missing." >&2
    echo "Restore it from version control, or generate a wrapper with a system Gradle." >&2
    exit 1
fi

if [ ! -x ./gradlew ]; then
    chmod +x ./gradlew
fi

echo "Build environment is ready. Run: ./build.sh"
