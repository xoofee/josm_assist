package org.openstreetmap.josm.plugins.josmassist;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

/**
 * Handles enhanced level processing.
 * When one level is selected, new elements automatically get the level tag
 * after the user exits edit mode.
 */
public class LevelProcessingHandler implements ActiveLayerChangeListener {

    private String currentLevelTag = null;
    private Set<OsmPrimitive> newElements = new HashSet<>();
    private DataSet currentDataSet = null;
    private int lastPrimitiveCount = 0;

    /**
     * Constructs a new {@code LevelProcessingHandler}.
     */
    public LevelProcessingHandler() {
        // Register as listener for layer changes
        MainApplication.getLayerManager().addActiveLayerChangeListener(this);
        
        // Setup initial dataset listener
        updateDataSetListener();
    }
    
    /**
     * Updates the dataset reference when the active layer changes.
     */
    private void updateDataSetListener() {
        // Update current dataset reference
        Layer activeLayer = MainApplication.getLayerManager().getActiveLayer();
        if (activeLayer instanceof OsmDataLayer) {
            currentDataSet = ((OsmDataLayer) activeLayer).data;
            if (currentDataSet != null) {
                lastPrimitiveCount = currentDataSet.allPrimitives().size();
            }
        } else {
            currentDataSet = null;
            lastPrimitiveCount = 0;
        }
        
        updateCurrentLevelTag();
    }
    
    /**
     * Scans for new primitives in the dataset.
     * Should be called periodically or when edit mode exits.
     */
    public void scanForNewPrimitives() {
        if (currentDataSet == null || currentLevelTag == null) {
            return;
        }
        
        int currentCount = currentDataSet.allPrimitives().size();
        if (currentCount > lastPrimitiveCount) {
            // New primitives might have been added
            for (OsmPrimitive prim : currentDataSet.allPrimitives()) {
                if (prim.isNew() && prim.get("level") == null && !newElements.contains(prim)) {
                    newElements.add(prim);
                    System.out.println("[JOSM Assist] DEBUG: Tracked new element: " + prim + " for level: " + currentLevelTag);
                }
            }
            lastPrimitiveCount = currentCount;
        }
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        updateDataSetListener();
    }
    
    /**
     * Called when primitives are added to track new elements.
     * This should be called manually when new primitives are created.
     */
    public void onPrimitivesAdded(Collection<? extends OsmPrimitive> primitives) {
        // Track newly added primitives
        if (currentLevelTag != null && currentDataSet != null) {
            for (OsmPrimitive prim : primitives) {
                if (prim.getDataSet() == currentDataSet && prim.isNew() && prim.get("level") == null) {
                    newElements.add(prim);
                }
            }
        }
    }
    
    /**
     * Called when primitives are removed.
     */
    public void onPrimitivesRemoved(Collection<? extends OsmPrimitive> primitives) {
        // Remove from tracking if deleted
        newElements.removeAll(primitives);
    }
    
    /**
     * Called when selection changes to update level tag.
     */
    public void onSelectionChanged() {
        // Update level tag when selection changes
        updateCurrentLevelTag();
    }

    /**
     * Updates the current level tag based on selected level filter.
     * JOSM's level filtering makes non-selected levels grey.
     * We detect the active level by checking JOSM's filter system.
     */
    private void updateCurrentLevelTag() {
        Layer activeLayer = MainApplication.getLayerManager().getActiveLayer();
        if (activeLayer instanceof OsmDataLayer) {
            OsmDataLayer dataLayer = (OsmDataLayer) activeLayer;
            DataSet ds = dataLayer.data;
            
            if (ds == null) {
                currentLevelTag = null;
                return;
            }
            
            // Method 1: Try to detect level filter from JOSM's filter system
            // JOSM has a filter system that can filter by level
            try {
                // Try to access filter through layer
                if (activeLayer instanceof OsmDataLayer) {
                    // Check if there's a filter active on the layer
                    // JOSM filters are typically accessed through the layer or dataset
                    // Try reflection to access filter information
                    try {
                        java.lang.reflect.Field filterField = ds.getClass().getDeclaredField("filter");
                        filterField.setAccessible(true);
                        Object filter = filterField.get(ds);
                        if (filter != null) {
                            String filterString = filter.toString();
                            if (filterString != null && filterString.contains("level")) {
                                // Try to extract level value from filter
                                java.util.regex.Pattern levelPattern = java.util.regex.Pattern.compile("level[=:](\\S+)");
                                java.util.regex.Matcher matcher = levelPattern.matcher(filterString);
                                if (matcher.find()) {
                                    String levelValue = matcher.group(1);
                                    currentLevelTag = levelValue;
                                    System.out.println("[JOSM Assist] DEBUG: Detected level from filter: " + currentLevelTag);
                                    return;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Filter detection via reflection failed, try other methods
                    }
                }
            } catch (Exception e) {
                // Filter detection failed, try other methods
            }
            
            // Method 2: Check selected primitives for level tags
            Collection<OsmPrimitive> selected = ds.getSelected();
            if (!selected.isEmpty()) {
                // Check if all selected have the same level tag
                String levelTag = null;
                for (OsmPrimitive prim : selected) {
                    String primLevel = prim.get("level");
                    if (primLevel != null) {
                        if (levelTag == null) {
                            levelTag = primLevel;
                        } else if (!levelTag.equals(primLevel)) {
                            // Multiple levels selected, don't auto-assign
                            levelTag = null;
                            break;
                        }
                    }
                }
                if (levelTag != null) {
                    currentLevelTag = levelTag;
                    System.out.println("[JOSM Assist] DEBUG: Detected level from selection: " + currentLevelTag);
                    return;
                }
            }
            
            // Method 3: Check visible (non-grey) primitives for consistent level
            // When a level filter is active, only primitives with that level are visible
            try {
                java.util.Map<String, Integer> levelCounts = new java.util.HashMap<>();
                int totalVisible = 0;
                
                for (OsmPrimitive prim : ds.allPrimitives()) {
                    // Check if primitive is visible (not filtered out)
                    if (!prim.isDisabled() && prim.isVisible()) {
                        totalVisible++;
                        String primLevel = prim.get("level");
                        if (primLevel != null && !primLevel.isEmpty()) {
                            levelCounts.put(primLevel, levelCounts.getOrDefault(primLevel, 0) + 1);
                        }
                    }
                }
                
                // If most visible primitives have the same level, that's likely the filtered level
                if (!levelCounts.isEmpty() && totalVisible > 0) {
                    // Find the level with the most primitives
                    String mostCommonLevel = null;
                    int maxCount = 0;
                    for (java.util.Map.Entry<String, Integer> entry : levelCounts.entrySet()) {
                        if (entry.getValue() > maxCount) {
                            maxCount = entry.getValue();
                            mostCommonLevel = entry.getKey();
                        }
                    }
                    
                    // If this level represents a significant portion of visible primitives, use it
                    if (mostCommonLevel != null && maxCount * 2 > totalVisible) {
                        currentLevelTag = mostCommonLevel;
                        System.out.println("[JOSM Assist] DEBUG: Detected level from visible primitives: " + currentLevelTag + " (" + maxCount + "/" + totalVisible + " visible)");
                        return;
                    }
                }
            } catch (Exception e) {
                System.out.println("[JOSM Assist] DEBUG: Visibility check failed: " + e.getMessage());
            }
            
            // No level detected
            currentLevelTag = null;
            System.out.println("[JOSM Assist] DEBUG: No level detected");
        } else {
            currentLevelTag = null;
        }
    }

    /**
     * Marks an element as newly created.
     * @param primitive the new element
     */
    public void markAsNew(OsmPrimitive primitive) {
        if (primitive != null && currentLevelTag != null) {
            newElements.add(primitive);
        }
    }

    /**
     * Processes new elements when edit mode is exited.
     * Assigns the current level tag to new elements.
     */
    public void processNewElementsOnEditExit() {
        // First, scan for any new primitives that might have been created
        scanForNewPrimitives();
        
        // Update level tag before processing (in case filter changed)
        updateCurrentLevelTag();
        
        if (currentLevelTag == null) {
            System.out.println("[JOSM Assist] DEBUG: No level tag to assign, clearing tracked elements");
            newElements.clear();
            return;
        }
        
        if (newElements.isEmpty()) {
            System.out.println("[JOSM Assist] DEBUG: No new elements to process");
            return;
        }

        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) {
            newElements.clear();
            return;
        }

        // Assign level tag to all new elements
        int assignedCount = 0;
        Set<OsmPrimitive> toRemove = new HashSet<>();
        for (OsmPrimitive prim : newElements) {
            if (prim.getDataSet() == ds && prim.get("level") == null) {
                prim.put("level", currentLevelTag);
                assignedCount++;
                System.out.println("[JOSM Assist] DEBUG: Assigned level tag '" + currentLevelTag + "' to: " + prim);
                toRemove.add(prim);
            } else if (prim.getDataSet() != ds) {
                // Element is from different dataset, remove from tracking
                toRemove.add(prim);
            }
        }
        
        newElements.removeAll(toRemove);
        System.out.println("[JOSM Assist] DEBUG: Assigned level tag to " + assignedCount + " new elements");
        
        // Update the map display
        MainApplication.getMap().repaint();
    }

    /**
     * Gets the current level tag.
     * @return the current level tag, or null if none
     */
    public String getCurrentLevelTag() {
        return currentLevelTag;
    }

    /**
     * Sets the current level tag manually.
     * @param levelTag the level tag to set
     */
    public void setCurrentLevelTag(String levelTag) {
        this.currentLevelTag = levelTag;
    }
}

