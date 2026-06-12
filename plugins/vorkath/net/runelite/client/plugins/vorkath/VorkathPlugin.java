/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Vorkath",
	description = "Ranged Vorkath farmer: prayer-flicks the fight, dodges acid/firebombs, "
		+ "Crumble-Undeads the spawn, loots and restocks on a loop",
	tags = {"vorkath", "boss", "pvm", "combat", "dragon", "ranged", "microbot"},
	enabledByDefault = false
)
public class VorkathPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(VorkathPlugin.class);

	@Inject
	private VorkathConfig config;

	@Inject
	private VorkathScript script;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private VorkathOverlay overlay;

	@Provides
	VorkathConfig provideConfig(ConfigManager configManager)
	{
		// RuneLite's config panel calls Enum.valueOf on the raw stored value with no null guard,
		// so un-persisted enum defaults make the panel throw "Name is null". Persist them eagerly.
		persistEnum(configManager, "offensivePrayer", OffensivePrayer.RIGOUR.name());
		persistEnum(configManager, "venomProtection", VenomProtection.SERPENTINE_HELM.name());
		return configManager.getConfig(VorkathConfig.class);
	}

	private static void persistEnum(ConfigManager configManager, String key, String value)
	{
		if (configManager.getConfiguration(VorkathConfig.GROUP, key) == null)
		{
			configManager.setConfiguration(VorkathConfig.GROUP, key, value);
		}
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		log.info("Vorkath started");
		script.run(config, itemManager);
	}

	@Override
	protected void shutDown()
	{
		script.shutdown();
		overlayManager.remove(overlay);
		log.info("Vorkath stopped");
	}

	public VorkathScript getScript()
	{
		return script;
	}
}
