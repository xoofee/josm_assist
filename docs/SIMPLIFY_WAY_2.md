# Simplify Way 2

`Simplify Way 2` is the JOSM Assist version of way simplification with
additional handling for protected shared nodes and rounded corners. It is
available in the Tools menu and toolbar, and uses **Ctrl+Shift+Y**.

## What it does

The action works only on selected ways. Before simplifying, it can combine a
selected chain such as `AB` + `BCD` into `ABCD`. Combination is allowed only
when all participating ways are selected, open, relation-free, have exactly
matching tags, and meet at a non-branching endpoint used by exactly two ways.
The shared endpoint is included once. Unselected neighboring ways are not
changed, so select every way that should participate in pre-combination.

The resulting paths are simplified with a Douglas–Peucker-style, metre-based
error test. The first and last nodes, tagged nodes, repeated nodes, nodes
referenced by relations, and nodes referenced by other ways are preserved.
This means a protected node remains an anchor, while removable nodes on either
side of it can still be simplified independently.

For example:

```text
A--b--c--D       ->       A-----------D
```

provided the intermediate nodes are within the simplification tolerance.
Nodes outside that tolerance remain.

## Rounded L corners

Within an unprotected span, Simplify Way 2 looks for two straight runs with a
sharp turn. It fits one line to the nodes before the bend and one line to the
nodes after it, then creates one new untagged node at the exact intersection
of the two infinite fitted lines:

```text
rounded bend               restored corner
      ...                         |
   ...                            |
 A                               +---D
      ...
```

The candidate is accepted only when both line residuals are within the
rounded-corner tolerance, the lines are not degenerate or parallel, the
intersection is near the bend, and the turn is sufficiently sharp. Gentle
curves, ambiguous fits, and intersections far outside the span are left
unchanged. Protected nodes are never replaced.

Ordinary Douglas–Peucker simplification runs after corner restoration to remove
any remaining redundant points.

## Configuration

The dialog contains two values:

* **Maximum simplification error (meters)** — default 3.0 m.
* **Rounded corner fitting error (meters)** — default 3.0 m.

Both have a minimum of 0.01 m and a 0.5 m spinner increment. “Do not ask
again” stores them independently in:

* `josmassist.simplify-way2.max-error`
* `josmassist.simplify-way2.corner-error`
* `josmassist.simplify-way2.remember`

Canceling the dialog makes no map changes.

## Safety and undo

Ways or joining nodes that belong to relations are not combined. Exact tag
matching is required; no tag conflict resolution is attempted. Nodes removed
from a way are deleted only after all way changes have been applied and only
when no references remain. New intersection nodes are added to the dataset
through the same command sequence.

Combination, corner restoration, ordinary simplification, and cleanup are one
undoable operation.

## Limitations

The action does not combine unselected ways. A chain intended for pre-
combination must therefore be selected in full. It also skips closed ways,
relation-associated geometry, protected-node spans, parallel fitted runs,
degenerate lines, and corner candidates whose residual or location is
ambiguous.
