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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

class BankValueTrackerPanel extends PluginPanel
{
	private enum Mode
	{
		BANK, ALERTS, HIDDEN, DETAIL
	}

	private static final Color GOLD = new Color(240, 207, 123);
	private static final Color ALCH = new Color(90, 170, 250);
	private static final Color GREEN = ColorScheme.PROGRESS_COMPLETE_COLOR;

	private static final String[] FILTERS = {"All", "F2P", "P2P"};
	private static final String[] PERIODS = {"1M", "3M", "6M", "1Y"};
	private static final int[] PERIOD_DAYS = {30, 90, 180, 365};

	private static final ImageIcon DELETE_ICON;
	private static final ImageIcon DELETE_HOVER_ICON;
	private static final ImageIcon GEAR_ICON;
	private static final ImageIcon GEAR_HOVER_ICON;

	static
	{
		final BufferedImage delete = ImageUtil.loadImageResource(BankValueTrackerPanel.class, "delete_icon.png");
		DELETE_ICON = new ImageIcon(delete);
		DELETE_HOVER_ICON = new ImageIcon(ImageUtil.alphaOffset(delete, -100));

		final BufferedImage gear = ImageUtil.loadImageResource(BankValueTrackerPanel.class, "gear_icon.png");
		GEAR_ICON = new ImageIcon(gear);
		GEAR_HOVER_ICON = new ImageIcon(ImageUtil.alphaOffset(gear, -100));
	}

	private final ItemManager itemManager;
	private final WikiPriceClient wikiPriceClient;

	private final JPanel header = new JPanel(new BorderLayout());
	private final MaterialTabGroup navGroup = new MaterialTabGroup();
	private final MaterialTab bankTab;
	private final MaterialTab alertsTab;
	private final MaterialTab hiddenTab;
	private final JPanel content = new JPanel();
	private final IconTextField searchField;

	private BankValueTrackerPlugin plugin;
	private Mode mode = Mode.BANK;
	private String filter = "All";
	private String search = "";

	// detail view state
	private BankItem detailItem;
	private String detailPeriod = "1Y";
	private List<PricePoint> detailSeries; // null = loading
	private boolean detailError;
	private int detailRequestId = -1;

	@Inject
	BankValueTrackerPanel(ItemManager itemManager, WikiPriceClient wikiPriceClient)
	{
		super();
		this.itemManager = itemManager;
		this.wikiPriceClient = wikiPriceClient;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(8, 8, 8, 8));

		// persistent nav (real MaterialTabGroup - orange underline like the rest of the client)
		navGroup.setLayout(new GridLayout(1, 3, 4, 0));
		bankTab = new MaterialTab("Bank", navGroup, null);
		alertsTab = new MaterialTab("Alerts", navGroup, null);
		hiddenTab = new MaterialTab("Hidden", navGroup, null);
		navGroup.addTab(bankTab);
		navGroup.addTab(alertsTab);
		navGroup.addTab(hiddenTab);
		navGroup.select(bankTab); // silent: callbacks not wired yet
		bankTab.setOnSelectEvent(() -> go(Mode.BANK));
		alertsTab.setOnSelectEvent(() -> go(Mode.ALERTS));
		hiddenTab.setOnSelectEvent(() -> go(Mode.HIDDEN));

		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(0, 0, 8, 0));
		header.add(navGroup, BorderLayout.CENTER);
		add(header, BorderLayout.NORTH);

		content.setLayout(new DynamicGridLayout(0, 1, 0, 6));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(content, BorderLayout.CENTER);

		searchField = new IconTextField();
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PANEL_WIDTH, 28));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				onSearch();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				onSearch();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				onSearch();
			}
		});
	}

	void setPlugin(BankValueTrackerPlugin plugin)
	{
		this.plugin = plugin;
	}

	private boolean go(Mode m)
	{
		mode = m;
		SwingUtilities.invokeLater(this::rebuild);
		return true;
	}

	private void onSearch()
	{
		search = searchField.getText().toLowerCase();
		rebuild();
		SwingUtilities.invokeLater(searchField::requestFocusInWindow);
	}

	/** Refresh nav state and rebuild the content area for the current mode. Call on the EDT. */
	void rebuild()
	{
		if (plugin == null)
		{
			return;
		}
		header.setVisible(mode != Mode.DETAIL);

		content.removeAll();
		switch (mode)
		{
			case ALERTS:
				buildAlertsView();
				break;
			case HIDDEN:
				buildHiddenView();
				break;
			case DETAIL:
				buildDetailView();
				break;
			default:
				buildBankView();
				break;
		}
		header.revalidate();
		header.repaint();
		content.revalidate();
		content.repaint();
	}

	// ---------- bank view ----------

	private void buildBankView()
	{
		final BankValueTrackerConfig config = plugin.getConfig();
		final List<BankItem> items = plugin.getBankItems();

		long geTotal = 0;
		long haTotal = 0;
		for (BankItem it : items)
		{
			if (!config.includeHiddenInTotal() && plugin.isHidden(it.getId()))
			{
				continue;
			}
			geTotal += it.geValue();
			haTotal += it.haValue();
		}
		content.add(valueCard(geTotal, haTotal, config.showHighAlch()));
		content.add(searchField);
		content.add(tabGroup(FILTERS, filter, f ->
		{
			filter = f;
			SwingUtilities.invokeLater(this::rebuild);
		}));

		final List<BankItem> shown = new ArrayList<>();
		for (BankItem it : items)
		{
			if (plugin.isHidden(it.getId()) || it.geValue() < config.minValue())
			{
				continue;
			}
			if (filter.equals("F2P") && it.isMembers())
			{
				continue;
			}
			if (filter.equals("P2P") && !it.isMembers())
			{
				continue;
			}
			if (!search.isEmpty() && !it.getName().toLowerCase().contains(search))
			{
				continue;
			}
			shown.add(it);
		}

		JLabel count = new JLabel(shown.size() + " item" + (shown.size() == 1 ? "" : "s"));
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		content.add(leftAlign(count));

		if (items.isEmpty())
		{
			content.add(hint("Open your bank in-game to populate the tracker."));
			return;
		}
		if (shown.isEmpty())
		{
			content.add(hint("No items match the current filter/search."));
			return;
		}

		for (BankItem it : shown)
		{
			content.add(bankRow(it, config));
		}
	}

	private JComponent valueCard(long ge, long ha, boolean showHa)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 10, 8, 10));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, showHa ? 66 : 48));

		JLabel cap = new JLabel("TOTAL BANK VALUE");
		cap.setFont(FontManager.getRunescapeSmallFont());
		cap.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cap.setAlignmentX(LEFT_ALIGNMENT);
		card.add(cap);

		JLabel val = new JLabel(BankValueFormat.gp(ge) + " gp");
		val.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 20f));
		val.setForeground(GOLD);
		val.setAlignmentX(LEFT_ALIGNMENT);
		card.add(val);

		if (showHa)
		{
			JLabel alch = new JLabel("High alch: " + BankValueFormat.gp(ha) + " gp");
			alch.setFont(FontManager.getRunescapeSmallFont());
			alch.setForeground(ALCH);
			alch.setAlignmentX(LEFT_ALIGNMENT);
			card.add(alch);
		}
		return card;
	}

	private JComponent bankRow(BankItem it, BankValueTrackerConfig config)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(32, 32));
		itemManager.getImage(it.getId(), it.getQuantity(), it.getQuantity() > 1).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		JPanel mid = new JPanel(new GridLayout(2, 1));
		mid.setOpaque(false);
		JLabel name = new JLabel(it.getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		JLabel qty = new JLabel("x " + BankValueFormat.qty(it.getQuantity()));
		qty.setFont(FontManager.getRunescapeSmallFont());
		qty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		mid.add(name);
		mid.add(qty);
		row.add(mid, BorderLayout.CENTER);

		JPanel val = new JPanel(new GridLayout(2, 1));
		val.setOpaque(false);
		JLabel ge = new JLabel(BankValueFormat.gp(it.geValue()), SwingConstants.RIGHT);
		ge.setFont(FontManager.getRunescapeSmallFont());
		ge.setForeground(GOLD);
		val.add(ge);
		if (config.showHighAlch())
		{
			JLabel ha = new JLabel(BankValueFormat.gp(it.haValue()), SwingConstants.RIGHT);
			ha.setFont(FontManager.getRunescapeSmallFont());
			ha.setForeground(ALCH);
			val.add(ha);
		}
		else
		{
			val.add(new JLabel());
		}
		row.add(val, BorderLayout.EAST);

		row.setComponentPopupMenu(buildItemMenu(it));
		name.setInheritsPopupMenu(true);
		qty.setInheritsPopupMenu(true);
		mid.setInheritsPopupMenu(true);
		val.setInheritsPopupMenu(true);
		ge.setInheritsPopupMenu(true);
		icon.setInheritsPopupMenu(true);
		return row;
	}

	private JPopupMenu buildItemMenu(BankItem it)
	{
		JPopupMenu menu = new JPopupMenu();

		JMenuItem history = new JMenuItem("Inspect price history");
		history.addActionListener(e -> openDetail(it));
		menu.add(history);

		JMenuItem wiki = new JMenuItem("Open wiki page");
		wiki.addActionListener(e ->
			LinkBrowser.browse("https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + it.getId()));
		menu.add(wiki);

		JMenuItem copy = new JMenuItem("Copy value");
		copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(Long.toString(it.geValue())), null));
		menu.add(copy);

		menu.addSeparator();

		JMenuItem alert = new JMenuItem("Set price alert...");
		alert.addActionListener(e -> showAlertDialog(it));
		menu.add(alert);

		JMenuItem hide = new JMenuItem("Hide from tracker");
		hide.addActionListener(e -> plugin.hide(it.getId()));
		menu.add(hide);

		return menu;
	}

	private void showAlertDialog(BankItem it)
	{
		JComboBox<String> condition = new JComboBox<>(new String[]{"Sell above", "Buy below"});
		JTextField price = new JTextField(BankValueFormat.gp(it.getGePrice()));

		JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
		form.add(new JLabel("Alert for " + it.getName()));
		form.add(condition);
		form.add(new JLabel("Target price (e.g. 1.5m, 40k, 1b)"));
		form.add(price);

		int result = JOptionPane.showConfirmDialog(this, form, "New price alert",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result != JOptionPane.OK_OPTION)
		{
			return;
		}

		long threshold = BankValueFormat.parseGp(price.getText());
		if (threshold < 0)
		{
			JOptionPane.showMessageDialog(this, "Could not understand that price.", "Invalid price",
				JOptionPane.WARNING_MESSAGE);
			return;
		}
		boolean above = condition.getSelectedIndex() == 0;
		plugin.addAlert(new PriceAlert(it.getId(), it.getName(), above, threshold));
		navGroup.select(alertsTab);
	}

	// ---------- alerts view ----------

	private void buildAlertsView()
	{
		content.add(sectionTitle("Price Alerts (" + plugin.getAlerts().size() + ")"));
		content.add(hint("Right-click any bank item and choose \"Set price alert\" to add one."));

		final List<PriceAlert> alerts = plugin.getAlerts();
		if (alerts.isEmpty())
		{
			content.add(hint("No alerts yet."));
			return;
		}

		for (PriceAlert a : alerts)
		{
			content.add(alertRow(a));
		}
	}

	private JComponent alertRow(PriceAlert a)
	{
		final String statusText = !a.isEnabled() ? "Paused" : (a.isTriggered() ? "Triggered" : "Armed");
		final Color statusColor = !a.isEnabled()
			? ColorScheme.LIGHT_GRAY_COLOR
			: (a.isTriggered() ? ColorScheme.BRAND_ORANGE : GREEN);

		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 7, 6, 7));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(32, 32));
		itemManager.getImage(a.getItemId()).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		JPanel mid = new JPanel(new GridLayout(3, 1));
		mid.setOpaque(false);
		JLabel name = new JLabel(a.getItemName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(a.isEnabled() ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		JLabel cond = new JLabel(a.describe());
		cond.setFont(FontManager.getRunescapeSmallFont());
		cond.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JLabel status = new JLabel(statusText);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setForeground(statusColor);
		mid.add(name);
		mid.add(cond);
		mid.add(status);
		row.add(mid, BorderLayout.CENTER);

		JPanel east = new JPanel();
		east.setLayout(new BoxLayout(east, BoxLayout.Y_AXIS));
		east.setOpaque(false);

		BankToggleButton toggle = new BankToggleButton();
		toggle.setSelected(a.isEnabled());
		toggle.setToolTipText(a.isEnabled() ? "Pause alert" : "Enable alert");
		toggle.addActionListener(e -> plugin.setAlertEnabled(a, toggle.isSelected()));
		toggle.setAlignmentX(RIGHT_ALIGNMENT);
		east.add(toggle);
		east.add(Box.createVerticalStrut(6));

		// edit (gear) + delete, side by side under the toggle
		JPanel actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
		actions.setOpaque(false);
		actions.setAlignmentX(RIGHT_ALIGNMENT);
		actions.add(iconButton(GEAR_ICON, GEAR_HOVER_ICON, "Edit alert", () -> editAlert(a)));
		actions.add(Box.createHorizontalStrut(6));
		actions.add(iconButton(DELETE_ICON, DELETE_HOVER_ICON, "Delete alert", () -> plugin.removeAlert(a)));
		east.add(actions);

		row.add(east, BorderLayout.EAST);
		return row;
	}

	/** A clickable icon label with an alphaOffset hover, the client's list-control pattern. */
	private JComponent iconButton(ImageIcon normal, ImageIcon hover, String tooltip, Runnable onClick)
	{
		JLabel label = new JLabel(normal);
		label.setToolTipText(tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				onClick.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setIcon(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setIcon(normal);
			}
		});
		return label;
	}

	private void editAlert(PriceAlert a)
	{
		JComboBox<String> condition = new JComboBox<>(new String[]{"Sell above", "Buy below"});
		condition.setSelectedIndex(a.isAbove() ? 0 : 1);
		JTextField price = new JTextField(BankValueFormat.gp(a.getThreshold()));

		JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
		form.add(new JLabel("Edit alert for " + a.getItemName()));
		form.add(condition);
		form.add(new JLabel("Target price (e.g. 1.5m, 40k, 1b)"));
		form.add(price);

		int result = JOptionPane.showConfirmDialog(this, form, "Edit price alert",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result != JOptionPane.OK_OPTION)
		{
			return;
		}

		long threshold = BankValueFormat.parseGp(price.getText());
		if (threshold < 0)
		{
			JOptionPane.showMessageDialog(this, "Could not understand that price.", "Invalid price",
				JOptionPane.WARNING_MESSAGE);
			return;
		}
		plugin.editAlert(a, condition.getSelectedIndex() == 0, threshold);
	}

	// ---------- hidden view ----------

	private void buildHiddenView()
	{
		content.add(sectionTitle("Hidden Items (" + plugin.getHidden().size() + ")"));
		content.add(hint("Hidden items are not listed and (by default) excluded from your total."));

		if (plugin.getHidden().isEmpty())
		{
			content.add(hint("Nothing hidden."));
			return;
		}

		content.add(leftAlign(clickable("Unhide all", ColorScheme.BRAND_ORANGE, () ->
		{
			for (Integer id : new ArrayList<>(plugin.getHidden()))
			{
				plugin.unhide(id);
			}
		})));

		for (Integer id : new ArrayList<>(plugin.getHidden()))
		{
			content.add(hiddenRow(id));
		}
	}

	private JComponent hiddenRow(int id)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(5, 8, 5, 8));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(28, 28));
		itemManager.getImage(id).addTo(icon);
		row.add(icon, BorderLayout.WEST);

		JLabel name = new JLabel(nameFor(id));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		row.add(name, BorderLayout.CENTER);

		row.add(clickable("Unhide", ColorScheme.BRAND_ORANGE, () -> plugin.unhide(id)), BorderLayout.EAST);
		return row;
	}

	private String nameFor(int id)
	{
		for (BankItem it : plugin.getBankItems())
		{
			if (it.getId() == id)
			{
				return it.getName();
			}
		}
		return "Item " + id;
	}

	// ---------- detail / price-history view ----------

	private void openDetail(BankItem it)
	{
		detailItem = it;
		detailPeriod = "1Y";
		detailSeries = null;
		detailError = false;
		mode = Mode.DETAIL;

		final int reqId = it.getId();
		detailRequestId = reqId;
		rebuild();

		wikiPriceClient.timeseries(reqId,
			points ->
			{
				if (detailRequestId == reqId)
				{
					detailSeries = points;
					detailError = points.isEmpty();
					rebuild();
				}
			},
			() ->
			{
				if (detailRequestId == reqId)
				{
					detailSeries = new ArrayList<>();
					detailError = true;
					rebuild();
				}
			});
	}

	private void buildDetailView()
	{
		content.add(leftAlign(clickable("< Back to bank", ColorScheme.BRAND_ORANGE, () ->
		{
			detailRequestId = -1;
			navGroup.select(bankTab);
		})));

		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setBackground(ColorScheme.DARK_GRAY_COLOR);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 36));
		itemManager.getImage(detailItem.getId()).addTo(icon);
		head.add(icon, BorderLayout.WEST);
		JPanel txt = new JPanel(new GridLayout(2, 1));
		txt.setOpaque(false);
		JLabel name = new JLabel(detailItem.getName());
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		JLabel cur = new JLabel("Current: " + BankValueFormat.gp(detailItem.getGePrice()) + " gp");
		cur.setFont(FontManager.getRunescapeSmallFont());
		cur.setForeground(GOLD);
		txt.add(name);
		txt.add(cur);
		head.add(txt, BorderLayout.CENTER);
		content.add(head);

		content.add(tabGroup(PERIODS, detailPeriod, p ->
		{
			detailPeriod = p;
			SwingUtilities.invokeLater(this::rebuild);
		}));

		if (detailSeries == null)
		{
			content.add(hint("Loading price history..."));
			return;
		}
		if (detailError || detailSeries.isEmpty())
		{
			content.add(hint("Couldn't load price history for this item."));
			return;
		}

		final List<PricePoint> slice = sliceForPeriod();
		content.add(new PriceHistoryChart(slice));
		content.add(detailStats(slice));
	}

	private List<PricePoint> sliceForPeriod()
	{
		int days = 365;
		for (int i = 0; i < PERIODS.length; i++)
		{
			if (PERIODS[i].equals(detailPeriod))
			{
				days = PERIOD_DAYS[i];
				break;
			}
		}
		final int from = Math.max(0, detailSeries.size() - days);
		return detailSeries.subList(from, detailSeries.size());
	}

	private JComponent detailStats(List<PricePoint> slice)
	{
		long hi = slice.get(0).getHigh();
		long lo = slice.get(0).getLow();
		for (PricePoint p : slice)
		{
			hi = Math.max(hi, p.getHigh());
			lo = Math.min(lo, p.getLow());
		}
		final long first = (slice.get(0).getHigh() + slice.get(0).getLow()) / 2;
		final long last = (slice.get(slice.size() - 1).getHigh() + slice.get(slice.size() - 1).getLow()) / 2;
		final double change = first == 0 ? 0 : (last - first) * 100.0 / first;

		JPanel p = new JPanel(new GridLayout(1, 3, 6, 0));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
		p.add(statBox("HIGH", BankValueFormat.gp(hi), GOLD));
		p.add(statBox("LOW", BankValueFormat.gp(lo), ALCH));
		p.add(statBox("CHANGE", String.format("%+.1f%%", change), change >= 0 ? GREEN : ColorScheme.PROGRESS_ERROR_COLOR));
		return p;
	}

	private JComponent statBox(String label, String value, Color color)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(new EmptyBorder(5, 6, 5, 6));
		JLabel l = new JLabel(label);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		JLabel v = new JLabel(value);
		v.setFont(FontManager.getRunescapeSmallFont());
		v.setForeground(color);
		v.setAlignmentX(LEFT_ALIGNMENT);
		box.add(l);
		box.add(v);
		return box;
	}

	// ---------- shared helpers ----------

	/** Build a MaterialTabGroup of string options, selecting {@code active} without firing the callback. */
	private MaterialTabGroup tabGroup(String[] options, String active, Consumer<String> onPick)
	{
		MaterialTabGroup group = new MaterialTabGroup();
		group.setLayout(new GridLayout(1, options.length, 4, 0));
		group.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

		MaterialTab[] tabs = new MaterialTab[options.length];
		MaterialTab activeTab = null;
		for (int i = 0; i < options.length; i++)
		{
			tabs[i] = new MaterialTab(options[i], group, null);
			group.addTab(tabs[i]);
			if (options[i].equals(active))
			{
				activeTab = tabs[i];
			}
		}
		if (activeTab != null)
		{
			group.select(activeTab); // callbacks not wired yet -> silent
		}
		for (int i = 0; i < options.length; i++)
		{
			final String option = options[i];
			tabs[i].setOnSelectEvent(() ->
			{
				onPick.accept(option);
				return true;
			});
		}
		return group;
	}

	private JComponent sectionTitle(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeBoldFont());
		l.setForeground(Color.WHITE);
		return leftAlign(l);
	}

	private JComponent hint(String text)
	{
		JLabel l = new JLabel("<html><div style='width:200px'>" + text + "</div></html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return leftAlign(l);
	}

	private JComponent clickable(String text, Color color, Runnable action)
	{
		JLabel l = new JLabel(text);
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

	private JComponent leftAlign(JComponent c)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.add(c, BorderLayout.WEST);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 2));
		return p;
	}
}
