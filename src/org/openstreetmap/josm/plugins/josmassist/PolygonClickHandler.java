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
     * @param e the mouse event
     * @return true if a polygon was selected
     */
    public boolean handleMouseClick(MouseEvent e) {
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
                
                // Search for nearest way within 50 meters with same level and a name
                System.out.println("[JOSM Assist] PolygonClickHandler: Searching for nearest named way in level '" + currentLevel + "' within 50 meters...");
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
}

