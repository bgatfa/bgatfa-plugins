/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.firemaker;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.runelite.api.Constants;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Bank → withdraw logs → light them along a valid outdoor lane → re-bank, on a loop.
 *
 * <p><b>Mechanics (per the OSRS wiki).</b> Lighting is {@link Rs2Inventory#combine}
 * (tinderbox on logs); each log is a separate action — there is no auto-chaining, so the
 * loop re-issues the combine every idle tick. After a fire lights, the engine steps the
 * player <b>west</b> (then east, then south, then north if blocked). We therefore only have
 * to keep the <b>westward</b> run clear and the player walks a straight, predictable line.
 *
 * <p><b>Valid path.</b> A fire can't be lit on occupied ground, on plants/ferns, beside
 * growing vines, in doorways, or indoors — and several of those tiles are still
 * <i>walkable</i>. So a lane is validated as <i>outdoors</i> ({@link Constants#TILE_FLAG_UNDER_ROOF}
 * clear) <b>and</b> walkable, and any light that fails to consume a log abandons the lane
 * and reroutes to a parallel one. That backstop is what catches plants/ferns and other
 * players' fires that the static check can't see.
 */
public class FiremakerScript extends Script
{
	private static final Logger log = LoggerFactory.getLogger(FiremakerScript.class);
	private static final String TINDERBOX = "Tinderbox";

	/** Tiles due west of the anchor a lane must keep clear before we light along it. */
	private static final int LANE_LENGTH = 15;
	/** How far north/south to search for a clear parallel lane when the current one blocks. */
	private static final int LANE_SHIFT_MAX = 6;

	/** Authoritative on/off switch, checked at the top of the loop and inside long waits. */
	private volatile boolean active;

	/** East end of the firemaking lane; logs are lit walking west from here. */
	private volatile WorldPoint anchor;

	public boolean run(FiremakerConfig config)
	{
		active = true;
		anchor = null;
		mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() ->
		{
			try
			{
				if (!active || !Microbot.isLoggedIn() || !super.run())
				{
					return;
				}

				// The lane anchor is discovered automatically: wherever the player is standing
				// when the plugin is enabled becomes the east end of the lane. Capture it once,
				// BEFORE any banking walk moves us.
				if (anchor == null)
				{
					anchor = Rs2Player.getWorldLocation();
					log.info("Firemaking lane anchor set to {}", anchor);
				}

				// Let the current action (bank op, walk, or the post-light step) resolve.
				if (Rs2Player.isMoving() || Rs2Player.isAnimating())
				{
					return;
				}

				final String logName = config.logType().getItemName();
				if (Rs2Inventory.hasItem(logName) && Rs2Inventory.hasItem(TINDERBOX))
				{
					lightPhase(logName);
				}
				else
				{
					bank(logName);
				}
			}
			catch (Exception ex)
			{
				log.error("Firemaker loop error: {}", ex.getMessage(), ex);
			}
		}, 0, 600, TimeUnit.MILLISECONDS);
		return true;
	}

	// ----- lighting -------------------------------------------------------------------

	private void lightPhase(String logName)
	{
		WorldPoint me = Rs2Player.getWorldLocation();

		// Keep lighting along the current line only while the tile under us and the next one
		// west are both lightable; otherwise find a fresh, validated lane and walk to it.
		if (!isLightable(me) || !isLightable(me.dx(-1)))
		{
			WorldPoint start = findClearLaneStart();
			if (start == null)
			{
				Microbot.status = "No clear outdoor lane nearby";
				log.warn("No clear, outdoor lane found within +/-{} of anchor {}", LANE_SHIFT_MAX, anchor);
				sleep(1500);
				return;
			}
			if (!me.equals(start))
			{
				Microbot.status = "Walking to lane " + start.getX() + "," + start.getY();
				Rs2Walker.walkTo(start, 0);
				sleepUntil(() -> !active || start.equals(Rs2Player.getWorldLocation()), 8000);
				return; // light next tick once standing on the lane start
			}
		}

		Microbot.status = "Lighting " + logName;
		int before = Rs2Inventory.count(logName);
		Rs2Inventory.combine(TINDERBOX, logName);

		// Success iff a log was consumed. If not, this tile is unlightable (plant/fern/fire/
		// doorway/etc.) — next tick isLightable(me) flips false and we reroute to a new lane.
		sleepUntil(() -> !active || Rs2Inventory.count(logName) < before, 3000);
	}

	/**
	 * Find the east end of a fully clear, outdoor, westward lane of {@code laneLength} tiles.
	 * Tries the anchor row first, then parallel rows offset ±1..{@code laneShiftMax} in Y.
	 */
	private WorldPoint findClearLaneStart()
	{
		if (anchor == null)
		{
			return null;
		}

		for (int shift = 0; shift <= LANE_SHIFT_MAX; shift++)
		{
			for (int sign : (shift == 0 ? new int[]{0} : new int[]{1, -1}))
			{
				WorldPoint start = new WorldPoint(anchor.getX(), anchor.getY() + sign * shift, anchor.getPlane());
				if (laneClear(start, LANE_LENGTH))
				{
					return start;
				}
			}
		}
		return null;
	}

	/** True iff every tile from {@code start} going {@code length} tiles west is lightable. */
	private boolean laneClear(WorldPoint start, int length)
	{
		for (int i = 0; i < length; i++)
		{
			if (!isLightable(start.dx(-i)))
			{
				return false;
			}
		}
		return true;
	}

	/** A tile is lightable if it is loaded, outdoors (not under a roof), and walkable. */
	private boolean isLightable(WorldPoint wp)
	{
		if (wp == null || !Rs2Tile.isWalkable(wp))
		{
			return false;
		}
		return isOutdoors(wp);
	}

	/** Outdoors = the tile's render settings don't have the under-roof flag set. */
	private boolean isOutdoors(WorldPoint wp)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			WorldView wv = Microbot.getClient().getTopLevelWorldView();
			if (wv == null)
			{
				return false;
			}
			LocalPoint lp = LocalPoint.fromWorld(wv, wp);
			if (lp == null)
			{
				return false; // outside the loaded scene — treat as not lightable
			}
			byte[][][] settings = wv.getTileSettings();
			int plane = wp.getPlane();
			int sx = lp.getSceneX();
			int sy = lp.getSceneY();
			if (settings == null || plane < 0 || plane >= settings.length
				|| sx < 0 || sx >= settings[plane].length || sy < 0 || sy >= settings[plane][sx].length)
			{
				return false;
			}
			return (settings[plane][sx][sy] & Constants.TILE_FLAG_UNDER_ROOF) == 0;
		}).orElse(false);
	}

	// ----- banking --------------------------------------------------------------------

	private void bank(String logName)
	{
		Microbot.status = "Banking";

		if (!Rs2Bank.isOpen())
		{
			if (!Rs2Bank.walkToBankAndUseBank() || !sleepUntil(() -> !active || Rs2Bank.isOpen(), 8000))
			{
				return; // try again next tick
			}
		}
		if (!active)
		{
			return;
		}

		// Deposit ash/leftovers so logs have room, but keep the tinderbox.
		Rs2Bank.depositAllExcept(TINDERBOX);
		sleep(300, 600);

		if (!Rs2Inventory.hasItem(TINDERBOX))
		{
			if (!Rs2Bank.hasBankItem(TINDERBOX))
			{
				Microbot.status = "No tinderbox in inventory or bank — stopping";
				log.warn("No tinderbox available; cannot firemake");
				shutdown();
				return;
			}
			Rs2Bank.withdrawX(TINDERBOX, 1);
			sleep(300, 600);
		}

		if (!Rs2Bank.hasBankItem(logName))
		{
			Microbot.status = "Out of " + logName + " — stopping";
			log.info("No more '{}' in bank; stopping", logName);
			shutdown();
			return;
		}

		Rs2Bank.withdrawAll(logName);
		sleepUntil(() -> !active || Rs2Inventory.hasItem(logName), 3000);
		sleep(300, 600);

		Rs2Bank.closeBank();
		sleepUntil(() -> !active || !Rs2Bank.isOpen(), 3000);
	}

	@Override
	public void shutdown()
	{
		active = false;                                       // bail any in-flight iteration
		Rs2Walker.clearWalkingRoute("firemaker:shutdown");    // stop any pending walk
		super.shutdown();                                     // cancel(true) the scheduled future
		Microbot.status = "IDLE";
	}
}
