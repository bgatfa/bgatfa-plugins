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
package net.runelite.client.plugins.bankvaluer;

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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
import net.runelite.client.util.LinkBrowser;

class BankValueTrackerPanel extends PluginPanel
{
	private enum Mode
	{
		BANK, DETAIL
	}

	private static final Color GOLD = new Color(240, 207, 123);
	private static final Color ALCH = new Color(90, 170, 250);
	private static final Color GREEN = ColorScheme.PROGRESS_COMPLETE_COLOR;

	private static final String[] FILTERS = {"All", "F2P", "P2P"};
	private static final String[] PERIODS = {"1M", "3M", "6M", "1Y"};
	private static final int[] PERIOD_DAYS = {30, 90, 180, 365};

	private final ItemManager itemManager;
	private final WikiPriceClient wikiPriceClient;

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

	/** Rebuild the content area for the current mode. Call on the EDT. */
	void rebuild()
	{
		if (plugin == null)
		{
			return;
		}

		content.removeAll();
		if (mode == Mode.DETAIL)
		{
			buildDetailView();
		}
		else
		{
			buildBankView();
		}
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
			if (isExcluded(it, config))
			{
				continue;
			}
			if (!matchesTab(it))
			{
				continue;
			}
			geTotal += it.geValue();
			haTotal += it.haValue();
		}
		final String cardCaption = filter.equals("All") ? "TOTAL BANK VALUE" : filter + " BANK VALUE";
		content.add(valueCard(cardCaption, geTotal, haTotal, config.showHighAlch()));
		content.add(searchField);
		content.add(tabGroup(FILTERS, filter, f ->
		{
			filter = f;
			SwingUtilities.invokeLater(this::rebuild);
		}));

		final List<BankItem> shown = new ArrayList<>();
		for (BankItem it : items)
		{
			// Per-view threshold: with "Show high alch" on the panel is curating HA value, otherwise GE.
			final boolean underThreshold = config.showHighAlch()
				? it.haValue() < config.minValueHa()
				: it.geValue() < config.minValue();
			if (underThreshold)
			{
				continue;
			}
			if (isExcluded(it, config))
			{
				continue;
			}
			if (!matchesTab(it))
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

	/** Untradeable items, and items worth nothing on both the GE and high alch, when the toggle is on. */
	private boolean isExcluded(BankItem it, BankValueTrackerConfig config)
	{
		return config.hideUntradeable() && (!it.isTradeable() || (it.geValue() == 0 && it.haValue() == 0));
	}

	/** Whether an item belongs to the currently focused filter tab (All / F2P / P2P). */
	private boolean matchesTab(BankItem it)
	{
		if (filter.equals("F2P") && it.isMembers())
		{
			return false;
		}
		if (filter.equals("P2P") && !it.isMembers())
		{
			return false;
		}
		return true;
	}

	private JComponent valueCard(String caption, long ge, long ha, boolean showHa)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(new EmptyBorder(8, 10, 8, 10));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, showHa ? 66 : 48));

		JLabel cap = new JLabel(caption);
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

		return menu;
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
			go(Mode.BANK);
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
			tabs[i].setHorizontalAlignment(SwingConstants.CENTER);
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
