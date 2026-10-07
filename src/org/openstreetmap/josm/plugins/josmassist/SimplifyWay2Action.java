package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.SimplifyWayAction;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Ellipsoid;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/**
 * Simplifies ways while allowing removable nodes immediately next to protected
 * shared nodes to be considered by Douglas-Peucker.
 */
public class SimplifyWay2Action extends JosmAction {

    /** Constructs the action. */
    public SimplifyWay2Action() {
        super(tr("Simplify Way 2"),
                new ImageProvider("simplify").setOptional(true)
                        .setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Simplify selected ways while preserving connected nodes"),
                null,
                true,
                "josmassist-simplify-way2",
                false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null) {
            return;
        }

        List<Way> ways = new ArrayList<>(dataSet.getSelectedWays());
        ways.removeIf(Way::isIncomplete);
        if (ways.isEmpty()) {
            return;
        }

        double threshold = SimplifyWayAction.askSimplifyWays(
                ways, tr("Simplify selected ways with the improved shared-node handling."), false);
        if (threshold <= 0) {
            return;
        }

        Command command = createCommand(ways, threshold);
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
    }

    /**
     * Creates the undoable command for the supplied ways.
     *
     * @param ways ways to simplify
     * @param threshold maximum error in metres
     * @return command, or {@code null} if no node can be removed
     */
    static Command createCommand(Collection<Way> ways, double threshold) {
        List<Command> commands = new ArrayList<>();
        Set<Node> deletedNodes = new HashSet<>();
        for (Way way : ways) {
            List<Node> simplified = simplifyWay(way, threshold);
            if (simplified.size() == way.getNodesCount()) {
                continue;
            }
            Set<Node> removed = new HashSet<>(way.getNodes());
            removed.removeAll(simplified);
            if (removed.isEmpty()) {
                continue;
            }
            commands.add(new ChangeNodesCommand(way, simplified));
            deletedNodes.addAll(removed);
        }
        if (commands.isEmpty()) {
            return null;
        }
        if (!deletedNodes.isEmpty()) {
            DataSet dataSet = ways.iterator().next().getDataSet();
            commands.add(new DeleteCommand(dataSet, deletedNodes));
        }
        return new SequenceCommand(tr("Simplify Way 2"), commands);
    }

    /** Simplifies one way while preserving all protected nodes and closure. */
    static List<Node> simplifyWay(Way way, double threshold) {
        List<Node> nodes = way.getNodes();
        if (nodes.size() < 3) {
            return nodes;
        }

        boolean closed = way.isClosed();
        int last = closed ? nodes.size() - 2 : nodes.size() - 1;
        List<Node> working = new ArrayList<>(nodes.subList(0, last + 1));
        Set<Node> repeated = repeatedNodes(working);
        List<Integer> anchors = new ArrayList<>();
        anchors.add(0);
        for (int i = 1; i < working.size() - 1; i++) {
            Node node = working.get(i);
            if (isProtected(way, node, repeated)) {
                anchors.add(i);
            }
        }
        if (working.size() > 1) {
            anchors.add(working.size() - 1);
        }

        List<Node> result = new ArrayList<>();
        for (int i = 0; i < anchors.size() - 1; i++) {
            appendSimplified(working, anchors.get(i), anchors.get(i + 1), threshold, result);
        }
        if (result.isEmpty()) {
            result.addAll(working);
        }
        if (closed) {
            result.add(result.get(0));
        }
        return result;
    }

    private static Set<Node> repeatedNodes(List<Node> nodes) {
        Set<Node> seen = new HashSet<>();
        Set<Node> repeated = new HashSet<>();
        for (Node node : nodes) {
            if (!seen.add(node)) {
                repeated.add(node);
            }
        }
        return repeated;
    }

    private static boolean isProtected(Way way, Node node, Set<Node> repeated) {
        if (node.isTagged() || repeated.contains(node)) {
            return true;
        }
        List<OsmPrimitive> referrers = new ArrayList<>(node.getReferrers());
        referrers.remove(way);
        return !referrers.isEmpty();
    }

    private static void appendSimplified(List<Node> nodes, int from, int to, double threshold,
            List<Node> output) {
        Node fromNode = nodes.get(from);
        Node toNode = nodes.get(to);
        if (from < to && fromNode == toNode) {
            if (output.isEmpty() || output.get(output.size() - 1) != fromNode) {
                output.add(fromNode);
            }
            output.add(toNode);
            return;
        }
        if (output.isEmpty() || output.get(output.size() - 1) != fromNode) {
            output.add(fromNode);
        }
        if (to <= from) {
            return;
        }

        int maxIndex = -1;
        double maxError = 0;
        for (int i = from + 1; i < to; i++) {
            double error = crossTrackError(fromNode, toNode, nodes.get(i));
            if (error > maxError) {
                maxError = error;
                maxIndex = i;
            }
        }
        if (maxIndex >= 0 && maxError >= threshold) {
            appendSimplified(nodes, from, maxIndex, threshold, output);
            appendSimplified(nodes, maxIndex, to, threshold, output);
        } else {
            if (output.get(output.size() - 1) != toNode) {
                output.add(toNode);
            }
        }
    }

    private static double crossTrackError(Node from, Node to, Node point) {
        double lat1 = Math.toRadians(from.lat());
        double lon1 = Math.toRadians(from.lon());
        double lat2 = Math.toRadians(to.lat());
        double lon2 = Math.toRadians(to.lon());
        double lat3 = Math.toRadians(point.lat());
        double lon3 = Math.toRadians(point.lon());
        double distance = distance(lat1, lon1, lat3, lon3);
        double bearingToPoint = course(lat1, lon1, lat3, lon3);
        double bearingToEnd = course(lat1, lon1, lat2, lon2);
        return Ellipsoid.WGS84.a * Math.abs(Math.asin(
                Math.sin(distance) * Math.sin(bearingToPoint - bearingToEnd)));
    }

    private static double distance(double lat1, double lon1, double lat2, double lon2) {
        return 2 * Math.asin(Math.sqrt(Math.pow(Math.sin((lat1 - lat2) / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin((lon1 - lon2) / 2), 2)));
    }

    private static double course(double lat1, double lon1, double lat2, double lon2) {
        return Math.atan2(Math.sin(lon1 - lon2) * Math.cos(lat2),
                Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2)
                        * Math.cos(lon1 - lon2)) % (2 * Math.PI);
    }
}
