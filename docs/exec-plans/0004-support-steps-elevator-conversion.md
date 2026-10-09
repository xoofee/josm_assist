# Support Closed `highway=steps` Ways

## Objective

Process complete closed `highway=steps` ways the same way as closed
`highway=elevator` ways during Merge Nearby Elevators.

## Implementation

- Accept exactly `highway=elevator` and `highway=steps` as conversion sources.
- Create `highway=elevator` nodes at the source way center when no nearby
  elevator node exists.
- Copy non-empty `level` and `name`, remove `highway` and `indoor` from the
  source way, and reuse the existing merge and undo sequence.
- Preserve threshold persistence and standalone elevator-node behavior.

## Verification

- Test steps conversion, copied tags, source cleanup, duplicate prevention,
  merging, and undo behavior.
- Run the focused cleanup tests, full `./gradlew test`, and
  `./build.sh --install`.
