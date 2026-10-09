# Remove orphan nodes with cleaned-up area ways

## Objective

Make `Clean Up Areas` delete safe orphan nodes together with qualifying named
area ways, while preserving nodes that are shared, tagged, relation-referenced,
incomplete, deleted, or outside the current data set.

## Implementation

- Extend `CleanupAction.createAreasCommand` to pass one deletion set containing
  matching area ways and safe orphan nodes to `DeleteCommand`.
- Keep the existing area matching criteria and elevator behavior unchanged.
- Document the node cleanup behavior in the README.

## Verification

- Test orphan-node deletion and undo restoration.
- Test preservation of shared, tagged, and relation-referenced nodes.
- Run the focused cleanup tests and full `./gradlew test`.
- Complete with `./build.sh --install`; fix and rerun repository-caused errors.
