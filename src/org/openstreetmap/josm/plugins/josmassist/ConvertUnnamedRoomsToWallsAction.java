package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.tools.ImageProvider;

/** Converts unnamed indoor room ways into wall ways. */
public class ConvertUnnamedRoomsToWallsAction extends JosmAction {

    /** Constructs the room-to-wall conversion action. */
    public ConvertUnnamedRoomsToWallsAction() {
        super(tr("Convert Unnamed Rooms to Walls"),
                new ImageProvider("dialogs/search").setOptional(true)
                        .setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Convert unnamed indoor room ways to barrier walls"),
                null, true, "josmassist-convert-rooms-to-walls", false);
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

    /** Creates the room-to-wall conversion command, or {@code null}. */
    static Command createCommand(DataSet dataSet) {
        if (dataSet == null) {
            return null;
        }

        List<Way> matchingWays = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (!way.isDeleted() && !way.isIncomplete()
                    && "room".equals(way.get("indoor")) && !way.hasKey("name")) {
                matchingWays.add(way);
            }
        }
        if (matchingWays.isEmpty()) {
            return null;
        }

        List<Command> commands = new ArrayList<>();
        commands.add(new ChangePropertyCommand(matchingWays, "indoor", null));
        commands.add(new ChangePropertyCommand(matchingWays, "barrier", "wall"));
        return new SequenceCommand(tr("Convert unnamed rooms to walls"), commands);
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null
                && !MainApplication.getLayerManager().getEditDataSet().isLocked());
    }
}
