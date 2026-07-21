#!/usr/bin/env sh

# Minimal POSIX launcher for the Gradle wrapper JAR committed to this project.
# It works on macOS and Linux without requiring a system Gradle installation.
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "ERROR: Gradle wrapper JAR not found: $WRAPPER_JAR" >&2
    echo "Run ./setup-build-env.sh or install Gradle and run: gradle wrapper" >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java is required. Install a JDK (Java 8 or newer) and try again." >&2
    exit 1
fi

exec java ${JAVA_OPTS:-} ${GRADLE_OPTS:-} -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
