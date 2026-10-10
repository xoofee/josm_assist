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
        assertTrue("L3;L4".equals(survivor.get("level:ref"))
                || "L4;L3".equals(survivor.get("level:ref")));
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

    @Test
    public void convertsClosedElevatorWayAndCopiesTags() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "Lift A", nodes);
        way.put("highway", "elevator");
        way.put("indoor", "room");
        way.put("level", "-2;0");
        way.put("ref", "preserve");

        Command command = CleanupAction.createElevatorCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        Node elevator = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).findFirst().orElse(null);
        assertNotNull(elevator);
        assertEquals("-2;0", elevator.get("level"));
        assertEquals("Lift A", elevator.get("name"));
        assertEquals("preserve", way.get("ref"));
        assertNull(way.get("highway"));
        assertNull(way.get("indoor"));
        assertEquals(averageLatitude(nodes), elevator.lat(), 1e-12);
        assertEquals(averageLongitude(nodes), elevator.lon(), 1e-12);

        command.undoCommand();
        assertEquals("elevator", way.get("highway"));
        assertEquals("room", way.get("indoor"));
        assertEquals(0, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
    }

    @Test
    public void doesNotCreateDuplicateForNearbyElevatorNode() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "Lift A", nodes);
        way.put("highway", "elevator");
        way.put("level", "1");
        Node existing = elevator(dataSet, averageLatitude(nodes), averageLongitude(nodes), "0", null);

        Command command = CleanupAction.createElevatorCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertFalse(existing.isDeleted());
        assertEquals(1, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        assertEquals("Lift A", existing.get("name"));
        assertNull(way.get("highway"));
    }

    @Test
    public void convertsClosedStepsWayToElevatorAndCopiesTags() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "Steps Lift", nodes);
        way.put("highway", "steps");
        way.put("indoor", "room");
        way.put("level", "0;1");
        way.put("ref", "steps-ref");

        Command command = CleanupAction.createElevatorCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        Node elevator = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).findFirst().orElse(null);
        assertNotNull(elevator);
        assertEquals("0;1", elevator.get("level"));
        assertEquals("Steps Lift", elevator.get("name"));
        assertEquals("steps-ref", way.get("ref"));
        assertNull(way.get("highway"));
        assertNull(way.get("indoor"));
        assertEquals(averageLatitude(nodes), elevator.lat(), 1e-12);
        assertEquals(averageLongitude(nodes), elevator.lon(), 1e-12);

        command.undoCommand();
        assertEquals("steps", way.get("highway"));
        assertEquals("room", way.get("indoor"));
        assertEquals(0, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
    }

    @Test
    public void stepsWayUsesNearbyExistingElevatorWithoutCreatingDuplicate() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "Steps", nodes);
        way.put("highway", "steps");
        Node existing = elevator(dataSet, averageLatitude(nodes), averageLongitude(nodes), "2", null);

        Command command = CleanupAction.createElevatorCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertFalse(existing.isDeleted());
        assertEquals(1, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        assertNull(way.get("highway"));
    }

    @Test
    public void nearbyStepsWayAddsItsLevelAndLevelRefToExistingElevator() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "扶梯", nodes);
        way.put("highway", "steps");
        way.put("indoor", "area");
        way.put("level", "-3");
        way.put("level:ref", "B3");
        Node existing = elevator(dataSet, averageLatitude(nodes), averageLongitude(nodes), "-4;-2", "B4;B2");

        Command command = CleanupAction.createElevatorCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertFalse(existing.isDeleted());
        assertEquals("-4;-3;-2", existing.get("level"));
        assertEquals("B4;B2;B3", existing.get("level:ref"));
        assertEquals(1, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        assertNull(way.get("highway"));
        assertNull(way.get("indoor"));
    }

    @Test
    public void mergesNodesCreatedFromMultipleElevatorWays() {
        DataSet dataSet = new DataSet();
        Node[] firstNodes = closedNodes(5);
        Way firstWay = way(dataSet, "First", firstNodes);
        firstWay.put("highway", "elevator");
        firstWay.put("name", "First");
        Node[] secondNodes = closedNodes(5);
        for (Node node : secondNodes) {
            node.setCoor(new LatLon(node.lat(), node.lon() + 0.00001));
        }
        Way secondWay = way(dataSet, "Second", secondNodes);
        secondWay.put("highway", "elevator");
        secondWay.put("name", "Second");

        Command command = CleanupAction.createElevatorCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertEquals(1, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        Node survivor = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).findFirst().orElse(null);
        assertEquals("First", survivor.get("name"));
        assertNull(firstWay.get("highway"));
        assertNull(secondWay.get("highway"));
    }

    @Test
    public void skipsElevatorWaysWithoutKnownCoordinates() {
        DataSet dataSet = new DataSet();
        Node unknown = new Node(new LatLon(0, 0));
        unknown.setCoor(null);
        Node secondUnknown = new Node(new LatLon(0, 0.00001));
        secondUnknown.setCoor(null);
        Way way = new Way();
        way.addNode(unknown);
        way.addNode(secondUnknown);
        way.addNode(unknown);
        way.put("highway", "elevator");
        dataSet.addPrimitiveRecursive(way);

        assertNull(CleanupAction.createElevatorCommand(dataSet, 2));
        assertEquals("elevator", way.get("highway"));
    }

    @Test
    public void persistsMergeElevatorDistance() {
        Config.getPref().put(MergeNearbyElevatorsAction.PREF_DISTANCE, "not-a-number");
        assertEquals(2.0, MergeNearbyElevatorsAction.getDistancePreference(), 0.0);

        MergeNearbyElevatorsAction.saveDistancePreference(7.5);
        assertEquals(7.5, MergeNearbyElevatorsAction.getDistancePreference(), 0.0);
    }

    private static Node elevator(DataSet dataSet, double lat, double lon, String level, String levelRef) {
        Node node = new Node(new LatLon(lat, lon));
        node.put("highway", "elevator");
        if (level != null) node.put("level", level);
        if (levelRef != null) node.put("level:ref", levelRef);
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

    private static double averageLatitude(Node[] nodes) {
        double total = 0;
        for (int i = 0; i < nodes.length - 1; i++) {
            total += nodes[i].lat();
        }
        return total / (nodes.length - 1);
    }

    private static double averageLongitude(Node[] nodes) {
        double total = 0;
        for (int i = 0; i < nodes.length - 1; i++) {
            total += nodes[i].lon();
        }
        return total / (nodes.length - 1);
    }
}
