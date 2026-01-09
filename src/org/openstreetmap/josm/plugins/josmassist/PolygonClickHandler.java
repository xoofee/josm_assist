package org.openstreetmap.josm.plugins.josmassist;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.tools.Geometry;

/**
 * Handles polygon selection by clicking inside polygons.
 * Only active when JOSM is in selection mode.
 */
public class PolygonClickHandler {

    /**
     * Handles mouse click events for polygon selection.
     * Only responds to right mouse click.
     * @param e the mouse event
     * @return true if a polygon was selected
     */
    public boolean handleMouseClick(MouseEvent e) {
        // Only respond to right mouse click (BUTTON3)
        if (e.getButton() != java.awt.event.MouseEvent.BUTTON3) {
            return false;
        }
        
        if (!JosmAssistPlugin.getInstance().isEnabled()) {
            return false;
        }

        // Only activate in selection mode
        if (!isSelectionMode()) {
            return false;
        }

        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame == null) {
            return false;
        }

        MapView mapView = mapFrame.mapView;
        if (mapView == null) {
            return false;
        }

        LatLon click = mapView.getLatLon(e.getX(), e.getY());
        if (click == null) {
            return false;
        }

        return selectWayContaining(click);
    }

    /**
     * Checks if JOSM is currently in selection mode.
     * @return true if in selection mode
     */
    private boolean isSelectionMode() {
        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame == null || mapFrame.mapMode == null) {
            return false;
        }
        
        // Check if current map mode is selection mode by class name
        String className = mapFrame.mapMode.getClass().getSimpleName();
        
        return className.contains("Select") || className.contains("select");
    }

    /**
     * Selects a way containing the clicked point.
     * For overlapping polygons, selects the smallest area first.
     * @param click the clicked location
     * @return true if a way was selected
     */
    private boolean selectWayContaining(LatLon click) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) {
            return false;
        }

        List<Way> containingWays = findAllContainingWays(click, ds);
        
        if (containingWays.isEmpty()) {
            return false;
        }

        // Sort by area (smallest first)
        // Calculate area using Geometry.getArea() helper with way nodes
        // getArea returns an Area object, we need to get its bounds to calculate size
        containingWays.sort(Comparator.comparingDouble(way -> {
            try {
                java.awt.geom.Area area = org.openstreetmap.josm.tools.Geometry.getArea(way.getNodes());
                if (area != null) {
                    java.awt.geom.Rectangle2D bounds = area.getBounds2D();
                    return Math.abs(bounds.getWidth() * bounds.getHeight());
                }
                return Double.MAX_VALUE;
            } catch (Exception e) {
                return Double.MAX_VALUE; // Put invalid ways at the end
            }
        }));

        // Select the smallest polygon
        Way selectedWay = containingWays.get(0);
        ds.clearSelection();
        ds.addSelected(selectedWay);

        // Check current name - only copy if empty or null
        String currentName = selectedWay.get("name");
        boolean hasName = currentName != null && !currentName.isEmpty();
        String nameToPaste = null;

        System.out.println("[JOSM Assist] PolygonClickHandler: Selected way has name: " + hasName + " (name: '" + currentName + "')");

        // Get level from the selected way itself
        // Only do this if the selected way doesn't already have a name
        if (!hasName) {
            String wayLevel = selectedWay.get("level");
            
            // Check if the selected way has a valid level tag
            if (wayLevel == null || wayLevel.isEmpty()) {
                System.err.println("[JOSM Assist] ERROR: Selected way does not have a level tag or has an empty level. Cannot search for names. Please add a level tag to the way first.");
                // Do nothing - just return after opening the tag editor
            } else {
                System.out.println("[JOSM Assist] PolygonClickHandler: Using level from selected way: " + wayLevel);
                
                // Calculate center of selected polygon using JOSM's centroid calculation
                org.openstreetmap.josm.data.coor.EastNorth centroidEN = org.openstreetmap.josm.tools.Geometry.getCentroid(selectedWay.getNodes());
                LatLon polygonCenter = null;
                if (centroidEN != null) {
                    polygonCenter = org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection().eastNorth2latlon(centroidEN);
                    System.out.println("[JOSM Assist] PolygonClickHandler: Polygon center calculated: " + polygonCenter);
                } else {
                    System.out.println("[JOSM Assist] PolygonClickHandler: Could not calculate polygon center, using click point");
                    polygonCenter = click;
                }
                
                // Try to interpolate name from two adjacent ways first
                System.out.println("[JOSM Assist] PolygonClickHandler: Attempting name interpolation from adjacent ways...");
                nameToPaste = NameInterpolator.interpolateName(selectedWay, polygonCenter, wayLevel, ds, 50.0);
                
                // If interpolation didn't work, fall back to nearest way
                if (nameToPaste == null) {
                    System.out.println("[JOSM Assist] PolygonClickHandler: Interpolation failed, searching for nearest named way in level '" + wayLevel + "' within 50 meters...");
                    Way nearestNamedWay = findNearestNamedWayInLevel(polygonCenter, selectedWay, wayLevel, ds, 50.0);
                    if (nearestNamedWay != null) {
                        String name = nearestNamedWay.get("name");
                        if (name != null && !name.isEmpty()) {
                            nameToPaste = name; // Store name to paste, but don't modify the way yet
                            System.out.println("[JOSM Assist] PolygonClickHandler: Found nearest named way! Name to paste: '" + nameToPaste + "'");
                        } else {
                            System.out.println("[JOSM Assist] PolygonClickHandler: Found nearest way but it has no name");
                        }
                    } else {
                        System.out.println("[JOSM Assist] PolygonClickHandler: No named way found within 50 meters with level '" + wayLevel + "'");
                    }
                } else {
                    System.out.println("[JOSM Assist] PolygonClickHandler: Successfully interpolated name: '" + nameToPaste + "'");
                }
            }
        } else {
            System.out.println("[JOSM Assist] PolygonClickHandler: Selected way already has a name, skipping name search");
        }

        // Ensure name tag exists (create if missing)
        if (selectedWay.get("name") == null) {
            selectedWay.put("name", "");
        }

        // Auto-popup name tag editing (like Alt+S)
        // Use SwingUtilities to ensure it runs on the EDT
        final String nameToPasteFinal = nameToPaste;
        javax.swing.SwingUtilities.invokeLater(() -> {
            openTagEditor(selectedWay, nameToPasteFinal);
        });
        
        return true;
    }

    /**
     * Finds all ways (closed polygons) that contain the given point.
     * When a level is selected, only returns ways matching that level.
     * @param click the point to check
     * @param ds the dataset to search
     * @return list of containing ways
     */
    private List<Way> findAllContainingWays(LatLon click, DataSet ds) {
        List<Way> hits = new ArrayList<>();
        Node clickNode = new Node(click);

        for (Way way : ds.getWays()) {
            if (!way.isClosed()) {
                continue;
            }
            if (!way.isArea()) {
                continue;
            }

            if (Geometry.nodeInsidePolygon(clickNode, way.getNodes())) {
                hits.add(way);
            }
        }

        return hits;
    }

    /**
     * Opens the tag editor for the selected way (simulates Alt+S).
     * Focuses on the "name" tag field.
     * @param way the way to edit
     * @param nameToPaste the name to paste into the editor (if any)
     */
    private void openTagEditor(Way way, String nameToPaste) {
        System.out.println("[JOSM Assist] PolygonClickHandler: openTagEditor called with nameToPaste: '" + nameToPaste + "'");
        try {
            MapFrame mapFrame = MainApplication.getMap();
            if (mapFrame == null) {
                System.out.println("[JOSM Assist] PolygonClickHandler: MapFrame is null");
                return;
            }

            // Open properties dialog via reflection
            java.lang.reflect.Field propDialogField = mapFrame.getClass().getDeclaredField("propertiesDialog");
            propDialogField.setAccessible(true);
            Object propDialog = propDialogField.get(mapFrame);
            
            if (propDialog != null) {
                System.out.println("[JOSM Assist] PolygonClickHandler: Properties dialog found, opening...");
                java.lang.reflect.Method setVisibleMethod = propDialog.getClass().getMethod("setVisible", boolean.class);
                setVisibleMethod.invoke(propDialog, true);
                
                // Bring dialog to front
                if (propDialog instanceof java.awt.Window) {
                    ((java.awt.Window) propDialog).toFront();
                    ((java.awt.Window) propDialog).requestFocus();
                }
                
                // Focus on name field and paste name if provided
                focusOnNameField(propDialog, nameToPaste);
            } else {
                System.out.println("[JOSM Assist] PolygonClickHandler: Properties dialog is null");
            }
        } catch (Exception ex) {
            System.out.println("[JOSM Assist] PolygonClickHandler: Exception in openTagEditor: " + ex.getMessage());
            ex.printStackTrace();
            // If dialog opening fails, at least ensure selection is visible
            try {
                MainApplication.getMap().repaint();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    /**
     * Finds the nearest way within the specified radius that has the same level and a name.
     * First tries lateral search (7x width, 1x height area), then falls back to circular radius search.
     * Uses spatial indexing via DataSet.searchWays(BBox) for efficient search.
     * @param centerPoint the center point (polygon centroid)
     * @param excludeWay the way to exclude from search (the selected way)
     * @param level the level to match
     * @param ds the dataset to search
     * @param radiusMeters the search radius in meters
     * @return the nearest way with a name, or null if none found
     */
    private Way findNearestNamedWayInLevel(LatLon centerPoint, Way excludeWay, String level, DataSet ds, double radiusMeters) {
        org.openstreetmap.josm.data.projection.Projection proj = 
            org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
        org.openstreetmap.josm.data.coor.EastNorth centerEN = proj.latlon2eastNorth(centerPoint);
        Node centerNode = new Node(centerPoint);
        
        // Try lateral search first (for parking spaces)
        OrientedBoundingBox obb = PolygonClickHandler.calculateOrientedBoundingBox(excludeWay);
        if (obb != null) {
            System.out.println("[JOSM Assist] PolygonClickHandler: Attempting lateral search first (7x width, 1x length)in findNearestNamedWayInLevel call findNearestNamedWayInLevelLateral");
            Way lateralResult = findNearestNamedWayInLevelLateral(centerPoint, centerEN, excludeWay, level, ds, radiusMeters, obb);
            if (lateralResult != null) {
                System.out.println("[JOSM Assist] PolygonClickHandler: Found way in lateral area");
                return lateralResult;
            }
            System.out.println("[JOSM Assist] PolygonClickHandler: No way found in lateral area, falling back to circular search");
        }
        
        // Fallback to original circular radius search
        return findNearestNamedWayInLevelCircular(centerPoint, centerNode, excludeWay, level, ds, radiusMeters);
    }
    
    /**
     * Lateral search: finds nearest way within lateral area (7x width, 1x length).
     * Only searches ways in the specified level (if level is provided).
     */
    private Way findNearestNamedWayInLevelLateral(LatLon centerPoint, org.openstreetmap.josm.data.coor.EastNorth centerEN,
            Way excludeWay, String level, DataSet ds, double radiusMeters, OrientedBoundingBox obb) {
        // DEBUG: Create debug polygon for lateral search area
        // createDebugLateralSearchPolygon(obb, level, ds);
        
        List<WayWithDistance> ways = findWaysInLateralArea(excludeWay, centerPoint, level, ds, radiusMeters, obb);
        return ways.isEmpty() ? null : ways.get(0).way;
    }
    
    /**
     * Circular search: original method using circular radius.
     */
    private Way findNearestNamedWayInLevelCircular(LatLon centerPoint, Node centerNode,
            Way excludeWay, String level, DataSet ds, double radiusMeters) {
        // Create circular bounding box
        BBox bbox = createCircularBoundingBox(centerPoint, radiusMeters);
        
        System.out.println("[JOSM Assist] PolygonClickHandler: Circular search in bounding box around center: " + centerPoint);

        // Use spatial indexing to search only ways within the bounding box
        List<Way> candidateWays = ds.searchWays(bbox);
        System.out.println("[JOSM Assist] PolygonClickHandler: Found " + candidateWays.size() + " ways in bounding box (out of " + ds.getWays().size() + " total)");

        Way nearestWay = null;
        double minDistance = Double.MAX_VALUE;

        for (Way way : candidateWays) {
            // Skip the selected way itself
            if (way.equals(excludeWay)) {
                continue;
            }

            // Filter by level (if level is specified)
            if (!matchesLevel(way, level)) {
                continue;
            }

            // Filter by name
            if (!hasName(way)) {
                continue;
            }
            
            String wayName = way.get("name");

            // Calculate distance from center point to way
            double distance = calculateDistanceToWayStatic(centerNode, way);
            
            System.out.println("[JOSM Assist] PolygonClickHandler: Found way with level '" + level + "' and name '" + wayName + "' at distance " + distance + " meters");
            
            // Check if within radius and is the nearest so far
            if (!Double.isNaN(distance) && distance <= radiusMeters && distance < minDistance) {
                minDistance = distance;
                nearestWay = way;
                System.out.println("[JOSM Assist] PolygonClickHandler: This is the nearest so far (distance: " + distance + " meters)");
            }
        }
        
        if (nearestWay != null) {
            System.out.println("[JOSM Assist] PolygonClickHandler: Selected nearest way with name '" + nearestWay.get("name") + 
                "' at distance " + minDistance + " meters");
        } else {
            System.out.println("[JOSM Assist] PolygonClickHandler: No way found matching criteria");
        }

        return nearestWay;
    }

    
    /**
     * Helper class to store oriented bounding box information.
     * For parking spaces: width = shorter (lateral, ~9 ft), length = longer (depth, ~18-20 ft).
     */
    private static class OrientedBoundingBox {
        final double width;      // Shorter dimension (lateral, parallel to driving aisle)
        final double length;     // Longer dimension (depth, perpendicular to aisle)
        final org.openstreetmap.josm.data.coor.EastNorth center;  // Center point
        final org.openstreetmap.josm.data.coor.EastNorth widthDir; // Unit vector along width direction (lateral)
        final org.openstreetmap.josm.data.coor.EastNorth lengthDir; // Unit vector along length direction (depth)
        
        OrientedBoundingBox(double width, double length,
                org.openstreetmap.josm.data.coor.EastNorth center,
                org.openstreetmap.josm.data.coor.EastNorth widthDir,
                org.openstreetmap.josm.data.coor.EastNorth lengthDir) {
            this.width = width;
            this.length = length;
            this.center = center;
            this.widthDir = widthDir;
            this.lengthDir = lengthDir;
        }
    }
    
    /**
     * Calculates the oriented bounding box of a parking space (rectangle).
     * Returns width (shorter dimension, lateral) and length (longer dimension, depth).
     * @param way the polygon way
     * @return oriented bounding box info, or null if calculation fails
     */
    private static OrientedBoundingBox calculateOrientedBoundingBox(Way way) {
        if (way == null || way.getNodes() == null || way.getNodes().size() < 3) {
            return null;
        }
        
        org.openstreetmap.josm.data.projection.Projection proj = 
            org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
        
        // Convert nodes to EastNorth coordinates
        List<org.openstreetmap.josm.data.coor.EastNorth> points = new ArrayList<>();
        for (Node node : way.getNodes()) {
            if (node.getCoor() != null) {
                org.openstreetmap.josm.data.coor.EastNorth en = proj.latlon2eastNorth(node.getCoor());
                if (en != null) {
                    points.add(en);
                }
            }
        }
        
        if (points.size() < 3) {
            return null;
        }
        
        // Calculate centroid
        org.openstreetmap.josm.data.coor.EastNorth centroid = 
            org.openstreetmap.josm.tools.Geometry.getCentroid(way.getNodes());
        if (centroid == null) {
            return null;
        }
        
        // For a rectangle (parking space), compare the first two edges
        // Width = shorter edge (lateral, ~9 ft, parallel to driving aisle)
        // Length = longer edge (depth, ~18-20 ft, perpendicular to aisle)
        if (points.size() < 4) {
            return null; // Need at least 4 points for a rectangle
        }
        
        // First edge: from point 0 to point 1
        org.openstreetmap.josm.data.coor.EastNorth edge1Start = points.get(0);
        org.openstreetmap.josm.data.coor.EastNorth edge1End = points.get(1);
        double edge1LenSq = edge1Start.distanceSq(edge1End);
        double edge1Len = Math.sqrt(edge1LenSq);
        
        // Second edge: from point 1 to point 2
        org.openstreetmap.josm.data.coor.EastNorth edge2Start = points.get(1);
        org.openstreetmap.josm.data.coor.EastNorth edge2End = points.get(2);
        double edge2LenSq = edge2Start.distanceSq(edge2End);
        double edge2Len = Math.sqrt(edge2LenSq);
        
        if (edge1Len < 1e-10 || edge2Len < 1e-10) {
            return null; // Invalid edges
        }
        
        // Determine which edge is shorter (width) and which is longer (length)
        double width, length;
        org.openstreetmap.josm.data.coor.EastNorth widthDir, lengthDir;
        
        if (edge1Len <= edge2Len) {
            // First edge is shorter (width - lateral)
            width = edge1Len;
            length = edge2Len;
            // Width direction: from edge1Start to edge1End (lateral direction)
            widthDir = new org.openstreetmap.josm.data.coor.EastNorth(
                (edge1End.east() - edge1Start.east()) / edge1Len,
                (edge1End.north() - edge1Start.north()) / edge1Len
            );
            // Length direction: from edge2Start to edge2End (depth direction)
            lengthDir = new org.openstreetmap.josm.data.coor.EastNorth(
                (edge2End.east() - edge2Start.east()) / edge2Len,
                (edge2End.north() - edge2Start.north()) / edge2Len
            );
        } else {
            // Second edge is shorter (width - lateral)
            width = edge2Len;
            length = edge1Len;
            // Width direction: from edge2Start to edge2End (lateral direction)
            widthDir = new org.openstreetmap.josm.data.coor.EastNorth(
                (edge2End.east() - edge2Start.east()) / edge2Len,
                (edge2End.north() - edge2Start.north()) / edge2Len
            );
            // Length direction: from edge1Start to edge1End (depth direction)
            lengthDir = new org.openstreetmap.josm.data.coor.EastNorth(
                (edge1End.east() - edge1Start.east()) / edge1Len,
                (edge1End.north() - edge1Start.north()) / edge1Len
            );
        }
        
        return new OrientedBoundingBox(width, length, centroid, widthDir, lengthDir);
    }
    
    /**
     * Checks if a point (way centroid) is within the lateral search area.
     * Lateral area is 7x width and 1x length of the selected polygon, centered on it.
     * @param point the point to check (in EastNorth coordinates)
     * @param obb the oriented bounding box of the selected polygon
     * @return true if the point is within the lateral search area
     */
    private static boolean isPointInLateralArea(org.openstreetmap.josm.data.coor.EastNorth point, OrientedBoundingBox obb) {
        if (obb == null || point == null) {
            return false;
        }
        
        // Calculate vector from obb center to point
        double dx = point.east() - obb.center.east();
        double dy = point.north() - obb.center.north();
        
        // Project onto width and length directions
        double projWidth = dx * obb.widthDir.east() + dy * obb.widthDir.north();
        double projLength = dx * obb.lengthDir.east() + dy * obb.lengthDir.north();
        
        // Check if within lateral area: 7x width (lateral), 1x length (depth)
        double lateralWidth = obb.width * 3.5;  // Half of 7x width
        double lateralLength = obb.length * 0.5;  // Half of 1x length
        
        return Math.abs(projWidth) <= lateralWidth && Math.abs(projLength) <= lateralLength;
    }
    
    /**
     * Creates a bounding box that encompasses the lateral search area.
     * Lateral area is 7x width and 1x length of the selected polygon.
     * @param obb the oriented bounding box of the selected polygon
     * @return a BBox that encompasses the lateral search area, or null if calculation fails
     */
    private static BBox createLateralSearchBoundingBox(OrientedBoundingBox obb) {
        if (obb == null) {
            return null;
        }
        
        // Lateral area extends 3.5x width in each direction along width axis (lateral), 0.5x length in each direction along length axis (depth)
        double lateralWidthHalf = obb.width * 3.5;
        double lateralLengthHalf = obb.length * 0.5;
        
        // Calculate corners of lateral area bounding box
        org.openstreetmap.josm.data.coor.EastNorth corner1 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() - lateralWidthHalf * obb.widthDir.east() - lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() - lateralWidthHalf * obb.widthDir.north() - lateralLengthHalf * obb.lengthDir.north()
        );
        org.openstreetmap.josm.data.coor.EastNorth corner2 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() + lateralWidthHalf * obb.widthDir.east() - lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() + lateralWidthHalf * obb.widthDir.north() - lateralLengthHalf * obb.lengthDir.north()
        );
        org.openstreetmap.josm.data.coor.EastNorth corner3 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() + lateralWidthHalf * obb.widthDir.east() + lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() + lateralWidthHalf * obb.widthDir.north() + lateralLengthHalf * obb.lengthDir.north()
        );
        org.openstreetmap.josm.data.coor.EastNorth corner4 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() - lateralWidthHalf * obb.widthDir.east() + lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() - lateralWidthHalf * obb.widthDir.north() + lateralLengthHalf * obb.lengthDir.north()
        );
        
        // Create bounding box from corners
        org.openstreetmap.josm.data.projection.Projection proj = 
            org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
        LatLon ll1 = proj.eastNorth2latlon(corner1);
        LatLon ll2 = proj.eastNorth2latlon(corner2);
        LatLon ll3 = proj.eastNorth2latlon(corner3);
        LatLon ll4 = proj.eastNorth2latlon(corner4);
        
        Way tempWay = new Way();
        tempWay.addNode(new Node(ll1));
        tempWay.addNode(new Node(ll2));
        tempWay.addNode(new Node(ll3));
        tempWay.addNode(new Node(ll4));
        
        return new BBox(tempWay);
    }
    
    /**
     * Checks if a way matches the level filter.
     * @param way the way to check
     * @param level the level to match (null means no level filtering)
     * @return true if the way matches the level (or level is null)
     */
    private static boolean matchesLevel(Way way, String level) {
        if (level == null || level.isEmpty()) {
            return true; // No level filtering
        }
        String wayLevel = way.get("level");
        return wayLevel != null && wayLevel.equals(level);
    }
    
    /**
     * Checks if a way has a name.
     * @param way the way to check
     * @return true if the way has a non-empty name
     */
    private static boolean hasName(Way way) {
        String wayName = way.get("name");
        return wayName != null && !wayName.isEmpty();
    }
    
    /**
     * Helper class to store way with distance for search results.
     */
    private static class WayWithDistance {
        final Way way;
        final double distance;
        
        WayWithDistance(Way way, double distance) {
            this.way = way;
            this.distance = distance;
        }
    }
    
    /**
     * Shared lateral search: finds all ways within lateral area (7x width, 1x length).
     * Returns a list of ways with their distances, sorted by distance.
     * Only searches ways in the specified level (if level is provided).
     */
    private static List<WayWithDistance> findWaysInLateralArea(Way excludeWay, LatLon centerPoint, String level,
            DataSet ds, double radiusMeters, OrientedBoundingBox obb) {
        List<WayWithDistance> result = new ArrayList<>();
        Node centerNode = new Node(centerPoint);
        
        // Create bounding box for lateral search area
        BBox bbox = createLateralSearchBoundingBox(obb);
        if (bbox == null) {
            return result;
        }
        
        List<Way> candidateWays = ds.searchWays(bbox);
        System.out.println("[JOSM Assist] PolygonClickHandler: Lateral search found " + candidateWays.size() + " candidate ways");
        
        for (Way way : candidateWays) {
            if (way.equals(excludeWay)) continue;
            
            // Filter by level (if level is specified)
            if (!matchesLevel(way, level)) continue;
            
            // Filter by name
            if (!hasName(way)) continue;
            
            // Check if way's centroid is within lateral area
            org.openstreetmap.josm.data.coor.EastNorth wayCentroidEN = 
                org.openstreetmap.josm.tools.Geometry.getCentroid(way.getNodes());
            if (wayCentroidEN == null) continue;
            
            if (!isPointInLateralArea(wayCentroidEN, obb)) {
                continue; // Skip if centroid is not in lateral area
            }
            
            // Calculate distance using static method
            double distance = calculateDistanceToWayStatic(centerNode, way);
            if (!Double.isNaN(distance) && distance <= radiusMeters) {
                result.add(new WayWithDistance(way, distance));
            }
        }
        
        // Sort by distance
        result.sort(Comparator.comparingDouble(w -> w.distance));
        
        return result;
    }
    
    /**
     * Creates a circular bounding box around a center point with specified radius.
     */
    private static BBox createCircularBoundingBox(LatLon centerPoint, double radiusMeters) {
        org.openstreetmap.josm.data.projection.Projection proj = 
            org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
        org.openstreetmap.josm.data.coor.EastNorth centerEN = proj.latlon2eastNorth(centerPoint);
        
        // Convert radius from meters to projection units
        double radiusInProjectionUnits = radiusMeters / proj.getMetersPerUnit();
        
        // Create bounding box in EastNorth coordinates
        org.openstreetmap.josm.data.coor.EastNorth minEN = new org.openstreetmap.josm.data.coor.EastNorth(
            centerEN.east() - radiusInProjectionUnits,
            centerEN.north() - radiusInProjectionUnits
        );
        org.openstreetmap.josm.data.coor.EastNorth maxEN = new org.openstreetmap.josm.data.coor.EastNorth(
            centerEN.east() + radiusInProjectionUnits,
            centerEN.north() + radiusInProjectionUnits
        );
        
        // Convert back to LatLon for BBox
        LatLon minLL = proj.eastNorth2latlon(minEN);
        LatLon maxLL = proj.eastNorth2latlon(maxEN);
        
        // Create BBox from LatLon bounds
        Node minNode = new Node(minLL);
        Node maxNode = new Node(maxLL);
        Node centerNode = new Node(centerPoint);
        Way tempWay = new Way();
        tempWay.addNode(minNode);
        tempWay.addNode(maxNode);
        tempWay.addNode(centerNode);
        
        return new BBox(tempWay);
    }
    
    /**
     * Calculates the minimum distance from a node to a way in meters.
     * @param node the node (point) to measure from
     * @param way the way to measure to
     * @return the distance in meters, or NaN if calculation fails
     */
    private static double calculateDistanceToWayStatic(Node node, Way way) {
        if (way == null || way.getNodes() == null || way.getNodes().isEmpty()) {
            return Double.NaN;
        }
        
        try {
            // Use Geometry.getDistance which returns distance in meters
            double distance = Geometry.getDistance(node, way);
            if (!Double.isNaN(distance) && distance != Double.MAX_VALUE) {
                return distance;
            }
        } catch (Exception e) {
            // Fallback: calculate distance to nearest node of the way
        }
        
        // Fallback: find minimum distance to any node in the way
        double minDist = Double.MAX_VALUE;
        for (Node wayNode : way.getNodes()) {
            if (wayNode != null && wayNode.getCoor() != null) {
                double dist = node.getCoor().greatCircleDistance(wayNode.getCoor());
                if (!Double.isNaN(dist) && dist < minDist) {
                    minDist = dist;
                }
            }
        }
        
        return (minDist == Double.MAX_VALUE) ? Double.NaN : minDist;
    }
    
    /**
     * Creates a debug polygon for the lateral search area.
     * This is for debugging purposes only - can be removed/commented out later.
     * @param obb the oriented bounding box
     * @param level the level tag to add (if not null)
     * @param ds the dataset to add the polygon to
     */
    private static void createDebugLateralSearchPolygon(OrientedBoundingBox obb, String level, DataSet ds) {
        if (obb == null || ds == null) {
            return;
        }
        
        // Calculate corners of lateral area
        double lateralWidthHalf = obb.width * 3.5;
        double lateralLengthHalf = obb.length * 0.5;
        
        org.openstreetmap.josm.data.coor.EastNorth corner1 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() - lateralWidthHalf * obb.widthDir.east() - lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() - lateralWidthHalf * obb.widthDir.north() - lateralLengthHalf * obb.lengthDir.north()
        );
        org.openstreetmap.josm.data.coor.EastNorth corner2 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() + lateralWidthHalf * obb.widthDir.east() - lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() + lateralWidthHalf * obb.widthDir.north() - lateralLengthHalf * obb.lengthDir.north()
        );
        org.openstreetmap.josm.data.coor.EastNorth corner3 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() + lateralWidthHalf * obb.widthDir.east() + lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() + lateralWidthHalf * obb.widthDir.north() + lateralLengthHalf * obb.lengthDir.north()
        );
        org.openstreetmap.josm.data.coor.EastNorth corner4 = new org.openstreetmap.josm.data.coor.EastNorth(
            obb.center.east() - lateralWidthHalf * obb.widthDir.east() + lateralLengthHalf * obb.lengthDir.east(),
            obb.center.north() - lateralWidthHalf * obb.widthDir.north() + lateralLengthHalf * obb.lengthDir.north()
        );
        
        // Convert to LatLon
        org.openstreetmap.josm.data.projection.Projection proj = 
            org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
        LatLon ll1 = proj.eastNorth2latlon(corner1);
        LatLon ll2 = proj.eastNorth2latlon(corner2);
        LatLon ll3 = proj.eastNorth2latlon(corner3);
        LatLon ll4 = proj.eastNorth2latlon(corner4);
        
        // Create nodes
        Node node1 = new Node(ll1);
        Node node2 = new Node(ll2);
        Node node3 = new Node(ll3);
        Node node4 = new Node(ll4);
        
        // Add nodes to dataset
        ds.addPrimitive(node1);
        ds.addPrimitive(node2);
        ds.addPrimitive(node3);
        ds.addPrimitive(node4);
        
        // Create closed way (polygon)
        Way debugPolygon = new Way();
        debugPolygon.addNode(node1);
        debugPolygon.addNode(node2);
        debugPolygon.addNode(node3);
        debugPolygon.addNode(node4);
        debugPolygon.addNode(node1); // Close the polygon
        
        // Add debug tags
        debugPolygon.put("josm_assist_debug", "lateral_search_area");
        debugPolygon.put("note", "DEBUG: Lateral search area (7x width, 1x length)");
        
        // Add level tag if level is selected
        if (level != null && !level.isEmpty()) {
            debugPolygon.put("level", level);
        }
        
        // Add to dataset
        ds.addPrimitive(debugPolygon);
        
        System.out.println("[JOSM Assist] PolygonClickHandler: Created debug lateral search polygon with level: " + level);
    }

    /**
     * Focuses on the "name" tag field in the properties dialog and optionally pastes text.
     * @param propDialog the properties dialog object
     * @param nameToPaste the name to paste into the editor (if any)
     */
    private void focusOnNameField(Object propDialog, String nameToPaste) {
        System.out.println("[JOSM Assist] PolygonClickHandler: focusOnNameField called with nameToPaste: '" + nameToPaste + "'");
        try {
            // Find the tag table and select the name row
            java.lang.reflect.Field tagTableField = propDialog.getClass().getDeclaredField("tagTable");
            tagTableField.setAccessible(true);
            Object tagTable = tagTableField.get(propDialog);
            
            if (tagTable instanceof javax.swing.JTable) {
                javax.swing.JTable table = (javax.swing.JTable) tagTable;
                int rowCount = table.getRowCount();
                System.out.println("[JOSM Assist] PolygonClickHandler: Tag table found with " + rowCount + " rows");
                
                // Find the "name" row
                for (int i = 0; i < rowCount; i++) {
                    Object keyValue = table.getValueAt(i, 0);
                    if (keyValue != null && "name".equalsIgnoreCase(keyValue.toString())) {
                        System.out.println("[JOSM Assist] PolygonClickHandler: Found 'name' row at index " + i);
                        // Select the row and value column
                        table.setRowSelectionInterval(i, i);
                        table.setColumnSelectionInterval(1, 1);
                        table.scrollRectToVisible(table.getCellRect(i, 1, true));
                        table.requestFocus();
                        
                        // Start editing with Alt+S keyboard shortcut, then paste if name provided
                        final String nameToPasteFinal = nameToPaste;
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            try {
                                System.out.println("[JOSM Assist] PolygonClickHandler: Starting paste sequence, nameToPaste: '" + nameToPasteFinal + "'");
                                Thread.sleep(100); // Small delay to ensure selection is processed
                                java.awt.Robot robot = new java.awt.Robot();
                                robot.setAutoDelay(10);
                                
                                // Start editing with Alt+S
                                System.out.println("[JOSM Assist] PolygonClickHandler: Pressing Alt+S to start editing...");
                                robot.keyPress(java.awt.event.KeyEvent.VK_ALT);
                                Thread.sleep(10);
                                robot.keyPress(java.awt.event.KeyEvent.VK_S);
                                Thread.sleep(10);
                                robot.keyRelease(java.awt.event.KeyEvent.VK_S);
                                Thread.sleep(10);
                                robot.keyRelease(java.awt.event.KeyEvent.VK_ALT);
                                
                                // If we have a name to paste, wait a bit for editor to open then paste
                                if (nameToPasteFinal != null && !nameToPasteFinal.isEmpty()) {
                                    System.out.println("[JOSM Assist] PolygonClickHandler: Preparing to paste text: '" + nameToPasteFinal + "'");
                                    Thread.sleep(150); // Wait for editor to be ready
                                    
                                    // Press Tab to move to value column if we're in key column
                                    System.out.println("[JOSM Assist] PolygonClickHandler: Pressing Tab to move to value column...");
                                    robot.keyPress(java.awt.event.KeyEvent.VK_TAB);
                                    Thread.sleep(10);
                                    robot.keyRelease(java.awt.event.KeyEvent.VK_TAB);
                                    Thread.sleep(50);
                                    
                                    // Select all existing text (Ctrl+A) then paste
                                    System.out.println("[JOSM Assist] PolygonClickHandler: Selecting all text (Ctrl+A)...");
                                    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                                    Thread.sleep(10);
                                    robot.keyPress(java.awt.event.KeyEvent.VK_A);
                                    Thread.sleep(10);
                                    robot.keyRelease(java.awt.event.KeyEvent.VK_A);
                                    Thread.sleep(10);
                                    robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                                    Thread.sleep(50);
                                    
                                    // Copy name to clipboard and paste
                                    System.out.println("[JOSM Assist] PolygonClickHandler: Copying to clipboard: '" + nameToPasteFinal + "'");
                                    java.awt.datatransfer.StringSelection stringSelection = 
                                        new java.awt.datatransfer.StringSelection(nameToPasteFinal);
                                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                                        .setContents(stringSelection, null);
                                    
                                    Thread.sleep(50);
                                    
                                    // Paste (Ctrl+V)
                                    System.out.println("[JOSM Assist] PolygonClickHandler: Pasting (Ctrl+V)...");
                                    robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
                                    Thread.sleep(10);
                                    robot.keyPress(java.awt.event.KeyEvent.VK_V);
                                    Thread.sleep(10);
                                    robot.keyRelease(java.awt.event.KeyEvent.VK_V);
                                    Thread.sleep(10);
                                    robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
                                    System.out.println("[JOSM Assist] PolygonClickHandler: Paste sequence completed");
                                } else {
                                    System.out.println("[JOSM Assist] PolygonClickHandler: No name to paste, skipping paste operation");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                System.out.println("[JOSM Assist] PolygonClickHandler: Paste sequence interrupted");
                            } catch (Exception e) {
                                System.out.println("[JOSM Assist] PolygonClickHandler: Exception during paste sequence: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                        return;
                    }
                }
                System.out.println("[JOSM Assist] PolygonClickHandler: 'name' row not found in tag table");
            } else {
                System.out.println("[JOSM Assist] PolygonClickHandler: Tag table is not a JTable");
            }
        } catch (Exception e) {
            System.out.println("[JOSM Assist] PolygonClickHandler: Exception in focusOnNameField: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Helper class for interpolating names from adjacent ways.
     * Handles pattern matching, digit extraction, and spatial interpolation.
     */
    private static class NameInterpolator {
        
        /**
         * Attempts to interpolate a name for the selected way from two adjacent ways.
         * @param selectedWay the way to name (P)
         * @param centerPoint the center point of the selected way
         * @param level the level to match
         * @param ds the dataset
         * @param radiusMeters search radius
         * @return interpolated name, or null if interpolation is not possible
         */
        static String interpolateName(Way selectedWay, LatLon centerPoint, String level, DataSet ds, double radiusMeters) {
            // Find two adjacent ways with names
            List<AdjacentWay> adjacentWays = findAdjacentNamedWays(selectedWay, centerPoint, level, ds, radiusMeters);
            
            if (adjacentWays.size() < 2) {
                System.out.println("[JOSM Assist] NameInterpolator: Found " + adjacentWays.size() + " adjacent ways, need 2 for interpolation");
                return null;
            }
            
            // Get the two closest ways
            AdjacentWay wayA = adjacentWays.get(0);
            AdjacentWay wayB = adjacentWays.get(1);
            
            System.out.println("[JOSM Assist] NameInterpolator: Found two adjacent ways: A='" + wayA.name + "' at " + wayA.distance + "m, B='" + wayB.name + "' at " + wayB.distance + "m");
            
            // Check if names match pattern (letters, digits, and -)
            if (!isValidNamePattern(wayA.name) || !isValidNamePattern(wayB.name)) {
                System.out.println("[JOSM Assist] NameInterpolator: Names don't match pattern (letters, digits, -)");
                return null;
            }
            
            // Extract trailing digits
            NameParts partsA = extractNameParts(wayA.name);
            NameParts partsB = extractNameParts(wayB.name);
            
            if (partsA == null || partsB == null) {
                System.out.println("[JOSM Assist] NameInterpolator: Could not extract digits from names");
                return null;
            }
            
            // Check if prefix matches (for interpolation to make sense)
            if (!partsA.prefix.equals(partsB.prefix)) {
                System.out.println("[JOSM Assist] NameInterpolator: Name prefixes don't match: '" + partsA.prefix + "' vs '" + partsB.prefix + "'");
                return null;
            }
            
            int diff = Math.abs(partsA.number - partsB.number);
            System.out.println("[JOSM Assist] NameInterpolator: Name numbers: A=" + partsA.number + ", B=" + partsB.number + ", diff=" + diff);
            
            // Ensure A has the smaller number for consistent ordering
            // This ensures that when ordering = -1 (A-B-P), A is leftmost and B is rightmost
            if (partsA.number > partsB.number) {
                // Swap A and B to ensure A has smaller number
                AdjacentWay tempWay = wayA;
                wayA = wayB;
                wayB = tempWay;
                NameParts tempParts = partsA;
                partsA = partsB;
                partsB = tempParts;
                System.out.println("[JOSM Assist] NameInterpolator: Swapped A and B to ensure A has smaller number");
            }
            
            // Calculate spatial relationships
            SpatialRelationship spatial = calculateSpatialRelationship(selectedWay, centerPoint, wayA.way, wayB.way);
            System.out.println("[JOSM Assist] NameInterpolator: Spatial relationship - isBetween=" + spatial.isBetween + 
                ", ordering=" + spatial.ordering + " (ordering: -1=P-A-B, 0=A-P-B, 1=A-B-P)");
            
            
            if (diff == 2) {
                // If difference is 2, check if P is between A and B
                if (spatial.isBetween) {
                    int interpolatedNumber = (partsA.number + partsB.number) / 2;
                    // Use the maximum padding width to preserve zero padding
                    int paddingWidth = Math.max(partsA.paddingWidth, partsB.paddingWidth);
                    String formattedNumber = String.format("%0" + paddingWidth + "d", interpolatedNumber);
                    String interpolatedName = partsA.prefix + formattedNumber;
                    System.out.println("[JOSM Assist] NameInterpolator: Difference=2, P is between A and B, interpolated: '" + interpolatedName + "'");
                    return interpolatedName;
                } else {
                    System.out.println("[JOSM Assist] NameInterpolator: Difference=2 but P is not between A and B, using nearest");
                    return null; // Fall back to nearest
                }
            } else if (diff == 1) {
                // If difference is 1, infer from relative position
                int interpolatedNumber = inferNumberFromPosition(partsA, partsB, spatial);
                if (interpolatedNumber > 0) {
                    // Use the maximum padding width to preserve zero padding
                    int paddingWidth = Math.max(partsA.paddingWidth, partsB.paddingWidth);
                    String formattedNumber = String.format("%0" + paddingWidth + "d", interpolatedNumber);
                    String interpolatedName = partsA.prefix + formattedNumber;
                    System.out.println("[JOSM Assist] NameInterpolator: Difference=1, inferred from position: '" + interpolatedName + "'");
                    return interpolatedName;
                }
            } else if (diff >= 3) {
                System.out.println("[JOSM Assist] NameInterpolator: Difference=" + diff + " (>=3), using nearest");
                return null; // Fall back to nearest
            }
            
            return null;
        }
        
        /**
         * Finds adjacent ways with names within the search radius.
         * First tries lateral search (7x width, 1x length area), then falls back to circular radius search.
         */
        private static List<AdjacentWay> findAdjacentNamedWays(Way selectedWay, LatLon centerPoint, String level, DataSet ds, double radiusMeters) {
            // Try lateral search first (for parking spaces)
            OrientedBoundingBox obb = PolygonClickHandler.calculateOrientedBoundingBox(selectedWay);
            if (obb != null) {
                System.out.println("[JOSM Assist] NameInterpolator: Attempting lateral search first (7x width, 1x length) in findAdjacentNamedWays, call findAdjacentNamedWaysLateral");
                List<AdjacentWay> lateralResult = findAdjacentNamedWaysLateral(selectedWay, centerPoint, level, ds, radiusMeters, obb);
                if (lateralResult.size() >= 2) {
                    System.out.println("[JOSM Assist] NameInterpolator: Found " + lateralResult.size() + " ways in lateral area");
                    return lateralResult;
                }
                System.out.println("[JOSM Assist] NameInterpolator: Found " + lateralResult.size() + " ways in lateral area, falling back to circular search");
            }
            
            // Fallback to original circular radius search
            return findAdjacentNamedWaysCircular(selectedWay, centerPoint, level, ds, radiusMeters);
        }
        
        /**
         * Lateral search: finds adjacent ways within lateral area (7x width, 1x length).
         * Only searches ways in the specified level (if level is provided).
         */
        private static List<AdjacentWay> findAdjacentNamedWaysLateral(Way selectedWay, LatLon centerPoint, String level,
                DataSet ds, double radiusMeters, OrientedBoundingBox obb) {
            // DEBUG: Create debug polygon for lateral search area
            // PolygonClickHandler.createDebugLateralSearchPolygon(obb, level, ds);
            
            List<WayWithDistance> ways = PolygonClickHandler.findWaysInLateralArea(selectedWay, centerPoint, level, ds, radiusMeters, obb);
            
            // Convert to AdjacentWay list
            List<AdjacentWay> adjacentWays = new ArrayList<>();
            for (WayWithDistance wwd : ways) {
                String wayName = wwd.way.get("name");
                adjacentWays.add(new AdjacentWay(wwd.way, wayName, wwd.distance));
            }
            
            return adjacentWays;
        }
        
        /**
         * Circular search: original method using circular radius.
         */
        private static List<AdjacentWay> findAdjacentNamedWaysCircular(Way selectedWay, LatLon centerPoint, String level, DataSet ds, double radiusMeters) {
            List<AdjacentWay> adjacentWays = new ArrayList<>();
            Node centerNode = new Node(centerPoint);
            
            // Create circular bounding box
            BBox bbox = PolygonClickHandler.createCircularBoundingBox(centerPoint, radiusMeters);
            
            List<Way> candidateWays = ds.searchWays(bbox);
            
            for (Way way : candidateWays) {
                if (way.equals(selectedWay)) continue;
                
                // Filter by level (if level is specified)
                if (!PolygonClickHandler.matchesLevel(way, level)) continue;
                
                // Filter by name
                if (!PolygonClickHandler.hasName(way)) continue;
                
                String wayName = way.get("name");
                
                double distance = PolygonClickHandler.calculateDistanceToWayStatic(centerNode, way);
                if (!Double.isNaN(distance) && distance <= radiusMeters) {
                    adjacentWays.add(new AdjacentWay(way, wayName, distance));
                }
            }
            
            // Sort by distance
            adjacentWays.sort(Comparator.comparingDouble(a -> a.distance));
            
            return adjacentWays;
        }
        
        /**
         * Checks if name matches pattern: only letters, digits, and hyphens.
         */
        private static boolean isValidNamePattern(String name) {
            if (name == null || name.isEmpty()) return false;
            // Pattern: letters, digits, and hyphens only
            return name.matches("[a-zA-Z0-9\\-]+");
        }
        
        /**
         * Extracts prefix and trailing digits from a name.
         * E.g., "A301" -> prefix="A", number=301, paddingWidth=3
         *       "B3-239" -> prefix="B3-", number=239, paddingWidth=3
         *       "B3-023" -> prefix="B3-", number=23, paddingWidth=3 (preserves zero padding)
         */
        private static NameParts extractNameParts(String name) {
            if (name == null || name.isEmpty()) return null;
            
            // Find trailing digits
            int lastDigitIndex = -1;
            for (int i = name.length() - 1; i >= 0; i--) {
                if (Character.isDigit(name.charAt(i))) {
                    lastDigitIndex = i;
                } else {
                    break;
                }
            }
            
            if (lastDigitIndex == -1) {
                // No trailing digits found
                return null;
            }
            
            try {
                String prefix = name.substring(0, lastDigitIndex);
                String trailingDigits = name.substring(lastDigitIndex);
                int number = Integer.parseInt(trailingDigits);
                int paddingWidth = trailingDigits.length(); // Preserve the original digit count (including leading zeros)
                return new NameParts(prefix, number, paddingWidth);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        /**
         * Calculates spatial relationship between selected way and two reference ways.
         */
        private static SpatialRelationship calculateSpatialRelationship(Way selectedWay, LatLon centerP, Way wayA, Way wayB) {
            // Get centers of A and B
            org.openstreetmap.josm.data.coor.EastNorth centroidA = Geometry.getCentroid(wayA.getNodes());
            org.openstreetmap.josm.data.coor.EastNorth centroidB = Geometry.getCentroid(wayB.getNodes());
            
            if (centroidA == null || centroidB == null) {
                return new SpatialRelationship(false, 0);
            }
            
            org.openstreetmap.josm.data.projection.Projection proj = 
                org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
            LatLon centerA = proj.eastNorth2latlon(centroidA);
            LatLon centerB = proj.eastNorth2latlon(centroidB);
            
            // Convert to EastNorth for calculations
            org.openstreetmap.josm.data.coor.EastNorth enP = proj.latlon2eastNorth(centerP);
            org.openstreetmap.josm.data.coor.EastNorth enA = proj.latlon2eastNorth(centerA);
            org.openstreetmap.josm.data.coor.EastNorth enB = proj.latlon2eastNorth(centerB);
            
            // Determine ordering: -1 if P-A-B (P before), 0 if A-P-B (P between), 1 if A-B-P (P after)
            int ordering = determineOrdering(enP, enA, enB);
            
            // isBetween is true when ordering is 0 (P is between A and B) and P is close to the line
            boolean isBetween = false;
            if (ordering == 0) {
                // Check if P is close to the line segment (within reasonable distance)
                isBetween = isPointCloseToLineSegment(enP, enA, enB);
            }
            
            return new SpatialRelationship(isBetween, ordering);
        }
        
        /**
         * Checks if point P is close to the line segment AB (distance check only).
         * Assumes that determineOrdering has already determined that ordering == 0.
         */
        private static boolean isPointCloseToLineSegment(org.openstreetmap.josm.data.coor.EastNorth p,
                org.openstreetmap.josm.data.coor.EastNorth a, org.openstreetmap.josm.data.coor.EastNorth b) {
            double abLenSq = a.distanceSq(b);
            if (abLenSq == 0) return false; // A and B are the same point
            
            org.openstreetmap.josm.data.coor.EastNorth ab = new org.openstreetmap.josm.data.coor.EastNorth(
                b.east() - a.east(), b.north() - a.north());
            org.openstreetmap.josm.data.coor.EastNorth ap = new org.openstreetmap.josm.data.coor.EastNorth(
                p.east() - a.east(), p.north() - a.north());
            
            // Calculate parameter t: position of projection along AB
            double t = (ap.east() * ab.east() + ap.north() * ab.north()) / abLenSq;
            
            // Calculate projection point
            org.openstreetmap.josm.data.coor.EastNorth projection = new org.openstreetmap.josm.data.coor.EastNorth(
                a.east() + t * ab.east(), a.north() + t * ab.north());
            double distToLine = p.distance(projection);
            double abLen = Math.sqrt(abLenSq);
            
            // P is close if within 10% of AB length
            return distToLine < abLen * 0.1;
        }
        
        /**
         * Determines spatial ordering: -1 if P-A-B (P before A), 0 if A-P-B (P between), 1 if A-B-P (P after B).
         * Aligned with parameter t: negative t → -1, t > 1 → 1, 0 <= t <= 1 → 0.
         * Uses projection onto line segment AB to determine ordering accurately.
         */
        private static int determineOrdering(org.openstreetmap.josm.data.coor.EastNorth p,
                org.openstreetmap.josm.data.coor.EastNorth a, org.openstreetmap.josm.data.coor.EastNorth b) {
            // Project P onto line AB to determine ordering
            double abLenSq = a.distanceSq(b);
            if (abLenSq == 0) {
                // A and B are the same point, use distance-based fallback
                double distPA = p.distance(a);
                double distPB = p.distance(b);
                if (distPA < distPB * 0.7) return 1;
                if (distPB < distPA * 0.7) return -1;
                return 0;
            }
            
            org.openstreetmap.josm.data.coor.EastNorth ab = new org.openstreetmap.josm.data.coor.EastNorth(
                b.east() - a.east(), b.north() - a.north());
            org.openstreetmap.josm.data.coor.EastNorth ap = new org.openstreetmap.josm.data.coor.EastNorth(
                p.east() - a.east(), p.north() - a.north());
            
            // Calculate parameter t: position of projection along AB
            // t = 0 at A, t = 1 at B
            double t = (ap.east() * ab.east() + ap.north() * ab.north()) / abLenSq;
            
            // Determine ordering based on projection position
            // Aligned with t: negative t → negative ordering (before), positive t > 1 → positive ordering (after)
            if (t < 0) {
                // P projects before A: P-A-B (ordering = -1, meaning before/smaller)
                return -1;
            } else if (t > 1) {
                // P projects after B: A-B-P (ordering = 1, meaning after/larger)
                return 1;
            } else {
                // P projects between A and B: A-P-B (ordering = 0)
                return 0;
            }
        }
        
        /**
         * Infers number from spatial position when difference is 1.
         */
        private static int inferNumberFromPosition(NameParts partsA, NameParts partsB, SpatialRelationship spatial) {
            int numA = partsA.number;
            int numB = partsB.number;
            
            if (spatial.ordering == -1) {
                // P-A-B: P is before both, so number should be smaller
                return Math.min(numA, numB) - 1;
            } else if (spatial.ordering == 1) {
                // A-B-P: P is after both, so number should be larger
                return Math.max(numA, numB) + 1;
            } else {
                // A-P-B: P is between, but numbers differ by 1, so can't interpolate
                return -1;
            }
        }
        
        
        /**
         * Helper class to store adjacent way information.
         */
        private static class AdjacentWay {
            final Way way;
            final String name;
            final double distance;
            
            AdjacentWay(Way way, String name, double distance) {
                this.way = way;
                this.name = name;
                this.distance = distance;
            }
        }
        
        /**
         * Helper class to store name parts (prefix and number).
         */
        private static class NameParts {
            final String prefix;
            final int number;
            final int paddingWidth; // Number of digits in the original trailing number (for zero padding)
            
            NameParts(String prefix, int number, int paddingWidth) {
                this.prefix = prefix;
                this.number = number;
                this.paddingWidth = paddingWidth;
            }
        }
        
        /**
         * Helper class to store spatial relationship information.
         */
        private static class SpatialRelationship {
            final boolean isBetween;
            final int ordering; // -1: P-A-B (P before), 0: A-P-B (P between), 1: A-B-P (P after)
            
            SpatialRelationship(boolean isBetween, int ordering) {
                this.isBetween = isBetween;
                this.ordering = ordering;
            }
        }
    }
}

