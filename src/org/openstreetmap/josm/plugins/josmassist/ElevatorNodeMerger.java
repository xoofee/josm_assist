package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.ChangeMembersCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/** Merges nearby elevator nodes and combines their tags. */
final class ElevatorNodeMerger {

    private ElevatorNodeMerger() {
        // Utility class.
    }

    /** Creates the merge command for existing elevator nodes, or {@code null}. */
    static Command createCommand(DataSet dataSet, double distanceMeters) {
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
            List<Node> tagOrder = orderByParentWay(group);
            String combinedLevel = combinedTag(tagOrder, "level", true);
            String combinedLevelRef = combinedTag(tagOrder, "level:ref", false);
            String combinedName = combinedNames(tagOrder);
            Command merge = createMerge(dataSet, group, target, averageCenter(group), combinedLevel,
                    combinedLevelRef, combinedName, tagOrder);
            if (merge != null) {
                commands.add(merge);
            }
        }
        return commands.isEmpty() ? null : new SequenceCommand(tr("Merge nearby elevator nodes"), commands);
    }

    private static Command createMerge(DataSet dataSet, List<Node> nodes, Node target, LatLon center,
            String combinedLevel, String combinedLevelRef, String combinedName, List<Node> tagOrder) {
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
                commands.add(new ChangeNodesCommand(way, replacement));
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

        Map<String, String> tags = mergedTags(tagOrder, combinedLevel, combinedLevelRef, combinedName);
        if (!tags.isEmpty()) {
            commands.add(new ChangePropertyCommand(dataSet, java.util.Collections.singleton(target), tags));
        }
        if (!target.getCoor().equals(center)) {
            commands.add(new MoveCommand(dataSet, target, target.getEastNorth(),
                    ProjectionRegistry.getProjection().latlon2eastNorth(center)));
        }
        if (!nodesToDelete.isEmpty()) {
            commands.add(new DeleteCommand(dataSet, nodesToDelete));
        }
        return commands.isEmpty() ? null : new SequenceCommand(tr("Merge nearby elevator nodes"), commands);
    }

    private static Map<String, String> mergedTags(List<Node> nodes, String combinedLevel,
            String combinedLevelRef, String combinedName) {
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
        if (combinedLevelRef != null) {
            tags.put("level:ref", combinedLevelRef);
        }
        if (combinedName != null) {
            tags.put("name", combinedName);
        }
        return tags;
    }

    private static Node selectTargetNode(List<Node> nodes) {
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
            addTagParts(values, node.get(key));
        }
        if (numericSort) {
            List<String> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.comparingDouble(ElevatorNodeMerger::numericLevel)
                    .thenComparing(String::compareTo));
            values = new LinkedHashSet<>(sorted);
        }
        return values.isEmpty() ? null : String.join(";", values);
    }

    private static String combinedNames(List<Node> nodes) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Node node : nodes) {
            addTagParts(names, node.get("name"));
        }
        return names.isEmpty() ? null : String.join(";", names);
    }

    private static void addTagParts(Set<String> values, String value) {
        if (value == null) {
            return;
        }
        for (String part : value.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
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
}
