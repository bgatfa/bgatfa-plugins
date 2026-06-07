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

import com.google.inject.Provider;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Bank Value Tracker",
	description = "Tracks your bank's GE and high-alch value, with price history",
	tags = {"bank", "value", "ge", "grand", "exchange", "alch", "price", "panel"}
)
public class BankValueTrackerPlugin extends Plugin
{
	private static final String CONFIG_GROUP = BankValueTrackerConfig.GROUP;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private BankValueTrackerConfig config;

	@Inject
	private EventBus eventBus;

	@Inject
	private BankValueSort bankValueSort;

	@Inject
	private Provider<BankValueTrackerPanel> panelProvider;

	private BankValueTrackerPanel panel;
	private NavigationButton navButton;

	private volatile List<BankItem> bankItems = Collections.emptyList();

	@Provides
	BankValueTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankValueTrackerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		panel = panelProvider.get();
		panel.setPlugin(this);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Bank Value Tracker")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		eventBus.register(bankValueSort);
		// If the bank is already open when we're enabled, inject the sort button now instead of
		// waiting for the next bank rebuild — refresh() forces a build (and no-ops when closed).
		bankValueSort.refresh();
		SwingUtilities.invokeLater(panel::rebuild);
	}

	@Override
	protected void shutDown() throws Exception
	{
		eventBus.unregister(bankValueSort);
		bankValueSort.reset();
		clientToolbar.removeNavigation(navButton);
		panel = null;
		bankItems = Collections.emptyList();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK)
		{
			rebuildSnapshot();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Re-render the panel when our display options change (e.g. hide untradeable, high alch).
		if (CONFIG_GROUP.equals(event.getGroup()))
		{
			refreshPanel();
			// Keep an open bank in step with the toggles too — these affect what the in-game value
			// sort shows/hides, so re-apply it live instead of waiting for the next bank rebuild.
			final String key = event.getKey();
			if ("hideUntradeable".equals(key) || "minValue".equals(key) || "minValueHa".equals(key))
			{
				bankValueSort.refresh();
			}
		}
	}

	/** Reads the bank container and resolves prices. Must run on the client thread. */
	private void rebuildSnapshot()
	{
		final ItemContainer container = client.getItemContainer(InventoryID.BANK);
		if (container == null)
		{
			return;
		}

		final List<BankItem> list = new ArrayList<>();
		for (Item item : container.getItems())
		{
			final int rawId = item.getId();
			final int quantity = item.getQuantity();
			if (rawId <= 0 || quantity <= 0 || rawId == ItemID.BANK_FILLER)
			{
				// empty slots and bank fillers
				continue;
			}

			// Bank placeholders are the faint reserved-slot items. canonicalize() turns a
			// placeholder into its real item, so we must detect it on the raw id beforehand.
			if (itemManager.getItemComposition(rawId).getPlaceholderTemplateId() != -1)
			{
				continue;
			}

			final int id = itemManager.canonicalize(rawId);
			final ItemComposition comp = itemManager.getItemComposition(id);
			final int ge = itemManager.getItemPrice(id);
			// ItemComposition.isTradeable() reflects the CURRENT world, so members items read as
			// untradeable on an F2P world. The GE price comes from a world-independent price list,
			// so a non-zero price means the item is really tradeable regardless of where we're logged in.
			final boolean tradeable = comp.isTradeable() || ge > 0;
			list.add(new BankItem(id, comp.getName(), quantity, ge, comp.getHaPrice(), comp.isMembers(), tradeable));
		}

		list.sort((a, b) -> Long.compare(b.geValue(), a.geValue()));
		bankItems = list;
		refreshPanel();
	}

	// ---- API used by the panel ----

	public ItemManager getItemManager()
	{
		return itemManager;
	}

	public BankValueTrackerConfig getConfig()
	{
		return config;
	}

	public List<BankItem> getBankItems()
	{
		return bankItems;
	}

	private void refreshPanel()
	{
		final BankValueTrackerPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(p::rebuild);
		}
	}
}
