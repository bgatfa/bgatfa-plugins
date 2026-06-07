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
package net.runelite.client.plugins.bankvaluer;

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
		keyName = "minValue",
		name = "Hide under gp (GE)",
		description = "Items whose total GE value is below this are not listed",
		position = 3,
		section = displaySection
	)
	default int minValue()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "minValueHa",
		name = "Hide under gp (HA)",
		description = "Items whose total high-alch value is below this are not listed",
		position = 4,
		section = displaySection
	)
	default int minValueHa()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "hideUntradeable",
		name = "Hide untradeable & worthless",
		description = "Exclude untradeable items, and items worth nothing on both the GE and high alch, from the list and totals",
		position = 5,
		section = displaySection
	)
	default boolean hideUntradeable()
	{
		return true;
	}
}
