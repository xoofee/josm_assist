package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/** Removes elevator nodes which represent only one level. */
public class RemoveSingleLevelElevatorsAction extends JosmAction {

    /** Constructs the single-level elevator removal action. */
    public RemoveSingleLevelElevatorsAction() {
        super(tr("Remove Single-Level Elevators"),
                new ImageProvider("dialogs/delete").setOptional(true)
                        .setMaxSize(ImageProvider.ImageSizes.TOOLBAR),
                tr("Remove elevator nodes serving only one level"),
                null, true, "josmassist-remove-single-level-elevators", false);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null || dataSet.isLocked()) {
            return;
        }
        Command command = createCommand(dataSet);
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
    }

    /** Creates an undoable command removing qualifying elevator nodes, or {@code null}. */
    static Command createCommand(DataSet dataSet) {
        if (dataSet == null) {
            return null;
        }
        List<Node> nodesToDelete = new ArrayList<>();
        for (Node node : dataSet.getNodes()) {
            if (isSingleLevelElevator(node)) {
                nodesToDelete.add(node);
            }
        }
        return nodesToDelete.isEmpty() ? null : new DeleteCommand(dataSet, nodesToDelete);
    }

    private static boolean isSingleLevelElevator(Node node) {
        if (node == null || node.isDeleted() || node.isIncomplete() || !node.isLatLonKnown()
                || !"elevator".equals(node.get("highway"))) {
            return false;
        }
        String level = node.get("level");
        if (level == null) {
            return false;
        }
        int nonEmptyParts = 0;
        for (String part : level.split(";", -1)) {
            if (!part.trim().isEmpty() && ++nonEmptyParts > 1) {
                return false;
            }
        }
        return nonEmptyParts == 1;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null
                && !MainApplication.getLayerManager().getEditDataSet().isLocked());
    }
}
