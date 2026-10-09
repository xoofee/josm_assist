package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangeMembersCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/** Removes unwanted area ways and merges nearby elevator nodes. */
public class CleanupAction extends JosmAction {

    private static final double INITIAL_DISTANCE_METERS = 2.0;
    private static double lastDistanceMeters = INITIAL_DISTANCE_METERS;

    /** Constructs the cleanup action. */
    public CleanupAction() {
        super(tr("Clean Up Areas and Elevators"),
                new ImageProvider("dialogs/delete").setOptional(true)
                        .setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Remove named area ways and merge nearby elevator nodes"),
                null, true, "josmassist-cleanup", false);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null || dataSet.isLocked()) {
            return;
        }

        String value = JOptionPane.showInputDialog(MainApplication.getMainFrame(),
                tr("Merge elevator nodes within this distance (meters):"),
                Double.toString(lastDistanceMeters));
        if (value == null) {
            return;
        }

        final double distance;
        try {
            distance = Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return;
        }
        if (!Double.isFinite(distance) || distance <= 0) {
            return;
        }
        lastDistanceMeters = distance;

        Command command = createCommand(dataSet, distance);
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
    }

    /** Creates the area cleanup command, or {@code null}. */
    static Command createAreasCommand(DataSet dataSet) {
        if (dataSet == null) {
            return null;
        }

        List<Way> waysToDelete = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (!way.isDeleted() && !way.isIncomplete() && way.isClosed()
                    && way.getNodesCount() >= 5 && way.get("name") != null
                    && way.get("name").contains("区")) {
                waysToDelete.add(way);
            }
        }
        return waysToDelete.isEmpty() ? null
                : new DeleteCommand(dataSet, collectAreaPrimitivesToDelete(dataSet, waysToDelete));
    }

    private static Collection<OsmPrimitive> collectAreaPrimitivesToDelete(DataSet dataSet,
            List<Way> waysToDelete) {
        Set<Way> areasToDelete = new HashSet<>(waysToDelete);
        Set<OsmPrimitive> primitivesToDelete = new LinkedHashSet<>(waysToDelete);
        Set<Node> candidateNodes = new LinkedHashSet<>();
        for (Way way : waysToDelete) {
            candidateNodes.addAll(way.getNodes());
        }
        for (Node node : candidateNodes) {
            if (node == null || node.getDataSet() != dataSet || node.isDeleted()
                    || node.isIncomplete() || node.isTagged()) {
                continue;
            }
            boolean hasExternalReferrer = node.getReferrers().stream()
                    .anyMatch(referrer -> !areasToDelete.contains(referrer));
            if (!hasExternalReferrer) {
                primitivesToDelete.add(node);
            }
        }
        return primitivesToDelete;
    }

    /** Creates the nearby elevator merge command, or {@code null}. */
    static Command createElevatorCommand(DataSet dataSet, double distanceMeters) {
        if (dataSet == null || !Double.isFinite(distanceMeters) || distanceMeters <= 0) {
            return null;
        }

        List<Node> elevators = new ArrayList<>();
        for (Node node : dataSet.getNodes()) {
            if (!node.isDeleted() && !node.isIncomplete() && "elevator".equals(node.get("highway"))
                    && node.isLatLonKnown()) {
                elevators.add(node);
            }
        }

        List<Command> commands = new ArrayList<>();
        for (List<Node> group : proximityGroups(elevators, distanceMeters)) {
            if (group.size() < 2) {
                continue;
            }
            Node target = selectTargetNode(group);
            LatLon center = averageCenter(group);
            String combinedLevel = combinedTag(group, "level", true);
            String combinedLevelref = combinedTag(group, "levelref", false);

            Command merge = createNonInteractiveMerge(group, target, center, combinedLevel, combinedLevelref);
            if (merge != null) {
                commands.add(merge);
            }
        }
        return commands.isEmpty() ? null : new SequenceCommand(tr("Merge nearby elevator nodes"), commands);
    }

    /** Creates the legacy combined cleanup command for existing callers and tests. */
    static Command createCommand(DataSet dataSet, double distanceMeters) {
        List<Command> commands = new ArrayList<>();
        Command elevatorCommand = createElevatorCommand(dataSet, distanceMeters);
        if (elevatorCommand != null) {
            commands.add(elevatorCommand);
        }
        Command areaCommand = createAreasCommand(dataSet);
        if (areaCommand != null) {
            commands.add(areaCommand);
        }
        return commands.isEmpty() ? null : new SequenceCommand(tr("Clean up areas and elevators"), commands);
    }

    private static Command createNonInteractiveMerge(List<Node> nodes, Node target, LatLon center,
            String combinedLevel, String combinedLevelref) {
        Set<Node> nodesToDelete = new HashSet<>(nodes);
        nodesToDelete.remove(target);
        List<Command> commands = new ArrayList<>();

        Set<Way> parentWays = new HashSet<>();
        Set<Relation> parentRelations = new HashSet<>();
        for (Node node : nodesToDelete) {
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (referrer instanceof Way) {
                    parentWays.add((Way) referrer);
                } else if (referrer instanceof Relation) {
                    parentRelations.add((Relation) referrer);
                }
            }
        }
        for (Way way : parentWays) {
            List<Node> replacement = new ArrayList<>();
            for (Node node : way.getNodes()) {
                Node replacementNode = nodesToDelete.contains(node) ? target : node;
                if (replacement.isEmpty() || replacement.get(replacement.size() - 1) != replacementNode) {
                    replacement.add(replacementNode);
                }
            }
            if (replacement.size() >= 2) {
                commands.add(new org.openstreetmap.josm.command.ChangeNodesCommand(way, replacement));
            }
        }
        for (Relation relation : parentRelations) {
            List<RelationMember> replacement = new ArrayList<>();
            for (RelationMember member : relation.getMembers()) {
                OsmPrimitive memberPrimitive = member.getMember();
                if (nodesToDelete.contains(memberPrimitive)) {
                    replacement.add(new RelationMember(member.getRole(), target));
                } else {
                    replacement.add(member);
                }
            }
            commands.add(new ChangeMembersCommand(relation, replacement));
        }

        Map<String, String> tags = mergedTags(orderByParentWay(nodes), combinedLevel, combinedLevelref);
        if (!tags.isEmpty()) {
            commands.add(new ChangePropertyCommand(Collections.singleton(target), tags));
        }
        if (!target.getCoor().equals(center)) {
            commands.add(new MoveCommand(target, center));
        }
        if (!nodesToDelete.isEmpty()) {
            commands.add(new DeleteCommand(nodesToDelete));
        }
        return commands.isEmpty() ? null : new SequenceCommand(tr("Merge nearby elevator nodes"), commands);
    }

    private static Map<String, String> mergedTags(List<Node> nodes, String combinedLevel,
            String combinedLevelref) {
        Map<String, String> tags = new HashMap<>();
        for (Node node : nodes) {
            for (String key : node.keySet()) {
                String value = node.get(key);
                if (value != null && !value.isEmpty() && !tags.containsKey(key)) {
                    tags.put(key, value);
                }
            }
        }
        if (combinedLevel != null) {
            tags.put("level", combinedLevel);
        }
        if (combinedLevelref != null) {
            tags.put("levelref", combinedLevelref);
        }
        return tags;
    }

    private static Node selectTargetNode(List<Node> nodes) {
        // DataSet iteration order is not way order. Preserve a stable member
        // from a common parent way when possible.
        List<Node> orderedNodes = orderByParentWay(nodes);
        for (Node node : orderedNodes) {
            if (!node.isNew() && !node.getReferrers().isEmpty()) {
                return node;
            }
        }
        for (Node node : orderedNodes) {
            if (!node.isNew()) {
                return node;
            }
        }
        return orderedNodes.get(0);
    }

    private static List<Node> orderByParentWay(List<Node> nodes) {
        if (nodes.size() < 2) {
            return nodes;
        }
        for (OsmPrimitive referrer : nodes.get(0).getReferrers()) {
            if (!(referrer instanceof Way)) {
                continue;
            }
            List<Node> wayNodes = ((Way) referrer).getNodes();
            if (nodes.stream().allMatch(wayNodes::contains)) {
                List<Node> ordered = new ArrayList<>(nodes);
                ordered.sort(Comparator.comparingInt(wayNodes::indexOf));
                return ordered;
            }
        }
        return nodes;
    }

    private static String combinedTag(List<Node> nodes, String key, boolean numericSort) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Node node : nodes) {
            String value = node.get(key);
            if (value != null) {
                for (String part : value.split(";")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        values.add(trimmed);
                    }
                }
            }
        }
        if (numericSort) {
            List<String> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.comparingDouble(CleanupAction::numericLevel)
                    .thenComparing(String::compareTo));
            values = new LinkedHashSet<>(sorted);
        }
        return values.isEmpty() ? null : String.join(";", values);
    }

    private static double numericLevel(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static LatLon averageCenter(Collection<Node> nodes) {
        double lat = 0;
        double lon = 0;
        for (Node node : nodes) {
            lat += node.lat();
            lon += node.lon();
        }
        return new LatLon(lat / nodes.size(), lon / nodes.size());
    }

    private static List<List<Node>> proximityGroups(List<Node> nodes, double distanceMeters) {
        Map<Node, Set<Node>> neighbors = new HashMap<>();
        for (Node node : nodes) {
            neighbors.put(node, new HashSet<>());
        }
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                Node first = nodes.get(i);
                Node second = nodes.get(j);
                if (first.getCoor().greatCircleDistance(second.getCoor()) <= distanceMeters) {
                    neighbors.get(first).add(second);
                    neighbors.get(second).add(first);
                }
            }
        }

        List<List<Node>> groups = new ArrayList<>();
        Set<Node> visited = new HashSet<>();
        for (Node start : nodes) {
            if (!visited.add(start)) {
                continue;
            }
            List<Node> group = new ArrayList<>();
            ArrayDeque<Node> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Node current = queue.removeFirst();
                group.add(current);
                for (Node neighbor : neighbors.get(current)) {
                    if (visited.add(neighbor)) {
                        queue.addLast(neighbor);
                    }
                }
            }
            group.sort(Comparator.comparingInt(nodes::indexOf));
            groups.add(group);
        }
        return groups;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null
                && !MainApplication.getLayerManager().getEditDataSet().isLocked());
    }
}
