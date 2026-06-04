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
import java.awt.Dimension;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JToolTip;
import net.runelite.client.plugins.microbot.loadouts.LoadoutIconSource;

/**
 * The item hover tooltip. Renders the shared {@link ItemSummaryPanel} (icon +
 * name + per-unit GE/high-alch prices), so hovering shows exactly what the slot
 * right-click menu header shows.
 */
public class LoadoutItemTooltip extends JToolTip
{
	public LoadoutItemTooltip(LoadoutIconSource iconSource, Icon icon, String name, int gePrice, int alchPrice)
	{
		setLayout(new BorderLayout());
		add(new ItemSummaryPanel(iconSource, new JLabel(icon), name, true, gePrice, alchPrice), BorderLayout.CENTER);
	}

	@Override
	public String getTipText()
	{
		// Suppress the default text painting; the summary panel carries the content.
		return "";
	}

	@Override
	public Dimension getPreferredSize()
	{
		return getLayout().preferredLayoutSize(this);
	}
}
