/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.topwithdraw;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("topwithdraw")
public interface BankCleanerConfig extends Config
{
	@ConfigItem(
		keyName = "showOverlay",
		name = "Show overlay",
		description = "Show the Bank Cleaner status overlay with current and upcoming item sprites.",
		position = 0
	)
	default boolean showOverlay()
	{
		return true;
	}
}
