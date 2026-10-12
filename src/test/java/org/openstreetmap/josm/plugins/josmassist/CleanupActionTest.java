package org.openstreetmap.josm.plugins.josmassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

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

        Command command = CleanupAction.createAreasCommand(dataSet);
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

        first.put("name", "Lift A;Lift B");
        second.put("name", "Lift B");
        third.put("name", "Lift C;Lift A");

        Command command = MergeNearbyElevatorsAction.createCommand(dataSet, 1.2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        Node survivor = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).findFirst().orElse(null);
        assertNotNull(survivor);
        assertEquals("-4;-3", survivor.get("level"));
        assertEquals(new HashSet<>(Arrays.asList("Lift A", "Lift B", "Lift C")),
                new HashSet<>(Arrays.asList(survivor.get("name").split(";"))));
        assertEquals(3, survivor.get("name").split(";").length);
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

        Command command = MergeNearbyElevatorsAction.createCommand(dataSet, 2);
        assertTrue(command == null);
        assertFalse(first.isDeleted());
        assertFalse(far.isDeleted());
    }

    @Test
    public void removesOnlyActiveSingleLevelElevatorNodesAndUndoRestoresThem() {
        DataSet dataSet = new DataSet();
        Node single = elevator(dataSet, 0, 0, " 0; ;", "single");
        Node multi = elevator(dataSet, 0, 0.00001, "0;1", null);
        Node missing = elevator(dataSet, 0, 0.00002, null, null);
        Node empty = elevator(dataSet, 0, 0.00003, " ; ", null);
        Node other = new Node(new LatLon(0, 0.00004));
        other.put("highway", "footway");
        other.put("level", "0");
        dataSet.addPrimitive(other);
        Node incomplete = new Node(new LatLon(0, 0.00006));
        incomplete.put("highway", "elevator");
        incomplete.put("level", "0");
        dataSet.addPrimitive(incomplete);
        incomplete.setCoor(null);
        Node deleted = elevator(dataSet, 0, 0.00005, "0", null);
        deleted.setDeleted(true);

        Command command = RemoveSingleLevelElevatorsAction.createCommand(dataSet);
        assertNotNull(command);
        assertTrue(command.executeCommand());
        assertTrue(single.isDeleted());
        assertFalse(multi.isDeleted());
        assertFalse(missing.isDeleted());
        assertFalse(empty.isDeleted());
        assertFalse(other.isDeleted());
        assertFalse(incomplete.isDeleted());
        assertTrue(deleted.isDeleted());
        assertEquals("single", single.get("level:ref"));

        command.undoCommand();
        assertFalse(single.isDeleted());
        assertFalse(multi.isDeleted());
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

        Command command = MergeNearbyElevatorsAction.createCommand(dataSet, 2);
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

        Command command = ElevatorWayConverter.createCommand(dataSet);
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
    public void alwaysCreatesNodeForWayNearExistingElevator() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "Lift A", nodes);
        way.put("highway", "elevator");
        way.put("level", "1");
        Node existing = elevator(dataSet, averageLatitude(nodes), averageLongitude(nodes), "0", null);

        Command command = ElevatorWayConverter.createCommand(dataSet);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertFalse(existing.isDeleted());
        assertEquals(2, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        assertNull(existing.get("name"));
        assertNull(way.get("highway"));
        Node created = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway")) && node != existing).findFirst().orElse(null);
        assertNotNull(created);
        assertEquals("Lift A", created.get("name"));
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

        Command command = ElevatorWayConverter.createCommand(dataSet);
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
    public void stepsWayConvertsEvenWhenExistingElevatorIsNearby() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "Steps", nodes);
        way.put("highway", "steps");
        Node existing = elevator(dataSet, averageLatitude(nodes), averageLongitude(nodes), "2", null);

        Command command = ElevatorWayConverter.createCommand(dataSet);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertFalse(existing.isDeleted());
        assertEquals(2, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        assertNull(way.get("highway"));
    }

    @Test
    public void conversionLeavesNearbyElevatorUntouchedAndCopiesWayTags() {
        DataSet dataSet = new DataSet();
        Node[] nodes = closedNodes(5);
        Way way = way(dataSet, "扶梯", nodes);
        way.put("highway", "steps");
        way.put("indoor", "area");
        way.put("level", "-3");
        way.put("level:ref", "B3");
        Node existing = elevator(dataSet, averageLatitude(nodes), averageLongitude(nodes), "-4;-2", "B4;B2");

        Command command = ElevatorWayConverter.createCommand(dataSet);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertFalse(existing.isDeleted());
        assertEquals("-4;-2", existing.get("level"));
        assertEquals("B4;B2", existing.get("level:ref"));
        assertEquals(1, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway")) && node != existing).count());
        Node created = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway")) && node != existing).findFirst().orElse(null);
        assertNotNull(created);
        assertEquals("-3", created.get("level"));
        assertEquals("B3", created.get("level:ref"));
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

        Command conversion = ElevatorWayConverter.createCommand(dataSet);
        assertNotNull(conversion);
        assertTrue(conversion.executeCommand());
        assertEquals(2, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());

        Command command = MergeNearbyElevatorsAction.createCommand(dataSet, 2);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertEquals(1, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        Node survivor = dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).findFirst().orElse(null);
        assertEquals("First;Second", survivor.get("name"));
        assertNull(firstWay.get("highway"));
        assertNull(secondWay.get("highway"));

        command.undoCommand();
        assertEquals(2, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
        conversion.undoCommand();
        assertEquals("elevator", firstWay.get("highway"));
        assertEquals("elevator", secondWay.get("highway"));
        assertEquals(0, dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count());
    }

    @Test
    public void conversionThenElevenMeterMergeKeepsAllLevelsAndUniqueNames() {
        DataSet dataSet = new DataSet();
        Node levelFour = elevator(dataSet, 29.6524382, 106.52531685, "-4", "B4");
        levelFour.put("name", "1号客梯厅");
        Node levelTwo = elevator(dataSet, 29.65246147, 106.5253034, "-2", "B2");
        levelTwo.put("name", "1号客梯厅");

        Node[] wayNodes = {
            new Node(new LatLon(29.65248018, 106.52532626)),
            new Node(new LatLon(29.65237761, 106.52531685)),
            new Node(new LatLon(29.65237649, 106.52533164)),
            new Node(new LatLon(29.65247905, 106.52534239)),
            null
        };
        wayNodes[4] = wayNodes[0];
        Way steps = way(dataSet, "扶梯", wayNodes);
        steps.put("highway", "steps");
        steps.put("indoor", "area");
        steps.put("level", "-3");
        steps.put("level:ref", "B3");

        Command conversion = ElevatorWayConverter.createCommand(dataSet);
        assertNotNull(conversion);
        assertTrue(conversion.executeCommand());
        assertEquals(3, activeElevators(dataSet));
        assertNull(steps.get("highway"));
        assertEquals("-4", levelFour.get("level"));
        assertEquals("-2", levelTwo.get("level"));

        Command merge = MergeNearbyElevatorsAction.createCommand(dataSet, 11);
        assertNotNull(merge);
        assertTrue(merge.executeCommand());

        assertEquals(1, activeElevators(dataSet));
        Node survivor = activeElevator(dataSet);
        assertEquals("-4;-3;-2", survivor.get("level"));
        assertEquals(new HashSet<>(Arrays.asList("B4", "B2", "B3")),
                new HashSet<>(Arrays.asList(survivor.get("level:ref").split(";"))));
        assertEquals(new HashSet<>(Arrays.asList("1号客梯厅", "扶梯")),
                new HashSet<>(Arrays.asList(survivor.get("name").split(";"))));

        merge.undoCommand();
        assertEquals(3, activeElevators(dataSet));
        assertNull(steps.get("highway"));
        assertNull(steps.get("indoor"));
        conversion.undoCommand();
        assertEquals(2, activeElevators(dataSet));
        assertEquals("steps", steps.get("highway"));
    }

    @Test
    public void mergeActionDoesNotConvertClosedStepsWays() {
        DataSet dataSet = new DataSet();
        elevator(dataSet, 0, 0, "-1", null);
        elevator(dataSet, 0, 0.00001, "0", null);
        Way steps = way(dataSet, "Steps", closedNodes(5));
        steps.put("highway", "steps");

        Command merge = MergeNearbyElevatorsAction.createCommand(dataSet, 2);
        assertNotNull(merge);
        assertTrue(merge.executeCommand());
        assertEquals("steps", steps.get("highway"));
        assertEquals(1, activeElevators(dataSet));
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

        assertNull(ElevatorWayConverter.createCommand(dataSet));
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

    private static long activeElevators(DataSet dataSet) {
        return dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).count();
    }

    private static Node activeElevator(DataSet dataSet) {
        return dataSet.getNodes().stream().filter(node -> !node.isDeleted()
                && "elevator".equals(node.get("highway"))).findFirst().orElse(null);
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
