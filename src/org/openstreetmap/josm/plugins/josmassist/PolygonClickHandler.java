package org.openstreetmap.josm.plugins.josmassist;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.data.coor.LatLon;
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

        // Ensure name tag exists (create if missing)
        if (selectedWay.get("name") == null) {
            selectedWay.put("name", "");
        }

        // Auto-popup name tag editing (like Alt+S)
        // Use SwingUtilities to ensure it runs on the EDT
        javax.swing.SwingUtilities.invokeLater(() -> {
            openTagEditor(selectedWay);
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
     */
    private void openTagEditor(Way way) {
        try {
            MapFrame mapFrame = MainApplication.getMap();
            if (mapFrame == null) {
                return;
            }

            // Open properties dialog via reflection
            java.lang.reflect.Field propDialogField = mapFrame.getClass().getDeclaredField("propertiesDialog");
            propDialogField.setAccessible(true);
            Object propDialog = propDialogField.get(mapFrame);
            
            if (propDialog != null) {
                java.lang.reflect.Method setVisibleMethod = propDialog.getClass().getMethod("setVisible", boolean.class);
                setVisibleMethod.invoke(propDialog, true);
                
                // Bring dialog to front
                if (propDialog instanceof java.awt.Window) {
                    ((java.awt.Window) propDialog).toFront();
                    ((java.awt.Window) propDialog).requestFocus();
                }
                
                // Focus on name field
                focusOnNameField(propDialog);
            }
        } catch (Exception ex) {
            // If dialog opening fails, at least ensure selection is visible
            try {
                MainApplication.getMap().repaint();
            } catch (Exception e) {
                // Ignore
            }
        }
    }
    
    /**
     * Focuses on the "name" tag field in the properties dialog.
     * @param propDialog the properties dialog object
     */
    private void focusOnNameField(Object propDialog) {
        try {
            // Find the tag table and select the name row
            java.lang.reflect.Field tagTableField = propDialog.getClass().getDeclaredField("tagTable");
            tagTableField.setAccessible(true);
            Object tagTable = tagTableField.get(propDialog);
            
            if (tagTable instanceof javax.swing.JTable) {
                javax.swing.JTable table = (javax.swing.JTable) tagTable;
                int rowCount = table.getRowCount();
                
                // Find the "name" row
                for (int i = 0; i < rowCount; i++) {
                    Object keyValue = table.getValueAt(i, 0);
                    if (keyValue != null && "name".equalsIgnoreCase(keyValue.toString())) {
                        // Select the row and value column
                        table.setRowSelectionInterval(i, i);
                        table.setColumnSelectionInterval(1, 1);
                        table.scrollRectToVisible(table.getCellRect(i, 1, true));
                        table.requestFocus();
                        
                        // Start editing with Alt+S keyboard shortcut
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            try {
                                Thread.sleep(100); // Small delay to ensure selection is processed
                                java.awt.Robot robot = new java.awt.Robot();
                                robot.setAutoDelay(10);
                                robot.keyPress(java.awt.event.KeyEvent.VK_ALT);
                                Thread.sleep(10);
                                robot.keyPress(java.awt.event.KeyEvent.VK_S);
                                Thread.sleep(10);
                                robot.keyRelease(java.awt.event.KeyEvent.VK_S);
                                Thread.sleep(10);
                                robot.keyRelease(java.awt.event.KeyEvent.VK_ALT);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) {
                                // Ignore if Alt+S simulation fails
                            }
                        });
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore if name field focusing fails
        }
    }
}

