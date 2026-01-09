This is an JOSM plugin for map edit.

All the code is for reference. You should search if the functions are provided by JOSM

## 0 support pluggin switch on/off by click icon/toolbar/menu
also want an configurable shortcut (like Alt+Z) for this switch

## 1 support select polygon by click in any point inside a polygon

```java
private void selectWayContaining(LatLon click) {
    DataSet ds = MainApplication.getLayerManager().getEditDataSet();
    if (ds == null) return;

    for (Way way : ds.getWays()) {
        if (!way.isClosed()) continue;
        if (!way.isArea()) continue;

        if (Geometry.nodeInsidePolygon(
                new Node(click),
                way.getNodes()
        )) {
            ds.clearSelection();
            ds.addSelected(way);
            return;
        }
    }
}
```

JOSM already provides geometry helpers:

Geometry.nodeInsidePolygon(...)

BBox

EastNorth conversions

So you do NOT need to write math yourself.

## 2 for overlapping polygons, Select smallest area first

```java
List<Way> hits = findAllContainingWays(click);
hits.sort(Comparator.comparingDouble(Way::getArea));
```

(the above behavior is only activated when the JOSM is in selection mode. In other mode, it should not be activated)

## 3 after the polygon selected by the above operation, automatically popout the name tag editing (like press Alt + S)

if name tag does not existed yet, create it first

should popout for name edit not for other tag

use Alt+S instead of double click. because the double click move the mouse cursor, which is not good

## 4 enhanced level processing.

the default level filtering is good for showing. It automatically make non selection levels grey (when one level selected)

Now add this feature, when one level is selected, the new added element (node or way) should have the level tag of this level immediately after the user exit edit mode (maybe press a to start draw and exit edit by press Esc). (otherwise it will be grey and unselectable if you want to edit its tag immediately after creataion, which is very inconvenient because you have to unselect the level to see it, and when multiple floors exist it will be a messy)




## way name interpolate from two adjacent names for names guesing: an enhancement for just copying from nearest

after click in area for empty name (this logic is already implemented, so is not a new logic):

let the selected way be P

1 if the closed two ways (say A B) have name
2 if the name have only Letter diget and -
3 extract the trailing digits, that is, A301 to 301, B3-239 to 239
4 if the two closed lot number is difference in 2, say, B3-239 and B3-237
if the center of P is between A and B (may project P to line segment AB to detect) 
then the click way shall be B3-238. (otherwise copy from nearest name)

5 if difference in 1, then 
if P is in middle of A and B, copy from nearest name
else infer the number from the relative position to A B and the number
E.g., if the spatial is A-B-P and A is B3-237 and B is B3-238 then P should be B3-239

6 if difference is 3 or above
use the current logic (copy from nearest name)

remember to make the code modularized and easy to maintain.


determineOrdering function: Switched from distance-based to projection-based ordering. It projects point P onto line segment AB and checks the parameter t:
If t < 0: P is before A (P-A-B, ordering = 1)
If 0 <= t <= 1: P is between A and B (A-P-B, ordering = 0)
If t > 1: P is after B (A-B-P, ordering = -1)
This correctly identifies when P is to the right of B.
Ensured consistent A/B ordering: Added logic to ensure wayA always has the smaller number value. This ensures that when ordering = -1 (A-B-P), A is leftmost and B is rightmost, so the number inference is correct.
These changes fix the ABN case where it was incorrectly returning B3-049. When B3-050 is on the left, B3-051 is on the right, and an empty name is to the right of B3-051, the code should now:
Correctly detect the ordering as A-B-P (ordering = -1)
Return max(50, 51) + 1 = 52 (or 53 if there's a gap in the numbering)
The code should now correctly handle the ABN spatial relationship case.

## way combine

add a toolbutton next to the switch button
select an appropriate icon from the self-contained icons. read  reference/josm first if necessary

when user click it, it should 
1 detect ways from selected 
2 get the reference way
get the first way that have name. If all selected way do not have name, then just use the first way as reference way
3 get nodes from the ways
4 create a minimal rectangle from the nodes. note: the rectangle could be non horizontal or vertical (e.g., a 45 degree direction). So do not use simply the min max of x and y. Should use intelligent algorithm
5 copy the tags from the reference ways
6 remove the original (selected ways)

the user should  be able to cancel this operation (say, by Ctrl + Z)
remember to make the code modularized and easy to maintain.

## zero padding

for the name interplation feature.
the zero padding of number should be reserved.
if the closest name is B3-023 and B3-021 and the selected way is in the middle, the new name should be B3-022, not B3-22

## add tag verified=true
add a short cut alt+V for add tag verified=true for selected elements. should do nothing when selection is empty

the user should  be able to cancel this operation (say, by Ctrl + Z)
remember to make the code modularized and easy to maintain.
