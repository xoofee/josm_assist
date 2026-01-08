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

        // Check if level is selected and find name from nearest way in same level
        // Only do this if the selected way doesn't already have a name
        if (!hasName) {
            LevelProcessingHandler levelHandler = JosmAssistPlugin.getInstance().getLevelHandler();
            String currentLevel = levelHandler != null ? levelHandler.getCurrentLevelTagWithUpdate() : null;
            
            System.out.println("[JOSM Assist] PolygonClickHandler: Current level: " + currentLevel);
            
            if (currentLevel != null && !currentLevel.isEmpty()) {
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
                nameToPaste = NameInterpolator.interpolateName(selectedWay, polygonCenter, currentLevel, ds, 50.0);
                
                // If interpolation didn't work, fall back to nearest way
                if (nameToPaste == null) {
                    System.out.println("[JOSM Assist] PolygonClickHandler: Interpolation failed, searching for nearest named way in level '" + currentLevel + "' within 50 meters...");
                    Way nearestNamedWay = findNearestNamedWayInLevel(polygonCenter, selectedWay, currentLevel, ds, 50.0);
                    if (nearestNamedWay != null) {
                        String name = nearestNamedWay.get("name");
                        if (name != null && !name.isEmpty()) {
                            nameToPaste = name; // Store name to paste, but don't modify the way yet
                            System.out.println("[JOSM Assist] PolygonClickHandler: Found nearest named way! Name to paste: '" + nameToPaste + "'");
                        } else {
                            System.out.println("[JOSM Assist] PolygonClickHandler: Found nearest way but it has no name");
                        }
                    } else {
                        System.out.println("[JOSM Assist] PolygonClickHandler: No named way found within 50 meters with level '" + currentLevel + "'");
                    }
                } else {
                    System.out.println("[JOSM Assist] PolygonClickHandler: Successfully interpolated name: '" + nameToPaste + "'");
                }
            } else {
                System.out.println("[JOSM Assist] PolygonClickHandler: No level selected, skipping name search");
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
     * Uses spatial indexing via DataSet.searchWays(BBox) for efficient search.
     * @param centerPoint the center point (polygon centroid)
     * @param excludeWay the way to exclude from search (the selected way)
     * @param level the level to match
     * @param ds the dataset to search
     * @param radiusMeters the search radius in meters
     * @return the nearest way with a name, or null if none found
     */
    private Way findNearestNamedWayInLevel(LatLon centerPoint, Way excludeWay, String level, DataSet ds, double radiusMeters) {
        Way nearestWay = null;
        double minDistance = Double.MAX_VALUE;
        Node centerNode = new Node(centerPoint);
        int sameLevelWays = 0;
        int namedWays = 0;
        int withinRadiusWays = 0;

        // Create bounding box around center point with radius
        // Use JOSM's projection system for accurate conversion
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
        // BBox has a constructor that takes a Way, so we create a temporary way with nodes
        // at the bounding box corners
        Node minNode = new Node(minLL);
        Node maxNode = new Node(maxLL);
        Node centerNode1 = new Node(centerPoint);
        Way tempWay = new Way();
        tempWay.addNode(minNode);
        tempWay.addNode(maxNode);
        tempWay.addNode(centerNode1); // Add center to ensure it's included
        BBox bbox = new BBox(tempWay);
        
        System.out.println("[JOSM Assist] PolygonClickHandler: Searching in bounding box around center: " + centerPoint);

        // Use spatial indexing to search only ways within the bounding box
        List<Way> candidateWays = ds.searchWays(bbox);
        System.out.println("[JOSM Assist] PolygonClickHandler: Found " + candidateWays.size() + " ways in bounding box (out of " + ds.getWays().size() + " total)");

        for (Way way : candidateWays) {
            // Skip the selected way itself
            if (way.equals(excludeWay)) {
                continue;
            }

            // Check if way has the same level
            String wayLevel = way.get("level");
            if (wayLevel == null || !wayLevel.equals(level)) {
                continue;
            }
            sameLevelWays++;

            // Check if way has a name
            String wayName = way.get("name");
            if (wayName == null || wayName.isEmpty()) {
                continue;
            }
            namedWays++;

            // Calculate distance from center point to way
            double distance = calculateDistanceToWay(centerNode, way);
            
            System.out.println("[JOSM Assist] PolygonClickHandler: Found way with level '" + level + "' and name '" + wayName + "' at distance " + distance + " meters");
            
            // Check if within radius and is the nearest so far
            if (!Double.isNaN(distance) && distance <= radiusMeters && distance < minDistance) {
                minDistance = distance;
                nearestWay = way;
                withinRadiusWays++;
                System.out.println("[JOSM Assist] PolygonClickHandler: This is the nearest so far (distance: " + distance + " meters)");
            }
        }

        System.out.println("[JOSM Assist] PolygonClickHandler: Search summary - Candidate ways: " + candidateWays.size() + 
            ", Same level: " + sameLevelWays + ", With name: " + namedWays + ", Within radius: " + withinRadiusWays);
        
        if (nearestWay != null) {
            System.out.println("[JOSM Assist] PolygonClickHandler: Selected nearest way with name '" + nearestWay.get("name") + 
                "' at distance " + minDistance + " meters");
        } else {
            System.out.println("[JOSM Assist] PolygonClickHandler: No way found matching criteria");
        }

        return nearestWay;
    }

    /**
     * Calculates the minimum distance from a node to a way in meters.
     * @param node the node (point) to measure from
     * @param way the way to measure to
     * @return the distance in meters, or NaN if calculation fails
     */
    private double calculateDistanceToWay(Node node, Way way) {
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
            
            // Calculate spatial relationships
            SpatialRelationship spatial = calculateSpatialRelationship(selectedWay, centerPoint, wayA.way, wayB.way);
            
            if (diff == 2) {
                // If difference is 2, check if P is between A and B
                if (spatial.isBetween) {
                    int interpolatedNumber = (partsA.number + partsB.number) / 2;
                    String interpolatedName = partsA.prefix + interpolatedNumber;
                    System.out.println("[JOSM Assist] NameInterpolator: Difference=2, P is between A and B, interpolated: '" + interpolatedName + "'");
                    return interpolatedName;
                } else {
                    System.out.println("[JOSM Assist] NameInterpolator: Difference=2 but P is not between A and B, using nearest");
                    return null; // Fall back to nearest
                }
            } else if (diff == 1) {
                // If difference is 1
                if (spatial.isMiddle) {
                    System.out.println("[JOSM Assist] NameInterpolator: Difference=1, P is in middle, using nearest");
                    return null; // Fall back to nearest
                } else {
                    // Infer from relative position
                    int interpolatedNumber = inferNumberFromPosition(partsA, partsB, spatial);
                    if (interpolatedNumber > 0) {
                        String interpolatedName = partsA.prefix + interpolatedNumber;
                        System.out.println("[JOSM Assist] NameInterpolator: Difference=1, inferred from position: '" + interpolatedName + "'");
                        return interpolatedName;
                    }
                }
            } else if (diff >= 3) {
                System.out.println("[JOSM Assist] NameInterpolator: Difference=" + diff + " (>=3), using nearest");
                return null; // Fall back to nearest
            }
            
            return null;
        }
        
        /**
         * Finds adjacent ways with names within the search radius.
         */
        private static List<AdjacentWay> findAdjacentNamedWays(Way selectedWay, LatLon centerPoint, String level, DataSet ds, double radiusMeters) {
            List<AdjacentWay> adjacentWays = new ArrayList<>();
            Node centerNode = new Node(centerPoint);
            
            // Create bounding box for spatial search
            org.openstreetmap.josm.data.projection.Projection proj = 
                org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
            org.openstreetmap.josm.data.coor.EastNorth centerEN = proj.latlon2eastNorth(centerPoint);
            double radiusInProjectionUnits = radiusMeters / proj.getMetersPerUnit();
            
            org.openstreetmap.josm.data.coor.EastNorth minEN = new org.openstreetmap.josm.data.coor.EastNorth(
                centerEN.east() - radiusInProjectionUnits, centerEN.north() - radiusInProjectionUnits);
            org.openstreetmap.josm.data.coor.EastNorth maxEN = new org.openstreetmap.josm.data.coor.EastNorth(
                centerEN.east() + radiusInProjectionUnits, centerEN.north() + radiusInProjectionUnits);
            
            LatLon minLL = proj.eastNorth2latlon(minEN);
            LatLon maxLL = proj.eastNorth2latlon(maxEN);
            
            Node minNode = new Node(minLL);
            Node maxNode = new Node(maxLL);
            Node centerNode1 = new Node(centerPoint);
            Way tempWay = new Way();
            tempWay.addNode(minNode);
            tempWay.addNode(maxNode);
            tempWay.addNode(centerNode1);
            BBox bbox = new BBox(tempWay);
            
            List<Way> candidateWays = ds.searchWays(bbox);
            
            for (Way way : candidateWays) {
                if (way.equals(selectedWay)) continue;
                
                String wayLevel = way.get("level");
                if (wayLevel == null || !wayLevel.equals(level)) continue;
                
                String wayName = way.get("name");
                if (wayName == null || wayName.isEmpty()) continue;
                
                double distance = calculateDistanceToWayStatic(centerNode, way);
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
         * E.g., "A301" -> prefix="A", number=301
         *       "B3-239" -> prefix="B3-", number=239
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
                int number = Integer.parseInt(name.substring(lastDigitIndex));
                return new NameParts(prefix, number);
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
                return new SpatialRelationship(false, false, 0);
            }
            
            org.openstreetmap.josm.data.projection.Projection proj = 
                org.openstreetmap.josm.data.projection.ProjectionRegistry.getProjection();
            LatLon centerA = proj.eastNorth2latlon(centroidA);
            LatLon centerB = proj.eastNorth2latlon(centroidB);
            
            // Convert to EastNorth for calculations
            org.openstreetmap.josm.data.coor.EastNorth enP = proj.latlon2eastNorth(centerP);
            org.openstreetmap.josm.data.coor.EastNorth enA = proj.latlon2eastNorth(centerA);
            org.openstreetmap.josm.data.coor.EastNorth enB = proj.latlon2eastNorth(centerB);
            
            // Check if P is between A and B (project P onto line segment AB)
            boolean isBetween = isPointBetweenOnLine(enP, enA, enB);
            
            // Check if P is in the middle (approximately equidistant from A and B)
            double distPA = enP.distance(enA);
            double distPB = enP.distance(enB);
            boolean isMiddle = Math.abs(distPA - distPB) < Math.min(distPA, distPB) * 0.2; // Within 20% of smaller distance
            
            // Determine ordering: -1 if A-B-P, 0 if A-P-B or B-P-A, 1 if P-A-B or P-B-A
            int ordering = determineOrdering(enP, enA, enB);
            
            return new SpatialRelationship(isBetween, isMiddle, ordering);
        }
        
        /**
         * Checks if point P is between points A and B on the line segment.
         */
        private static boolean isPointBetweenOnLine(org.openstreetmap.josm.data.coor.EastNorth p, 
                org.openstreetmap.josm.data.coor.EastNorth a, org.openstreetmap.josm.data.coor.EastNorth b) {
            // Project P onto line AB
            double abLenSq = a.distanceSq(b);
            if (abLenSq == 0) return false; // A and B are the same point
            
            org.openstreetmap.josm.data.coor.EastNorth ab = new org.openstreetmap.josm.data.coor.EastNorth(
                b.east() - a.east(), b.north() - a.north());
            org.openstreetmap.josm.data.coor.EastNorth ap = new org.openstreetmap.josm.data.coor.EastNorth(
                p.east() - a.east(), p.north() - a.north());
            
            double t = (ap.east() * ab.east() + ap.north() * ab.north()) / abLenSq;
            
            // Check if projection is between A and B (0 <= t <= 1)
            if (t < 0 || t > 1) return false;
            
            // Check if P is close to the line (within reasonable distance)
            org.openstreetmap.josm.data.coor.EastNorth projection = new org.openstreetmap.josm.data.coor.EastNorth(
                a.east() + t * ab.east(), a.north() + t * ab.north());
            double distToLine = p.distance(projection);
            double abLen = Math.sqrt(abLenSq);
            
            // P is between if it's close to the line segment (within 10% of AB length)
            return distToLine < abLen * 0.1;
        }
        
        /**
         * Determines spatial ordering: -1 if A-B-P, 0 if A-P-B, 1 if P-A-B or P-B-A.
         */
        private static int determineOrdering(org.openstreetmap.josm.data.coor.EastNorth p,
                org.openstreetmap.josm.data.coor.EastNorth a, org.openstreetmap.josm.data.coor.EastNorth b) {
            double distPA = p.distance(a);
            double distPB = p.distance(b);
            
            // If P is much closer to A than B, ordering is P-A-B (1)
            if (distPA < distPB * 0.7) return 1;
            // If P is much closer to B than A, ordering is A-B-P (-1)
            if (distPB < distPA * 0.7) return -1;
            // Otherwise, P is roughly between A and B (0)
            return 0;
        }
        
        /**
         * Infers number from spatial position when difference is 1.
         */
        private static int inferNumberFromPosition(NameParts partsA, NameParts partsB, SpatialRelationship spatial) {
            int numA = partsA.number;
            int numB = partsB.number;
            
            if (spatial.ordering == 1) {
                // P-A-B or P-B-A: P is before both, so number should be smaller
                return Math.min(numA, numB) - 1;
            } else if (spatial.ordering == -1) {
                // A-B-P: P is after both, so number should be larger
                return Math.max(numA, numB) + 1;
            } else {
                // A-P-B or B-P-A: P is between, but numbers differ by 1, so can't interpolate
                return -1;
            }
        }
        
        /**
         * Calculates distance from node to way (static version for use in helper class).
         */
        private static double calculateDistanceToWayStatic(Node node, Way way) {
            if (way == null || way.getNodes() == null || way.getNodes().isEmpty()) {
                return Double.NaN;
            }
            
            try {
                double distance = Geometry.getDistance(node, way);
                if (!Double.isNaN(distance) && distance != Double.MAX_VALUE) {
                    return distance;
                }
            } catch (Exception e) {
                // Fallback
            }
            
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
            
            NameParts(String prefix, int number) {
                this.prefix = prefix;
                this.number = number;
            }
        }
        
        /**
         * Helper class to store spatial relationship information.
         */
        private static class SpatialRelationship {
            final boolean isBetween;
            final boolean isMiddle;
            final int ordering; // -1: A-B-P, 0: A-P-B, 1: P-A-B
            
            SpatialRelationship(boolean isBetween, boolean isMiddle, int ordering) {
                this.isBetween = isBetween;
                this.isMiddle = isMiddle;
                this.ordering = ordering;
            }
        }
    }
}

