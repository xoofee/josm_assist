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
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

public class ConvertUnnamedRoomsToWallsActionTest {

    @BeforeClass
    public static void setUpPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    public void convertsUnnamedRoomsAndSupportsUndo() {
        DataSet dataSet = new DataSet();
        Node[] nodes = squareNodes();
        Way room = way(dataSet, nodes);
        room.put("indoor", "room");
        room.put("ref", "A");

        Command command = ConvertUnnamedRoomsToWallsAction.createCommand(dataSet);
        assertNotNull(command);
        assertTrue(command.executeCommand());

        assertNull(room.get("indoor"));
        assertEquals("wall", room.get("barrier"));
        assertEquals("A", room.get("ref"));
        assertEquals(Arrays.asList(nodes), room.getNodes());

        command.undoCommand();
        assertEquals("room", room.get("indoor"));
        assertNull(room.get("barrier"));
        assertEquals("A", room.get("ref"));
    }

    @Test
    public void ignoresNamedInvalidAndNonRoomWays() {
        DataSet dataSet = new DataSet();

        Way named = room(dataSet, "named");
        Way matching = room(dataSet, null);
        Way otherIndoor = room(dataSet, null);
        otherIndoor.put("indoor", "corridor");
        Way incomplete = new Way(1);
        incomplete.put("indoor", "room");
        dataSet.addPrimitive(incomplete);
        Way deleted = room(dataSet, null);
        deleted.setDeleted(true);
        Node node = new Node(new LatLon(1, 1));
        node.put("indoor", "room");
        dataSet.addPrimitive(node);

        Command command = ConvertUnnamedRoomsToWallsAction.createCommand(dataSet);
        assertNotNull(command);
        command.executeCommand();

        assertNull(matching.get("indoor"));
        assertEquals("wall", matching.get("barrier"));
        assertEquals("room", named.get("indoor"));
        assertEquals("corridor", otherIndoor.get("indoor"));
        assertEquals("room", incomplete.get("indoor"));
        assertEquals("room", deleted.get("indoor"));
        assertEquals("room", node.get("indoor"));
    }

    @Test
    public void returnsNullWhenThereAreNoMatches() {
        DataSet dataSet = new DataSet();
        room(dataSet, "named");
        assertNull(ConvertUnnamedRoomsToWallsAction.createCommand(dataSet));
    }

    private static Way room(DataSet dataSet, String name) {
        Way way = way(dataSet, squareNodes());
        way.put("indoor", "room");
        if (name != null) {
            way.put("name", name);
        }
        return way;
    }

    private static Way way(DataSet dataSet, Node[] nodes) {
        Way way = new Way();
        way.setNodes(Arrays.asList(nodes));
        for (Node node : nodes) {
            dataSet.addPrimitive(node);
        }
        dataSet.addPrimitive(way);
        return way;
    }

    private static Node[] squareNodes() {
        return new Node[] {
            new Node(new LatLon(0, 0)),
            new Node(new LatLon(0, 1)),
            new Node(new LatLon(1, 1)),
            new Node(new LatLon(1, 0)),
            new Node(new LatLon(0, 0))
        };
    }
}
