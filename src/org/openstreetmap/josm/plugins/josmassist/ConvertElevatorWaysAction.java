package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/** Converts closed elevator and steps ways into elevator nodes. */
public class ConvertElevatorWaysAction extends JosmAction {

    /** Constructs the conversion action. */
    public ConvertElevatorWaysAction() {
        super(tr("Convert Elevator Ways to Nodes"),
                new ImageProvider("dialogs/search").setOptional(true)
                        .setMaxSize(ImageProvider.ImageSizes.TOOLBAR),
                tr("Create elevator nodes from closed elevator and steps ways"),
                null, true, "josmassist-convert-elevator-ways", false);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null || dataSet.isLocked()) {
            return;
        }
        Command command = ElevatorWayConverter.createCommand(dataSet);
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null
                && !MainApplication.getLayerManager().getEditDataSet().isLocked());
    }
}
