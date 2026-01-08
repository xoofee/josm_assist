package org.openstreetmap.josm.plugins.josmassist;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;

/**
 * Mouse listener for polygon selection.
 * Integrates with JOSM's existing selection mode.
 */
public class JosmAssistMapMode extends MouseAdapter {

    private final PolygonClickHandler clickHandler;

    /**
     * Constructs a new {@code JosmAssistMapMode}.
     */
    public JosmAssistMapMode() {
        this.clickHandler = new PolygonClickHandler();
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        clickHandler.handleMouseClick(e);
    }

    /**
     * Registers this listener with the map view.
     */
    public void register() {
        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame != null && mapFrame.mapView != null) {
            mapFrame.mapView.addMouseListener(this);
        }
    }

    /**
     * Unregisters this listener from the map view.
     */
    public void unregister() {
        MapFrame mapFrame = MainApplication.getMap();
        if (mapFrame != null && mapFrame.mapView != null) {
            mapFrame.mapView.removeMouseListener(this);
        }
    }
}

