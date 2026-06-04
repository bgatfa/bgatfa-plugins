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

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.swing.JToggleButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.SwingUtil;

/**
 * The on/off switch RuneLite uses for plugin toggles (a {@link JToggleButton}
 * with the client's switcher icon). Mirrors {@code PluginToggleButton} so the
 * control is identical to the rest of the client.
 */
class BankToggleButton extends JToggleButton
{
	private static final ImageIcon ON_SWITCHER;
	private static final ImageIcon OFF_SWITCHER;

	static
	{
		final BufferedImage onSwitcher = ImageUtil.loadImageResource(BankToggleButton.class, "switcher_on.png");
		ON_SWITCHER = new ImageIcon(onSwitcher);
		OFF_SWITCHER = new ImageIcon(ImageUtil.flipImage(
			ImageUtil.luminanceScale(
				ImageUtil.grayscaleImage(onSwitcher),
				0.61f),
			true, false));
	}

	BankToggleButton()
	{
		super(OFF_SWITCHER);
		setSelectedIcon(ON_SWITCHER);
		SwingUtil.removeButtonDecorations(this);
		// keep the switch transparent and blended into the panel under any look-and-feel
		setContentAreaFilled(false);
		setBorderPainted(false);
		setFocusPainted(false);
		setOpaque(false);
		setMargin(new Insets(0, 0, 0, 0));
		setPreferredSize(new Dimension(25, 15));
		setMaximumSize(new Dimension(25, 15));
	}
}
