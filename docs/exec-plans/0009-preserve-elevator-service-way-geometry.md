# Preserve elevator service-way geometry

## Objective

Prevent connecting multiple elevators from changing the shape of an existing
service way when projected connection points are inserted on different
segments.

## Implementation

- Record each new projected node against the segment index in the original
  service way.
- Rebuild each changed way's node list by walking the original nodes and
  inserting projected nodes after their original segment starts.
- Keep connector creation, level matching, and undo behavior unchanged.

## Verification

- Run `./build.sh --install`.
