/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 */
package net.runelite.client.plugins.microbot.bankvaluer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(BankValueTrackerConfig.GROUP)
public interface BankValueTrackerConfig extends Config
{
	String GROUP = "bankvaluetracker";

	@ConfigSection(
		name = "Display",
		description = "What to show in the bank value panel",
		position = 0
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Alerts",
		description = "Price alert behaviour",
		position = 1
	)
	String alertSection = "alerts";

	@ConfigItem(
		keyName = "showHighAlch",
		name = "Show high alch",
		description = "Show each item's high-alchemy value alongside its GE value",
		position = 1,
		section = displaySection
	)
	default boolean showHighAlch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "includeHiddenInTotal",
		name = "Hidden items count to total",
		description = "Include hidden items when computing the total bank value",
		position = 2,
		section = displaySection
	)
	default boolean includeHiddenInTotal()
	{
		return false;
	}

	@ConfigItem(
		keyName = "minValue",
		name = "Hide items under (gp)",
		description = "Items whose total GE value is below this are not listed",
		position = 3,
		section = displaySection
	)
	default int minValue()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "repeatAlerts",
		name = "Repeat alerts",
		description = "Re-arm an alert after the price crosses back over the threshold, instead of firing only once",
		position = 1,
		section = alertSection
	)
	default boolean repeatAlerts()
	{
		return true;
	}
}
