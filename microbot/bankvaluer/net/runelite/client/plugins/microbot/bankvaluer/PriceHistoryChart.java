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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.JComponent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/** Interactive buy/sell + volume line chart with a hover crosshair and tooltip. */
class PriceHistoryChart extends JComponent
{
	private static final Color BUY = new Color(240, 207, 123);
	private static final Color SELL = new Color(90, 170, 250);
	private static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("MMM ''yy");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

	private final List<PricePoint> data;
	private int hover = -1;

	PriceHistoryChart(List<PricePoint> data)
	{
		this.data = data;
		setPreferredSize(new Dimension(10, 150));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));

		final MouseAdapter ma = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				hover = nearest(e.getX());
				repaint();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hover = -1;
				repaint();
			}
		};
		addMouseMotionListener(ma);
		addMouseListener(ma);
	}

	private int nearest(int mx)
	{
		final int n = data.size();
		if (n <= 1)
		{
			return n - 1;
		}
		final int padL = 6;
		final int pw = getWidth() - padL - 6;
		int idx = (int) Math.round((mx - padL) / (double) pw * (n - 1));
		return Math.max(0, Math.min(n - 1, idx));
	}

	@Override
	protected void paintComponent(Graphics g0)
	{
		final Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		final int w = getWidth();
		final int h = getHeight();
		g.setColor(ColorScheme.DARKER_GRAY_COLOR);
		g.fillRect(0, 0, w, h);

		final int n = data.size();
		if (n == 0)
		{
			g.dispose();
			return;
		}

		final int padL = 6;
		final int padR = 6;
		final int padT = 14;
		final int padB = 18;
		final int volH = 20;
		final int gap = 6;
		final int pw = w - padL - padR;
		final int plotTop = padT;
		final int plotBottom = h - padB - volH - gap;
		final int priceH = plotBottom - plotTop;
		final int volTop = plotBottom + gap;
		final int volBottom = h - padB;

		long min = data.get(0).getLow();
		long max = data.get(0).getHigh();
		long maxVol = 1;
		for (PricePoint p : data)
		{
			max = Math.max(max, p.getHigh());
			min = Math.min(min, p.getLow());
			maxVol = Math.max(maxVol, p.getVolume());
		}
		final long range = Math.max(1, max - min);

		// gridlines
		for (int k = 0; k <= 2; k++)
		{
			final int gy = plotTop + priceH * k / 2;
			g.setColor(new Color(77, 77, 77, 70));
			g.drawLine(padL, gy, w - padR, gy);
		}

		// high/low paths + margin band
		final GeneralPath hi = new GeneralPath();
		final GeneralPath lo = new GeneralPath();
		final GeneralPath band = new GeneralPath();
		for (int i = 0; i < n; i++)
		{
			final double x = px(i, n, padL, pw);
			final double yH = py(data.get(i).getHigh(), min, range, plotTop, priceH);
			final double yL = py(data.get(i).getLow(), min, range, plotTop, priceH);
			if (i == 0)
			{
				hi.moveTo(x, yH);
				lo.moveTo(x, yL);
				band.moveTo(x, yH);
			}
			else
			{
				hi.lineTo(x, yH);
				lo.lineTo(x, yL);
				band.lineTo(x, yH);
			}
		}
		for (int i = n - 1; i >= 0; i--)
		{
			band.lineTo(px(i, n, padL, pw), py(data.get(i).getLow(), min, range, plotTop, priceH));
		}
		band.closePath();
		g.setColor(new Color(240, 207, 123, 38));
		g.fill(band);

		// volume bars
		final double bw = Math.max(1.0, (double) pw / n - 1);
		g.setColor(new Color(120, 120, 120, 95));
		for (int i = 0; i < n; i++)
		{
			final double x = px(i, n, padL, pw);
			final int bh = (int) Math.round((volBottom - volTop) * (data.get(i).getVolume() / (double) maxVol));
			g.fill(new Rectangle2D.Double(x - bw / 2, volBottom - bh, bw, bh));
		}

		// lines
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(SELL);
		g.draw(lo);
		g.setColor(BUY);
		g.draw(hi);

		// labels
		final Font small = FontManager.getRunescapeSmallFont();
		g.setFont(small);
		g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
		g.drawString(BankValueFormat.gp(max), padL + 2, plotTop + 9);
		g.drawString(BankValueFormat.gp(min), padL + 2, plotBottom - 2);
		g.drawString("Vol", padL + 2, volTop + 9);
		g.drawString(month(data.get(0).getTimestamp()), padL, h - 4);
		final String end = month(data.get(n - 1).getTimestamp());
		g.drawString(end, w - padR - g.getFontMetrics().stringWidth(end), h - 4);

		// hover crosshair + tooltip
		if (hover >= 0 && hover < n)
		{
			final PricePoint p = data.get(hover);
			final double hx = px(hover, n, padL, pw);
			final double yH = py(p.getHigh(), min, range, plotTop, priceH);
			final double yL = py(p.getLow(), min, range, plotTop, priceH);

			g.setColor(new Color(198, 198, 198, 110));
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3, 3}, 0));
			g.drawLine((int) hx, plotTop, (int) hx, volBottom);

			g.setStroke(new BasicStroke(2f));
			marker(g, hx, yH, BUY);
			marker(g, hx, yL, SELL);

			final String d = day(p.getTimestamp());
			final String buy = "Buy   " + BankValueFormat.gp(p.getHigh());
			final String sell = "Sell   " + BankValueFormat.gp(p.getLow());
			final int wmax = Math.max(g.getFontMetrics(small).stringWidth(d),
				Math.max(g.getFontMetrics(small).stringWidth(buy), g.getFontMetrics(small).stringWidth(sell)));
			final int boxW = wmax + 14;
			final int boxH = 44;
			int tx = (int) (hx + 10);
			if (tx + boxW > w - 2)
			{
				tx = (int) (hx - 10 - boxW);
			}
			final int ty = (int) Math.max(plotTop + 2, Math.min(yH - boxH - 6, plotBottom - boxH));

			g.setColor(new Color(20, 20, 20, 240));
			g.fillRoundRect(tx, ty, boxW, boxH, 8, 8);
			g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
			g.setStroke(new BasicStroke(1f));
			g.drawRoundRect(tx, ty, boxW, boxH, 8, 8);
			g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g.drawString(d, tx + 7, ty + 13);
			g.setColor(BUY);
			g.drawString(buy, tx + 7, ty + 27);
			g.setColor(SELL);
			g.drawString(sell, tx + 7, ty + 39);
		}

		g.dispose();
	}

	private static double px(int i, int n, int padL, int pw)
	{
		return padL + (n == 1 ? 0 : pw * (double) i / (n - 1));
	}

	private static double py(long v, long min, long range, int top, int hgt)
	{
		return top + hgt * (1 - (v - min) / (double) range);
	}

	private static void marker(Graphics2D g, double x, double y, Color fill)
	{
		g.setColor(Color.WHITE);
		g.draw(new Ellipse2D.Double(x - 3.5, y - 3.5, 7, 7));
		g.setColor(fill);
		g.fill(new Ellipse2D.Double(x - 2.5, y - 2.5, 5, 5));
	}

	private static String month(long ts)
	{
		return Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).format(MON);
	}

	private static String day(long ts)
	{
		return Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).format(DAY);
	}
}
