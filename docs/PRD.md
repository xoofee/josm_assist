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


