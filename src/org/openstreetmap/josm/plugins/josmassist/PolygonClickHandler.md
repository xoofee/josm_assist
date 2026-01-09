selectWayContaining(LatLon click)
  ├─> findAllContainingWays(click, ds)
  │     └─> [No level filtering - finds ALL containing polygons]
  │     └─> Geometry.nodeInsidePolygon(clickNode, way.getNodes())
  │
  └─> [If selected way has no name]
      ├─> selectedWay.get("level")  [Get level from way itself]
      │
      ├─> [If wayLevel is null or empty]
      │     └─> System.err.println("ERROR: No level tag")
      │     └─> [Do nothing - just open tag editor]
      │
      └─> [If wayLevel is valid]
            ├─> Geometry.getCentroid(selectedWay.getNodes())
            │
            ├─> NameInterpolator.interpolateName(...)  [Path 1: Interpolation]
            │     └─> findAdjacentNamedWays(...)
            │           ├─> calculateOrientedBoundingBox(selectedWay)
            │           ├─> findAdjacentNamedWaysLateral(...) [Try lateral first]
            │           │     └─> findWaysInLateralArea(...) [SHARED]
            │           │           ├─> createLateralSearchBoundingBox(obb)
            │           │           ├─> matchesLevel(way, wayLevel) [Uses way's level]
            │           │           ├─> hasName(way)
            │           │           ├─> isPointInLateralArea(wayCentroidEN, obb)
            │           │           └─> calculateDistanceToWayStatic(node, way)
            │           │ 
            │           │ (for ---- ---- arrayed lateral parking space), it does not fit the findWaysInLateralArea
            │           └─> findAdjacentNamedWaysCircular(...) [Fallback]
            │                 ├─> createCircularBoundingBox(centerPoint, radiusMeters)
            │                 ├─> matchesLevel(way, wayLevel) [Uses way's level]
            │                 ├─> hasName(way)
            │                 └─> calculateDistanceToWayStatic(node, way)
            │
            └─> findNearestNamedWayInLevel(...)  [Path 2: Nearest neighbor - if interpolation fails]
                  ├─> calculateOrientedBoundingBox(excludeWay)
                  ├─> findNearestNamedWayInLevelLateral(...) [Try lateral first]
                  │     └─> findWaysInLateralArea(...) [SHARED - same as above]
                  │
                  └─> findNearestNamedWayInLevelCircular(...) [Fallback]
                        └─> createCircularBoundingBox(centerPoint, radiusMeters)
                              └─> calculateDistanceToWayStatic(node, way)