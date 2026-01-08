package org.openstreetmap.josm.plugins.josmassist;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;

/**
 * Listens for edit mode exit (Esc key or switching from draw mode to another mode)
 * and processes new elements.
 */
public class EditModeExitListener implements MapFrame.MapModeChangeListener, KeyListener {

    private final LevelProcessingHandler levelHandler;

    /**
     * Constructs a new {@code EditModeExitListener}.
     * @param levelHandler the level processing handler
     */
    public EditModeExitListener(LevelProcessingHandler levelHandler) {
        this.levelHandler = levelHandler;
    }

    @Override
    public void mapModeChange(MapMode oldMapMode, MapMode newMapMode) {
        // Detect when exiting draw mode (switching from DrawAction to any other mode)
        if (oldMapMode != null && isDrawMode(oldMapMode) && !isDrawMode(newMapMode)) {
            processNewElements();
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        // Detect Esc key to exit edit mode
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            processNewElements();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // No-op
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // No-op
    }

    /**
     * Processes new elements with a delay to ensure edit mode has fully exited.
     */
    private void processNewElements() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                Thread.sleep(100); // Wait for edit mode to fully exit
                levelHandler.processNewElementsOnEditExit();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                // Ignore
            }
        });
    }

    /**
     * Checks if the given map mode is a draw mode.
     * @param mapMode the map mode to check
     * @return true if it's a draw mode
     */
    private boolean isDrawMode(MapMode mapMode) {
        if (mapMode == null) return false;
        // Check if it's DrawAction or any subclass that represents drawing
        String className = mapMode.getClass().getSimpleName();
        return className.contains("Draw") || className.equals("DrawAction");
    }

    /**
     * Registers this listener with the map frame.
     */
    public void register() {
        MapFrame.addMapModeChangeListener(this);
        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame != null && mapFrame.mapView != null) {
            mapFrame.mapView.addKeyListener(this);
        }
    }

    /**
     * Unregisters this listener from the map frame.
     */
    public void unregister() {
        MapFrame.removeMapModeChangeListener(this);
        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame != null && mapFrame.mapView != null) {
            mapFrame.mapView.removeKeyListener(this);
        }
    }
}
