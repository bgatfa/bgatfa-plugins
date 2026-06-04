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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

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

	private final ConfigManager configManager;
	private final Gson gson;

	private final List<Loadout> loadouts = new ArrayList<>();

	@Inject
	LoadoutManager(ConfigManager configManager, Gson gson)
	{
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

		for (final Rs2ItemModel item : Rs2Inventory.all())
		{
			final int slot = item.getSlot();
			if (slot >= 0 && slot < Loadout.INVENTORY_SIZE)
			{
				loadout.getInventory().set(slot, new Loadout.Item(item.getId(), item.getQuantity(), item.getName()));
			}
		}

		Rs2Equipment.all().forEach(item ->
		{
			final int slot = item.getSlot();
			if (slot >= 0 && slot < Loadout.EQUIPMENT_SIZE)
			{
				loadout.getEquipment().set(slot, new Loadout.Item(item.getId(), item.getQuantity(), item.getName()));
			}
		});

		return loadout;
	}
}
