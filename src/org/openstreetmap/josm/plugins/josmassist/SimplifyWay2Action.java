package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Ellipsoid;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

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
                Shortcut.registerShortcut("plugin:josmassist:simplifyway2",
                        tr("Simplify Way 2"), KeyEvent.VK_Y, Shortcut.CTRL_SHIFT),
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
        List<Way> selectedWays = ways.stream()
                .filter(way -> way != null && !way.isIncomplete())
                .collect(Collectors.toList());
        List<CombinedWay> combinedWays = findCombinedWays(selectedWays);
        Set<Way> absorbedWays = combinedWays.stream()
                .flatMap(combined -> combined.absorbed.stream())
                .collect(Collectors.toSet());
        List<Command> commands = new ArrayList<>();
        Set<Node> deletedNodes = new HashSet<>();
        Set<Way> changedWays = new HashSet<>();

        for (CombinedWay combined : combinedWays) {
            List<Node> simplified = simplifyNodes(combined.target, combined.path, combined.ways, threshold);
            addWayChange(combined.target, combined.path, simplified, commands, deletedNodes);
            changedWays.add(combined.target);
        }
        for (Way way : selectedWays) {
            if (absorbedWays.contains(way) || changedWays.contains(way)) {
                continue;
            }
            List<Node> simplified = simplifyWay(way, threshold);
            addWayChange(way, way.getNodes(), simplified, commands, deletedNodes);
        }
        if (commands.isEmpty()) {
            return null;
        }
        Set<OsmPrimitive> deletions = new LinkedHashSet<>(absorbedWays);
        deletions.addAll(deletedNodes);
        if (!deletions.isEmpty()) {
            commands.add(new DeleteCommand(selectedWays.get(0).getDataSet(), deletions));
        }
        return new SequenceCommand(tr("Simplify Way 2"), commands);
    }

    private static void addWayChange(Way way, List<Node> original, List<Node> simplified,
            List<Command> commands, Set<Node> deletedNodes) {
        if (simplified.size() == original.size()) {
            return;
        }
        Set<Node> removed = new HashSet<>(original);
        removed.removeAll(simplified);
        if (removed.isEmpty()) {
            return;
        }
        commands.add(new ChangeNodesCommand(way, simplified));
        deletedNodes.addAll(removed);
    }

    private static List<CombinedWay> findCombinedWays(List<Way> selectedWays) {
        Set<Way> selected = new HashSet<>(selectedWays);
        Map<Node, List<Way>> joins = new LinkedHashMap<>();
        for (Way way : selectedWays) {
            if (way.isClosed() || way.getNodesCount() < 2 || hasRelationReferrer(way)) {
                continue;
            }
            addJoinIfEligible(way, way.getNode(0), selected, joins);
            addJoinIfEligible(way, way.getNode(way.getNodesCount() - 1), selected, joins);
        }

        Map<Way, Set<Node>> wayJoins = new LinkedHashMap<>();
        for (Map.Entry<Node, List<Way>> entry : joins.entrySet()) {
            if (entry.getValue().size() == 2) {
                for (Way way : entry.getValue()) {
                    wayJoins.computeIfAbsent(way, ignored -> new LinkedHashSet<>()).add(entry.getKey());
                }
            }
        }

        Set<Way> visited = new HashSet<>();
        List<CombinedWay> result = new ArrayList<>();
        for (Way seed : selectedWays) {
            if (visited.contains(seed) || !wayJoins.containsKey(seed)) {
                continue;
            }
            Set<Way> component = collectComponent(seed, wayJoins, joins, visited);
            if (component.size() < 2 || component.stream().anyMatch(way -> wayJoins.get(way).size() > 2)) {
                continue;
            }
            CombinedWay combined = buildCombinedWay(component, wayJoins, joins);
            if (combined != null) {
                result.add(combined);
            }
        }
        return result;
    }

    private static void addJoinIfEligible(Way way, Node node, Set<Way> selected,
            Map<Node, List<Way>> joins) {
        if (hasRelationReferrer(node)) {
            return;
        }
        List<Way> parentWays = node.getParentWays().stream()
                .filter(parent -> !parent.isIncomplete())
                .collect(Collectors.toList());
        if (parentWays.size() != 2 || !parentWays.contains(way)
                || !selected.contains(parentWays.get(0)) || !selected.contains(parentWays.get(1))
                || !sameTags(parentWays.get(0), parentWays.get(1))) {
            return;
        }
        joins.put(node, parentWays);
    }

    private static boolean sameTags(Way first, Way second) {
        return first.getKeys().equals(second.getKeys());
    }

    private static boolean hasRelationReferrer(OsmPrimitive primitive) {
        return primitive.getReferrers().stream().anyMatch(Relation.class::isInstance);
    }

    private static Set<Way> collectComponent(Way seed, Map<Way, Set<Node>> wayJoins,
            Map<Node, List<Way>> joins, Set<Way> visited) {
        Set<Way> component = new LinkedHashSet<>();
        List<Way> pending = new ArrayList<>();
        pending.add(seed);
        while (!pending.isEmpty()) {
            Way way = pending.remove(pending.size() - 1);
            if (!component.add(way)) {
                continue;
            }
            visited.add(way);
            for (Node node : wayJoins.getOrDefault(way, new LinkedHashSet<>())) {
                for (Way neighbor : joins.get(node)) {
                    if (!component.contains(neighbor)) {
                        pending.add(neighbor);
                    }
                }
            }
        }
        return component;
    }

    private static CombinedWay buildCombinedWay(Set<Way> component, Map<Way, Set<Node>> wayJoins,
            Map<Node, List<Way>> joins) {
        Way startWay = null;
        Node entry = null;
        for (Way way : component) {
            if (wayJoins.get(way).size() == 1) {
                Node joinNode = wayJoins.get(way).iterator().next();
                startWay = way;
                entry = way.getNode(0) == joinNode
                        ? way.getNode(way.getNodesCount() - 1) : way.getNode(0);
                break;
            }
        }
        if (startWay == null) {
            return null;
        }

        Way current = startWay;
        Set<Way> ordered = new LinkedHashSet<>();
        List<Node> path = new ArrayList<>();
        while (current != null && ordered.add(current)) {
            List<Node> currentNodes = current.getNodes();
            boolean forward = currentNodes.get(0) == entry;
            boolean reverse = currentNodes.get(currentNodes.size() - 1) == entry;
            if (!forward && !reverse) {
                return null;
            }
            if (reverse) {
                currentNodes = new ArrayList<>(currentNodes);
                Collections.reverse(currentNodes);
            }
            if (path.isEmpty()) {
                path.addAll(currentNodes);
            } else {
                path.addAll(currentNodes.subList(1, currentNodes.size()));
            }

            Node exit = currentNodes.get(currentNodes.size() - 1);
            Way next = null;
            for (Way candidate : joins.getOrDefault(exit, new ArrayList<>())) {
                if (component.contains(candidate) && !ordered.contains(candidate)) {
                    next = candidate;
                    break;
                }
            }
            entry = exit;
            current = next;
        }
        if (ordered.size() != component.size()) {
            return null;
        }
        Way target = ordered.stream().filter(way -> !way.isNew()).findFirst()
                .orElse(ordered.iterator().next());
        Set<Way> absorbed = new LinkedHashSet<>(ordered);
        absorbed.remove(target);
        return new CombinedWay(target, absorbed, ordered, path);
    }

    private static List<Node> simplifyNodes(Way context, List<Node> nodes, Set<Way> ignoredReferrers,
            double threshold) {
        if (nodes.size() < 3) {
            return nodes;
        }
        Set<Node> repeated = repeatedNodes(nodes);
        List<Integer> anchors = new ArrayList<>();
        anchors.add(0);
        for (int i = 1; i < nodes.size() - 1; i++) {
            if (isProtected(context, nodes.get(i), repeated, ignoredReferrers)) {
                anchors.add(i);
            }
        }
        anchors.add(nodes.size() - 1);
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < anchors.size() - 1; i++) {
            appendSimplified(nodes, anchors.get(i), anchors.get(i + 1), threshold, result);
        }
        return result;
    }

    private static final class CombinedWay {
        private final Way target;
        private final Set<Way> absorbed;
        private final Set<Way> ways;
        private final List<Node> path;

        CombinedWay(Way target, Set<Way> absorbed, Set<Way> ways, List<Node> path) {
            this.target = target;
            this.absorbed = absorbed;
            this.ways = ways;
            this.path = path;
        }
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
            if (isProtected(way, node, repeated, Collections.emptySet())) {
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

    private static boolean isProtected(Way way, Node node, Set<Node> repeated,
            Set<Way> ignoredReferrers) {
        if (node.isTagged() || repeated.contains(node)) {
            return true;
        }
        List<OsmPrimitive> referrers = new ArrayList<>(node.getReferrers());
        referrers.remove(way);
        referrers.removeAll(ignoredReferrers);
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
