# Refactor Merge Nearby Elevators

## Objective

Convert qualifying closed `highway=elevator` ways into elevator nodes when no
nearby elevator node exists, clean the original way tags, merge all nearby
elevator nodes using the existing logic, and persist the distance threshold.

## Implementation

- Discover complete, non-deleted, closed `highway=elevator` ways with known
  coordinates.
- Use each way's average center and the selected distance threshold to find
  nearby `highway=elevator` nodes.
- Add a centered elevator node with non-empty `level` and `name` tags when no
  nearby node exists, then remove only `highway` and `indoor` from the source
  way.
- Include newly created nodes in the existing proximity merge command and keep
  conversion, cleanup, and merging in one undoable sequence.
- Persist the accepted threshold in JOSM preferences with a 2-metre default.

## Verification

- Test conversion, duplicate prevention, tag cleanup, multiple-way merging,
  first-non-empty name handling, invalid coordinates, and undo behavior.
- Run the focused cleanup tests and full `./gradlew test`.
- Complete with `./build.sh --install`; fix and rerun repository-caused errors.
