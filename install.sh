#!/usr/bin/env sh

# Install the built plugin into the current user's JOSM plugin directory.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
cd "$SCRIPT_DIR"

JAR_PATH="build/libs/josmassist-1.0.jar"
if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: $JAR_PATH does not exist. Run ./build.sh first." >&2
    exit 1
fi

case "$(uname -s)" in
    Darwin)
        PLUGIN_DIR="$HOME/Library/Application Support/JOSM/plugins"
        ;;
    Linux)
        XDG_JOSM_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/JOSM"
        # JOSM uses XDG directories in current Linux installations, but keep
        # using a legacy directory when that is the user's only JOSM profile.
        if [ -d "$HOME/.josm" ] && [ ! -d "$XDG_JOSM_DIR" ]; then
            PLUGIN_DIR="$HOME/.josm/plugins"
        else
            PLUGIN_DIR="$XDG_JOSM_DIR/plugins"
        fi
        ;;
    *)
        echo "ERROR: Unsupported operating system: $(uname -s)" >&2
        echo "Copy $JAR_PATH to your JOSM plugins directory manually." >&2
        exit 1
        ;;
esac

mkdir -p "$PLUGIN_DIR"
cp "$JAR_PATH" "$PLUGIN_DIR/"
echo "Installed JOSM Assist to: $PLUGIN_DIR/josmassist-1.0.jar"
echo "Restart JOSM, in Edit > Preferences > Plugins search jossassist, check to enable it"
