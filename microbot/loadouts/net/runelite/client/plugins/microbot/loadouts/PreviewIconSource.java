/*
 * Copyright (c) 2026, Microbot
 * All rights reserved.
 *
 * Standalone visual-design preview support. Not shipped in the client jar.
 */
package net.runelite.client.plugins.microbot.loadouts;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;

/**
 * A {@link LoadoutIconSource} for the standalone preview. Loads real OSRS item
 * sprites pre-downloaded to {@code <dir>/icons/<id>.png} (via the RuneLite
 * static cache), falling back to a synthesised tile if an icon is missing.
 * Empty equipment slots get a drawn silhouette stand-in (the live client uses
 * the real {@code SpriteManager} art).
 */
class PreviewIconSource implements LoadoutIconSource
{
	private static final Color[] PALETTE = {
			new Color(0x6E, 0x8B, 0x3D), new Color(0x8B, 0x5A, 0x2B), new Color(0x46, 0x6B, 0x8C),
			new Color(0x8C, 0x46, 0x6B), new Color(0x9C, 0x8A, 0x3A), new Color(0x4F, 0x8C, 0x6B),
	};

	private final File iconDir;
	private final Map<Integer, ImageIcon> cache = new HashMap<>();

	PreviewIconSource(File baseDir)
	{
		this.iconDir = new File(baseDir, "icons");
	}

	@Override
	public void applyItemIcon(JLabel label, int itemId, int quantity)
	{
		label.setIcon(cache.computeIfAbsent(itemId, this::loadIcon));
	}

	private ImageIcon loadIcon(int itemId)
	{
		final File file = new File(iconDir, itemId + ".png");
		if (file.isFile())
		{
			try
			{
				final BufferedImage img = ImageIO.read(file);
				if (img != null)
				{
					return new ImageIcon(img);
				}
			}
			catch (Exception ignored)
			{
				// fall through to synthesised tile
			}
		}
		return new ImageIcon(syntheticTile(itemId));
	}

	private static BufferedImage syntheticTile(int itemId)
	{
		final BufferedImage img = new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final Color base = PALETTE[Math.floorMod(itemId, PALETTE.length)];
		g.setColor(base.darker());
		g.fillRoundRect(4, 2, 28, 28, 8, 8);
		g.setColor(base.brighter());
		g.fillRoundRect(6, 4, 24, 24, 6, 6);
		g.setColor(Color.WHITE);
		g.setFont(new Font("SansSerif", Font.BOLD, 9));
		g.drawString(String.valueOf(itemId), 7, 19);
		g.dispose();
		return img;
	}

	@Override
	public int gePrice(int itemId)
	{
		// Deterministic pseudo-price so the tooltip has plausible numbers.
		return 5 + (itemId * 37) % 20_000;
	}

	@Override
	public int highAlchPrice(int itemId)
	{
		return (int) (gePrice(itemId) * 0.6);
	}

	@Override
	public void fetchCoinIcon(Consumer<BufferedImage> consumer)
	{
		// Real coins item icon (995); the live client uses the in-game coin sprite.
		consumer.accept(loadScaled(new File(iconDir, "995.png"), 16));
	}

	@Override
	public void fetchAlchIcon(Consumer<BufferedImage> consumer)
	{
		// Real High Level Alchemy spell icon; the live client uses the game sprite.
		consumer.accept(loadScaled(new File(iconDir, "alch.png"), 16));
	}

	private static BufferedImage loadScaled(File file, int targetHeight)
	{
		try
		{
			final BufferedImage src = ImageIO.read(file);
			if (src != null && src.getHeight() > 0)
			{
				final int w = Math.max(1, src.getWidth() * targetHeight / src.getHeight());
				final BufferedImage out = new BufferedImage(w, targetHeight, BufferedImage.TYPE_INT_ARGB);
				final Graphics2D g = out.createGraphics();
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(src, 0, 0, w, targetHeight, null);
				g.dispose();
				return out;
			}
		}
		catch (Exception ignored)
		{
			// fall through
		}
		return null;
	}

	@Override
	public void fetchPlaceholder(int equipmentSlotIdx, Consumer<BufferedImage> consumer)
	{
		final BufferedImage img = new BufferedImage(28, 28, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(255, 255, 255, 22));
		g.drawRoundRect(5, 5, 17, 17, 6, 6);
		g.dispose();
		consumer.accept(img);
	}
}
