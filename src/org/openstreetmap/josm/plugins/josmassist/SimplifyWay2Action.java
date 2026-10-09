package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Ellipsoid;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.ExtendedDialog;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IPreferences;
import org.openstreetmap.josm.tools.GBC;
import org.openstreetmap.josm.tools.Geometry;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Simplifies ways while allowing removable nodes immediately next to protected
 * shared nodes to be considered by Douglas-Peucker.
 */
public class SimplifyWay2Action extends JosmAction {

    private static final String PREF_MAX_ERROR = "josmassist.simplify-way2.max-error";
    private static final String PREF_CORNER_ERROR = "josmassist.simplify-way2.corner-error";
    private static final String PREF_REMEMBER = "josmassist.simplify-way2.remember";
    private static final double DEFAULT_ERROR = 3.0;

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

        List<Way> ways = resolveInputWays(dataSet, dataSet.getSelectedWays());
        if (ways.isEmpty()) {
            return;
        }

        SimplifyParameters parameters = askSimplifyParameters();
        if (parameters == null) {
            return;
        }

        Command command = createCommand(ways, parameters.simplificationError, parameters.cornerError);
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
    }

    /** Resolves the ways covered by the action without changing the selection. */
    static List<Way> resolveInputWays(DataSet dataSet, Collection<Way> selectedWays) {
        LinkedHashSet<Way> selected = selectedWays.stream()
                .filter(way -> isUsableWay(dataSet, way))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (selected.stream().anyMatch(SimplifyWay2Action::isServiceWay)) {
            return new ArrayList<>(selected);
        }

        LinkedHashSet<Way> resolved = collectServiceWays(dataSet);
        if (resolved.isEmpty()) {
            return Collections.emptyList();
        }
        Deque<Way> pending = new ArrayDeque<>(resolved);
        while (!pending.isEmpty()) {
            Way current = pending.removeFirst();
            for (Node node : current.getNodes()) {
                for (Way connected : node.getParentWays()) {
                    if (isUsableWay(dataSet, connected) && resolved.add(connected)) {
                        pending.addLast(connected);
                    }
                }
            }
        }
        return new ArrayList<>(resolved);
    }

    private static LinkedHashSet<Way> collectServiceWays(DataSet dataSet) {
        return dataSet.getWays().stream()
                .filter(way -> isUsableWay(dataSet, way) && isServiceWay(way))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean isServiceWay(Way way) {
        return "service".equals(way.get("highway"));
    }

    private static boolean isUsableWay(DataSet dataSet, Way way) {
        return way != null && way.getDataSet() == dataSet && !way.isIncomplete() && !way.isDeleted();
    }

    /**
     * Creates the undoable command for the supplied ways.
     *
     * @param ways ways to simplify
     * @param threshold maximum error in metres
     * @return command, or {@code null} if no node can be removed
     */
    static Command createCommand(Collection<Way> ways, double threshold) {
        return createCommand(ways, threshold, 0);
    }

    static Command createCommand(Collection<Way> ways, double threshold, double cornerTolerance) {
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
        Set<Node> addedNodes = new LinkedHashSet<>();

        for (CombinedWay combined : combinedWays) {
            SimplificationResult result = simplifyNodes(combined.target, combined.path, combined.ways,
                    threshold, cornerTolerance);
            List<Node> simplified = result.nodes;
            addedNodes.addAll(result.addedNodes);
            addWayChange(combined.target, combined.target.getNodes(), combined.path, simplified,
                    commands, deletedNodes);
            changedWays.add(combined.target);
        }
        for (Way way : selectedWays) {
            if (absorbedWays.contains(way) || changedWays.contains(way)) {
                continue;
            }
            SimplificationResult result = simplifyWayWithCorners(way, threshold, cornerTolerance);
            addedNodes.addAll(result.addedNodes);
            addWayChange(way, way.getNodes(), way.getNodes(), result.nodes, commands, deletedNodes);
        }
        if (commands.isEmpty()) {
            return null;
        }
        if (!addedNodes.isEmpty()) {
            DataSet dataSet = selectedWays.get(0).getDataSet();
            List<Command> additions = new ArrayList<>();
            for (Node node : addedNodes) {
                additions.add(new AddCommand(dataSet, node));
            }
            commands.addAll(0, additions);
        }
        Set<OsmPrimitive> deletions = new LinkedHashSet<>(absorbedWays);
        deletions.addAll(deletedNodes);
        if (!deletions.isEmpty()) {
            commands.add(new DeleteCommand(selectedWays.get(0).getDataSet(), deletions));
        }
        return new SequenceCommand(tr("Simplify Way 2"), commands);
    }

    static SimplifyParameters askSimplifyParameters() {
        IPreferences preferences = Config.getPref();
        if ("yes".equals(preferences.get(PREF_REMEMBER, "ask"))) {
            return new SimplifyParameters(preferences.getDouble(PREF_MAX_ERROR, DEFAULT_ERROR),
                    preferences.getDouble(PREF_CORNER_ERROR, DEFAULT_ERROR));
        }

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        SpinnerNumberModel simplifyModel = new SpinnerNumberModel(
                preferences.getDouble(PREF_MAX_ERROR, DEFAULT_ERROR), 0.01, null, 0.5);
        SpinnerNumberModel cornerModel = new SpinnerNumberModel(
                preferences.getDouble(PREF_CORNER_ERROR, DEFAULT_ERROR), 0.01, null, 0.5);
        JSpinner simplifySpinner = new JSpinner(simplifyModel);
        JSpinner cornerSpinner = new JSpinner(cornerModel);
        ((JSpinner.DefaultEditor) simplifySpinner.getEditor()).getTextField().setColumns(5);
        ((JSpinner.DefaultEditor) cornerSpinner.getEditor()).getTextField().setColumns(5);
        panel.add(new JLabel(tr("Maximum simplification error (meters):")), GBC.std());
        panel.add(simplifySpinner, GBC.eol());
        panel.add(new JLabel(tr("Rounded corner fitting error (meters):")), GBC.std());
        panel.add(cornerSpinner, GBC.eol());
        JCheckBox remember = new JCheckBox(tr("Do not ask again"));
        panel.add(remember, GBC.eol());

        ExtendedDialog dialog = new ExtendedDialog(MainApplication.getMainFrame(),
                tr("Simplify Way 2"), tr("Simplify"), tr("Cancel"))
                .setContent(panel)
                .setButtonIcons("simplify", "cancel");
        if (dialog.showDialog().getValue() != 1) {
            return null;
        }
        double simplificationError = simplifyModel.getNumber().doubleValue();
        double cornerError = cornerModel.getNumber().doubleValue();
        preferences.putDouble(PREF_MAX_ERROR, simplificationError);
        preferences.putDouble(PREF_CORNER_ERROR, cornerError);
        preferences.put(PREF_REMEMBER, remember.isSelected() ? "yes" : "ask");
        return new SimplifyParameters(simplificationError, cornerError);
    }

    static final class SimplifyParameters {
        final double simplificationError;
        final double cornerError;

        SimplifyParameters(double simplificationError, double cornerError) {
            this.simplificationError = simplificationError;
            this.cornerError = cornerError;
        }
    }

    private static void addWayChange(Way way, List<Node> current, List<Node> deletionSource,
            List<Node> simplified, List<Command> commands, Set<Node> deletedNodes) {
        if (simplified.equals(current)) {
            return;
        }
        Set<Node> removed = new HashSet<>(deletionSource);
        removed.removeAll(simplified);
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

    private static SimplificationResult simplifyNodes(Way context, List<Node> nodes,
            Set<Way> ignoredReferrers, double threshold, double cornerTolerance) {
        if (nodes.size() < 3) {
            return new SimplificationResult(nodes, Collections.emptySet());
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
        Set<Node> addedNodes = new LinkedHashSet<>();
        for (int i = 0; i < anchors.size() - 1; i++) {
            int from = anchors.get(i);
            int to = anchors.get(i + 1);
            CornerResult corner = cornerTolerance > 0
                    ? findRoundedCorner(nodes, from, to, cornerTolerance) : null;
            if (corner != null) {
                appendNode(result, nodes.get(from));
                appendNode(result, corner.node);
                appendNode(result, nodes.get(to));
                addedNodes.add(corner.node);
            } else {
                appendSimplified(nodes, from, to, threshold, result);
            }
        }
        return new SimplificationResult(result, addedNodes);
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

    static final class SimplificationResult {
        final List<Node> nodes;
        final Set<Node> addedNodes;

        SimplificationResult(List<Node> nodes, Set<Node> addedNodes) {
            this.nodes = nodes;
            this.addedNodes = addedNodes;
        }
    }

    private static final class CornerResult {
        private final Node node;

        CornerResult(Node node) {
            this.node = node;
        }
    }

    /** Simplifies one way while preserving all protected nodes and closure. */
    static List<Node> simplifyWay(Way way, double threshold) {
        return simplifyWayWithCorners(way, threshold, 0).nodes;
    }

    static SimplificationResult simplifyWayWithCorners(Way way, double threshold,
            double cornerTolerance) {
        List<Node> nodes = way.getNodes();
        if (nodes.size() < 3) {
            return new SimplificationResult(nodes, Collections.emptySet());
        }

        boolean closed = way.isClosed();
        int last = closed ? nodes.size() - 2 : nodes.size() - 1;
        List<Node> working = new ArrayList<>(nodes.subList(0, last + 1));
        SimplificationResult simplified = simplifyNodes(way, working, Collections.emptySet(),
                threshold, cornerTolerance);
        List<Node> result = new ArrayList<>(simplified.nodes);
        if (closed) {
            result.add(result.get(0));
        }
        return new SimplificationResult(result, simplified.addedNodes);
    }

    private static void appendNode(List<Node> output, Node node) {
        if (output.isEmpty() || output.get(output.size() - 1) != node) {
            output.add(node);
        }
    }

    private static CornerResult findRoundedCorner(List<Node> nodes, int from, int to,
            double tolerance) {
        if (to - from < 3) {
            return null;
        }
        CornerResult best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int split = from + 1; split < to; split++) {
            FitLine first = fitLine(nodes, from, split);
            FitLine second = fitLine(nodes, split, to);
            if (first == null || second == null) {
                continue;
            }
            double turn = Math.acos(Math.max(-1, Math.min(1, dot(first.direction, second.direction))));
            turn = Math.min(turn, Math.PI - turn);
            if (turn < Math.toRadians(30)) {
                continue;
            }
            EastNorth intersection = Geometry.getLineLineIntersection(first.start, first.end,
                    second.start, second.end);
            if (intersection == null || !nearSpan(nodes, from, to, intersection, tolerance)) {
                continue;
            }
            double residual = Math.max(first.residual, second.residual);
            if (residual > tolerance) {
                continue;
            }
            LatLon position = ProjectionRegistry.getProjection().eastNorth2latlon(intersection);
            Node candidate = new Node(position);
            double score = residual + intersection.distance(nodes.get(split).getEastNorth()) * 1e-4;
            if (score < bestScore) {
                bestScore = score;
                best = new CornerResult(candidate);
            }
        }
        return best;
    }

    private static boolean nearSpan(List<Node> nodes, int from, int to, EastNorth point,
            double tolerance) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = from; i <= to; i++) {
            EastNorth en = nodes.get(i).getEastNorth();
            minX = Math.min(minX, en.east());
            minY = Math.min(minY, en.north());
            maxX = Math.max(maxX, en.east());
            maxY = Math.max(maxY, en.north());
        }
        double margin = Math.max(10, tolerance * 4);
        return point.east() >= minX - margin && point.east() <= maxX + margin
                && point.north() >= minY - margin && point.north() <= maxY + margin;
    }

    private static FitLine fitLine(List<Node> nodes, int from, int to) {
        if (to <= from) {
            return null;
        }
        double meanX = 0;
        double meanY = 0;
        int count = to - from + 1;
        for (int i = from; i <= to; i++) {
            EastNorth point = nodes.get(i).getEastNorth();
            meanX += point.east();
            meanY += point.north();
        }
        meanX /= count;
        meanY /= count;
        double xx = 0;
        double yy = 0;
        double xy = 0;
        for (int i = from; i <= to; i++) {
            EastNorth point = nodes.get(i).getEastNorth();
            double x = point.east() - meanX;
            double y = point.north() - meanY;
            xx += x * x;
            yy += y * y;
            xy += x * y;
        }
        double angle = 0.5 * Math.atan2(2 * xy, xx - yy);
        double dx = Math.cos(angle);
        double dy = Math.sin(angle);
        if (xx + yy < 1e-12) {
            return null;
        }
        if (dx * (nodes.get(to).getEastNorth().east() - nodes.get(from).getEastNorth().east())
                + dy * (nodes.get(to).getEastNorth().north() - nodes.get(from).getEastNorth().north()) < 0) {
            dx = -dx;
            dy = -dy;
        }
        EastNorth center = new EastNorth(meanX, meanY);
        EastNorth start = new EastNorth(meanX - dx * 1_000_000, meanY - dy * 1_000_000);
        EastNorth end = new EastNorth(meanX + dx * 1_000_000, meanY + dy * 1_000_000);
        double residual = 0;
        for (int i = from; i <= to; i++) {
            residual = Math.max(residual, perpendicularDistance(center, dx, dy,
                    nodes.get(i).getEastNorth()));
        }
        return new FitLine(start, end, new EastNorth(dx, dy), residual);
    }

    private static double perpendicularDistance(EastNorth center, double dx, double dy,
            EastNorth point) {
        return Math.abs((point.east() - center.east()) * dy
                - (point.north() - center.north()) * dx);
    }

    private static double dot(EastNorth first, EastNorth second) {
        return first.east() * second.east() + first.north() * second.north();
    }

    private static final class FitLine {
        private final EastNorth start;
        private final EastNorth end;
        private final EastNorth direction;
        private final double residual;

        FitLine(EastNorth start, EastNorth end, EastNorth direction, double residual) {
            this.start = start;
            this.end = end;
            this.direction = direction;
            this.residual = residual;
        }
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
