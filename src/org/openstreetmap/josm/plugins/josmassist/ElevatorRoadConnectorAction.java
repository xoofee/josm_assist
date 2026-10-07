package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/** Adds missing level-specific connections from elevators to service roads. */
public class ElevatorRoadConnectorAction extends JosmAction {

    /** Constructs the elevator connector action. */
    public ElevatorRoadConnectorAction() {
        super(tr("Connect Elevators to Service Roads"),
                new ImageProvider("dialogs/search").setOptional(true)
                        .setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Connect all elevator nodes to the nearest matching service roads"),
                null,
                true,
                "josmassist-connect-elevators",
                false);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null || dataSet.isLocked()) {
            return;
        }

        List<Node> elevators = new ArrayList<>();
        for (Node node : dataSet.getNodes()) {
            if (!node.isDeleted() && !node.isIncomplete()
                    && node.isLatLonKnown() && "elevator".equals(node.get("highway"))) {
                elevators.add(node);
            }
        }

        Command command = ElevatorRoadConnector.createCommand(dataSet, elevators);
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
    }
}
