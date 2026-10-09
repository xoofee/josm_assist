# JOSM Assist Plugin

A JOSM (Java OpenStreetMap Editor) plugin that provides enhanced polygon selection and level processing features.

Windows:

```powershell
# Windows: set UTF-8 to prevent garbled Chinese Gradle output
chcp 65001
.\gradlew.bat build; .\install.ps1
```

macOS / Ubuntu:

```bash
# macOS / Ubuntu
./build.sh --install
```

## Features

### 1. Plugin Toggle
- Enable/disable the plugin via menu item or keyboard shortcut (Ctrl+Alt+J)
- Toggle button in the main menu

### 2. Polygon Selection by Click
- Click anywhere inside a polygon to select it
- Works seamlessly with JOSM's existing selection mode
- Uses JOSM's built-in geometry helpers (`Geometry.nodeInsidePolygon`)

### 3. Overlapping Polygon Handling
- When multiple polygons overlap at the click point, automatically selects the smallest area first
- Only active when JOSM is in selection mode

### 4. Auto Tag Editor
- After selecting a polygon, automatically opens the tag editor (simulates Alt+S)
- Allows immediate editing of the selected polygon's tags

### 5. Enhanced Level Processing
- When a level filter is active, newly created elements (nodes or ways) automatically receive the level tag
- Tags are assigned when the user exits edit mode (e.g., by pressing Esc)
- Prevents newly created elements from being greyed out and unselectable

### 6. Simplify Way 2
- Run with **Ctrl+Shift+Y**, from the Assist menu, or its toolbar button.
- Select the ways to simplify. Selected, open ways with matching tags can be combined through a non-branching shared endpoint before simplification; both ways must be selected.
- Tagged nodes, repeated nodes, relation-referenced nodes, and nodes shared with other ways are protected.
- Configure separate maximum errors for ordinary simplification and rounded-corner fitting. Both default to 3 metres and can be remembered independently.
- Rounded L-shaped runs are replaced by an untagged node at the intersection of their fitted straight lines. Ambiguous, parallel, or poorly fitted corners are left unchanged.
- The fitted lines use the points in each candidate run; the result is not guaranteed to equal the intersection of only the first and last endpoint-adjacent segments of a rounded way.
- Combining, corner restoration, node removal, and cleanup are performed as one undoable operation.

See [docs/SIMPLIFY_WAY_2.md](docs/SIMPLIFY_WAY_2.md) for the algorithm, safety rules, and examples.

### 7. Cleanup tools
- **Clean Up Areas** removes qualifying named area ways and their untagged,
  safe orphan nodes. Nodes shared with other ways or relations, or carrying
  tags, are preserved.
- **Merge Nearby Elevators** merges nearby elevator nodes after asking for a distance threshold.
- **Convert Unnamed Rooms to Walls** converts unnamed `indoor=room` ways to
  `barrier=wall` ways.
- These actions are available in the Assist menu and can be added independently from JOSM's toolbar configuration.

## Installation

1. Build the plugin:
   ```bash
   # macOS / Ubuntu
   ./build.sh

   # Windows
   gradlew.bat build
   ```

2. Copy the generated JAR file from `build/libs/josmassist-1.0.jar` to your JOSM plugins directory:
   - Windows: `%APPDATA%\JOSM\plugins\`
   - Linux: `~/.local/share/JOSM/plugins/` (or `$XDG_DATA_HOME/JOSM/plugins/`)
     for current JOSM installations; `~/.josm/plugins/` for legacy profiles
   - macOS: `~/Library/Application Support/JOSM/plugins/`

3. Restart JOSM or go to Edit → Preferences → Plugins and enable "JOSM Assist"

## Usage

1. **Toggle Plugin**: Use the menu item or press Ctrl+Alt+J to enable/disable the plugin
2. **Select Polygon**: Make sure you're in selection mode, then click inside any polygon to select it
3. **Level Processing**: When a level filter is active, create new elements and they will automatically get the level tag when you exit edit mode

## Development

### Building

```bash
# macOS / Ubuntu: check Java and wrapper prerequisites once, then build
./setup-build-env.sh
./build.sh

# Copy the built plugin to JOSM's plugin directory
./install.sh
# Or build and install in one step
./build.sh --install
```

macOS and Ubuntu/Linux use the same POSIX shell scripts. Both require a JDK
(Java 8 or newer), `curl` or `wget`, and internet access on the first build.
`build.sh` downloads the tested JOSM JAR to `libs/josm.jar` if it is not
already present. The install location is selected
automatically: `~/Library/Application Support/JOSM/plugins/` on macOS and
`~/.local/share/JOSM/plugins/` on current Linux installations (or
`$XDG_DATA_HOME/JOSM/plugins/`). Existing legacy `~/.josm` profiles continue
to use `~/.josm/plugins/`.

To use a local JOSM JAR instead of the Maven dependency:

```bash
./download-josm.sh
```

### Project Structure

```
src/org/openstreetmap/josm/plugins/josmassist/
├── JosmAssistPlugin.java          # Main plugin class
├── JosmAssistMapMode.java         # Mouse listener for polygon selection
├── PolygonClickHandler.java       # Handles polygon selection logic
├── LevelProcessingHandler.java    # Handles level tag assignment
└── EditModeExitListener.java      # Listens for edit mode exit
```

## Requirements

- JOSM (latest version recommended)
- Java 8 or higher

## License

[Specify your license here]

