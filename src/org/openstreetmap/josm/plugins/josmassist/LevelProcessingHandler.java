package org.openstreetmap.josm.plugins.josmassist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
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
 * Handles level processing for newly created primitives.
 * When a level filter is active, new primitives automatically get the level tag
 * when the user exits edit mode (presses Esc).
 */
public class LevelProcessingHandler implements ActiveLayerChangeListener, DataSetListener {

    private String currentLevelTag = null;
    private final Set<OsmPrimitive> newElements = new HashSet<>();
    private DataSet currentDataSet = null;
    private static final Pattern LEVEL_PATTERN = Pattern.compile("level[=:]([^\\s]+)");

    public LevelProcessingHandler() {
        MainApplication.getLayerManager().addActiveLayerChangeListener(this);
        DatasetEventManager.getInstance().addDatasetListener(this, FireMode.IN_EDT_CONSOLIDATED);
        updateCurrentDataSet();
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        updateCurrentDataSet();
    }

    private void updateCurrentDataSet() {
        Layer activeLayer = MainApplication.getLayerManager().getActiveLayer();
        currentDataSet = (activeLayer instanceof OsmDataLayer) ? ((OsmDataLayer) activeLayer).data : null;
        updateCurrentLevelTag();
    }

    // ========== DataSetListener Implementation ==========

    @Override
    public void primitivesAdded(PrimitivesAddedEvent event) {
        if (event.wasIncomplete()) {
            return;
        }

        updateCurrentLevelTag();

        for (OsmPrimitive prim : event.getPrimitives()) {
            if (shouldTrack(prim)) {
                newElements.add(prim);
                System.out.println("[JOSM Assist] primitivesAdded DEBUG: Tracked: " + prim.getClass().getSimpleName() + " " + prim);
            }
        }
    }

    @Override
    public void primitivesRemoved(PrimitivesRemovedEvent event) {
        newElements.removeAll(event.getPrimitives());
    }

    @Override
    public void tagsChanged(TagsChangedEvent event) {
        OsmPrimitive prim = event.getPrimitive();
        if (prim.get("level") != null) {
            newElements.remove(prim);
        }
    }

    @Override
    public void dataChanged(DataChangedEvent event) {
        List<AbstractDatasetChangedEvent> subEvents = event.getEvents();
        if (subEvents != null) {
            for (AbstractDatasetChangedEvent subEvent : subEvents) {
                if (subEvent instanceof PrimitivesAddedEvent) {
                    primitivesAdded((PrimitivesAddedEvent) subEvent);
                }
            }
        }
        updateCurrentLevelTag();
    }

    @Override
    public void nodeMoved(NodeMovedEvent event) {
        // Not relevant
    }

    @Override
    public void wayNodesChanged(WayNodesChangedEvent event) {
        // Not relevant
    }

    @Override
    public void relationMembersChanged(RelationMembersChangedEvent event) {
        // Not relevant
    }

    @Override
    public void otherDatasetChange(AbstractDatasetChangedEvent event) {
        // Not relevant
    }

    // ========== Level Detection ==========

    private void updateCurrentLevelTag() {
        if (currentDataSet == null) {
            currentLevelTag = null;
            return;
        }

        // Method 1: AutoFilterManager (primary method)
        String level = detectLevelFromAutoFilter();
        if (level != null) {
            currentLevelTag = level;
            System.out.println("[JOSM Assist] DEBUG: Detected level from AutoFilterManager: " + level);
            return;
        }

        // Method 2: Selected primitives
        level = detectLevelFromSelection(currentDataSet);
        if (level != null) {
            currentLevelTag = level;
            System.out.println("[JOSM Assist] DEBUG: Detected level from selection: " + level);
            return;
        }

        // Method 3: Visible primitives (fallback)
        level = detectLevelFromVisiblePrimitives(currentDataSet);
        if (level != null) {
            currentLevelTag = level;
            System.out.println("[JOSM Assist] DEBUG: Detected level from visible primitives: " + level);
            return;
        }

        currentLevelTag = null;
        System.out.println("[JOSM Assist] DEBUG: No level detected");
    }

    private String detectLevelFromAutoFilter() {
        try {
            AutoFilterManager manager = AutoFilterManager.getInstance();
            if (manager == null) return null;

            AutoFilter autoFilter = manager.getCurrentAutoFilter();
            if (autoFilter == null) return null;

            String filterText = getFilterText(autoFilter.getFilter());
            if (filterText == null || filterText.isEmpty()) return null;

            Matcher matcher = LEVEL_PATTERN.matcher(filterText);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            System.out.println("[JOSM Assist] DEBUG: AutoFilterManager check failed: " + e.getMessage());
        }
        return null;
    }

    private String getFilterText(org.openstreetmap.josm.data.osm.Filter filter) {
        if (filter == null) return null;
        try {
            return filter.text; // Direct access to public field
        } catch (Exception e) {
            return filter.toString(); // Fallback
        }
    }

    private String detectLevelFromSelection(DataSet ds) {
        Collection<OsmPrimitive> selected = ds.getSelected();
        if (selected.isEmpty()) return null;

        String levelTag = null;
        for (OsmPrimitive prim : selected) {
            String primLevel = prim.get("level");
            if (primLevel != null) {
                if (levelTag == null) {
                    levelTag = primLevel;
                } else if (!levelTag.equals(primLevel)) {
                    return null; // Multiple levels selected
                }
            }
        }
        return levelTag;
    }

    private String detectLevelFromVisiblePrimitives(DataSet ds) {
        try {
            Map<String, Integer> levelCounts = new HashMap<>();
            int totalVisible = 0;

            for (OsmPrimitive prim : ds.allPrimitives()) {
                if (!prim.isDisabled() && prim.isVisible()) {
                    totalVisible++;
                    String primLevel = prim.get("level");
                    if (primLevel != null && !primLevel.isEmpty()) {
                        levelCounts.put(primLevel, levelCounts.getOrDefault(primLevel, 0) + 1);
                    }
                }
            }

            if (levelCounts.isEmpty() || totalVisible == 0) return null;

            // Find most common level
            String mostCommonLevel = null;
            int maxCount = 0;
            for (Map.Entry<String, Integer> entry : levelCounts.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    mostCommonLevel = entry.getKey();
                }
            }

            // Use if it represents significant portion
            if (mostCommonLevel != null && maxCount * 2 > totalVisible) {
                return mostCommonLevel;
            }
        } catch (Exception e) {
            System.out.println("[JOSM Assist] DEBUG: Visibility check failed: " + e.getMessage());
        }
        return null;
    }

    // ========== Processing ==========

    public void processNewElementsOnEditExit() {
        updateCurrentLevelTag();

        if (currentLevelTag == null || newElements.isEmpty()) {
            newElements.clear();
            return;
        }

        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) {
            newElements.clear();
            return;
        }

        // Separate nodes and ways
        List<Node> nodes = new ArrayList<>();
        List<Way> ways = new ArrayList<>();
        for (OsmPrimitive prim : newElements) {
            if (prim instanceof Node && isValidForProcessing(prim, ds)) {
                nodes.add((Node) prim);
            } else if (prim instanceof Way && isValidForProcessing(prim, ds)) {
                ways.add((Way) prim);
            }
        }

        // Decision: assign to single node only if exactly 1 node and no ways
        boolean assignToSingleNode = (nodes.size() == 1 && ways.isEmpty());

        // Assign level tags
        int assignedCount = 0;
        for (OsmPrimitive prim : newElements) {
            if (!isValidForProcessing(prim, ds)) continue;

            boolean shouldAssign = false;
            if (prim instanceof Node) {
                shouldAssign = assignToSingleNode;
            } else if (prim instanceof Way) {
                shouldAssign = true; // Always assign to ways
            } else {
                shouldAssign = true; // Relations and other types
            }

            if (shouldAssign) {
                prim.put("level", currentLevelTag);
                assignedCount++;
                System.out.println("[JOSM Assist] DEBUG: Assigned level '" + currentLevelTag + "' to: " + prim);
            }
        }

        newElements.clear();
        System.out.println("[JOSM Assist] DEBUG: Assigned level to " + assignedCount + " element(s)");
        MainApplication.getMap().repaint();
    }

    private boolean shouldTrack(OsmPrimitive prim) {
        return prim.getDataSet() == currentDataSet 
            && prim.isNew() 
            && prim.get("level") == null
            && !newElements.contains(prim);
    }

    private boolean isValidForProcessing(OsmPrimitive prim, DataSet ds) {
        return prim.getDataSet() == ds 
            && prim.isNew() 
            && prim.get("level") == null;
    }

    // ========== Public API ==========

    public String getCurrentLevelTag() {
        return currentLevelTag;
    }

    public void setCurrentLevelTag(String levelTag) {
        this.currentLevelTag = levelTag;
    }

    public void onSelectionChanged() {
        updateCurrentLevelTag();
    }
}
