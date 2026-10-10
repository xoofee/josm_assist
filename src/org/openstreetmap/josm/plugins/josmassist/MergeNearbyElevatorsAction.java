package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.ImageProvider;

/** Merges nearby elevator nodes. */
public class MergeNearbyElevatorsAction extends JosmAction {

    private static final double INITIAL_DISTANCE_METERS = 2.0;
    static final String PREF_DISTANCE = "josmassist.merge-elevators.distance";

    /** Constructs the elevator merge action. */
    public MergeNearbyElevatorsAction() {
        super(tr("Merge Nearby Elevators"),
                new ImageProvider("dialogs/delete").setOptional(true)
                        .setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Merge nearby elevator nodes within a chosen distance"),
                null, true, "josmassist-merge-elevators", false);
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        DataSet dataSet = MainApplication.getLayerManager().getEditDataSet();
        if (dataSet == null || dataSet.isLocked()) {
            return;
        }

        String value = JOptionPane.showInputDialog(MainApplication.getMainFrame(),
                tr("Merge elevator nodes within this distance (meters):"),
                Double.toString(getDistancePreference()));
        if (value == null) {
            return;
        }

        final double distance;
        try {
            distance = Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return;
        }
        if (!Double.isFinite(distance) || distance <= 0) {
            return;
        }
        saveDistancePreference(distance);

        Command command = createCommand(dataSet, distance);
        if (command != null) {
            UndoRedoHandler.getInstance().add(command);
        }
    }

    /** Creates a command that merges existing elevator nodes only. */
    static Command createCommand(DataSet dataSet, double distanceMeters) {
        return ElevatorNodeMerger.createCommand(dataSet, distanceMeters);
    }

    static double getDistancePreference() {
        double distance = Config.getPref().getDouble(PREF_DISTANCE, INITIAL_DISTANCE_METERS);
        return Double.isFinite(distance) && distance > 0 ? distance : INITIAL_DISTANCE_METERS;
    }

    static void saveDistancePreference(double distance) {
        if (Double.isFinite(distance) && distance > 0) {
            Config.getPref().putDouble(PREF_DISTANCE, distance);
        }
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(MainApplication.getLayerManager().getEditDataSet() != null
                && !MainApplication.getLayerManager().getEditDataSet().isLocked());
    }
}
