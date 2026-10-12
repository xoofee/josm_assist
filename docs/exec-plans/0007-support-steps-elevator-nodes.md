# Support `highway=steps` Nodes in Elevator Conversion

## Objective

Retag existing `highway=steps` nodes as `highway=elevator` in place when the
Convert Elevator Ways to Nodes action runs, while preserving all other tags and
supporting undo.

## Implementation

- Scan eligible `highway=steps` nodes in addition to the existing closed
  elevator and steps ways.
- Change only the node's `highway` tag to `elevator` through the same undoable
  command sequence.
- Keep existing way conversion, tag copying, source cleanup, and nearby-node
  behavior unchanged.
- Update action and converter descriptions to mention steps nodes.

## Verification

- Test node retagging, tag preservation, undo, filtering, and mixed
  way-and-node conversion.
- Run focused elevator tests, the full Gradle test suite, and
  `./build.sh --install`.
