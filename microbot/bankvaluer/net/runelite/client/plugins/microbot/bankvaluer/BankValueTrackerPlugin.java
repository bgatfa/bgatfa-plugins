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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provider;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import java.time.temporal.ChronoUnit;

@Slf4j
@PluginDescriptor(
	name = "Bank Value Tracker",
	description = "Tracks your bank's GE and high-alch value, with price history, alerts and hidden items",
	tags = {"bank", "value", "ge", "grand", "exchange", "alch", "price", "alert", "panel"}
)
public class BankValueTrackerPlugin extends Plugin
{
	private static final String CONFIG_GROUP = BankValueTrackerConfig.GROUP;
	private static final String KEY_ALERTS = "alerts";
	private static final String KEY_HIDDEN = "hidden";

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private Notifier notifier;

	@Inject
	private Gson gson;

	@Inject
	private BankValueTrackerConfig config;

	@Inject
	private Provider<BankValueTrackerPanel> panelProvider;

	private BankValueTrackerPanel panel;
	private NavigationButton navButton;

	private volatile List<BankItem> bankItems = Collections.emptyList();
	private final List<PriceAlert> alerts = new ArrayList<>();
	private final Set<Integer> hidden = new LinkedHashSet<>();

	@Provides
	BankValueTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankValueTrackerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		loadAlerts();
		loadHidden();

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
		SwingUtilities.invokeLater(panel::rebuild);
	}

	@Override
	protected void shutDown() throws Exception
	{
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
			if (rawId <= 0 || quantity <= 0)
			{
				// empty slots and placeholders (quantity 0)
				continue;
			}

			final int id = itemManager.canonicalize(rawId);
			final ItemComposition comp = itemManager.getItemComposition(id);
			final int ge = itemManager.getItemPrice(id);
			list.add(new BankItem(id, comp.getName(), quantity, ge, comp.getHaPrice(), comp.isMembers()));
		}

		list.sort((a, b) -> Long.compare(b.geValue(), a.geValue()));
		bankItems = list;
		refreshPanel();
	}

	/** Periodically evaluate price alerts. Runs on the client thread so ItemManager is safe to use. */
	@Schedule(period = 5, unit = ChronoUnit.MINUTES)
	public void checkAlerts()
	{
		if (alerts.isEmpty())
		{
			return;
		}

		boolean changed = false;
		for (PriceAlert alert : alerts)
		{
			if (!alert.isEnabled())
			{
				continue;
			}

			final long price = itemManager.getItemPrice(alert.getItemId());
			final boolean met = alert.isMet(price);

			if (met && !alert.isTriggered())
			{
				notifier.notify("Bank Tracker: " + alert.getItemName() + " is now "
					+ BankValueFormat.gp(price) + " (" + alert.describe() + ")");
				alert.setTriggered(true);
				changed = true;
			}
			else if (!met && alert.isTriggered() && config.repeatAlerts())
			{
				// re-arm once the price moves back across the threshold
				alert.setTriggered(false);
				changed = true;
			}
		}

		if (changed)
		{
			saveAlerts();
			refreshPanel();
		}
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

	public List<PriceAlert> getAlerts()
	{
		return alerts;
	}

	public void addAlert(PriceAlert alert)
	{
		alerts.add(alert);
		saveAlerts();
		refreshPanel();
	}

	public void removeAlert(PriceAlert alert)
	{
		alerts.remove(alert);
		saveAlerts();
		refreshPanel();
	}

	public void setAlertEnabled(PriceAlert alert, boolean enabled)
	{
		alert.setEnabled(enabled);
		if (enabled)
		{
			alert.setTriggered(false);
		}
		saveAlerts();
		refreshPanel();
	}

	public void editAlert(PriceAlert alert, boolean above, long threshold)
	{
		alert.setAbove(above);
		alert.setThreshold(threshold);
		alert.setTriggered(false); // re-arm with the new condition
		saveAlerts();
		refreshPanel();
	}

	public Set<Integer> getHidden()
	{
		return hidden;
	}

	public boolean isHidden(int itemId)
	{
		return hidden.contains(itemId);
	}

	public void hide(int itemId)
	{
		if (hidden.add(itemId))
		{
			saveHidden();
			refreshPanel();
		}
	}

	public void unhide(int itemId)
	{
		if (hidden.remove(itemId))
		{
			saveHidden();
			refreshPanel();
		}
	}

	private void refreshPanel()
	{
		final BankValueTrackerPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(p::rebuild);
		}
	}

	// ---- persistence (ConfigManager + Gson) ----

	private void saveAlerts()
	{
		if (alerts.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, KEY_ALERTS);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, KEY_ALERTS, gson.toJson(alerts));
		}
	}

	private void loadAlerts()
	{
		alerts.clear();
		final String json = configManager.getConfiguration(CONFIG_GROUP, KEY_ALERTS);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			final List<PriceAlert> loaded = gson.fromJson(json, new TypeToken<ArrayList<PriceAlert>>()
			{
			}.getType());
			if (loaded != null)
			{
				alerts.addAll(loaded);
			}
		}
		catch (Exception e)
		{
			log.warn("Could not parse saved price alerts", e);
		}
	}

	private void saveHidden()
	{
		if (hidden.isEmpty())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, KEY_HIDDEN);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, KEY_HIDDEN, gson.toJson(hidden));
		}
	}

	private void loadHidden()
	{
		hidden.clear();
		final String json = configManager.getConfiguration(CONFIG_GROUP, KEY_HIDDEN);
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			final Set<Integer> loaded = gson.fromJson(json, new TypeToken<LinkedHashSet<Integer>>()
			{
			}.getType());
			if (loaded != null)
			{
				hidden.addAll(loaded);
			}
		}
		catch (Exception e)
		{
			log.warn("Could not parse saved hidden items", e);
		}
	}
}
