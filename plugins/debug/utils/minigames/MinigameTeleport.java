package utils.minigames;

import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.Optional;

/**
 * Typed handle on the Minigame Grouping / Teleport interface (widget group 951).
 *
 * <p>Each constant carries its row {@code childId} and the expected display label; the row's menu
 * action is "Select". Only minigames that expose a direct teleport are listed — Sorceress's Garden
 * (951.9) and Mage Training Arena (951.15) are label-only in-game (you must speak to an NPC first)
 * and are deliberately absent.
 *
 * <p>IDs were mapped live from the running client via {@link Rs2InterfaceMap#rows()} and validated
 * by {@link MinigameTeleportDryRun}; regenerate if Jagex reshuffles the group.
 */
public enum MinigameTeleport {

    TZHAAR_FIGHT_PIT(5, "TzHaar Fight Pit"),
    TROUBLE_BREWING(6, "Trouble Brewing"),
    TITHE_FARM(7, "Tithe Farm"),
    SOUL_WARS(8, "Soul Wars"),
    SHADES_OF_MORTTON(10, "Shades of Mort'ton"),
    RAT_PITS(11, "Rat Pits"),
    PEST_CONTROL(12, "Pest Control"),
    NIGHTMARE_ZONE(13, "Nightmare Zone"),
    MASTERING_MIXOLOGY(14, "Mastering Mixology"),
    LAST_MAN_STANDING(16, "Last Man Standing"),
    GUARDIANS_OF_THE_RIFT(17, "Guardians of the Rift"),
    GIANTS_FOUNDRY(18, "Giants' Foundry"),
    FISHING_TRAWLER(19, "Fishing Trawler"),
    CLAN_WARS(20, "Clan Wars"),
    CASTLE_WARS(21, "Castle Wars"),
    BURTHORPE_GAMES_ROOM(22, "Burthorpe Games Room"),
    BOUNTY_HUNTER(23, "Bounty Hunter"),
    BLAST_FURNACE(24, "Blast Furnace"),
    BARBARIAN_ASSAULT(25, "Barbarian Assault");

    /** Widget group id of the Minigame Grouping / Teleport interface. */
    public static final int GROUP = 951;

    public final int childId;
    public final String display;

    MinigameTeleport(int childId, String display) {
        this.childId = childId;
        this.display = display;
    }

    /** True if the Minigame Teleport interface is currently open. */
    public static boolean isOpen() {
        return Rs2Widget.isWidgetVisible(GROUP, 0);
    }

    /** Click this minigame's row (the "Select" teleport). Returns false if it isn't clickable. */
    public boolean teleport() {
        return Rs2Widget.clickWidget(GROUP, childId);
    }

    /** Resolve this row live against the open interface (childId, label, action node). */
    public Optional<Rs2InterfaceMap.Row> resolve() {
        for (Rs2InterfaceMap.Row row : Rs2InterfaceMap.of(GROUP).rows()) {
            if (row.childId == childId) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }
}
