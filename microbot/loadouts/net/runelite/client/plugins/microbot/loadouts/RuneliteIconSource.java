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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.JLabel;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ItemID;
import net.runelite.api.SpriteID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Live-game {@link LoadoutIconSource} backed by {@link ItemManager} (item icons
 * and prices) and {@link SpriteManager} (empty equipment-slot sprites).
 */
public class RuneliteIconSource implements LoadoutIconSource
{
	/** Equipment slot idx -> placeholder sprite shown when the slot is empty. */
	private static final int[] PLACEHOLDER_SPRITES = new int[Loadout.EQUIPMENT_SIZE];

	static
	{
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.HEAD.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_HEAD;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.CAPE.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_CAPE;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.AMULET.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_NECK;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.AMMO.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_AMMUNITION;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.WEAPON.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_WEAPON;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.BODY.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_TORSO;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.SHIELD.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_SHIELD;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.LEGS.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_LEGS;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.GLOVES.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_HANDS;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.BOOTS.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_FEET;
		PLACEHOLDER_SPRITES[EquipmentInventorySlot.RING.getSlotIdx()] = SpriteID.EQUIPMENT_SLOT_RING;
	}

	/** Inline icon height for the coin/alch tooltip glyphs. */
	private static final int GLYPH_HEIGHT = 16;

	private final ItemManager itemManager;
	private final SpriteManager spriteManager;

	private BufferedImage coinIcon;
	private BufferedImage alchIcon;

	public RuneliteIconSource(ItemManager itemManager, SpriteManager spriteManager)
	{
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
	}

	@Override
	public void applyItemIcon(JLabel label, int itemId, int quantity)
	{
		final AsyncBufferedImage image = itemManager.getImage(itemId, quantity, quantity > 1);
		image.addTo(label);
	}

	@Override
	public int gePrice(int itemId)
	{
		return itemManager.getItemPrice(itemId);
	}

	@Override
	public int highAlchPrice(int itemId)
	{
		return itemManager.getItemComposition(itemId).getHaPrice();
	}

	@Override
	public void fetchCoinIcon(Consumer<BufferedImage> consumer)
	{
		if (coinIcon != null)
		{
			consumer.accept(coinIcon);
			return;
		}
		// The actual coins item icon (large pile variant, no stack number drawn).
		final AsyncBufferedImage src = itemManager.getImage(ItemID.COINS_995, 10000, false);
		src.onLoaded(() ->
		{
			coinIcon = scale(src, GLYPH_HEIGHT);
			consumer.accept(coinIcon);
		});
	}

	@Override
	public void fetchAlchIcon(Consumer<BufferedImage> consumer)
	{
		if (alchIcon != null)
		{
			consumer.accept(alchIcon);
			return;
		}
		spriteManager.getSpriteAsync(SpriteID.SPELL_HIGH_LEVEL_ALCHEMY, 0, img ->
		{
			alchIcon = scale(img, GLYPH_HEIGHT);
			consumer.accept(alchIcon);
		});
	}

	/** Scales {@code src} to {@code targetHeight}px, preserving aspect ratio. */
	private static BufferedImage scale(BufferedImage src, int targetHeight)
	{
		if (src == null || src.getHeight() <= 0)
		{
			return null;
		}
		final int w = Math.max(1, src.getWidth() * targetHeight / src.getHeight());
		final BufferedImage out = new BufferedImage(w, targetHeight, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, w, targetHeight, null);
		g.dispose();
		return out;
	}

	@Override
	public void fetchPlaceholder(int equipmentSlotIdx, Consumer<BufferedImage> consumer)
	{
		if (equipmentSlotIdx < 0 || equipmentSlotIdx >= PLACEHOLDER_SPRITES.length)
		{
			return;
		}
		final int spriteId = PLACEHOLDER_SPRITES[equipmentSlotIdx];
		if (spriteId != 0)
		{
			spriteManager.getSpriteAsync(spriteId, 0, consumer::accept);
		}
	}
}
