# Remove Single-Level Elevator Nodes

## Goal

Add a separate Assist action below “Merge Nearby Elevators” that removes active
`highway=elevator` nodes serving exactly one non-empty level.

## Implementation

- Add an undoable `DeleteCommand` action for qualifying elevator nodes.
- Ignore deleted, incomplete, non-elevator, coordinate-less, missing-level,
  empty-level, and multi-level nodes.
- Register the action in the Assist menu and toolbar.
- Treat whitespace-only semicolon-separated parts as empty.

## Verification

- Cover filtering, normalization, and undo behavior with regression tests.
- Run the focused and full test suites, then `./build.sh --install`.
