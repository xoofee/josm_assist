package org.openstreetmap.josm.plugins.josmassist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/** Connects level-specific elevator nodes to the nearest service-road graph. */
public final class ElevatorRoadConnector {

    private static final double ENDPOINT_EPSILON = 1e-7;

    private ElevatorRoadConnector() {
        // Utility class.
    }

    /**
     * Builds one undoable command which connects the supplied elevator nodes.
     * An elevator is a node tagged {@code highway=elevator}; its {@code level}
     * value may contain several levels separated by ';' or ','.
     *
     * @param dataSet data set containing the nodes and service ways
     * @param elevators nodes to connect
     * @return a command, or {@code null} when there is nothing to connect
     */
    public static Command createCommand(DataSet dataSet, Collection<Node> elevators) {
        if (dataSet == null || elevators == null || elevators.isEmpty()) {
            return null;
        }

        List<Command> commands = new ArrayList<>();
        Map<Way, Way> changedWays = new HashMap<>();
        Set<Way> serviceWays = new HashSet<>(dataSet.getWays());

        for (Node elevator : elevators) {
            if (!isElevator(elevator) || elevator.getDataSet() != dataSet) {
                continue;
            }

            Set<String> levels = levelsOf(elevator.get("level"));
            if (levels.isEmpty()) {
                levels = Collections.singleton("");
            }

            for (String level : levels) {
                if (hasExistingConnector(elevator, level)) {
                    continue;
                }

                Projection projection = findNearestServiceProjection(elevator, level, serviceWays);
                if (projection == null) {
                    continue;
                }

                Node roadNode = projection.node;
                if (roadNode == null) {
                    roadNode = new Node(projection.position);
                    commands.add(new AddCommand(dataSet, roadNode));

                    Way changedWay = changedWays.get(projection.way);
                    if (changedWay == null) {
                        changedWay = new Way(projection.way);
                        changedWays.put(projection.way, changedWay);
                    }
                    List<Node> wayNodes = new ArrayList<>(changedWay.getNodes());
                    wayNodes.add(projection.insertIndex, roadNode);
                    changedWay.setNodes(wayNodes);
                }

                Way connector = new Way();
                connector.put("highway", "service");
                if (!level.isEmpty()) {
                    connector.put("level", level);
                }
                connector.setNodes(java.util.Arrays.asList(elevator, roadNode));
                commands.add(new AddCommand(dataSet, connector));
            }
        }

        for (Map.Entry<Way, Way> entry : changedWays.entrySet()) {
            commands.add(new ChangeCommand(entry.getKey(), entry.getValue()));
        }

        return commands.isEmpty() ? null : new SequenceCommand("Connect elevators to service roads", commands);
    }

    private static boolean isElevator(Node node) {
        return node != null && "elevator".equals(node.get("highway"));
    }

    private static Set<String> levelsOf(String value) {
        Set<String> levels = new HashSet<>();
        if (value == null) {
            return levels;
        }
        for (String level : value.split("[;,]")) {
            String trimmed = level.trim();
            if (!trimmed.isEmpty()) {
                levels.add(trimmed);
            }
        }
        return levels;
    }

    private static boolean hasExistingConnector(Node elevator, String level) {
        for (Way way : elevator.getParentWays()) {
            if (way.getNodesCount() < 2) {
                continue;
            }
            String wayLevel = way.get("level");
            if (wayLevel != null && !wayLevel.trim().isEmpty() && levelsOf(wayLevel).contains(level)) {
                return true;
            }
        }
        return false;
    }

    private static Projection findNearestServiceProjection(Node elevator, String level,
            Collection<Way> serviceWays) {
        EastNorth elevatorPosition = elevator.getEastNorth(ProjectionRegistry.getProjection());
        Projection nearest = null;
        for (Way way : serviceWays) {
            if (!"service".equals(way.get("highway")) || !matchesLevel(way.get("level"), level)
                    || way.hasIncompleteNodes()) {
                continue;
            }
            List<Node> nodes = way.getNodes();
            for (int i = 0; i + 1 < nodes.size(); i++) {
                Node first = nodes.get(i);
                Node second = nodes.get(i + 1);
                EastNorth a = first.getEastNorth(ProjectionRegistry.getProjection());
                EastNorth b = second.getEastNorth(ProjectionRegistry.getProjection());
                EastNorth projected = project(elevatorPosition, a, b);
                double distance = elevatorPosition.distance(projected);
                if (nearest == null || distance < nearest.distance) {
                    Node endpoint = null;
                    int insertIndex = i + 1;
                    if (projected.distance(a) <= ENDPOINT_EPSILON) {
                        endpoint = first;
                    } else if (projected.distance(b) <= ENDPOINT_EPSILON) {
                        endpoint = second;
                    }
                    nearest = new Projection(way, projected, distance, endpoint, insertIndex);
                }
            }
        }
        return nearest;
    }

    private static boolean matchesLevel(String wayLevel, String requestedLevel) {
        if (requestedLevel.isEmpty()) {
            return wayLevel == null || wayLevel.trim().isEmpty();
        }
        return levelsOf(wayLevel).contains(requestedLevel);
    }

    private static EastNorth project(EastNorth point, EastNorth start, EastNorth end) {
        double dx = end.east() - start.east();
        double dy = end.north() - start.north();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return start;
        }
        double t = ((point.east() - start.east()) * dx + (point.north() - start.north()) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return new EastNorth(start.east() + t * dx, start.north() + t * dy);
    }

    private static final class Projection {
        private final Way way;
        private final EastNorth position;
        private final double distance;
        private final Node node;
        private final int insertIndex;

        private Projection(Way way, EastNorth position, double distance, Node node, int insertIndex) {
            this.way = way;
            this.position = position;
            this.distance = distance;
            this.node = node;
            this.insertIndex = insertIndex;
        }
    }
}
