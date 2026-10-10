# Split Elevator Conversion and Merge Actions

## Objective

Separate conversion of closed elevator/steps ways from merging nearby
`highway=elevator` nodes so users can inspect converted nodes before merging.

## Implementation

- Add a conversion action that always creates one centered elevator node for
  each eligible closed way, copies `level`, `level:ref`, and `name`, then removes
  `highway` and `indoor` from the source way.
- Make Merge Nearby Elevators operate only on existing elevator nodes and
  combine `level`, `level:ref`, and unique semicolon-separated name parts.
- Register both actions in the Assist menu and toolbar, with conversion before
  merging, and retain the existing merge-distance preference.

## Verification

- Test conversion near existing nodes without merging, node-only merging,
  combined unique names, two independent undo steps, and the supplied `-4`,
  `-3`, `-2` scenario at 11 m.
- Run focused tests, the full test suite, and `./build.sh --install`.
