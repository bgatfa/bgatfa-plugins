package utils.minigames;

import net.runelite.client.plugins.microbot.util.widget.Rs2WidgetInspector.WidgetDescription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a single widget reading, flattened from the live tree.
 *
 * <p>Built from {@link WidgetDescription} — the same structure {@code Rs2WidgetInspector} and the
 * Agent Server's {@code /widgets/describe} produce — so it holds no live, thread-unsafe
 * {@code Widget} reference and is safe to pass around and cache for the life of a snapshot.
 */
public final class IfaceNode {

    public final int group;
    public final int child;
    public final int index;        // >= 0 for a dynamic child within its parent component, else -1
    public final int itemId;
    public final int spriteId;
    public final boolean hidden;
    public final String type;      // e.g. LAYER, GRAPHIC, TEXT, RECTANGLE
    public final String text;      // "<br>" normalised to " | ", trimmed
    public final List<String> actions; // never null; empty == not clickable

    private IfaceNode(WidgetDescription d) {
        this.group = d.groupId;
        this.child = d.childId;
        this.index = d.index;
        this.itemId = d.itemId;
        this.spriteId = d.spriteId;
        this.hidden = d.hidden;
        this.type = d.type == null ? "" : d.type;
        this.text = d.text == null ? "" : d.text.replace("<br>", " | ").trim();

        List<String> acts = new ArrayList<>();
        if (d.actions != null) {
            for (String a : d.actions) {
                if (a != null && !a.trim().isEmpty()) {
                    acts.add(a);
                }
            }
        }
        this.actions = Collections.unmodifiableList(acts);
    }

    public static IfaceNode from(WidgetDescription d) {
        return new IfaceNode(d);
    }

    /** Visible and exposes at least one menu action. */
    public boolean interactive() {
        return !hidden && !actions.isEmpty();
    }

    /** Packed component id: {@code (group << 16) | child}. */
    public int packedId() {
        return (group << 16) | child;
    }

    public boolean hasAction(String action) {
        for (String a : actions) {
            if (a.equalsIgnoreCase(action)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return group + "." + child + (index >= 0 ? "[" + index + "]" : "")
            + " " + type
            + (text.isEmpty() ? "" : " \"" + text + "\"")
            + (actions.isEmpty() ? "" : " " + actions);
    }
}
