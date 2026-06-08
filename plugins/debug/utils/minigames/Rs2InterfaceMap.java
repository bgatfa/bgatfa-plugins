package utils.minigames;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.widget.Rs2WidgetInspector;
import net.runelite.client.plugins.microbot.util.widget.Rs2WidgetInspector.WidgetDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A typed, queryable snapshot of one RuneLite widget interface group.
 *
 * <p>Wraps {@link Rs2WidgetInspector#describeWidget(int, int, int)} — the flattening walk behind
 * the Agent Server's {@code /widgets/describe} — into immutable {@link IfaceNode}s with finders
 * and a row model. The walk is marshalled onto the client thread; the result is a point-in-time
 * snapshot and does not track later UI changes (re-{@link #of(int) snapshot} after the UI moves).
 */
public final class Rs2InterfaceMap {

    private final int group;
    private final List<IfaceNode> nodes;

    private Rs2InterfaceMap(int group, List<IfaceNode> nodes) {
        this.group = group;
        this.nodes = nodes;
    }

    /** Snapshot an interface group (default depth). Safe to call from any thread. */
    public static Rs2InterfaceMap of(int group) {
        return of(group, 8);
    }

    public static Rs2InterfaceMap of(int group, int maxDepth) {
        List<WidgetDescription> raw = Microbot.getClientThread()
            .runOnClientThreadOptional(() -> Rs2WidgetInspector.describeWidget(group, 0, maxDepth))
            .orElse(Collections.emptyList());

        List<IfaceNode> mapped = new ArrayList<>(raw.size());
        for (WidgetDescription d : raw) {
            mapped.add(IfaceNode.from(d));
        }
        return new Rs2InterfaceMap(group, Collections.unmodifiableList(mapped));
    }

    public int group() {
        return group;
    }

    public List<IfaceNode> all() {
        return nodes;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** Every visible node that carries at least one menu action. */
    public List<IfaceNode> interactive() {
        List<IfaceNode> out = new ArrayList<>();
        for (IfaceNode n : nodes) {
            if (n.interactive()) {
                out.add(n);
            }
        }
        return out;
    }

    /** First node whose text equals (case-insensitive) the given label. */
    public Optional<IfaceNode> byText(String text) {
        for (IfaceNode n : nodes) {
            if (n.text.equalsIgnoreCase(text)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /** First node whose text contains the given substring (case-insensitive). */
    public Optional<IfaceNode> containing(String substring) {
        String needle = substring.toLowerCase();
        for (IfaceNode n : nodes) {
            if (n.text.toLowerCase().contains(needle)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /** First interactive node exposing the given action (e.g. "Select", "Close"). */
    public Optional<IfaceNode> withAction(String action) {
        for (IfaceNode n : nodes) {
            if (n.hasAction(action)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    /**
     * Row model for list-style interfaces: one {@link Row} per {@code childId} that has a
     * clickable node, paired with the TEXT label found under the same childId.
     *
     * <p>This is exactly how the Minigame Grouping panel (group 951) decomposes — and childIds
     * with no interactive node (Sorceress's Garden, Mage Training Arena) are naturally excluded
     * because their rows expose only label text, no action.
     */
    public List<Row> rows() {
        Map<Integer, List<IfaceNode>> byChild = new LinkedHashMap<>();
        for (IfaceNode n : nodes) {
            byChild.computeIfAbsent(n.child, k -> new ArrayList<>()).add(n);
        }

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Integer, List<IfaceNode>> e : byChild.entrySet()) {
            IfaceNode action = null;
            String label = "";
            for (IfaceNode n : e.getValue()) {
                if (action == null && n.interactive()) {
                    action = n;
                }
                if (label.isEmpty() && "TEXT".equals(n.type) && !n.text.isEmpty()) {
                    label = n.text;
                }
            }
            if (action != null) {
                rows.add(new Row(e.getKey(), label, action));
            }
        }
        return rows;
    }

    /** A clickable list entry: its childId, human label, and the action-bearing node. */
    public static final class Row {
        public final int childId;
        public final String label;
        public final IfaceNode action;

        Row(int childId, String label, IfaceNode action) {
            this.childId = childId;
            this.label = label;
            this.action = action;
        }

        @Override
        public String toString() {
            return childId + "  " + label + "  " + action.actions;
        }
    }
}
