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
package net.runelite.client.plugins.loadouts.ui;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.plugins.loadouts.Loadout;
import net.runelite.client.plugins.loadouts.LoadoutIconSource;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * Renders a {@link Loadout}'s equipment (in the in-game cross layout) and its
 * 28-slot inventory grid, mirroring how the items appear in the client. Each
 * slot carries a right-click menu for editing the stored item.
 */
class LoadoutContainerPanel extends JPanel
{
	/** Per-slot edit callbacks, invoked from the slot right-click menus. */
	interface SlotActions
	{
		/** Capture the live game item from this slot into the loadout. */
		void setFromCurrent(boolean equipment, int idx);

		/** Clear the stored item from this slot. */
		void clearSlot(boolean equipment, int idx);

		/** Edit the stored quantity of this slot's item. */
		void setQuantity(boolean equipment, int idx);
	}

	private final LoadoutIconSource iconSource;
	private final SlotActions actions;

	private final LoadoutSlot[] equipmentSlots = new LoadoutSlot[Loadout.EQUIPMENT_SIZE];
	private final LoadoutSlot[] inventorySlots = new LoadoutSlot[Loadout.INVENTORY_SIZE];

	LoadoutContainerPanel(LoadoutIconSource iconSource, Loadout loadout, SlotActions actions)
	{
		this.iconSource = iconSource;
		this.actions = actions;
		setLayout(new BorderLayout(0, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(buildSection("EQUIPMENT", buildEquipmentPanel()), BorderLayout.NORTH);
		add(buildSection("INVENTORY", buildInventoryPanel()), BorderLayout.CENTER);

		display(loadout);
	}

	/** Builds a slot menu led by the right-clicked item's icon and name. */
	private void attachMenu(LoadoutSlot slot, boolean equipment, int idx, Loadout.Item item)
	{
		final JPopupMenu menu = new JPopupMenu();
		menu.add(menuHeader(item));
		menu.addSeparator();
		// GE/high-alch prices resolve on the client thread after the panel first
		// renders, so the header built above (on a cold cache) reads "-". By the time
		// the user right-clicks the cache is warm, so rebuild the header on open to show
		// real prices — and suppress the hover tooltip meanwhile (the header is that
		// same summary, expanded, so showing both at once would duplicate it).
		menu.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
				menu.remove(0);
				menu.insert(menuHeader(item), 0);
				ToolTipManager.sharedInstance().setEnabled(false); // also dismisses the one showing
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
				ToolTipManager.sharedInstance().setEnabled(true);
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
				ToolTipManager.sharedInstance().setEnabled(true);
			}
		});

		final JMenuItem set = new JMenuItem("Replace with current slot");
		set.addActionListener(e -> actions.setFromCurrent(equipment, idx));
		menu.add(set);

		if (!item.isEmpty())
		{
			final JMenuItem qty = new JMenuItem("Set quantity…");
			qty.addActionListener(e -> actions.setQuantity(equipment, idx));
			menu.add(qty);

			final JMenuItem clear = new JMenuItem("Remove item");
			clear.addActionListener(e -> actions.clearSlot(equipment, idx));
			menu.add(clear);
		}
		slot.setMenu(menu);
	}

	/**
	 * A non-interactive header showing the right-clicked item — the same icon +
	 * name + per-unit GE/high-alch summary as the hover tooltip, so the menu just
	 * adds its options below it.
	 */
	private JComponent menuHeader(Loadout.Item item)
	{
		final JLabel icon = new JLabel();
		icon.setPreferredSize(new java.awt.Dimension(32, 32));
		final boolean present = !item.isEmpty();
		if (present)
		{
			iconSource.applyItemIcon(icon, item.getId(), item.getQuantity());
		}

		final String name = present ? item.getName() : "Empty slot";
		final ItemSummaryPanel header = new ItemSummaryPanel(iconSource, icon, name, present,
				present ? iconSource.gePrice(item.getId()) : 0,
				present ? iconSource.highAlchPrice(item.getId()) : 0);
		header.setBorder(new javax.swing.border.EmptyBorder(2, 6, 2, 10));
		return header;
	}

	private JPanel buildSection(String title, JPanel body)
	{
		final JPanel section = new JPanel(new BorderLayout(0, 4));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JLabel header = new JLabel(title);
		header.setFont(FontManager.getRunescapeSmallFont());
		header.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		// Group the grid in a dark card so the lighter slots read as inventory cells.
		final JPanel card = new JPanel(new BorderLayout());
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new javax.swing.border.EmptyBorder(7, 7, 7, 7));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.add(body, BorderLayout.CENTER);

		section.add(header, BorderLayout.NORTH);
		section.add(card, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildEquipmentPanel()
	{
		for (final EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			final int idx = slot.getSlotIdx();
			if (idx < 0 || idx >= Loadout.EQUIPMENT_SIZE)
			{
				continue;
			}

			final LoadoutSlot loadoutSlot = new LoadoutSlot(iconSource);
			equipmentSlots[idx] = loadoutSlot;

			// Resolve the faded empty-slot placeholder sprite (may be async).
			iconSource.fetchPlaceholder(idx, img ->
			{
				loadoutSlot.setPlaceholder(img);
				loadoutSlot.repaint();
			});
		}

		// In-game equipment cross: 5 rows x 3 columns.
		final JPanel panel = new JPanel(new GridLayout(5, 3, 2, 2));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		addEquipment(panel, -1);
		addEquipment(panel, EquipmentInventorySlot.HEAD.getSlotIdx());
		addEquipment(panel, -1);
		addEquipment(panel, EquipmentInventorySlot.CAPE.getSlotIdx());
		addEquipment(panel, EquipmentInventorySlot.AMULET.getSlotIdx());
		addEquipment(panel, EquipmentInventorySlot.AMMO.getSlotIdx());
		addEquipment(panel, EquipmentInventorySlot.WEAPON.getSlotIdx());
		addEquipment(panel, EquipmentInventorySlot.BODY.getSlotIdx());
		addEquipment(panel, EquipmentInventorySlot.SHIELD.getSlotIdx());
		addEquipment(panel, -1);
		addEquipment(panel, EquipmentInventorySlot.LEGS.getSlotIdx());
		addEquipment(panel, -1);
		addEquipment(panel, EquipmentInventorySlot.GLOVES.getSlotIdx());
		addEquipment(panel, EquipmentInventorySlot.BOOTS.getSlotIdx());
		addEquipment(panel, EquipmentInventorySlot.RING.getSlotIdx());

		return panel;
	}

	private void addEquipment(JPanel panel, int idx)
	{
		if (idx < 0)
		{
			// Empty spacer cell to form the cross shape.
			final JPanel spacer = new JPanel();
			spacer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			panel.add(spacer);
			return;
		}
		panel.add(equipmentSlots[idx]);
	}

	private JPanel buildInventoryPanel()
	{
		final JPanel panel = new JPanel(new GridLayout(7, 4, 2, 2));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (int i = 0; i < Loadout.INVENTORY_SIZE; i++)
		{
			final LoadoutSlot slot = new LoadoutSlot(iconSource);
			inventorySlots[i] = slot;
			panel.add(slot);
		}
		return panel;
	}

	void display(Loadout loadout)
	{
		for (int i = 0; i < Loadout.EQUIPMENT_SIZE; i++)
		{
			if (equipmentSlots[i] != null)
			{
				final Loadout.Item item = loadout.getEquipment().get(i);
				equipmentSlots[i].setItem(item);
				attachMenu(equipmentSlots[i], true, i, item);
			}
		}
		for (int i = 0; i < Loadout.INVENTORY_SIZE; i++)
		{
			final Loadout.Item item = loadout.getInventory().get(i);
			inventorySlots[i].setItem(item);
			attachMenu(inventorySlots[i], false, i, item);
		}
		revalidate();
		repaint();
	}
}
