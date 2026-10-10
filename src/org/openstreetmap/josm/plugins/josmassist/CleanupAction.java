package org.openstreetmap.josm.plugins.josmassist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;

/** Builds cleanup commands for qualifying named area ways. */
public final class CleanupAction {

    private CleanupAction() {
        // Utility class.
    }

    /** Creates the area cleanup command, or {@code null}. */
    static DeleteCommand createAreasCommand(DataSet dataSet) {
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
}
