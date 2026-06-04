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

import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.JLabel;

/**
 * Supplies the imagery the loadout panel renders. Abstracted so the panel can be
 * driven by the live game ({@code ItemManager}/{@code SpriteManager}) in the
 * client, or by a standalone preview harness for visual design iteration.
 */
public interface LoadoutIconSource
{
	/** Sets the icon for {@code itemId} (optionally stacked) onto {@code label}. May resolve asynchronously. */
	void applyItemIcon(JLabel label, int itemId, int quantity);

	/** Grand Exchange price for a single {@code itemId} (0 if untradeable/unknown). */
	int gePrice(int itemId);

	/** High-alchemy value for a single {@code itemId} (0 if unknown). */
	int highAlchPrice(int itemId);

	/** Resolves a small coin icon (for GE value). May resolve asynchronously. */
	void fetchCoinIcon(Consumer<BufferedImage> consumer);

	/** Resolves a small high-alchemy icon. May resolve asynchronously. */
	void fetchAlchIcon(Consumer<BufferedImage> consumer);

	/**
	 * Resolves the faded empty-slot placeholder for the given equipment slot
	 * index ({@link net.runelite.api.EquipmentInventorySlot#getSlotIdx()}) and
	 * hands it to {@code consumer}. May resolve asynchronously.
	 */
	void fetchPlaceholder(int equipmentSlotIdx, Consumer<BufferedImage> consumer);
}
