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
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.plugins.loadouts.Loadout;
import net.runelite.client.plugins.loadouts.LoadoutIconSource;
import net.runelite.client.plugins.loadouts.LoadoutManager;
import net.runelite.client.plugins.loadouts.LoadoutSnapshotConfig;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * The Loadout Snapshots side panel. Borrows the Bank Value Tracker's flat,
 * dark-themed look (no out-of-place buttons): the search box and dark rows are
 * the only chrome, and every action — load, save/update, rename, delete and
 * per-slot edits — lives in right-click menus.
 */
public class LoadoutSnapshotPanel extends PluginPanel implements LoadoutContainerPanel.SlotActions
{
	/** Captures the live game item from a single slot into a loadout. */
	public interface SlotCapture
	{
		void capture(Loadout loadout, boolean equipment, int idx);
	}

	private enum Mode
	{
		OVERVIEW, DETAIL
	}

	private final LoadoutIconSource iconSource;
	private final LoadoutManager manager;
	private final LoadoutSnapshotConfig config;

	// Plugin-supplied, client-thread-backed actions (no-ops until wired).
	private Consumer<String> captureHandler = name -> {};
	private SlotCapture slotCaptureHandler = (loadout, equipment, idx) -> {};

	private final JPanel header = new JPanel(new BorderLayout());
	private final JLabel countLabel = new JLabel();
	private final JPanel content = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
	private final JPanel footer = new JPanel(new BorderLayout());
	private final IconTextField searchField = new IconTextField();

	private Mode mode = Mode.OVERVIEW;
	private Loadout detailLoadout;
	private String search = "";

	public LoadoutSnapshotPanel(LoadoutIconSource iconSource, LoadoutManager manager, LoadoutSnapshotConfig config)
	{
		this.iconSource = iconSource;
		this.manager = manager;
		this.config = config;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));

		final JLabel title = new JLabel("Loadout Snapshots");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(0, 0, 8, 0));
		header.add(title, BorderLayout.WEST);
		countLabel.setFont(FontManager.getRunescapeSmallFont());
		countLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(countLabel, BorderLayout.EAST);

		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setBorder(new EmptyBorder(8, 0, 0, 0));

		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PANEL_WIDTH, 28));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.addClearListener(this::onSearch);
		searchField.getDocument().addDocumentListener(new SimpleDocListener(this::onSearch));

		add(header, BorderLayout.NORTH);
		add(content, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);

		rebuild();
	}

	public void setCaptureHandler(Consumer<String> captureHandler)
	{
		this.captureHandler = captureHandler;
	}

	public void setSlotCaptureHandler(SlotCapture slotCaptureHandler)
	{
		this.slotCaptureHandler = slotCaptureHandler;
	}

	public void reload()
	{
		rebuild();
	}

	public void openLoadout(Loadout loadout)
	{
		mode = Mode.DETAIL;
		detailLoadout = loadout;
		rebuild();
	}

	public void showOverview()
	{
		mode = Mode.OVERVIEW;
		detailLoadout = null;
		rebuild();
	}

	private void onSearch()
	{
		search = searchField.getText().toLowerCase();
		rebuild();
	}

	private void rebuild()
	{
		// Keep showing detail only while the loadout still exists.
		if (mode == Mode.DETAIL && (detailLoadout == null || !manager.getLoadouts().contains(detailLoadout)))
		{
			mode = Mode.OVERVIEW;
			detailLoadout = null;
		}

		header.setVisible(mode == Mode.OVERVIEW);
		content.removeAll();
		footer.removeAll();

		if (mode == Mode.DETAIL)
		{
			buildDetail(detailLoadout);
		}
		else
		{
			buildOverview();
		}

		revalidate();
		repaint();
	}

	// ------------------------------------------------------------------
	// Overview
	// ------------------------------------------------------------------

	private void buildOverview()
	{
		final List<Loadout> loadouts = manager.getLoadouts();
		countLabel.setText(loadouts.isEmpty() ? "" : loadouts.size() + " saved");
		content.add(searchField);

		// Pinned loadouts float to the top, otherwise keep insertion order.
		final List<Loadout> ordered = new ArrayList<>(loadouts);
		ordered.sort(Comparator.comparing(Loadout::isPinned).reversed());

		boolean any = false;
		for (final Loadout l : ordered)
		{
			if (!search.isEmpty() && !l.getName().toLowerCase().contains(search))
			{
				continue;
			}
			content.add(loadoutRow(l));
			any = true;
		}

		if (loadouts.isEmpty())
		{
			content.add(hint("No loadouts yet. Save your current setup below."));
		}
		else if (!any)
		{
			content.add(hint("No loadouts match your search."));
		}

		final JPanel actions = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actions.add(linkRow("+  Save current setup", this::onCapture));
		actions.add(linkRow("Import from clipboard", this::onImport));
		footer.add(actions, BorderLayout.CENTER);
	}

	private JComponent loadoutRow(Loadout loadout)
	{
		final JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Pinned loadouts get an orange edge marker.
		if (loadout.isPinned())
		{
			row.setBorder(javax.swing.BorderFactory.createCompoundBorder(
					new javax.swing.border.MatteBorder(0, 3, 0, 0, ColorScheme.BRAND_ORANGE),
					new EmptyBorder(5, 4, 5, 7)));
		}
		else
		{
			row.setBorder(new EmptyBorder(5, 7, 5, 7));
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		final int rep = representativeItem(loadout);
		if (rep != -1)
		{
			iconSource.applyItemIcon(icon, rep, 1);
		}
		row.add(icon, BorderLayout.WEST);

		final JPanel mid = new JPanel(new GridLayout(2, 1));
		mid.setOpaque(false);
		final JLabel name = new JLabel(loadout.getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		final JLabel meta = new JLabel(loadout.itemCount() + (loadout.itemCount() == 1 ? " item" : " items"));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		mid.add(name);
		mid.add(meta);
		row.add(mid, BorderLayout.CENTER);

		final JPopupMenu menu = loadoutMenu(loadout, true);
		row.setComponentPopupMenu(menu);
		mid.setInheritsPopupMenu(true);
		icon.setInheritsPopupMenu(true);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e))
				{
					openLoadout(loadout);
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});

		return row;
	}

	private JPopupMenu loadoutMenu(Loadout loadout, boolean includeOpen)
	{
		final JPopupMenu menu = new JPopupMenu();
		if (includeOpen)
		{
			menu.add(item("Open", () -> openLoadout(loadout)));
		}
		menu.add(item("Update from current setup", () -> captureHandler.accept(loadout.getName())));
		menu.addSeparator();
		menu.add(item(loadout.isPinned() ? "Unpin" : "Pin to top", () -> togglePin(loadout)));
		menu.add(item("Duplicate", () -> duplicateLoadout(loadout)));
		menu.add(item("Export to clipboard", () -> exportToClipboard(loadout)));
		menu.add(item("Rename", () -> renameLoadout(loadout)));
		menu.add(item("Delete", () -> deleteLoadout(loadout)));
		return menu;
	}

	private void onCapture()
	{
		final String name = JOptionPane.showInputDialog(this, "Name this loadout:",
				"Loadout " + (manager.getLoadouts().size() + 1));
		if (name == null || name.trim().isEmpty())
		{
			return;
		}
		final String trimmed = name.trim();
		if (manager.exists(trimmed)
				&& JOptionPane.showConfirmDialog(this,
				"A loadout named \"" + trimmed + "\" exists. Overwrite it?",
				"Overwrite loadout", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
		{
			return;
		}
		captureHandler.accept(trimmed);
	}

	// ------------------------------------------------------------------
	// Detail
	// ------------------------------------------------------------------

	private void buildDetail(Loadout loadout)
	{
		content.add(clickable("< Back to loadouts", ColorScheme.BRAND_ORANGE, this::showOverview));

		// Head: representative icon + name + item count, with the loadout menu.
		final JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		head.setBorder(new EmptyBorder(5, 7, 5, 7));
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		final int rep = representativeItem(loadout);
		if (rep != -1)
		{
			iconSource.applyItemIcon(icon, rep, 1);
		}
		head.add(icon, BorderLayout.WEST);

		final JPanel txt = new JPanel(new GridLayout(2, 1));
		txt.setOpaque(false);
		final JLabel name = new JLabel(loadout.getName());
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		final JLabel meta = new JLabel(loadout.itemCount() + (loadout.itemCount() == 1 ? " item" : " items"));
		meta.setFont(FontManager.getRunescapeSmallFont());
		meta.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		txt.add(name);
		txt.add(meta);
		head.add(txt, BorderLayout.CENTER);

		final JPopupMenu menu = loadoutMenu(loadout, false);
		head.setComponentPopupMenu(menu);
		txt.setInheritsPopupMenu(true);
		icon.setInheritsPopupMenu(true);
		content.add(head);

		content.add(clickable("Update from current setup", ColorScheme.BRAND_ORANGE,
				() -> updateFromCurrent(loadout)));
		content.add(hint("Right-click a slot to edit it."));

		// Equipment + inventory grids with real icons and per-slot menus.
		content.add(new LoadoutContainerPanel(iconSource, loadout, this));
	}

	/** Re-captures the whole loadout from the live setup, after a warning. */
	private void updateFromCurrent(Loadout loadout)
	{
		final int answer = JOptionPane.showConfirmDialog(this,
				"Replace all of \"" + loadout.getName() + "\" with your current inventory and "
						+ "equipment?\nAny manual slot edits will be lost.",
				"Update from current setup", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (answer == JOptionPane.YES_OPTION)
		{
			captureHandler.accept(loadout.getName());
		}
	}

	// LoadoutContainerPanel.SlotActions ---------------------------------

	@Override
	public void setFromCurrent(boolean equipment, int idx)
	{
		if (detailLoadout != null)
		{
			slotCaptureHandler.capture(detailLoadout, equipment, idx);
		}
	}

	@Override
	public void clearSlot(boolean equipment, int idx)
	{
		if (detailLoadout == null)
		{
			return;
		}
		final List<Loadout.Item> container = equipment ? detailLoadout.getEquipment() : detailLoadout.getInventory();
		container.set(idx, Loadout.Item.empty());
		manager.save();
		rebuild();
	}

	@Override
	public void setQuantity(boolean equipment, int idx)
	{
		if (detailLoadout == null)
		{
			return;
		}
		final List<Loadout.Item> container = equipment ? detailLoadout.getEquipment() : detailLoadout.getInventory();
		final Loadout.Item item = container.get(idx);
		if (item.isEmpty())
		{
			return;
		}

		final String input = JOptionPane.showInputDialog(this,
				"Quantity of " + item.getName() + ":", item.getQuantity());
		if (input == null)
		{
			return;
		}
		try
		{
			final int qty = Math.max(1, Integer.parseInt(input.trim()));
			container.set(idx, new Loadout.Item(item.getId(), qty, item.getName()));
			manager.save();
			rebuild();
		}
		catch (NumberFormatException ex)
		{
			JOptionPane.showMessageDialog(this, "\"" + input + "\" isn't a number.",
					"Invalid quantity", JOptionPane.WARNING_MESSAGE);
		}
	}

	// ------------------------------------------------------------------
	// Import / export
	// ------------------------------------------------------------------

	private void exportToClipboard(Loadout loadout)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new StringSelection(manager.toJson(loadout)), null);
		JOptionPane.showMessageDialog(this, "Copied \"" + loadout.getName() + "\" to the clipboard.",
				"Export loadout", JOptionPane.INFORMATION_MESSAGE);
	}

	private void onImport()
	{
		String clip;
		try
		{
			clip = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
					.getData(DataFlavor.stringFlavor);
		}
		catch (Exception e)
		{
			clip = null;
		}

		final Loadout imported = clip == null ? null : manager.fromJson(clip);
		if (imported == null)
		{
			JOptionPane.showMessageDialog(this, "The clipboard doesn't contain a valid loadout.",
					"Import loadout", JOptionPane.WARNING_MESSAGE);
			return;
		}

		imported.setPinned(false);
		imported.setName(manager.uniqueName(imported.getName().isEmpty() ? "Imported loadout" : imported.getName()));
		manager.add(imported);
		openLoadout(imported);
	}

	// ------------------------------------------------------------------
	// Loadout-level actions
	// ------------------------------------------------------------------

	private void togglePin(Loadout loadout)
	{
		loadout.setPinned(!loadout.isPinned());
		manager.save();
		rebuild();
	}

	private void duplicateLoadout(Loadout loadout)
	{
		final Loadout copy = loadout.copyAs(manager.uniqueName(loadout.getName() + " copy"));
		manager.add(copy);
		rebuild();
	}

	private void renameLoadout(Loadout loadout)
	{
		final String newName = JOptionPane.showInputDialog(this, "New name:", loadout.getName());
		if (newName != null && !newName.trim().isEmpty())
		{
			manager.rename(loadout, newName.trim());
			rebuild();
		}
	}

	private void deleteLoadout(Loadout loadout)
	{
		if (config.confirmDelete()
				&& JOptionPane.showConfirmDialog(this,
				"Delete \"" + loadout.getName() + "\"?", "Delete loadout",
				JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
		{
			return;
		}
		manager.delete(loadout);
		showOverview();
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** Picks the icon that best represents a loadout: weapon, else first item. */
	private static int representativeItem(Loadout loadout)
	{
		final Loadout.Item weapon = loadout.getEquipment().get(net.runelite.api.EquipmentInventorySlot.WEAPON.getSlotIdx());
		if (!weapon.isEmpty())
		{
			return weapon.getId();
		}
		for (final Loadout.Item item : loadout.getEquipment())
		{
			if (!item.isEmpty())
			{
				return item.getId();
			}
		}
		for (final Loadout.Item item : loadout.getInventory())
		{
			if (!item.isEmpty())
			{
				return item.getId();
			}
		}
		return -1;
	}

	private JMenuItem item(String text, Runnable action)
	{
		final JMenuItem menuItem = new JMenuItem(text);
		menuItem.addActionListener(e -> action.run());
		return menuItem;
	}

	private JComponent hint(String text)
	{
		final JLabel l = new JLabel("<html><div style='width:195px'>" + text + "</div></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setBorder(new EmptyBorder(6, 2, 0, 2));
		return l;
	}

	/** A flat, full-width clickable row that blends with the list (no button chrome). */
	private JComponent linkRow(String text, Runnable action)
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(8, 8, 8, 8));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		final JLabel l = new JLabel(text, SwingConstants.CENTER);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.BRAND_ORANGE);
		row.add(l, BorderLayout.CENTER);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		return row;
	}

	private JComponent clickable(String text, Color color, Runnable action)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		l.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				action.run();
			}
		});
		return l;
	}

	/** Minimal document listener that runs a callback on any change. */
	private static final class SimpleDocListener implements javax.swing.event.DocumentListener
	{
		private final Runnable onChange;

		SimpleDocListener(Runnable onChange)
		{
			this.onChange = onChange;
		}

		@Override
		public void insertUpdate(javax.swing.event.DocumentEvent e)
		{
			onChange.run();
		}

		@Override
		public void removeUpdate(javax.swing.event.DocumentEvent e)
		{
			onChange.run();
		}

		@Override
		public void changedUpdate(javax.swing.event.DocumentEvent e)
		{
			onChange.run();
		}
	}
}
