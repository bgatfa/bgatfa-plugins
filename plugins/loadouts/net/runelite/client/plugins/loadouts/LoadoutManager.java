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
package net.runelite.client.plugins.loadouts;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;

/**
 * Owns the in-memory list of {@link Loadout}s, their persistence to the config
 * store, and capturing the live game containers into a new loadout.
 */
@Slf4j
@Singleton
public class LoadoutManager
{
	private static final String KEY = "loadouts";
	private static final Type LIST_TYPE = new TypeToken<List<Loadout>>() {}.getType();

	private final Client client;
	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final Gson gson;

	private final List<Loadout> loadouts = new ArrayList<>();

	@Inject
	LoadoutManager(Client client, ItemManager itemManager, ConfigManager configManager, Gson gson)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.gson = gson;
	}

	public List<Loadout> getLoadouts()
	{
		return loadouts;
	}

	public void load()
	{
		loadouts.clear();
		final String json = configManager.getConfiguration(LoadoutSnapshotConfig.GROUP, KEY);
		if (json == null || json.isEmpty())
		{
			return;
		}

		try
		{
			final List<Loadout> stored = gson.fromJson(json, LIST_TYPE);
			if (stored != null)
			{
				loadouts.addAll(stored);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to parse stored loadouts", e);
		}
	}

	public void save()
	{
		configManager.setConfiguration(LoadoutSnapshotConfig.GROUP, KEY, gson.toJson(loadouts, LIST_TYPE));
	}

	public boolean exists(String name)
	{
		return loadouts.stream().anyMatch(l -> l.getName().equalsIgnoreCase(name));
	}

	public void delete(Loadout loadout)
	{
		loadouts.remove(loadout);
		save();
	}

	public void rename(Loadout loadout, String newName)
	{
		loadout.setName(newName);
		save();
	}

	/**
	 * Adds {@code loadout}, replacing any existing loadout with the same name
	 * (case-insensitive) so re-capturing an existing name overwrites it.
	 */
	public void addOrReplace(Loadout loadout)
	{
		loadouts.removeIf(l -> l.getName().equalsIgnoreCase(loadout.getName()));
		loadouts.add(loadout);
		save();
	}

	public void add(Loadout loadout)
	{
		loadouts.add(loadout);
		save();
	}

	/** Serialises a single loadout to JSON (for export to clipboard). */
	public String toJson(Loadout loadout)
	{
		return gson.toJson(loadout);
	}

	/** Parses a single loadout from JSON, or returns {@code null} if invalid. */
	public Loadout fromJson(String json)
	{
		try
		{
			final Loadout loadout = gson.fromJson(json, Loadout.class);
			// Reject anything that isn't a well-formed loadout with both containers.
			if (loadout == null || loadout.getInventory() == null || loadout.getEquipment() == null
					|| loadout.getInventory().size() != Loadout.INVENTORY_SIZE
					|| loadout.getEquipment().size() != Loadout.EQUIPMENT_SIZE)
			{
				return null;
			}
			return loadout;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** Returns {@code base}, or {@code base (2)}, {@code base (3)}… if taken. */
	public String uniqueName(String base)
	{
		if (!exists(base))
		{
			return base;
		}
		int n = 2;
		while (exists(base + " (" + n + ")"))
		{
			n++;
		}
		return base + " (" + n + ")";
	}

	/**
	 * Builds a {@link Loadout} from the current inventory and equipment.
	 * Must be called on the client thread.
	 */
	public Loadout capture(String name)
	{
		final Loadout loadout = new Loadout(name);

		copyContainer(client.getItemContainer(InventoryID.INV), loadout.getInventory(), Loadout.INVENTORY_SIZE);
		copyContainer(client.getItemContainer(InventoryID.WORN), loadout.getEquipment(), Loadout.EQUIPMENT_SIZE);

		return loadout;
	}

	/**
	 * Copies the occupied slots of {@code container} into {@code target}, resolving
	 * item names via the {@link ItemManager}. Empty slots are left untouched. The
	 * container's item array is indexed by slot, so the array index is the slot.
	 */
	private void copyContainer(ItemContainer container, List<Loadout.Item> target, int size)
	{
		if (container == null)
		{
			return;
		}

		final Item[] items = container.getItems();
		for (int slot = 0; slot < items.length && slot < size; slot++)
		{
			final int id = items[slot].getId();
			if (id < 0)
			{
				continue;
			}
			final String itemName = itemManager.getItemComposition(id).getName();
			target.set(slot, new Loadout.Item(id, items[slot].getQuantity(), itemName));
		}
	}
}
