# JOSM Assist Plugin

A JOSM (Java OpenStreetMap Editor) plugin that provides enhanced polygon selection and level processing features.

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

## Installation

1. Build the plugin:
   ```bash
   ./gradlew build
   ```

2. Copy the generated JAR file from `build/libs/josmassist-1.0.jar` to your JOSM plugins directory:
   - Windows: `%APPDATA%\JOSM\plugins\`
   - Linux: `~/.josm/plugins/`
   - macOS: `~/Library/Application Support/JOSM/plugins/`

3. Restart JOSM or go to Edit → Preferences → Plugins and enable "JOSM Assist"

## Usage

1. **Toggle Plugin**: Use the menu item or press Ctrl+Alt+J to enable/disable the plugin
2. **Select Polygon**: Make sure you're in selection mode, then click inside any polygon to select it
3. **Level Processing**: When a level filter is active, create new elements and they will automatically get the level tag when you exit edit mode

## Development

### Building

```bash
./gradlew build
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

