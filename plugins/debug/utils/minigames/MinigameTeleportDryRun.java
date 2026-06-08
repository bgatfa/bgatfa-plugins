package utils.minigames;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Non-destructive validation of every {@link MinigameTeleport} mapping against the live interface.
 *
 * <p>Snapshots widget group 951 once and checks that each constant's {@code childId} resolves to a
 * row whose label matches the expected {@link MinigameTeleport#display} name and exposes the
 * "Select" action — <b>without clicking anything</b>. Use this to confirm the ID table is still
 * correct (e.g. after a game update) before relying on {@link MinigameTeleport#teleport()}.
 *
 * <p>Run it from inside the client with the Minigame Grouping panel <b>open</b>; nothing teleports.
 * For an external (no-plugin) equivalent, GET {@code /widgets/describe?groupId=951} from the Agent
 * Server and apply the same checks.
 */
public final class MinigameTeleportDryRun {

    private MinigameTeleportDryRun() {
    }

    /** Outcome for one teleport mapping. */
    public static final class Result {
        public final MinigameTeleport teleport;
        public final boolean pass;
        public final String detail;

        Result(MinigameTeleport teleport, boolean pass, String detail) {
            this.teleport = teleport;
            this.pass = pass;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return (pass ? "PASS " : "FAIL ") + teleport.name()
                + " (951." + teleport.childId + ") " + detail;
        }
    }

    /** Validate every mapping against the open interface. Never clicks. */
    public static List<Result> run() {
        Rs2InterfaceMap map = Rs2InterfaceMap.of(MinigameTeleport.GROUP);
        List<Result> results = new ArrayList<>();

        if (map.isEmpty()) {
            for (MinigameTeleport t : MinigameTeleport.values()) {
                results.add(new Result(t, false, "interface not open (empty snapshot)"));
            }
            return results;
        }

        Map<Integer, Rs2InterfaceMap.Row> byChild = new HashMap<>();
        for (Rs2InterfaceMap.Row row : map.rows()) {
            byChild.put(row.childId, row);
        }

        for (MinigameTeleport t : MinigameTeleport.values()) {
            Rs2InterfaceMap.Row row = byChild.get(t.childId);
            if (row == null) {
                results.add(new Result(t, false, "no clickable row at this childId"));
            } else if (!row.action.hasAction("Select")) {
                results.add(new Result(t, false, "row lacks 'Select' (actions=" + row.action.actions + ")"));
            } else if (!row.label.toLowerCase().contains(t.display.toLowerCase())) {
                results.add(new Result(t, false, "label '" + row.label + "' != expected '" + t.display + "'"));
            } else {
                results.add(new Result(t, true, "'" + row.label + "' [Select]"));
            }
        }
        return results;
    }

    /** Convenience: a printable report with a pass/total header. */
    public static String report() {
        List<Result> results = run();
        int passed = 0;
        StringBuilder sb = new StringBuilder();
        for (Result r : results) {
            if (r.pass) {
                passed++;
            }
            sb.append("  ").append(r).append('\n');
        }
        return "MinigameTeleport dry run: " + passed + "/" + results.size() + " valid\n" + sb;
    }
}
