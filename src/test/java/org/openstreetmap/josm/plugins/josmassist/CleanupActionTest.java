package org.openstreetmap.josm.plugins.josmassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Tests for named-area cleanup and elevator merging. */
public class CleanupActionTest {

    @BeforeClass
    public static void setUpProjection() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    public void deletesClosedNamedAreaWithFiveNodes() {
        DataSet dataSet = new DataSet();
        Way matching = way(dataSet, "商业区入口", closedNodes(5));
        Way fourNodes = way(dataSet, "商业区入口", closedNodes(4));
        Way unnamed = way(dataSet, null, closedNodes(5));
        Way open = way(dataSet, "商业区入口", openNodes(5));

        Command command = CleanupAction.createCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());
        assertTrue(matching.isDeleted());
        assertFalse(fourNodes.isDeleted());
        assertFalse(unnamed.isDeleted());
        assertFalse(open.isDeleted());

        command.undoCommand();
        assertFalse(matching.isDeleted());
    }

    @Test
    public void deletesOrphanAreaNodesAndUndoRestoresThem() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way matching = way(dataSet, "商业区入口", nodes);

        Command command = CleanupAction.createAreasCommand(dataSet);
        assertNotNull(command);
        assertTrue(command.executeCommand());
        assertTrue(matching.isDeleted());
        for (Node node : nodes) {
            assertTrue(node.isDeleted());
        }

        command.undoCommand();
        assertFalse(matching.isDeleted());
        for (Node node : nodes) {
            assertFalse(node.isDeleted());
        }
    }

    @Test
    public void preservesSharedTaggedAndRelationReferencedAreaNodes() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        nodes[1].put("amenity", "bench");
        Way matching = way(dataSet, "商业区入口", nodes);
        Node shared = nodes[2];
        way(dataSet, null, shared, new Node(new LatLon(1, 1)));
        Relation relation = new Relation();
        relation.addMember(new RelationMember("point", nodes[3]));
        dataSet.addPrimitive(relation);

        Command command = CleanupAction.createAreasCommand(dataSet);
        assertTrue(command.executeCommand());

        assertTrue(matching.isDeleted());
        assertTrue(nodes[0].isDeleted());
        assertFalse(nodes[1].isDeleted());
        assertFalse(nodes[2].isDeleted());
        assertFalse(nodes[3].isDeleted());
        assertTrue(nodes[4].isDeleted());
    }

    @Test
    public void returnsNullWhenThereAreNoQualifyingAreaWays() {
        DataSet dataSet = new DataSet();
        way(dataSet, "商业区入口", openNodes(5));
        way(dataSet, null, closedNodes(5));
        way(dataSet, "商业区入口", closedNodes(4));

        assertNull(CleanupAction.createAreasCommand(dataSet));
    }

    @Test
    public void mergesTransitiveElevatorsAndCombinesTags() {
        DataSet dataSet = new DataSet();
        Node first = elevator(dataSet, 0, 0, "-4", "L4");
        Node second = elevator(dataSet, 0, 0.00001, "-3", "L3");
        Node third = elevator(dataSet, 0, 0.00002, "-4;-3", "L3");
        double expectedLongitude = (first.lon() + second.lon() + third.lon()) / 3;

        Command command = CleanupAction.createCommand(dataSet, 1.2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        Node survivor = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).findFirst().orElse(null);
        assertNotNull(survivor);
        assertEquals("-4;-3", survivor.get("level"));
        assertTrue("L3;L4".equals(survivor.get("levelref"))
                || "L4;L3".equals(survivor.get("levelref")));
        assertEquals((first.lat() + second.lat() + third.lat()) / 3, survivor.lat(), 1e-12);
        assertEquals(expectedLongitude, survivor.lon(), 1e-12);
        assertEquals(1, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());

        command.undoCommand();
        assertFalse(first.isDeleted());
        assertFalse(second.isDeleted());
        assertFalse(third.isDeleted());
    }

    @Test
    public void doesNotMergeElevatorsOutsideThresholdOrWithoutCoordinates() {
        DataSet dataSet = new DataSet();
        Node first = elevator(dataSet, 0, 0, "-1", null);
        Node far = elevator(dataSet, 0, 0.01, "-2", null);

        Command command = CleanupAction.createCommand(dataSet, 2);
        assertTrue(command == null);
        assertFalse(first.isDeleted());
        assertFalse(far.isDeleted());
    }

    @Test
    public void redirectsParentWayAndKeepsFirstNonEmptyOtherTag() {
        DataSet dataSet = new DataSet();
        Node first = elevator(dataSet, 0, 0, "-4", null);
        first.put("ref", "first");
        Node second = elevator(dataSet, 0, 0.00001, "-3", null);
        second.put("ref", "second");
        Node end = new Node(new LatLon(0, 0.001));
        dataSet.addPrimitive(end);
        Way way = way(dataSet, null, first, second, end);

        Command command = CleanupAction.createCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertEquals("first", first.get("ref"));
        assertEquals("-4;-3", first.get("level"));
        assertEquals(Arrays.asList(first, end), way.getNodes());
        assertTrue(second.isDeleted());
    }

    private static Node elevator(DataSet dataSet, double lat, double lon, String level, String levelref) {
        Node node = new Node(new LatLon(lat, lon));
        node.put("highway", "elevator");
        if (level != null) node.put("level", level);
        if (levelref != null) node.put("levelref", levelref);
        dataSet.addPrimitive(node);
        return node;
    }

    private static Way way(DataSet dataSet, String name, Node... nodes) {
        Way way = new Way();
        if (name != null) way.put("name", name);
        for (Node node : nodes) way.addNode(node);
        dataSet.addPrimitiveRecursive(way);
        return way;
    }

    private static Node[] closedNodes(int nodeCount) {
        Node[] nodes = new Node[nodeCount];
        for (int i = 0; i < nodeCount - 1; i++) {
            nodes[i] = new Node(new LatLon(i == 0 || i == 1 ? 0 : 0.00001,
                    i == 0 || i == 3 ? 0 : 0.00001));
        }
        nodes[nodeCount - 1] = nodes[0];
        return nodes;
    }

    private static Node[] openNodes(int count) {
        Node[] nodes = new Node[count];
        for (int i = 0; i < count; i++) {
            nodes[i] = new Node(new LatLon(0, i * 0.00001));
        }
        return nodes;
    }
}
