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
package net.runelite.client.plugins.microbot.loadouts.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToolTip;
import net.runelite.client.plugins.microbot.loadouts.Loadout;
import net.runelite.client.plugins.microbot.loadouts.LoadoutIconSource;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * A single inventory/equipment cell. Renders the stored item's icon, or a
 * faded equipment-slot placeholder sprite when the slot is empty.
 */
class LoadoutSlot extends JPanel
{
	private static final Dimension SIZE = new Dimension(36, 32);
	/** Slightly lighter than the card behind it, for an in-game slot look. */
	static final Color SLOT_COLOR = new Color(45, 45, 45);

	private final LoadoutIconSource iconSource;

	/** The item currently shown, so the hover tooltip can describe it. */
	private Loadout.Item currentItem = Loadout.Item.empty();

	private final JLabel imageLabel = new JLabel()
	{
		@Override
		public JToolTip createToolTip()
		{
			if (currentItem == null || currentItem.isEmpty() || getIcon() == null)
			{
				return super.createToolTip();
			}
			final int id = currentItem.getId();
			final JToolTip tip = new LoadoutItemTooltip(iconSource, getIcon(), currentItem.getName(),
					iconSource.gePrice(id), iconSource.highAlchPrice(id));
			tip.setComponent(this);
			return tip;
		}
	};
	private final JLabel quantityLabel = new JLabel();

	/** Faded placeholder shown when this slot holds no item (equipment only). */
	private BufferedImage placeholder;

	LoadoutSlot(LoadoutIconSource iconSource)
	{
		this.iconSource = iconSource;
		setLayout(new GridBagLayout());
		setPreferredSize(SIZE);
		setBackground(SLOT_COLOR);

		quantityLabel.setFont(FontManager.getRunescapeSmallFont());
		quantityLabel.setForeground(Color.YELLOW);

		// Quantity in the top-left, like the in-game stack count.
		final GridBagConstraints qtyConstraints = new GridBagConstraints(0, 0, 1, 1, 1, 1,
				GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, new Insets(1, 2, 0, 0), 0, 0);
		final GridBagConstraints iconConstraints = new GridBagConstraints(0, 0, 1, 1, 1, 1,
				GridBagConstraints.CENTER, GridBagConstraints.NONE, new Insets(0, 0, 0, 0), 0, 0);

		// Add the quantity first so it keeps a lower index, and therefore paints
		// on top of the icon (Swing paints lower-indexed children last). Otherwise
		// the stack count hides behind item sprites that fill the corner (coins).
		add(quantityLabel, qtyConstraints);
		add(imageLabel, iconConstraints);
	}

	void setPlaceholder(BufferedImage placeholder)
	{
		this.placeholder = placeholder;
	}

	/** Attaches a right-click menu that also fires from the icon child. */
	void setMenu(JPopupMenu menu)
	{
		setComponentPopupMenu(menu);
		imageLabel.setInheritsPopupMenu(true);
		quantityLabel.setInheritsPopupMenu(true);
	}

	void setItem(Loadout.Item item)
	{
		if (item == null || item.isEmpty())
		{
			renderEmpty();
			return;
		}

		currentItem = item;
		final int quantity = item.getQuantity();
		iconSource.applyItemIcon(imageLabel, item.getId(), quantity);
		// Registering any text arms the tooltip; createToolTip() renders icon+name.
		imageLabel.setToolTipText(tooltipText(item));
		quantityLabel.setText(quantity > 1 ? QuantityFormatter.quantityToRSDecimalStack(quantity) : "");
		revalidate();
		repaint();
	}

	private static String tooltipText(Loadout.Item item)
	{
		return item.getQuantity() > 1 ? item.getName() + " (" + item.getQuantity() + ")" : item.getName();
	}

	private void renderEmpty()
	{
		currentItem = Loadout.Item.empty();
		quantityLabel.setText("");
		imageLabel.setToolTipText(null);
		if (placeholder != null)
		{
			imageLabel.setIcon(new ImageIcon(placeholder));
		}
		else
		{
			imageLabel.setIcon(null);
		}
		revalidate();
		repaint();
	}
}
