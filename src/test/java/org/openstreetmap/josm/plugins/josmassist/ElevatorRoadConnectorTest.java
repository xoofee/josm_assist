package org.openstreetmap.josm.plugins.josmassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

public class ElevatorRoadConnectorTest {

    @BeforeClass
    public static void setUpProjection() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    public void connectsElevatorToNearestServiceSegmentForEveryLevel() {
        DataSet dataSet = new DataSet();

        Node serviceStart = node(0, 0);
        Node serviceEnd = node(10, 0);
        Way service = new Way();
        service.put("highway", "service");
        service.put("level", "0;1");
        service.setNodes(Arrays.asList(serviceStart, serviceEnd));

        Node elevator = node(4, 3);
        elevator.put("highway", "elevator");
        elevator.put("level", "0;1");

        dataSet.addPrimitive(serviceStart);
        dataSet.addPrimitive(serviceEnd);
        dataSet.addPrimitive(service);
        dataSet.addPrimitive(elevator);

        Command command = ElevatorRoadConnector.createCommand(dataSet, Arrays.asList(elevator));
        assertNotNull(command);
        command.executeCommand();

        assertEquals("The service way is split once per level", 4, service.getNodesCount());
        assertEquals(2, connectorCount(elevator));
        assertEquals(2, serviceConnectorCount(elevator));
        assertEquals(3, dataSet.getWays().stream()
                .filter(way -> "service".equals(way.get("highway"))).count());

        assertNull("A second run must not add duplicate level connections",
                ElevatorRoadConnector.createCommand(dataSet, Arrays.asList(elevator)));
    }

    @Test
    public void doesNotConnectToDifferentLevel() {
        DataSet dataSet = new DataSet();

        Node serviceStart = node(0, 0);
        Node serviceEnd = node(10, 0);
        Way service = new Way();
        service.put("highway", "service");
        service.put("level", "1");
        service.setNodes(Arrays.asList(serviceStart, serviceEnd));

        Node elevator = node(4, 3);
        elevator.put("highway", "elevator");
        elevator.put("level", "0");

        dataSet.addPrimitive(serviceStart);
        dataSet.addPrimitive(serviceEnd);
        dataSet.addPrimitive(service);
        dataSet.addPrimitive(elevator);

        assertNull(ElevatorRoadConnector.createCommand(dataSet, Arrays.asList(elevator)));
    }

    @Test
    public void skipsConnectedLevelAndProcessesRemainingLevels() {
        DataSet dataSet = new DataSet();

        Node serviceStart = node(0, 0);
        Node serviceEnd = node(10, 0);
        Way service = new Way();
        service.put("highway", "service");
        service.put("level", "0;1");
        service.setNodes(Arrays.asList(serviceStart, serviceEnd));

        Node elevator = node(4, 3);
        elevator.put("highway", "elevator");
        elevator.put("level", "0;1");

        Node existingRoadNode = node(4, 0);
        Way existingConnector = new Way();
        existingConnector.put("highway", "path");
        existingConnector.put("level", "0");
        existingConnector.setNodes(Arrays.asList(elevator, existingRoadNode));

        dataSet.addPrimitive(serviceStart);
        dataSet.addPrimitive(serviceEnd);
        dataSet.addPrimitive(service);
        dataSet.addPrimitive(elevator);
        dataSet.addPrimitive(existingRoadNode);
        dataSet.addPrimitive(existingConnector);

        Command command = ElevatorRoadConnector.createCommand(dataSet, Arrays.asList(elevator));
        assertNotNull(command);
        command.executeCommand();

        assertEquals(2, connectorCount(elevator));
        assertEquals(3, service.getNodesCount());
        assertEquals(1, levelCount(elevator, "0"));
        assertEquals(1, levelCount(elevator, "1"));
    }

    private static Node node(double east, double north) {
        return new Node(new EastNorth(east, north));
    }

    private static long connectorCount(Node elevator) {
        return elevator.getParentWays().stream()
                .filter(way -> way.getNodesCount() >= 2 && way.get("level") != null)
                .count();
    }

    private static long serviceConnectorCount(Node elevator) {
        return elevator.getParentWays().stream()
                .filter(way -> "service".equals(way.get("highway")) && way.getNodesCount() == 2)
                .count();
    }

    private static long levelCount(Node elevator, String level) {
        return elevator.getParentWays().stream()
                .filter(way -> way.getNodesCount() >= 2 && level.equals(way.get("level")))
                .count();
    }
}
