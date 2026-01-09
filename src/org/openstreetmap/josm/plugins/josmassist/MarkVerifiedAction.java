package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Collection;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Action to add verified=true tag to selected elements.
 * Uses Ctrl+Alt+V keyboard shortcut.
 */
public class MarkVerifiedAction extends JosmAction {

    private static final String VERIFIED_KEY = "verified";
    private static final String VERIFIED_VALUE = "true";

    /**
     * Constructs a new {@code MarkVerifiedAction}.
     */
    public MarkVerifiedAction() {
        super(tr("Mark as Verified"), 
                new ImageProvider("ok").setOptional(true).setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                tr("Add verified=true tag to selected elements"),
                Shortcut.registerShortcut("plugin:josmassist:markverified",
                        tr("Mark as Verified"), KeyEvent.VK_V, Shortcut.CTRL | Shortcut.ALT),
                false, // don't register in toolbar by default
                "josmassist-markverified", // toolbar ID
                false); // don't install adapters
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        // Do nothing if no dataset or selection is empty
        if (getLayerManager().getEditDataSet() == null) {
            return;
        }
        
        Collection<OsmPrimitive> selection = getLayerManager().getEditDataSet().getSelected();
        if (selection == null || selection.isEmpty()) {
            return;
        }

        // Create command to add verified=true tag
        ChangePropertyCommand cmd = new ChangePropertyCommand(selection, VERIFIED_KEY, VERIFIED_VALUE);
        
        // Only execute if command would modify something
        if (cmd.getObjectsNumber() > 0) {
            UndoRedoHandler.getInstance().add(cmd);
        }
    }

    @Override
    protected void updateEnabledState() {
        updateEnabledStateOnCurrentSelection();
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        // Enable only if there's a data layer and selection is not empty
        updateEnabledStateOnModifiableSelection(selection);
    }
}
