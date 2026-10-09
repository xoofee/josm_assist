package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/** Removes qualifying named area ways. */
public class CleanUpAreasAction extends JosmAction {

    /** Constructs the area cleanup action. */
    public CleanUpAreasAction() {
        super(tr("Clean Up Areas"),
                new ImageProvider("dialogs/delete").setOptional(true)
                        .setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Remove qualifying named area ways"),
                null, true, "josmassist-cleanup-areas", false);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null || dataSet.isLocked()) {
            return;
        }
        Command command = CleanupAction.createAreasCommand(dataSet);
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
