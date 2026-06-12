/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Duration;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Compact status panel: current state + Vorkath phase, kill rate, runtime and looted value. */
public class VorkathOverlay extends OverlayPanel
{
	private final VorkathPlugin plugin;

	@Inject
	VorkathOverlay(VorkathPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final VorkathScript script = plugin.getScript();
		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(170, 0));

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Vorkath")
			.color(Color.CYAN)
			.build());

		if (script == null)
		{
			return super.render(graphics);
		}

		line("State", script.getState().name());
		line("Phase", script.getPhase().label());

		final long kills = script.getKills();
		line("Kills", Long.toString(kills));

		final long runtimeMs = script.getRuntimeMillis();
		line("Runtime", formatDuration(runtimeMs));
		line("Kills/hr", Long.toString(perHour(kills, runtimeMs)));
		line("Loot", formatGp(script.getLootValue()) + " gp");

		return super.render(graphics);
	}

	private void line(String left, String right)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.build());
	}

	private static long perHour(long count, long elapsedMs)
	{
		if (elapsedMs <= 0)
		{
			return 0;
		}
		return count * 3_600_000L / elapsedMs;
	}

	private static String formatDuration(long ms)
	{
		final Duration d = Duration.ofMillis(Math.max(0, ms));
		return String.format("%02d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
	}

	private static String formatGp(long gp)
	{
		if (gp >= 1_000_000)
		{
			return String.format("%.1fM", gp / 1_000_000.0);
		}
		if (gp >= 1_000)
		{
			return String.format("%.1fK", gp / 1_000.0);
		}
		return Long.toString(gp);
	}
}
