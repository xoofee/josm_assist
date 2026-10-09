package org.openstreetmap.josm.plugins.josmassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

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
import org.junit.BeforeClass;

/** Tests for the shared-node-aware simplifier. */
public class SimplifyWay2ActionTest {

    @BeforeClass
    public static void setUpProjection() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    public void removesCollinearInternalNode() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0, 0.00002);
        Way way = way(dataSet, a, b, c);

        List<Node> result = SimplifyWay2Action.simplifyWay(way, 3);

        assertEquals(Arrays.asList(a, c), result);
    }

    @Test
    public void removesNodeAfterSharedEndpoint() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0, 0.00002);
        Node d = node(0, 0.00003);
        way(dataSet, a, b);
        Way selected = way(dataSet, b, c, d);

        List<Node> result = SimplifyWay2Action.simplifyWay(selected, 3);

        assertEquals(Arrays.asList(b, d), result);
        assertSame(b, result.get(0));
    }

    @Test
    public void combinesSelectedWaysAndSimplifiesTheCombinedPath() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0, 0.00002);
        Node d = node(0, 0.00003);
        Way first = way(dataSet, a, b);
        Way second = way(dataSet, b, c, d);

        Command command = SimplifyWay2Action.createCommand(Arrays.asList(first, second), 3);
        assertTrue(command.executeCommand());

        assertEquals(Arrays.asList(a, d), first.getNodes());
        assertTrue(second.isDeleted());
        assertTrue(b.isDeleted());
        assertTrue(c.isDeleted());
    }

    @Test
    public void combinesAReversedSelectedWayChain() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0, 0.00002);
        Node d = node(0, 0.00003);
        Way first = way(dataSet, b, a);
        Way second = way(dataSet, b, c, d);

        Command command = SimplifyWay2Action.createCommand(Arrays.asList(first, second), 3);
        assertTrue(command.executeCommand());

        assertEquals(Arrays.asList(a, d), first.getNodes());
        assertTrue(second.isDeleted());
    }

    @Test
    public void doesNotCombineUnselectedOrIncompatibleWays() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0, 0.00002);
        Node d = node(0, 0.00003);
        Way neighbor = way(dataSet, a, b);
        Way selected = way(dataSet, b, c, d);

        Command unselectedNeighborCommand = SimplifyWay2Action.createCommand(Arrays.asList(selected), 3);
        assertTrue(unselectedNeighborCommand.executeCommand());
        assertEquals(Arrays.asList(b, d), selected.getNodes());
        assertEquals(Arrays.asList(a, b), neighbor.getNodes());
        unselectedNeighborCommand.undoCommand();
        neighbor.put("highway", "service");
        Command incompatibleCommand = SimplifyWay2Action.createCommand(Arrays.asList(neighbor, selected), 3);
        assertTrue(incompatibleCommand.executeCommand());
        assertFalse(neighbor.isDeleted());
        assertFalse(selected.isDeleted());
    }

    @Test
    public void keepsTaggedAndRelationNodes() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node tagged = node(0, 0.00001);
        tagged.put("indoor", "room");
        Node relationMember = node(0, 0.00002);
        Node d = node(0, 0.00003);
        Way selected = way(dataSet, a, tagged, relationMember, d);
        Relation relation = new Relation();
        relation.addMember(new RelationMember("", relationMember));
        dataSet.addPrimitive(relation);

        List<Node> result = SimplifyWay2Action.simplifyWay(selected, 3);

        assertTrue(result.contains(tagged));
        assertTrue(result.contains(relationMember));
    }

    @Test
    public void leavesNodeOutsideToleranceAndRepeatedNode() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node outside = node(0.0001, 0.00001);
        Node d = node(0, 0.00002);
        Way way = way(dataSet, a, outside, d);
        assertEquals(3, SimplifyWay2Action.simplifyWay(way, 3).size());

        Node repeated = node(0, 0.00003);
        Way repeatedWay = way(dataSet, a, repeated, repeated, d);
        assertEquals(4, SimplifyWay2Action.simplifyWay(repeatedWay, 3).size());
    }

    @Test
    public void createsNoCommandWhenNothingCanBeRemoved() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0.0001, 0.00001);
        Node c = node(0, 0.00002);
        Way way = way(dataSet, a, b, c);

        Command command = SimplifyWay2Action.createCommand(Arrays.asList(way), 3);

        assertNull(command);
    }

    @Test
    public void commandIsUndoableAndDeletesOnlyRemovedNodes() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0, 0.00002);
        Way way = way(dataSet, a, b, c);

        Command command = SimplifyWay2Action.createCommand(Arrays.asList(way), 3);
        assertTrue(command.executeCommand());
        assertEquals(Arrays.asList(a, c), way.getNodes());
        assertTrue(b.isDeleted());

        command.undoCommand();
        assertEquals(Arrays.asList(a, b, c), way.getNodes());
        assertFalse(b.isDeleted());
    }

    @Test
    public void restoresRoundedCornerAtFittedIntersection() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node p1 = node(0, 0.00003);
        Node p2 = node(0.000005, 0.000035);
        Node p3 = node(0.000015, 0.000045);
        Node d = node(0.00003, 0.000045);
        Way way = way(dataSet, a, p1, p2, p3, d);

        Command command = SimplifyWay2Action.createCommand(Arrays.asList(way), 3, 3);
        assertTrue(command.executeCommand());
        assertEquals(3, way.getNodesCount());
        Node corner = way.getNode(1);
        assertFalse(corner == p1 || corner == p2 || corner == p3);
        assertTrue(corner.isNew());
        command.undoCommand();
        assertEquals(Arrays.asList(a, p1, p2, p3, d), way.getNodes());
        assertFalse(dataSet.containsNode(corner));
    }

    @Test
    public void doesNotRestoreGentleOrParallelCorner() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0.000001, 0.00002);
        Node d = node(0.000003, 0.00003);
        Way gentle = way(dataSet, a, b, c, d);
        SimplifyWay2Action.SimplificationResult gentleResult =
                SimplifyWay2Action.simplifyWayWithCorners(gentle, 0.01, 3);
        assertTrue(gentleResult.addedNodes.isEmpty());

        Node e = node(0.00001, 0.00004);
        Node f = node(0.00002, 0.00005);
        Way parallel = way(dataSet, a, b, e, f);
        SimplifyWay2Action.SimplificationResult parallelResult =
                SimplifyWay2Action.simplifyWayWithCorners(parallel, 0.01, 0.01);
        assertTrue(parallelResult.addedNodes.isEmpty());
    }

    @Test
    public void rememberedTolerancesAreLoadedWithoutShowingDialog() {
        Config.getPref().putDouble("josmassist.simplify-way2.max-error", 1.5);
        Config.getPref().putDouble("josmassist.simplify-way2.corner-error", 2.5);
        Config.getPref().put("josmassist.simplify-way2.remember", "yes");
        SimplifyWay2Action.SimplifyParameters parameters = SimplifyWay2Action.askSimplifyParameters();
        assertEquals(1.5, parameters.simplificationError, 0.001);
        assertEquals(2.5, parameters.cornerError, 0.001);
    }

    @Test
    public void resolvesAllDisconnectedServiceWaysWhenNothingIsSelected() {
        DataSet dataSet = new DataSet();
        Way first = serviceWay(dataSet, node(0, 0), node(0, 0.00001));
        Way second = serviceWay(dataSet, node(1, 1), node(1, 1.00001));

        List<Way> resolved = SimplifyWay2Action.resolveInputWays(dataSet, dataSet.getSelectedWays());

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(first));
        assertTrue(resolved.contains(second));
    }

    @Test
    public void resolvesTransitivelyConnectedWaysButNotUnrelatedWays() {
        DataSet dataSet = new DataSet();
        Node a = node(0, 0);
        Node b = node(0, 0.00001);
        Node c = node(0, 0.00002);
        Node d = node(0, 0.00003);
        Way service = serviceWay(dataSet, a, b);
        Way connector = way(dataSet, b, c);
        Way secondConnector = way(dataSet, c, d);
        Way unrelated = way(dataSet, node(1, 1), node(1, 1.00001));

        List<Way> resolved = SimplifyWay2Action.resolveInputWays(dataSet, dataSet.getSelectedWays());

        assertEquals(Arrays.asList(service, connector, secondConnector), resolved);
        assertFalse(resolved.contains(unrelated));
    }

    @Test
    public void usesFallbackWhenOnlyNonServiceWaysAreSelected() {
        DataSet dataSet = new DataSet();
        Way service = serviceWay(dataSet, node(0, 0), node(0, 0.00001));
        Way selectedNonService = way(dataSet, node(1, 1), node(1, 1.00001));
        dataSet.addSelected(selectedNonService);

        List<Way> resolved = SimplifyWay2Action.resolveInputWays(dataSet, dataSet.getSelectedWays());

        assertEquals(Arrays.asList(service), resolved);
        assertTrue(dataSet.getSelectedWays().contains(selectedNonService));
    }

    @Test
    public void selectedServiceWayKeepsSelectedWayScope() {
        DataSet dataSet = new DataSet();
        Way selectedService = serviceWay(dataSet, node(0, 0), node(0, 0.00001));
        Way selectedOther = way(dataSet, node(0, 0.00001), node(0, 0.00002));
        Way unrelatedService = serviceWay(dataSet, node(1, 1), node(1, 1.00001));
        dataSet.addSelected(selectedService);
        dataSet.addSelected(selectedOther);

        List<Way> resolved = SimplifyWay2Action.resolveInputWays(dataSet, dataSet.getSelectedWays());

        assertEquals(Arrays.asList(selectedService, selectedOther), resolved);
        assertFalse(resolved.contains(unrelatedService));
    }

    @Test
    public void excludesDeletedWaysFromFallback() {
        DataSet dataSet = new DataSet();
        Way usable = serviceWay(dataSet, node(0, 0), node(0, 0.00001));
        Way deleted = serviceWay(dataSet, node(1, 1), node(1, 1.00001));
        deleted.setDeleted(true);
        List<Way> resolved = SimplifyWay2Action.resolveInputWays(dataSet, dataSet.getSelectedWays());

        assertEquals(Arrays.asList(usable), resolved);
    }

    @Test
    public void returnsEmptyFallbackScopeWithoutServiceSeeds() {
        DataSet dataSet = new DataSet();
        way(dataSet, node(0, 0), node(0, 0.00001));

        assertTrue(SimplifyWay2Action.resolveInputWays(dataSet, dataSet.getSelectedWays()).isEmpty());
    }

    private static Node node(double lat, double lon) {
        return new Node(new LatLon(lat, lon));
    }

    private static Way way(DataSet dataSet, Node... nodes) {
        Way way = new Way();
        for (Node node : nodes) {
            way.addNode(node);
        }
        dataSet.addPrimitiveRecursive(way);
        return way;
    }

    private static Way serviceWay(DataSet dataSet, Node... nodes) {
        Way way = way(dataSet, nodes);
        way.put("highway", "service");
        return way;
    }

}
