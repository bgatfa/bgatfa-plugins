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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.plugins.microbot.loadouts.LoadoutIconSource;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * The shared icon + item summary used by both the hover tooltip and the slot
 * right-click menu header: an item icon on the left and, stacked left-aligned
 * beside it, the name plus the per-unit GE and high-alch prices (each tagged
 * with a coin / alchemy glyph). Keeping it in one place ensures hovering and
 * right-clicking read identically.
 */
public class ItemSummaryPanel extends JPanel
{
	public ItemSummaryPanel(LoadoutIconSource iconSource, JLabel iconLabel, String name,
			boolean showPrices, int gePrice, int alchPrice)
	{
		setOpaque(false);
		setLayout(new BorderLayout(6, 0));

		iconLabel.setVerticalAlignment(SwingConstants.TOP);
		iconLabel.setBorder(new EmptyBorder(0, 0, 0, 2));
		add(iconLabel, BorderLayout.WEST);

		final JPanel lines = new JPanel();
		lines.setOpaque(false);
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		lines.setBorder(new EmptyBorder(1, 0, 1, 4));

		lines.add(line(name, showPrices ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR, null, iconSource));
		if (showPrices)
		{
			lines.add(line(price(gePrice) + "/ea", ColorScheme.LIGHT_GRAY_COLOR, Boolean.TRUE, iconSource));
			lines.add(line(price(alchPrice) + "/ea", ColorScheme.LIGHT_GRAY_COLOR, Boolean.FALSE, iconSource));
		}
		add(lines, BorderLayout.CENTER);
	}

	/**
	 * One left-aligned line. {@code coin} of {@code null} = plain text;
	 * {@code TRUE} = coin glyph, {@code FALSE} = alch glyph (fetched async).
	 */
	private static JLabel line(String text, Color color, Boolean coin, LoadoutIconSource iconSource)
	{
		final JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);

		if (coin != null)
		{
			l.setIconTextGap(4);
			final Consumer<java.awt.image.BufferedImage> set = img ->
			{
				if (img != null)
				{
					l.setIcon(new ImageIcon(img));
					l.revalidate();
					l.repaint();
				}
			};
			if (coin)
			{
				iconSource.fetchCoinIcon(set);
			}
			else
			{
				iconSource.fetchAlchIcon(set);
			}
		}
		return l;
	}

	private static String price(int value)
	{
		return value > 0 ? QuantityFormatter.formatNumber(value) : "-";
	}
}
