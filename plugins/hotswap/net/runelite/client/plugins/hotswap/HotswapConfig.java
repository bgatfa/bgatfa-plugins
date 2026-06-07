/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.hotswap;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(HotswapConfig.GROUP)
public interface HotswapConfig extends Config
{
	String GROUP = "hotswap";

	@Range(min = 200, max = 10000)
	@Units(Units.MILLISECONDS)
	@ConfigItem(
		keyName = "pollIntervalMillis",
		name = "Poll interval",
		description = "How often to scan the sideloaded-plugins folder for changed jars."
	)
	default int pollIntervalMillis()
	{
		return 750;
	}

	@ConfigItem(
		keyName = "unloadOnDelete",
		name = "Unload on delete",
		description = "Stop and remove a plugin when its jar is deleted from the folder."
	)
	default boolean unloadOnDelete()
	{
		return true;
	}
}
