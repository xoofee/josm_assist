# Convert Unnamed Rooms to Walls

## Objective

Find complete, non-deleted ways tagged `indoor=room` without a `name` tag,
remove `indoor=room`, and add `barrier=wall` in one undoable action.

## Implementation

- Scan all ways in the current edit dataset.
- Preserve geometry and all tags other than the requested `indoor` replacement.
- Expose the operation as an Assist menu action immediately after the elevator
  road connector and as a toolbar-configurable action.

## Verification

- Test matching and non-matching ways, unrelated tag preservation, no-op
  behavior, and undo restoration.
- Run focused and full tests, then complete with `./build.sh --install`.
