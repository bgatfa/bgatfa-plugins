/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.topwithdraw;

import com.google.inject.Provides;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;

/**
 * Withdraws the highest-value tradeable items from the open bank (excluding coins) as notes,
 * top value first, until the inventory is full. Value ranking keys off the same model as the
 * Bank Value Tracker: GE unit price (RuneLite {@link ItemManager#getItemPrice(int)}) × quantity.
 *
 * <p>Runs once each time the plugin is enabled, on a background thread (the Rs2* calls block).
 * Dry run is ON by default: it logs and writes the plan to {@code ~/.runelite/topwithdraw-last.txt}
 * without touching the bank. Turn dry run off and re-enable to actually withdraw.
 *
 * <p>This is phase 1 of the bank → Grand Exchange liquidation loop.
 */
@PluginDescriptor(
	name = "Bank Cleaner",
	description = "Cleans out your bank and dumps it into the Grand Exchange.",
	tags = {"bank", "ge", "value", "noted", "liquidate"},
	enabledByDefault = false
)
@Slf4j
public class BankCleanerPlugin extends Plugin
{
	private static final int COINS_ID = 995;
	private static final int INVENTORY_SIZE = 28;

	@Inject
	private BankCleanerConfig config;

	@Inject
	private ItemManager itemManager;

	private ExecutorService executor;

	@Provides
	BankCleanerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankCleanerConfig.class);
	}

	@Override
	protected void startUp()
	{
		executor = Executors.newSingleThreadExecutor();
		executor.submit(this::run);
	}

	@Override
	protected void shutDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	private void run()
	{
		try
		{
			report(execute());
		}
		catch (Throwable t)
		{
			log.error("[topwithdraw] failed", t);
			report("[topwithdraw] failed: " + t);
		}
	}

	/** Does the work and returns a human-readable report. */
	private String execute()
	{
		// Always open the bank if it's closed. walkToBankAndUseBank() walks to the nearest bank
		// (or just opens it when already within range, e.g. at a GE booth) and uses it;
		// openBank() is an in-range fallback. (Plain walkToBank() only walks — it does not open.)
		if (!Rs2Bank.isOpen() && !Rs2Bank.walkToBankAndUseBank() && !Rs2Bank.openBank())
		{
			return "[topwithdraw] could not open a bank. Aborted.";
		}

		List<Rs2ItemModel> bank = Rs2Bank.bankItems();
		if (bank == null || bank.isEmpty())
		{
			return "[topwithdraw] bank is empty.";
		}

		// Non-coin, positive-quantity candidates (effectively final for the client-thread lambda).
		final List<Rs2ItemModel> candidates = bank.stream()
			.filter(it -> it.getId() != COINS_ID && it.getQuantity() > 0)
			.collect(Collectors.toList());

		// Score on the client thread (composition + price lookups must run there). Tradeability is
		// judged by ItemComposition.isTradeable() ALONE — getItemPrice() synthesizes a non-zero
		// value for untradeable combination items (e.g. Slayer helmet, Marks of grace), so it is
		// NOT a tradeability signal and would otherwise let untradeables through.
		List<Scored> ranked = Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			List<Scored> out = new ArrayList<>();
			for (Rs2ItemModel it : candidates)
			{
				ItemComposition comp = itemManager.getItemComposition(it.getId());
				if (comp.getPlaceholderTemplateId() != -1 || !comp.isTradeable())
				{
					continue; // skip bank placeholders and untradeables
				}
				int unit = itemManager.getItemPrice(it.getId());
				if (unit <= 0)
				{
					continue; // skip worthless
				}
				out.add(new Scored(it, (long) unit * it.getQuantity()));
			}
			return out;
		}).orElse(Collections.emptyList());

		ranked.sort((a, b) -> Long.compare(b.value, a.value));

		int freeSlots = Rs2Inventory.emptySlotCount();
		boolean dry = config.dryRun();

		if (ranked.isEmpty())
		{
			return "[topwithdraw] no tradeable, priced, non-coin items in bank.";
		}
		if (freeSlots <= 0)
		{
			return "[topwithdraw] inventory already full (" + INVENTORY_SIZE + "/" + INVENTORY_SIZE + ").";
		}

		if (!dry)
		{
			Rs2Bank.setWithdrawAsNote();
		}

		StringBuilder sb = new StringBuilder();
		sb.append(dry ? "[topwithdraw] DRY RUN — would withdraw (noted), top value first:\n"
			: "[topwithdraw] withdrawing (noted), top value first:\n");

		// Noted items stack to one slot each, so at most freeSlots distinct items. Bounding by
		// freeSlots (not just isFull) prevents over-withdraw if withdrawAll lags a tick.
		int taken = 0;
		long totalValue = 0;
		for (Scored s : ranked)
		{
			if (taken >= freeSlots)
			{
				break;
			}
			if (!dry)
			{
				final int before = Rs2Inventory.emptySlotCount();
				Rs2Bank.withdrawAll(s.item.getId());
				Global.sleepUntil(() -> Rs2Inventory.emptySlotCount() < before || Rs2Inventory.isFull(), 2000);
			}
			taken++;
			totalValue += s.value;
			sb.append(String.format("  #%2d  %-28s x%-7d  %,d gp%n", taken, s.item.getName(), s.item.getQuantity(), s.value));
		}

		// Once the inventory is full, close the bank.
		if (!dry && Rs2Inventory.isFull() && Rs2Bank.isOpen())
		{
			boolean closed = Rs2Bank.closeBank();
			sb.append(closed ? "Inventory full — closed the bank.\n"
				: "Inventory full — tried to close the bank but it stayed open.\n");
		}

		sb.append(String.format("%s %d item(s) into %d free slot(s); total value %,d gp.",
			dry ? "Plan:" : "Done:", taken, freeSlots, totalValue));
		return sb.toString();
	}

	/** A bank item paired with its total GE value (unit price × quantity). */
	private static final class Scored
	{
		final Rs2ItemModel item;
		final long value;

		Scored(Rs2ItemModel item, long value)
		{
			this.item = item;
			this.value = value;
		}
	}

	private void report(String text)
	{
		log.info(text);
		if (config.writeReport())
		{
			try
			{
				Path out = Paths.get(System.getProperty("user.home"), ".runelite", "topwithdraw-last.txt");
				Files.write(out, text.getBytes(StandardCharsets.UTF_8));
			}
			catch (Exception e)
			{
				log.warn("[topwithdraw] could not write report file", e);
			}
		}
	}
}
