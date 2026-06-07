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

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A serializable snapshot of a player's inventory and equipment.
 *
 * <p>Inventory is a fixed list of {@value #INVENTORY_SIZE} slots, equipment a
 * fixed list of {@value #EQUIPMENT_SIZE} slots indexed by
 * {@link net.runelite.api.EquipmentInventorySlot#getSlotIdx()}. Empty slots are
 * represented by an {@link Item} with {@code id == -1}.
 */
@Data
@NoArgsConstructor
public class Loadout
{
	public static final int INVENTORY_SIZE = 28;
	public static final int EQUIPMENT_SIZE = 14;

	/** A single stored item; id {@code -1} means the slot is empty. */
	@Data
	@NoArgsConstructor
	public static class Item
	{
		private int id = -1;
		private int quantity = 0;
		private String name = "";

		public Item(int id, int quantity, String name)
		{
			this.id = id;
			this.quantity = quantity;
			this.name = name;
		}

		public boolean isEmpty()
		{
			return id == -1;
		}

		public static Item empty()
		{
			return new Item(-1, 0, "");
		}
	}

	private String name = "";
	private long createdAt = System.currentTimeMillis();
	private boolean pinned = false;
	private List<Item> inventory = emptyList(INVENTORY_SIZE);
	private List<Item> equipment = emptyList(EQUIPMENT_SIZE);

	public Loadout(String name)
	{
		this.name = name;
	}

	/** Deep copy under a new name (for duplicating a loadout). */
	public Loadout copyAs(String newName)
	{
		final Loadout copy = new Loadout(newName);
		for (int i = 0; i < INVENTORY_SIZE; i++)
		{
			final Item it = inventory.get(i);
			copy.inventory.set(i, new Item(it.getId(), it.getQuantity(), it.getName()));
		}
		for (int i = 0; i < EQUIPMENT_SIZE; i++)
		{
			final Item it = equipment.get(i);
			copy.equipment.set(i, new Item(it.getId(), it.getQuantity(), it.getName()));
		}
		return copy;
	}

	/** Total number of non-empty inventory + equipment slots. */
	public int itemCount()
	{
		return (int) (inventory.stream().filter(i -> !i.isEmpty()).count()
				+ equipment.stream().filter(i -> !i.isEmpty()).count());
	}

	private static List<Item> emptyList(int size)
	{
		final List<Item> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++)
		{
			list.add(Item.empty());
		}
		return list;
	}
}
