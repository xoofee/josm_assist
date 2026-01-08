package org.openstreetmap.josm.plugins.josmassist;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;

/**
 * Listens for edit mode exit (Esc key) and processes new elements.
 */
public class EditModeExitListener implements KeyListener {

    private final LevelProcessingHandler levelHandler;

    /**
     * Constructs a new {@code EditModeExitListener}.
     * @param levelHandler the level processing handler
     */
    public EditModeExitListener(LevelProcessingHandler levelHandler) {
        this.levelHandler = levelHandler;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        // Detect Esc key to exit edit mode
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            // Small delay to ensure edit mode has fully exited
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    Thread.sleep(100); // Wait for edit mode to fully exit
                    // Process new elements when exiting edit mode
                    levelHandler.processNewElementsOnEditExit();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    // Ignore
                }
            });
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // Not used
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Not used
    }

    /**
     * Registers this listener with the map frame.
     */
    public void register() {
        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame != null && mapFrame.mapView != null) {
            mapFrame.mapView.addKeyListener(this);
        }
    }

    /**
     * Unregisters this listener from the map frame.
     */
    public void unregister() {
        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame != null && mapFrame.mapView != null) {
            mapFrame.mapView.removeKeyListener(this);
        }
    }
}

