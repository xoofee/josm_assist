package org.openstreetmap.josm.plugins.josmassist;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.event.DataSetListener;
import org.openstreetmap.josm.data.osm.event.DatasetEventManager;
import org.openstreetmap.josm.data.osm.event.DatasetEventManager.FireMode;
import org.openstreetmap.josm.data.osm.event.PrimitivesAddedEvent;
import org.openstreetmap.josm.data.osm.event.PrimitivesRemovedEvent;
import org.openstreetmap.josm.data.osm.event.TagsChangedEvent;
import org.openstreetmap.josm.data.osm.event.NodeMovedEvent;
import org.openstreetmap.josm.data.osm.event.WayNodesChangedEvent;
import org.openstreetmap.josm.data.osm.event.RelationMembersChangedEvent;
import org.openstreetmap.josm.data.osm.event.DataChangedEvent;
import org.openstreetmap.josm.data.osm.event.AbstractDatasetChangedEvent;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.autofilter.AutoFilter;
import org.openstreetmap.josm.gui.autofilter.AutoFilterManager;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

/**
 * Handles enhanced level processing.
 * When one level is selected, new elements automatically get the level tag
 * after the user exits edit mode.
 */
public class LevelProcessingHandler implements ActiveLayerChangeListener, DataSetListener {

    private String currentLevelTag = null;
    private Set<OsmPrimitive> newElements = new HashSet<>();
    private DataSet currentDataSet = null;

    /**
     * Constructs a new {@code LevelProcessingHandler}.
     */
    public LevelProcessingHandler() {
        // Register as listener for layer changes
        MainApplication.getLayerManager().addActiveLayerChangeListener(this);
        
        // Register as DataSet listener to track new primitives
        // Use IN_EDT_CONSOLIDATED to get events in EDT, but events might be consolidated
        DatasetEventManager.getInstance().addDatasetListener(this, FireMode.IN_EDT_CONSOLIDATED);
        System.out.println("[JOSM Assist] DEBUG: LevelProcessingHandler initialized and registered as DataSet listener");
        
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
        } else {
            currentDataSet = null;
        }
        
        updateCurrentLevelTag();
    }
    
    /**
     * Scans for new primitives in the dataset.
     * This is a fallback method - we primarily rely on primitivesAdded events.
     * Only scans primitives that were added since last check.
     */
    public void scanForNewPrimitives() {
        // Don't use this method - we rely on primitivesAdded events instead
        // This method could incorrectly identify existing primitives as new
        // Keeping it empty to avoid false positives
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        updateDataSetListener();
    }
    
    /**
     * Called when primitives are added to track new elements.
     * This should be called manually when new primitives are created.
     * Only tracks primitives that are TRULY newly created (isNew() == true).
     */
    public void onPrimitivesAdded(Collection<? extends OsmPrimitive> primitives) {
        // Track newly added primitives (even if level not detected yet - we might detect it later)
        // IMPORTANT: Only track primitives that are truly new (isNew() == true)
        if (currentDataSet != null) {
            for (OsmPrimitive prim : primitives) {
                // Triple-check: must be in correct dataset, must be new, must not have level tag yet
                if (prim.getDataSet() == currentDataSet && prim.isNew() && prim.get("level") == null) {
                    // Additional check: make sure it's not already tracked
                    if (!newElements.contains(prim)) {
                        newElements.add(prim);
                        System.out.println("[JOSM Assist] DEBUG: Tracked newly created element: " + prim.getClass().getSimpleName() + " " + prim + " (current level: " + currentLevelTag + ")");
                    }
                } else {
                    // Debug why we're not tracking this primitive
                    if (prim.getDataSet() != currentDataSet) {
                        System.out.println("[JOSM Assist] DEBUG: Not tracking - wrong dataset: " + prim);
                    } else if (!prim.isNew()) {
                        System.out.println("[JOSM Assist] DEBUG: Not tracking - not new: " + prim);
                    } else if (prim.get("level") != null) {
                        System.out.println("[JOSM Assist] DEBUG: Not tracking - already has level: " + prim);
                    }
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
    
    // DataSetListener implementation
    @Override
    public void primitivesAdded(PrimitivesAddedEvent event) {
        System.out.println("[JOSM Assist] DEBUG: primitivesAdded() called - wasIncomplete=" + event.wasIncomplete() + ", dataset=" + event.getDataset());
        
        if (event.wasIncomplete()) {
            // Primitive was already in dataset, just became complete - don't track
            System.out.println("[JOSM Assist] DEBUG: Skipping incomplete primitives");
            return;
        }
        
        // Update level tag first in case filter changed (this is important!)
        updateCurrentLevelTag();
        
        // Track ONLY the primitives that were just added in this event
        // These are the truly newly created primitives
        Collection<? extends OsmPrimitive> justAdded = event.getPrimitives();
        System.out.println("[JOSM Assist] DEBUG: primitivesAdded event - " + justAdded.size() + " primitive(s) just added");
        
        // Debug: print details of each primitive
        for (OsmPrimitive prim : justAdded) {
            System.out.println("[JOSM Assist] DEBUG:   - " + prim.getClass().getSimpleName() + " " + prim + 
                " (isNew=" + prim.isNew() + ", hasLevel=" + (prim.get("level") != null) + 
                ", dataset=" + (prim.getDataSet() == currentDataSet) + ")");
        }
        
        // Track new primitives (will track even if level not detected yet)
        onPrimitivesAdded(justAdded);
        
        System.out.println("[JOSM Assist] DEBUG: After tracking, newElements.size()=" + newElements.size());
    }
    
    @Override
    public void primitivesRemoved(PrimitivesRemovedEvent event) {
        onPrimitivesRemoved(event.getPrimitives());
    }
    
    @Override
    public void tagsChanged(TagsChangedEvent event) {
        // If a tracked element gets a level tag, remove it from tracking
        OsmPrimitive prim = event.getPrimitive();
        if (prim.get("level") != null && newElements.contains(prim)) {
            newElements.remove(prim);
        }
    }
    
    @Override
    public void nodeMoved(NodeMovedEvent event) {
        // Not relevant for level tracking
    }
    
    @Override
    public void wayNodesChanged(WayNodesChangedEvent event) {
        // Not relevant for level tracking
    }
    
    @Override
    public void relationMembersChanged(RelationMembersChangedEvent event) {
        // Not relevant for level tracking
    }
    
    @Override
    public void otherDatasetChange(AbstractDatasetChangedEvent event) {
        // Not relevant for level tracking
    }
    
    @Override
    public void dataChanged(DataChangedEvent event) {
        // Update level tag when data changes significantly
        System.out.println("[JOSM Assist] DEBUG: dataChanged event received");
        
        // DataChangedEvent can contain other events when consolidated
        // Check if it contains PrimitivesAddedEvents
        List<AbstractDatasetChangedEvent> events = event.getEvents();
        if (events != null && !events.isEmpty()) {
            System.out.println("[JOSM Assist] DEBUG: dataChanged contains " + events.size() + " sub-events");
            for (AbstractDatasetChangedEvent subEvent : events) {
                if (subEvent instanceof PrimitivesAddedEvent) {
                    System.out.println("[JOSM Assist] DEBUG: Found PrimitivesAddedEvent in dataChanged");
                    primitivesAdded((PrimitivesAddedEvent) subEvent);
                }
            }
        } else {
            // No sub-events, this is a full dataset change
            System.out.println("[JOSM Assist] DEBUG: dataChanged is a full dataset change (no sub-events)");
        }
        
        updateCurrentLevelTag();
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
     * We detect the active level by checking JOSM's AutoFilterManager.
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
            
            // Method 1: Check JOSM's AutoFilterManager for active level filter
            // This is the primary method - JOSM uses AutoFilterManager for level filtering
            try {
                AutoFilterManager autoFilterManager = AutoFilterManager.getInstance();
                if (autoFilterManager != null) {
                    AutoFilter currentAutoFilter = autoFilterManager.getCurrentAutoFilter();
                    if (currentAutoFilter != null) {
                        org.openstreetmap.josm.data.osm.Filter filter = currentAutoFilter.getFilter();
                        if (filter != null) {
                            // Try to get filter text - it's a public field in SearchSetting (parent of Filter)
                            String filterText = null;
                            try {
                                // Direct access to public field
                                filterText = filter.text;
                            } catch (Exception e1) {
                                // Fallback: try reflection
                                try {
                                    java.lang.reflect.Field textField = filter.getClass().getField("text");
                                    filterText = (String) textField.get(filter);
                                } catch (Exception e2) {
                                    // Last fallback: use toString()
                                    filterText = filter.toString();
                                }
                            }
                            
                            if (filterText != null && !filterText.isEmpty()) {
                                // Filter text is typically "level=1" or "level=-1"
                                java.util.regex.Pattern levelPattern = java.util.regex.Pattern.compile("level[=:]([^\\s]+)");
                                java.util.regex.Matcher matcher = levelPattern.matcher(filterText);
                                if (matcher.find()) {
                                    String levelValue = matcher.group(1);
                                    currentLevelTag = levelValue;
                                    System.out.println("[JOSM Assist] DEBUG: Detected level from AutoFilterManager: " + currentLevelTag + " (filter: " + filterText + ")");
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[JOSM Assist] DEBUG: AutoFilterManager check failed: " + e.getMessage());
                e.printStackTrace();
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
     * Only processes primitives that were tracked via primitivesAdded events.
     */
    public void processNewElementsOnEditExit() {
        // Don't scan - we only track primitives via primitivesAdded events
        // scanForNewPrimitives(); // Removed - could incorrectly identify existing primitives
        
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

        // Assign level tag to all tracked new elements
        // These should only be primitives that were added via primitivesAdded events
        int assignedCount = 0;
        Set<OsmPrimitive> toRemove = new HashSet<>();
        for (OsmPrimitive prim : newElements) {
            // Double-check: only assign to primitives that are still new and in the correct dataset
            if (prim.getDataSet() == ds && prim.isNew() && prim.get("level") == null) {
                prim.put("level", currentLevelTag);
                assignedCount++;
                System.out.println("[JOSM Assist] DEBUG: Assigned level tag '" + currentLevelTag + "' to newly created: " + prim);
                toRemove.add(prim);
            } else if (prim.getDataSet() != ds) {
                // Element is from different dataset, remove from tracking
                toRemove.add(prim);
            } else if (!prim.isNew()) {
                // Primitive is no longer new (might have been uploaded or changed), remove from tracking
                System.out.println("[JOSM Assist] DEBUG: Skipping primitive that is no longer new: " + prim);
                toRemove.add(prim);
            } else if (prim.get("level") != null) {
                // Already has a level tag, remove from tracking
                System.out.println("[JOSM Assist] DEBUG: Skipping primitive that already has level tag: " + prim);
                toRemove.add(prim);
            }
        }
        
        newElements.removeAll(toRemove);
        System.out.println("[JOSM Assist] DEBUG: Assigned level tag to " + assignedCount + " newly created element(s)");
        
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

