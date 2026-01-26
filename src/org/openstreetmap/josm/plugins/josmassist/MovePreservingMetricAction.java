package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.NodeData;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.PrimitiveData;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WayData;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.gui.datatransfer.ClipboardUtils;
import org.openstreetmap.josm.gui.datatransfer.data.PrimitiveTransferData;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Action to move copied elements (nodes/ways) to the current center of window
 * while preserving metric distances.
 * 
 * This action works by:
 * 1. Detecting elements in the clipboard
 * 2. Finding the corresponding elements in the current dataset
 * 3. Moving them to the center of the view window
 * 4. Using EastNorth coordinates to preserve metric distances (avoiding latitude distortion)
 */
public class MovePreservingMetricAction extends JosmAction {

    /**
     * Constructs a new {@code MovePreservingMetricAction}.
     */
    public MovePreservingMetricAction() {
        super(tr("Move (Preserving Metric)"), 
                new ImageProvider("move").setOptional(true).setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Move copied elements to center of window while preserving metric distances"),
                null, // no shortcut by default
                false, // don't register in toolbar by default
                "josmassist-move-metric", // toolbar ID
                false); // don't install adapters
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("No data layer selected"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Get clipboard content
        Transferable transferable = ClipboardUtils.getClipboardContent();
        if (transferable == null) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("No data in clipboard"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Extract PrimitiveTransferData from clipboard
        PrimitiveTransferData transferData = null;
        try {
            if (transferable.isDataFlavorSupported(PrimitiveTransferData.DATA_FLAVOR)) {
                transferData = (PrimitiveTransferData) transferable.getTransferData(PrimitiveTransferData.DATA_FLAVOR);
            }
        } catch (UnsupportedFlavorException | IOException ex) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Could not read clipboard data: {0}", ex.getMessage()),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (transferData == null || transferData.getAll().isEmpty()) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("No OSM primitives found in clipboard"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Get target position (center of current view) in LatLon
        EastNorth targetCenterEN = MainApplication.getMap().mapView.getCenter();
        if (targetCenterEN == null) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Could not determine view center"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        // Convert target center to LatLon
        LatLon targetCenter = ProjectionRegistry.getProjection().eastNorth2latlon(targetCenterEN);
        if (targetCenter == null) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Could not convert view center to coordinates"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Create command to move elements
        Command cmd = createMoveCommand(ds, transferData, targetCenter);
        if (cmd != null) {
            UndoRedoHandler.getInstance().add(cmd);
            MainApplication.getMap().repaint();
        }
    }

    /**
     * Creates a command to move elements from clipboard to target position.
     * Uses great circle distance and bearing to preserve metric distances.
     * @param ds the dataset
     * @param transferData the clipboard data
     * @param targetCenter the target center position in LatLon coordinates
     * @return the command, or null if operation cannot be performed
     */
    private Command createMoveCommand(DataSet ds, PrimitiveTransferData transferData, LatLon targetCenter) {
        // Step 1: Find all nodes referenced in the clipboard data
        Map<Long, Node> nodesToMove = new HashMap<>();
        
        // Collect all node IDs from clipboard (from nodes directly and from ways)
        Set<Long> nodeIds = new HashSet<>();
        
        for (PrimitiveData pd : transferData.getAll()) {
            if (pd instanceof NodeData) {
                nodeIds.add(pd.getUniqueId());
            } else if (pd instanceof WayData) {
                // Collect node IDs from the way
                WayData wd = (WayData) pd;
                nodeIds.addAll(wd.getNodeIds());
            }
        }

        if (nodeIds.isEmpty()) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("No nodes found in clipboard data"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // Step 2: Find corresponding nodes in the dataset
        for (Long nodeId : nodeIds) {
            Node node = (Node) ds.getPrimitiveById(nodeId, OsmPrimitiveType.NODE);
            if (node != null && node.getCoor() != null && !node.isDeleted()) {
                nodesToMove.put(nodeId, node);
            }
        }

        if (nodesToMove.isEmpty()) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("No matching nodes found in current dataset"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // Step 3: Calculate current center of all nodes in LatLon space (geographic center)
        LatLon sourceCenter = calculateCenterInLatLon(nodesToMove.values());
        if (sourceCenter == null) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Could not calculate center of elements"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // Step 4: Create commands to move all nodes using distance and bearing
        // This preserves metric distances by using great circle calculations directly
        List<Command> commands = new ArrayList<>();
        for (Node node : nodesToMove.values()) {
            LatLon oldCoor = node.getCoor();
            if (oldCoor == null) continue;

            // Calculate distance and bearing from source center to this node
            double distanceMeters = sourceCenter.greatCircleDistance(oldCoor); // distance in meters
            double bearing = sourceCenter.bearing(oldCoor); // bearing in radians
            
            // Calculate new position at target center with same distance and bearing
            // Use direct great circle calculation to preserve metric distances
            LatLon newCoor = calculateDestinationPoint(targetCenter, bearing, distanceMeters);

            // Create command to change node coordinates
            Node newNode = new Node(node);
            newNode.setCoor(newCoor);
            commands.add(new ChangeCommand(ds, node, newNode));
        }

        if (commands.isEmpty()) {
            return null;
        }

        return new SequenceCommand(tr("Move {0} elements (preserving metric)", nodesToMove.size()), commands);
    }

    /**
     * Calculates a destination point using great circle navigation.
     * This preserves metric distances correctly regardless of latitude.
     * @param startPoint the starting point
     * @param bearing the bearing in radians (0 = north, PI/2 = east)
     * @param distanceMeters the distance in meters
     * @return the destination point
     */
    private LatLon calculateDestinationPoint(LatLon startPoint, double bearing, double distanceMeters) {
        // WGS84 semi-major axis in meters
        final double EARTH_RADIUS_METERS = 6378137.0;
        
        double lat1 = Math.toRadians(startPoint.lat());
        double lon1 = Math.toRadians(startPoint.lon());
        
        // Angular distance in radians
        double angularDistance = distanceMeters / EARTH_RADIUS_METERS;
        
        // Calculate destination using spherical trigonometry (great circle)
        double lat2 = Math.asin(
            Math.sin(lat1) * Math.cos(angularDistance) +
            Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing)
        );
        
        double lon2 = lon1 + Math.atan2(
            Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1),
            Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2)
        );
        
        return new LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2));
    }

    /**
     * Calculates the geographic center of a collection of nodes in LatLon coordinates.
     * Uses simple average of lat/lon (for small areas this is sufficient).
     * @param nodes the nodes
     * @return the center in LatLon coordinates, or null if calculation fails
     */
    private LatLon calculateCenterInLatLon(Collection<Node> nodes) {
        if (nodes.isEmpty()) {
            return null;
        }

        double sumLat = 0.0;
        double sumLon = 0.0;
        int count = 0;

        for (Node node : nodes) {
            if (node.getCoor() == null) continue;
            
            LatLon coor = node.getCoor();
            sumLat += coor.lat();
            sumLon += coor.lon();
            count++;
        }

        if (count == 0) {
            return null;
        }

        return new LatLon(sumLat / count, sumLon / count);
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null && 
                   isClipboardDataAvailable());
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        updateEnabledState();
    }

    /**
     * Checks if clipboard contains OSM primitive data.
     * @return true if clipboard has OSM data
     */
    private boolean isClipboardDataAvailable() {
        try {
            Transferable transferable = ClipboardUtils.getClipboardContent();
            if (transferable == null) {
                return false;
            }
            return transferable.isDataFlavorSupported(PrimitiveTransferData.DATA_FLAVOR);
        } catch (Exception e) {
            return false;
        }
    }
}
