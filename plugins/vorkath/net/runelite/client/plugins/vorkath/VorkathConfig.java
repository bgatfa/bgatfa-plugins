/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(VorkathConfig.GROUP)
public interface VorkathConfig extends Config
{
	String GROUP = "vorkath";

	// ----- sections -----------------------------------------------------------------

	@ConfigSection(name = "Combat", description = "Prayers, weapons and special attack", position = 0)
	String combatSection = "combat";

	@ConfigSection(name = "Supplies", description = "Food, prayer, antifire and venom", position = 1)
	String suppliesSection = "supplies";

	@ConfigSection(name = "Looting", description = "What to pick up", position = 2)
	String lootSection = "loot";

	@ConfigSection(name = "Travel & restock", description = "Banking and re-entry", position = 3)
	String travelSection = "travel";

	@ConfigSection(name = "Session", description = "Stop conditions", position = 4)
	String sessionSection = "session";

	// ----- combat -------------------------------------------------------------------

	@ConfigItem(
		keyName = "offensivePrayer",
		name = "Offensive prayer",
		description = "Damage prayer kept on alongside Protect from Missiles",
		position = 0,
		section = combatSection
	)
	default OffensivePrayer offensivePrayer()
	{
		return OffensivePrayer.RIGOUR;
	}

	@ConfigItem(
		keyName = "rangedWeaponName",
		name = "Ranged weapon",
		description = "Name (or substring) of your crossbow — re-equipped after each spawn",
		position = 1,
		section = combatSection
	)
	default String rangedWeaponName()
	{
		return "Dragon hunter crossbow";
	}

	@ConfigItem(
		keyName = "slayerStaffName",
		name = "Spawn-kill staff",
		description = "Staff swapped in to Crumble Undead the Zombified Spawn (gives the magic "
			+ "bonus so the cast lands), then swapped back to the crossbow",
		position = 2,
		section = combatSection
	)
	default String slayerStaffName()
	{
		return "Slayer's staff";
	}

	@ConfigItem(
		keyName = "useSpecialAttack",
		name = "Use special attack",
		description = "Fire the crossbow special during the normal phase when energy allows",
		position = 3,
		section = combatSection
	)
	default boolean useSpecialAttack()
	{
		return false;
	}

	@ConfigItem(
		keyName = "specThreshold",
		name = "Spec energy %",
		description = "Minimum special-attack energy before using it",
		position = 4,
		section = combatSection
	)
	default int specThreshold()
	{
		return 50;
	}

	// ----- supplies -----------------------------------------------------------------

	@ConfigItem(
		keyName = "foodName",
		name = "Food",
		description = "Name of the food to eat",
		position = 0,
		section = suppliesSection
	)
	default String foodName()
	{
		return "Shark";
	}

	@ConfigItem(
		keyName = "eatAtPercent",
		name = "Eat at HP %",
		description = "Eat when health drops to this percentage",
		position = 1,
		section = suppliesSection
	)
	default int eatAtPercent()
	{
		return 55;
	}

	@ConfigItem(
		keyName = "prayerPotName",
		name = "Prayer restore",
		description = "Name of the prayer/super-restore potion to drink",
		position = 2,
		section = suppliesSection
	)
	default String prayerPotName()
	{
		return "Prayer potion";
	}

	@ConfigItem(
		keyName = "drinkPrayerAt",
		name = "Drink prayer at",
		description = "Drink a restore when prayer points fall below this",
		position = 3,
		section = suppliesSection
	)
	default int drinkPrayerAt()
	{
		return 25;
	}

	@ConfigItem(
		keyName = "antifireName",
		name = "Antifire",
		description = "Name of the antifire potion (kept active the whole fight)",
		position = 4,
		section = suppliesSection
	)
	default String antifireName()
	{
		return "Extended antifire";
	}

	@ConfigItem(
		keyName = "venomProtection",
		name = "Venom protection",
		description = "How venomous dragonfire's venom is handled",
		position = 5,
		section = suppliesSection
	)
	default VenomProtection venomProtection()
	{
		return VenomProtection.SERPENTINE_HELM;
	}

	@ConfigItem(
		keyName = "antiVenomName",
		name = "Anti-venom",
		description = "Anti-venom potion name (only used when Venom protection = Anti-venom)",
		position = 6,
		section = suppliesSection
	)
	default String antiVenomName()
	{
		return "Anti-venom+";
	}

	// ----- looting ------------------------------------------------------------------

	@ConfigItem(
		keyName = "lootWhitelist",
		name = "Loot whitelist",
		description = "Comma-separated name patterns to pick up. Supports * wildcards and regex, "
			+ "e.g. Superior dragon bones, Rune*, * dragon bolts, Vorkath's head",
		position = 0,
		section = lootSection
	)
	default String lootWhitelist()
	{
		return "Superior dragon bones, Vorkath's head, Jar of decay, Draconic visage, Dragonbone necklace";
	}

	// ----- travel & restock ---------------------------------------------------------

	@ConfigItem(
		keyName = "bankTeleportName",
		name = "Bank teleport",
		description = "Inventory item used to teleport towards the bank (e.g. Rellekka teleport, "
			+ "Enchanted lyre). Left blank = walk the whole way.",
		position = 0,
		section = travelSection
	)
	default String bankTeleportName()
	{
		return "Rellekka teleport";
	}

	@ConfigItem(
		keyName = "escapeTeleportName",
		name = "Escape teleport",
		description = "Item fired immediately on an emergency (out of supplies / watchdog)",
		position = 1,
		section = travelSection
	)
	default String escapeTeleportName()
	{
		return "Teleport to house";
	}

	@ConfigItem(
		keyName = "foodAmount",
		name = "Food per trip",
		description = "Target food count withdrawn each restock",
		position = 2,
		section = travelSection
	)
	default int foodAmount()
	{
		return 8;
	}

	@ConfigItem(
		keyName = "prayerPotAmount",
		name = "Prayer doses",
		description = "Target prayer/restore potions withdrawn each restock",
		position = 3,
		section = travelSection
	)
	default int prayerPotAmount()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "antifireAmount",
		name = "Antifire doses",
		description = "Target antifire potions withdrawn each restock",
		position = 4,
		section = travelSection
	)
	default int antifireAmount()
	{
		return 2;
	}

	// ----- session ------------------------------------------------------------------

	@ConfigItem(
		keyName = "killTarget",
		name = "Kill target",
		description = "Stop after this many kills (0 = unlimited)",
		position = 0,
		section = sessionSection
	)
	default int killTarget()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "stopOnDeath",
		name = "Stop on death",
		description = "If the player dies, stop instead of re-entering",
		position = 1,
		section = sessionSection
	)
	default boolean stopOnDeath()
	{
		return true;
	}
}
