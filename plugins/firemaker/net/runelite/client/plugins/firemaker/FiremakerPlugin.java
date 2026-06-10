/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.firemaker;

import com.google.inject.Provides;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Firemaker",
	description = "Withdraws logs from the bank and lights them in a loop",
	tags = {"firemaking", "fire", "skilling", "bank", "microbot", "log"},
	enabledByDefault = false
)
public class FiremakerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(FiremakerPlugin.class);

	@Inject
	private FiremakerConfig config;

	@Inject
	private FiremakerScript script;

	@Provides
	FiremakerConfig provideConfig(ConfigManager configManager)
	{
		// RuneLite's config panel builds the enum dropdown by calling Enum.valueOf on the RAW
		// stored value with no null guard (ConfigPanel.createComboBox), so an un-persisted enum
		// default makes opening the panel throw "Name is null". Persist the default eagerly.
		if (configManager.getConfiguration(FiremakerConfig.GROUP, "logType") == null)
		{
			configManager.setConfiguration(FiremakerConfig.GROUP, "logType", LogType.MAPLE.name());
		}
		return configManager.getConfig(FiremakerConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("Firemaker started");
		script.run(config);
	}

	@Override
	protected void shutDown()
	{
		script.shutdown();
		log.info("Firemaker stopped");
	}
}
