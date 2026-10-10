# Preserve levels when merging nearby elevator ways

## Objective

Preserve `level` and `level:ref` from closed elevator/steps ways when they are
near an existing elevator node, so the resulting elevator represents every
level served by the nearby features.

## Implementation

- Always convert qualifying closed `highway=elevator` and `highway=steps` ways
  into temporary elevator nodes before proximity grouping.
- Merge the temporary node through the existing proximity merge, rather than
  discarding its tags when an elevator is already nearby.
- Copy and combine only the valid `level:ref` key; remove the incorrect
  `levelref` handling.

## Verification

- Update existing level-reference tests.
- Add a regression test for a nearby `-4;-2` elevator and `-3` steps way.
- Run focused tests, the full test suite, and `./build.sh --install`.
