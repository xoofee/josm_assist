package org.openstreetmap.josm.plugins.josmassist;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;

import org.openstreetmap.josm.actions.ToggleAction;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Main plugin class for JOSM Assist plugin.
 * Provides enhanced polygon selection and level processing features.
 */
public class JosmAssistPlugin extends Plugin {

    private static JosmAssistPlugin instance;
    private boolean pluginEnabled = true;
    private JosmAssistMapMode mapMode;
    private TogglePluginAction toggleAction;
    private LevelProcessingHandler levelHandler;
    private EditModeExitListener editModeExitListener;
    private PolygonClickHandler clickHandler;

    /**
     * Constructs a new {@code JosmAssistPlugin}.
     * @param info plugin information
     */
    public JosmAssistPlugin(PluginInformation info) {
        super(info);
        instance = this;
        
        // Initialize components
        toggleAction = new TogglePluginAction();
        levelHandler = new LevelProcessingHandler();
        editModeExitListener = new EditModeExitListener(levelHandler);
        clickHandler = new PolygonClickHandler();
        
        // Add toggle menu item to tools menu
        try {
            if (MainApplication.getMenu() != null && MainApplication.getMenu().toolsMenu != null) {
                MainApplication.getMenu().toolsMenu.add(toggleAction);
            }
        } catch (Exception e) {
            System.err.println("Could not add menu item: " + e.getMessage());
        }
        
        // Register toolbar button
        try {
            if (MainApplication.getToolbar() != null) {
                MainApplication.getToolbar().register(toggleAction);
            }
        } catch (Exception e) {
            System.err.println("Could not register toolbar button: " + e.getMessage());
        }
        
        // Register map mode listener
        MainApplication.getLayerManager().addActiveLayerChangeListener(e -> {
            if (pluginEnabled) {
                setupMapMode();
                editModeExitListener.register();
                // Update level detection when layer changes
                levelHandler.onSelectionChanged();
            } else {
                removeMapMode();
                editModeExitListener.unregister();
            }
        });
        
        // Register selection change listener to detect level changes
        // This will be done when layer changes
        
        // Setup initial map mode if layer is already active
        if (MainApplication.getLayerManager().getActiveLayer() != null) {
            setupMapMode();
            editModeExitListener.register();
            levelHandler.onSelectionChanged();
        }
    }

    /**
     * Gets the plugin instance.
     * @return the plugin instance
     */
    public static JosmAssistPlugin getInstance() {
        return instance;
    }

    /**
     * Checks if the plugin is enabled.
     * @return true if enabled
     */
    public boolean isEnabled() {
        return pluginEnabled;
    }

    /**
     * Sets up the map mode for polygon selection.
     */
    private void setupMapMode() {
        if (mapMode == null) {
            mapMode = new JosmAssistMapMode();
        }
        mapMode.register();
    }

    /**
     * Removes the map mode.
     */
    private void removeMapMode() {
        if (mapMode != null) {
            mapMode.unregister();
        }
    }

    /**
     * Toggles the plugin on/off.
     */
    public void togglePlugin() {
        // Trigger the action to handle state update
        toggleAction.actionPerformed(null);
    }
    
    /**
     * Updates plugin state and enables/disables features.
     */
    private void updatePluginState(boolean enabled) {
        pluginEnabled = enabled;
        
        if (pluginEnabled) {
            setupMapMode();
            editModeExitListener.register();
        } else {
            removeMapMode();
            editModeExitListener.unregister();
        }
    }

    /**
     * Action to toggle plugin on/off.
     * Supports toolbar button with visual indicator.
     */
    private class TogglePluginAction extends ToggleAction {
        private JCheckBoxMenuItem menuItem;

        public TogglePluginAction() {
            super(tr("Toggle JOSM Assist"), 
                    new ImageProvider("presets/vehicle/parking/parking_space").setOptional(true).setMaxSize(org.openstreetmap.josm.tools.ImageProvider.ImageSizes.TOOLBAR),
                    tr("Enable/disable JOSM Assist plugin features"),
                    Shortcut.registerShortcut("plugin:josmassist:toggle",
                            tr("Toggle JOSM Assist"), KeyEvent.VK_Z, Shortcut.ALT),
                    true, // register in toolbar
                    "josmassist-toggle", // toolbar ID
                    false); // don't install adapters
            // Initialize selected state
            try {
                java.lang.reflect.Method setSelectedMethod = ToggleAction.class.getDeclaredMethod("setSelected", boolean.class);
                setSelectedMethod.setAccessible(true);
                setSelectedMethod.invoke(this, pluginEnabled);
            } catch (Exception e) {
                // If reflection fails, state will be set on first action
            }
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            toggleSelectedState(e);
            updatePluginState(isSelected());
        }

        @Override
        protected void notifySelectedState() {
            super.notifySelectedState();
            // State is already updated in actionPerformed
        }

        public JMenuItem createMenuComponent() {
            menuItem = new JCheckBoxMenuItem(this);
            menuItem.setSelected(pluginEnabled);
            return menuItem;
        }
    }

    /**
     * Gets the level processing handler.
     * @return the level handler
     */
    public LevelProcessingHandler getLevelHandler() {
        return levelHandler;
    }

    /**
     * Gets the map mode instance.
     * @return the map mode
     */
    public JosmAssistMapMode getMapMode() {
        return mapMode;
    }
}

