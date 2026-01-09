package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Action to combine multiple selected ways into a single minimal bounding rectangle.
 * The rectangle can be rotated to minimize area.
 */
public class WayCombineAction extends JosmAction {

    /**
     * Constructs a new {@code WayCombineAction}.
     */
    public WayCombineAction() {
        super(tr("Combine Ways to Rectangle"), 
                new ImageProvider("combineway").setOptional(true).setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Combine selected ways into a minimal bounding rectangle"),
                null, // no shortcut
                true, // register in toolbar
                "josmassist-combine", // toolbar ID
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

        Collection<OsmPrimitive> selected = ds.getSelected();
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Please select at least one way"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Get all selected ways
        List<Way> selectedWays = new ArrayList<>();
        for (OsmPrimitive prim : selected) {
            if (prim instanceof Way) {
                selectedWays.add((Way) prim);
            }
        }

        if (selectedWays.isEmpty()) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Please select at least one way"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Execute the combine operation
        Command cmd = createCombineCommand(ds, selectedWays);
        if (cmd != null) {
            UndoRedoHandler.getInstance().add(cmd);
            MainApplication.getMap().repaint();
        }
    }

    /**
     * Creates a command to combine ways into a minimal bounding rectangle.
     * @param ds the dataset
     * @param ways the ways to combine
     * @return the command, or null if operation cannot be performed
     */
    private Command createCombineCommand(DataSet ds, List<Way> ways) {
        // Step 1: Get reference way (first with name, or first if none have name)
        Way referenceWay = getReferenceWay(ways);
        if (referenceWay == null) {
            return null;
        }

        // Step 2: Collect all nodes from selected ways
        Set<Node> allNodes = new HashSet<>();
        for (Way way : ways) {
            for (Node node : way.getNodes()) {
                if (node.getCoor() != null) {
                    allNodes.add(node);
                }
            }
        }

        if (allNodes.size() < 3) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Selected ways must contain at least 3 nodes with coordinates"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // Step 3: Calculate minimal bounding rectangle
        List<Node> rectangleNodes = calculateMinimalBoundingRectangle(new ArrayList<>(allNodes));
        if (rectangleNodes == null || rectangleNodes.size() < 4) {
            JOptionPane.showMessageDialog(
                    MainApplication.getMainFrame(),
                    tr("Could not calculate bounding rectangle"),
                    tr("Error"),
                    JOptionPane.ERROR_MESSAGE);
            return null;
        }

        // Step 4: Create new way with rectangle nodes
        // First, add the new nodes to the dataset
        List<Command> commands = new ArrayList<>();
        for (Node node : rectangleNodes) {
            // Only add if node is not already in dataset
            if (node.getDataSet() == null) {
                commands.add(new AddCommand(ds, node));
            }
        }
        
        Way newWay = new Way();
        for (Node node : rectangleNodes) {
            newWay.addNode(node);
        }
        // Close the way to make it a polygon
        if (!newWay.isClosed()) {
            newWay.addNode(newWay.getNode(0));
        }

        // Step 5: Copy tags from reference way
        for (String key : referenceWay.keySet()) {
            newWay.put(key, referenceWay.get(key));
        }

        // Step 6: Add way and delete original ways
        commands.add(new AddCommand(ds, newWay));
        commands.add(new DeleteCommand(ds, ways));

        return new SequenceCommand(tr("Combine {0} ways to rectangle", ways.size()), commands);
    }

    /**
     * Gets the reference way (first way with name, or first way if none have name).
     * @param ways the list of ways
     * @return the reference way
     */
    private Way getReferenceWay(List<Way> ways) {
        // First, try to find a way with a name
        for (Way way : ways) {
            String name = way.get("name");
            if (name != null && !name.isEmpty()) {
                return way;
            }
        }
        // If no way has a name, return the first way
        return ways.isEmpty() ? null : ways.get(0);
    }

    /**
     * Calculates the minimal bounding rectangle (possibly rotated) from a set of nodes.
     * Uses rotating calipers algorithm on the convex hull.
     * @param nodes the nodes to bound
     * @return list of 4 nodes forming the rectangle corners, or null if calculation fails
     */
    private List<Node> calculateMinimalBoundingRectangle(List<Node> nodes) {
        if (nodes.size() < 3) {
            return null;
        }

        // Convert nodes to EastNorth coordinates for calculations
        List<EastNorth> points = new ArrayList<>();
        for (Node node : nodes) {
            if (node.getCoor() != null) {
                EastNorth en = ProjectionRegistry.getProjection().latlon2eastNorth(node.getCoor());
                if (en != null) {
                    points.add(en);
                }
            }
        }

        if (points.size() < 3) {
            return null;
        }

        // Calculate convex hull
        List<EastNorth> hull = calculateConvexHull(points);
        if (hull.size() < 3) {
            // If convex hull fails, use axis-aligned bounding box
            return calculateAxisAlignedBoundingBox(nodes);
        }

        // Use rotating calipers to find minimum area rectangle
        return findMinimalRectangle(hull, nodes);
    }

    /**
     * Calculates the convex hull of a set of points using Graham scan algorithm.
     * @param points the points
     * @return the convex hull points
     */
    private List<EastNorth> calculateConvexHull(List<EastNorth> points) {
        if (points.size() <= 3) {
            return new ArrayList<>(points);
        }

        // Find bottom-most point (or leftmost in case of tie)
        EastNorth bottom = points.get(0);
        for (EastNorth p : points) {
            if (p.north() < bottom.north() || 
                (p.north() == bottom.north() && p.east() < bottom.east())) {
                bottom = p;
            }
        }

        // Sort points by polar angle with respect to bottom point
        final EastNorth origin = bottom;
        List<EastNorth> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> {
            if (a.equals(origin)) return -1;
            if (b.equals(origin)) return 1;
            
            double angleA = Math.atan2(a.north() - origin.north(), a.east() - origin.east());
            double angleB = Math.atan2(b.north() - origin.north(), b.east() - origin.east());
            
            if (Math.abs(angleA - angleB) < 1e-10) {
                // Same angle, sort by distance
                double distA = origin.distance(a);
                double distB = origin.distance(b);
                return Double.compare(distA, distB);
            }
            return Double.compare(angleA, angleB);
        });

        // Build convex hull using Graham scan
        List<EastNorth> hull = new ArrayList<>();
        for (EastNorth point : sorted) {
            while (hull.size() >= 2) {
                EastNorth p1 = hull.get(hull.size() - 2);
                EastNorth p2 = hull.get(hull.size() - 1);
                
                // Check if turning right (clockwise)
                double cross = crossProduct(p1, p2, point);
                if (cross <= 0) {
                    hull.remove(hull.size() - 1);
                } else {
                    break;
                }
            }
            hull.add(point);
        }

        return hull;
    }

    /**
     * Calculates cross product for three points (for convex hull).
     */
    private double crossProduct(EastNorth a, EastNorth b, EastNorth c) {
        return (b.east() - a.east()) * (c.north() - a.north()) - 
               (b.north() - a.north()) * (c.east() - a.east());
    }

    /**
     * Finds the minimal area rectangle using rotating calipers.
     * @param hull the convex hull points
     * @param originalNodes the original nodes (for creating result nodes)
     * @return list of 4 nodes forming the rectangle
     */
    private List<Node> findMinimalRectangle(List<EastNorth> hull, List<Node> originalNodes) {
        if (hull.size() < 3) {
            return calculateAxisAlignedBoundingBox(originalNodes);
        }

        double minArea = Double.MAX_VALUE;
        List<EastNorth> bestRect = null;

        int n = hull.size();
        
        // For each edge of the convex hull, try it as one side of the rectangle
        for (int i = 0; i < n; i++) {
            EastNorth p1 = hull.get(i);
            EastNorth p2 = hull.get((i + 1) % n);
            
            // Calculate edge direction
            double dx = p2.east() - p1.east();
            double dy = p2.north() - p1.north();
            double edgeLen = Math.sqrt(dx * dx + dy * dy);
            
            if (edgeLen < 1e-10) continue;
            
            // Unit vector along edge
            double ux = dx / edgeLen;
            double uy = dy / edgeLen;
            
            // Perpendicular vector (rotated 90 degrees)
            double vx = -uy;
            double vy = ux;
            
            // Project all points onto edge direction and perpendicular
            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
            
            for (EastNorth p : hull) {
                double u = (p.east() - p1.east()) * ux + (p.north() - p1.north()) * uy;
                double v = (p.east() - p1.east()) * vx + (p.north() - p1.north()) * vy;
                
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
            
            // Calculate rectangle corners
            double width = maxU - minU;
            double height = maxV - minV;
            double area = width * height;
            
            if (area < minArea) {
                minArea = area;
                
                // Calculate corner points
                EastNorth corner1 = new EastNorth(
                    p1.east() + minU * ux + minV * vx,
                    p1.north() + minU * uy + minV * vy
                );
                EastNorth corner2 = new EastNorth(
                    p1.east() + maxU * ux + minV * vx,
                    p1.north() + maxU * uy + minV * vy
                );
                EastNorth corner3 = new EastNorth(
                    p1.east() + maxU * ux + maxV * vx,
                    p1.north() + maxU * uy + maxV * vy
                );
                EastNorth corner4 = new EastNorth(
                    p1.east() + minU * ux + maxV * vx,
                    p1.north() + minU * uy + maxV * vy
                );
                
                bestRect = new ArrayList<>();
                bestRect.add(corner1);
                bestRect.add(corner2);
                bestRect.add(corner3);
                bestRect.add(corner4);
            }
        }

        if (bestRect == null) {
            return calculateAxisAlignedBoundingBox(originalNodes);
        }

        // Convert EastNorth back to LatLon and create nodes
        List<Node> result = new ArrayList<>();
        for (EastNorth en : bestRect) {
            LatLon ll = ProjectionRegistry.getProjection().eastNorth2latlon(en);
            if (ll != null) {
                Node node = new Node(ll);
                result.add(node);
            }
        }

        return result.size() == 4 ? result : calculateAxisAlignedBoundingBox(originalNodes);
    }

    /**
     * Calculates an axis-aligned bounding box as fallback.
     * @param nodes the nodes
     * @return list of 4 nodes forming the rectangle
     */
    private List<Node> calculateAxisAlignedBoundingBox(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return null;
        }

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

        for (Node node : nodes) {
            if (node.getCoor() != null) {
                LatLon coor = node.getCoor();
                minLat = Math.min(minLat, coor.lat());
                maxLat = Math.max(maxLat, coor.lat());
                minLon = Math.min(minLon, coor.lon());
                maxLon = Math.max(maxLon, coor.lon());
            }
        }

        if (minLat == Double.MAX_VALUE) {
            return null;
        }

        List<Node> result = new ArrayList<>();
        result.add(new Node(new LatLon(minLat, minLon)));
        result.add(new Node(new LatLon(minLat, maxLon)));
        result.add(new Node(new LatLon(maxLat, maxLon)));
        result.add(new Node(new LatLon(maxLat, minLon)));

        return result;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null);
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null && 
                   selection != null && !selection.isEmpty());
    }
}
