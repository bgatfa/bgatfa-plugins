/*
 * Copyright (c) 2026, Microbot
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
package net.runelite.client.plugins.microbot.loadouts;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.loadouts.ui.LoadoutSnapshotPanel;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
		name = PluginDescriptor.Mocrosoft + "Loadout Snapshots",
		description = "Snapshot your inventory and equipment into named, icon-rendered loadouts",
		tags = {"inventory", "equipment", "loadout", "setup", "gear", "microbot"},
		enabledByDefault = false
)
public class LoadoutSnapshotPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private LoadoutManager loadoutManager;

	@Inject
	private LoadoutSnapshotConfig config;

	private LoadoutSnapshotPanel panel;
	private NavigationButton navButton;

	@Provides
	LoadoutSnapshotConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LoadoutSnapshotConfig.class);
	}

	@Override
	protected void startUp()
	{
		loadoutManager.load();

		final RuneliteIconSource iconSource = new RuneliteIconSource(itemManager, spriteManager);
		panel = new LoadoutSnapshotPanel(iconSource, loadoutManager, config);
		panel.setCaptureHandler(this::captureCurrentLoadout);
		panel.setSlotCaptureHandler(this::captureSlotFromCurrent);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "loadout_icon.png");
		navButton = NavigationButton.builder()
				.tooltip("Loadout Snapshots")
				.icon(icon)
				.priority(7)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);
		SwingUtilities.invokeLater(panel::reload);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
	}

	/**
	 * Reads the live inventory and equipment on the client thread, stores the
	 * resulting loadout, then refreshes the panel on the Swing thread.
	 */
	private void captureCurrentLoadout(String name)
	{
		clientThread.invokeLater(() ->
		{
			final Loadout loadout = loadoutManager.capture(name);
			loadoutManager.addOrReplace(loadout);
			SwingUtilities.invokeLater(panel::reload);
		});
	}

	/**
	 * Reads the live game item in a single slot (on the client thread) and stores
	 * it into the corresponding slot of {@code loadout}.
	 */
	private void captureSlotFromCurrent(Loadout loadout, boolean equipment, int idx)
	{
		clientThread.invokeLater(() ->
		{
			final Loadout current = loadoutManager.capture(loadout.getName());
			final Loadout.Item item = equipment
					? current.getEquipment().get(idx)
					: current.getInventory().get(idx);
			(equipment ? loadout.getEquipment() : loadout.getInventory()).set(idx, item);
			loadoutManager.save();
			SwingUtilities.invokeLater(panel::reload);
		});
	}
}
