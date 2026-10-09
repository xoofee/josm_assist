# Simplify Way 2 service-road fallback

## Objective

Change `Simplify Way 2` so that, when the current selection contains no
`highway=service` way, the action simplifies all complete `highway=service`
ways in the edit data set and all complete ways connected to them. Keep the
current selected-way workflow unchanged when at least one selected way is a
`highway=service` way.

## Current behavior

- `SimplifyWay2Action.actionPerformed` reads only `dataSet.getSelectedWays()`.
- Incomplete selected ways are discarded.
- If no usable selected way remains, the action returns without showing the
  parameter dialog or creating a command.
- Combination and simplification already operate on the supplied way
  collection, including same-tag checks, protected nodes, rounded-corner
  fitting, and one undoable command.

## Proposed behavior

1. Resolve the action input ways before asking for simplification parameters.
2. If the selection contains at least one complete way tagged
   `highway=service`, use the existing complete selected ways as the input.
3. Otherwise, find every complete, non-deleted way in the edit data set tagged
   exactly `highway=service`.
4. Expand that seed set through connected ways: any complete way sharing a
   node with a seed way is included, and the search continues transitively
   until no new connected way is found. This allows service-road chains and
   their connecting ways to be processed together.
5. Deduplicate the expanded collection and pass it through the existing
   combination/simplification command builder. Do not mutate the selection as
   part of scope discovery.
6. If there are no service seeds, or no resulting ways that can be processed,
   return without opening the parameter dialog. Preserve the existing
   no-op behavior when the command builder returns `null`.
7. Keep the operation one undoable command and preserve all current safety
   rules: incomplete ways are excluded, relation/tag compatibility still
   controls combination, and protected nodes remain protected.

The fallback scope should be computed from the edit data set rather than all
layers. Connected means shared `Node` identity through JOSM parent-way
relationships; it does not mean geometric proximity or crossing segments.

## Implementation outline

### `SimplifyWay2Action`

- Extract input resolution from `actionPerformed` into a package-private or
  private helper that accepts the `DataSet` and selected ways.
- Add small helpers for the exact `highway=service` predicate, collecting
  service seeds, and transitively collecting connected complete ways.
- Use a queue/deque plus a visited set to avoid repeated scans and ensure
  deterministic ordering where practical.
- Reuse `createCommand` unchanged unless tests show that its current selected
  way assumptions prevent a data-set-wide input from being handled safely.
- Keep dialog timing unchanged for a valid resolved scope: resolve the scope
  first, then ask for tolerances, then enqueue the command.

### Tests

Extend `SimplifyWay2ActionTest` with focused cases covering:

- no selection plus multiple disconnected `highway=service` ways: all service
  ways are included;
- a service way connected to an untagged or differently tagged way: both are
  included, while unrelated ways are excluded;
- transitive connectivity across more than one connecting way;
- selected non-service ways with no selected service way: the fallback scope is
  used rather than only the non-service selection;
- at least one selected service way: the existing selected-way scope is kept
  and unrelated service ways are not unexpectedly changed;
- incomplete or deleted service ways and connected ways are excluded;
- no service seeds: no command/input scope is produced;
- the resulting command remains executable and undoable, including combined
  ways and removed nodes.

Prefer testing scope resolution separately from command execution so the
selection rules and simplification algorithm remain independently diagnosable.
Use the existing in-memory `DataSet`, node-sharing helpers, and projection
setup.

## Documentation

Update `docs/SIMPLIFY_WAY_2.md` to describe both modes:

- selected `highway=service` mode uses the selected ways;
- otherwise the action discovers all service ways and their transitively
  connected way network in the current edit layer.

Document that the fallback does not use geometric proximity, does not modify
the current selection, skips incomplete/deleted ways, and remains undoable.
Update the limitations section if the scope rules introduce any new boundary
cases.

## Verification

1. Run the focused `SimplifyWay2ActionTest` suite.
2. Run the repository’s full test task (for example, `./gradlew test`) and fix
   regressions caused by the implementation.
3. Review the final diff to confirm that only the action, its tests,
   `docs/SIMPLIFY_WAY_2.md`, and this execution plan are changed; preserve any
   unrelated pre-existing worktree changes.

## Acceptance criteria

- With no selected service way, one invocation processes every complete
  `highway=service` way and every transitively node-connected complete way in
  the current edit data set.
- With a selected service way, existing selected-way behavior remains intact.
- Unrelated ways are untouched, and selection is not rewritten.
- Empty/incomplete/deleted scopes do not show an unnecessary dialog or create
  a command.
- Existing simplification, combination, protection, rounded-corner, and undo
  behavior remains passing.
