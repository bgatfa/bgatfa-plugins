/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.bankcleaner;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;

class BankCleanerOverlay extends OverlayPanel
{
	private static final int WIDTH = 226;
	private static final Color MUTED = new Color(190, 190, 190);
	private static final Color WARNING = new Color(255, 190, 85);
	private static final Color STOPPED = new Color(255, 115, 115);

	private final BankCleanerPlugin plugin;
	private final BankCleanerConfig config;
	private final ItemManager itemManager;

	@Inject
	BankCleanerOverlay(BankCleanerPlugin plugin, BankCleanerConfig config, ItemManager itemManager)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_RIGHT);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
		panelComponent.setBorder(new Rectangle(6, 5, 6, 5));
		panelComponent.setGap(new Point(0, 3));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		BankCleanerPlugin.OverlayState state = plugin.getOverlayStateSnapshot();
		graphics.setFont(FontManager.getRunescapeSmallFont());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Bank Cleaner")
			.build());

		panelComponent.getChildren().add(new DividerComponent());
		panelComponent.getChildren().add(new StatsComponent(state));

		if (state.skipped > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Skipped")
				.right(Integer.toString(state.skipped))
				.leftColor(WARNING)
				.rightColor(WARNING)
				.build());
		}

		if (state.current != null)
		{
			panelComponent.getChildren().add(new DividerComponent());
			panelComponent.getChildren().add(new ItemRowComponent(itemManager, state.current, state.currentLabel()));
		}
		else if (!state.message.isEmpty())
		{
			panelComponent.getChildren().add(new DividerComponent());
			panelComponent.getChildren().add(LineComponent.builder()
				.left(state.message)
				.leftColor(colorForMessage(state.message))
				.build());
		}

		if (!state.message.isEmpty() && state.current != null)
		{
			panelComponent.getChildren().add(new DividerComponent());
			panelComponent.getChildren().add(LineComponent.builder()
				.left(state.message)
				.leftColor(colorForMessage(state.message))
				.build());
		}

		return super.render(graphics);
	}

	private static Color colorForMessage(String message)
	{
		String lower = message.toLowerCase();
		if (lower.contains("failed") || lower.contains("abort") || lower.contains("timeout") || lower.contains("stopped"))
		{
			return STOPPED;
		}
		if (lower.contains("skip") || lower.contains("deposit") || lower.contains("wait"))
		{
			return WARNING;
		}
		return MUTED;
	}

	private static final class DividerComponent implements LayoutableRenderableEntity
	{
		private static final Color DIVIDER = new Color(95, 95, 95, 180);
		private final Rectangle bounds = new Rectangle();
		private Point preferredLocation = new Point();
		private Dimension preferredSize = new Dimension(WIDTH, 0);

		@Override
		public Dimension render(Graphics2D graphics)
		{
			int y = preferredLocation.y + 3;
			graphics.setColor(DIVIDER);
			graphics.drawLine(preferredLocation.x, y, preferredLocation.x + preferredSize.width, y);

			Dimension dimension = new Dimension(preferredSize.width, 7);
			bounds.setLocation(preferredLocation);
			bounds.setSize(dimension);
			return dimension;
		}

		@Override
		public void setPreferredLocation(Point preferredLocation)
		{
			this.preferredLocation = preferredLocation;
		}

		@Override
		public void setPreferredSize(Dimension preferredSize)
		{
			this.preferredSize = preferredSize;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}
	}

	private static final class ItemRowComponent implements LayoutableRenderableEntity
	{
		private static final int GAP = 6;

		private final ItemManager itemManager;
		private final BankCleanerPlugin.OverlayItem item;
		private final String label;
		private final Rectangle bounds = new Rectangle();
		private Point preferredLocation = new Point();
		private Dimension preferredSize = new Dimension(WIDTH, 0);

		ItemRowComponent(ItemManager itemManager, BankCleanerPlugin.OverlayItem item, String label)
		{
			this.itemManager = itemManager;
			this.item = item;
			this.label = label;
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			BufferedImage image = itemManager.getImage(item.itemId, item.quantity, item.quantity > 1);
			int x = preferredLocation.x;
			int y = preferredLocation.y;
			int imageWidth = image == null ? 0 : image.getWidth();
			int imageHeight = image == null ? 0 : image.getHeight();
			if (image != null)
			{
				graphics.drawImage(image, x, y, null);
			}

			FontMetrics metrics = graphics.getFontMetrics();
			int textX = x + imageWidth + GAP;
			graphics.setColor(Color.WHITE);
			graphics.drawString(label, textX, y + metrics.getAscent() + 1);
			graphics.setColor(MUTED);
			graphics.drawString(item.name + " x" + formatQuantity(item.quantity), textX, y + metrics.getAscent() + metrics.getHeight());

			Dimension dimension = new Dimension(preferredSize.width, Math.max(imageHeight, metrics.getHeight() * 2));
			bounds.setLocation(preferredLocation);
			bounds.setSize(dimension);
			return dimension;
		}

		@Override
		public void setPreferredLocation(Point preferredLocation)
		{
			this.preferredLocation = preferredLocation;
		}

		@Override
		public void setPreferredSize(Dimension preferredSize)
		{
			this.preferredSize = preferredSize;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}
	}

	private static final class StatsComponent implements LayoutableRenderableEntity
	{
		private final BankCleanerPlugin.OverlayState state;
		private final Rectangle bounds = new Rectangle();
		private Point preferredLocation = new Point();
		private Dimension preferredSize = new Dimension(WIDTH, 0);

		StatsComponent(BankCleanerPlugin.OverlayState state)
		{
			this.state = state;
		}

		@Override
		public Dimension render(Graphics2D graphics)
		{
			FontMetrics metrics = graphics.getFontMetrics();
			int columnWidth = preferredSize.width / 3;
			int y = preferredLocation.y;

			drawColumn(graphics, metrics, preferredLocation.x, y, columnWidth, "Withdrawn", state.withdrawn);
			drawColumn(graphics, metrics, preferredLocation.x + columnWidth, y, columnWidth, "Listed", state.listed);
			drawColumn(graphics, metrics, preferredLocation.x + columnWidth * 2, y, preferredSize.width - columnWidth * 2, "Collected", state.collected);

			Dimension dimension = new Dimension(preferredSize.width, metrics.getHeight() * 2 + 2);
			bounds.setLocation(preferredLocation);
			bounds.setSize(dimension);
			return dimension;
		}

		private static void drawColumn(Graphics2D graphics, FontMetrics metrics, int x, int y, int width, String label, int value)
		{
			String number = Integer.toString(value);
			graphics.setColor(Color.WHITE);
			graphics.drawString(number, x + (width - metrics.stringWidth(number)) / 2, y + metrics.getAscent());
			graphics.setColor(MUTED);
			graphics.drawString(label, x + (width - metrics.stringWidth(label)) / 2, y + metrics.getAscent() + metrics.getHeight());
		}

		@Override
		public void setPreferredLocation(Point preferredLocation)
		{
			this.preferredLocation = preferredLocation;
		}

		@Override
		public void setPreferredSize(Dimension preferredSize)
		{
			this.preferredSize = preferredSize;
		}

		@Override
		public Rectangle getBounds()
		{
			return bounds;
		}
	}

	private static String formatQuantity(int quantity)
	{
		if (quantity >= 1_000_000)
		{
			return quantity / 1_000_000 + "M";
		}
		if (quantity >= 10_000)
		{
			return quantity / 1_000 + "K";
		}
		return Integer.toString(quantity);
	}
}
