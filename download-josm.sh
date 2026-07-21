#!/usr/bin/env sh

# Download a JOSM JAR for local plugin compilation on macOS or Linux.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
DESTINATION="$SCRIPT_DIR/libs/josm.jar"
URL="https://josm.openstreetmap.de/josm-tested.jar"

case "${1:-}" in
    "")
        ;;
    --latest)
        URL="https://josm.openstreetmap.de/josm-latest.jar"
        ;;
    -h|--help)
        echo "Usage: ./download-josm.sh [--latest]"
        echo "Downloads the tested JOSM JAR by default to libs/josm.jar."
        exit 0
        ;;
    *)
        echo "ERROR: Unknown option: $1" >&2
        echo "Usage: ./download-josm.sh [--latest]" >&2
        exit 2
        ;;
esac

mkdir -p "$(dirname -- "$DESTINATION")"
TEMP_DESTINATION="$DESTINATION.tmp.$$"
trap 'rm -f "$TEMP_DESTINATION"' EXIT HUP INT TERM

echo "Downloading JOSM from: $URL"
if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --output "$TEMP_DESTINATION" "$URL"
elif command -v wget >/dev/null 2>&1; then
    wget --tries=3 --output-document="$TEMP_DESTINATION" "$URL"
else
    echo "ERROR: curl or wget is required to download JOSM." >&2
    exit 1
fi

if [ ! -s "$TEMP_DESTINATION" ]; then
    echo "ERROR: JOSM download was empty." >&2
    exit 1
fi

mv "$TEMP_DESTINATION" "$DESTINATION"
trap - EXIT HUP INT TERM
echo "JOSM JAR downloaded to: $DESTINATION"
