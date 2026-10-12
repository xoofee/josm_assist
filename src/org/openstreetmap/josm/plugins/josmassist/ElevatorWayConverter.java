package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/** Converts elevator and steps primitives into inspectable elevator nodes. */
final class ElevatorWayConverter {

    private ElevatorWayConverter() {
        // Utility class.
    }

    /** Creates the conversion command, or {@code null}. */
    static Command createCommand(DataSet dataSet) {
        if (dataSet == null) {
            return null;
        }

        List<Command> commands = new ArrayList<>();
        for (Node node : dataSet.getNodes()) {
            if (isConvertible(node)) {
                commands.add(new ChangePropertyCommand(Collections.singleton(node),
                        "highway", "elevator"));
            }
        }
        for (Way way : dataSet.getWays()) {
            if (!isConvertible(way)) {
                continue;
            }
            Node elevator = new Node(averageWayCenter(way));
            elevator.put("highway", "elevator");
            copyNonEmptyTag(way, elevator, "level");
            copyNonEmptyTag(way, elevator, "level:ref");
            copyNonEmptyTag(way, elevator, "name");

            commands.add(new AddCommand(dataSet, elevator));
            if (way.hasKey("highway")) {
                commands.add(new ChangePropertyCommand(Collections.singleton(way), "highway", null));
            }
            if (way.hasKey("indoor")) {
                commands.add(new ChangePropertyCommand(Collections.singleton(way), "indoor", null));
            }
        }
        return commands.isEmpty() ? null
                : new SequenceCommand(tr("Convert elevator ways and steps nodes"), commands);
    }

    private static boolean isConvertible(Node node) {
        return node != null && !node.isDeleted() && !node.isIncomplete()
                && node.isLatLonKnown() && "steps".equals(node.get("highway"));
    }

    private static boolean isConvertible(Way way) {
        return way != null && !way.isDeleted() && !way.isIncomplete() && way.isClosed()
                && ("elevator".equals(way.get("highway")) || "steps".equals(way.get("highway")))
                && averageWayCenter(way) != null;
    }

    private static LatLon averageWayCenter(Way way) {
        Set<Node> distinctNodes = new LinkedHashSet<>(way.getNodes());
        distinctNodes.remove(null);
        if (distinctNodes.isEmpty() || distinctNodes.stream().anyMatch(node -> !node.isLatLonKnown())) {
            return null;
        }
        double lat = 0;
        double lon = 0;
        for (Node node : distinctNodes) {
            lat += node.lat();
            lon += node.lon();
        }
        return new LatLon(lat / distinctNodes.size(), lon / distinctNodes.size());
    }

    private static void copyNonEmptyTag(Way source, Node target, String key) {
        String value = source.get(key);
        if (value != null && !value.trim().isEmpty()) {
            target.put(key, value);
        }
    }
}
