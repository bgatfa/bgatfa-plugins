/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.firemaker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(FiremakerConfig.GROUP)
public interface FiremakerConfig extends Config
{
	String GROUP = "firemaker";

	@ConfigItem(
		keyName = "logType",
		name = "Log",
		description = "Which logs to withdraw from the bank and burn. Stand where you want to "
			+ "firemake, then enable the plugin — that spot becomes the east end of the lane."
	)
	default LogType logType()
	{
		return LogType.MAPLE;
	}
}
