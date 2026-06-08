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
		keyName = "dryRun",
		name = "Dry run",
		description = "Preview the withdrawal plan (log + report file) without actually withdrawing.",
		position = 0
	)
	default boolean dryRun()
	{
		return true;
	}

	@ConfigItem(
		keyName = "writeReport",
		name = "Write report file",
		description = "Also write the plan/result to ~/.runelite/topwithdraw-last.txt.",
		position = 1
	)
	default boolean writeReport()
	{
		return true;
	}
}
