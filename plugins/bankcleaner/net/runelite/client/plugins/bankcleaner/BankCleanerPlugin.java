/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.bankcleaner;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import net.runelite.client.plugins.microbot.util.grandexchange.GrandExchangeSlots;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Withdraws the highest-value tradeable bank items (excluding coins) as notes, sells them
 * through the Grand Exchange at 1 gp, collects completed offers to the bank, and repeats
 * until no eligible bank items remain. Value ranking keys off the same model as the Bank
 * Value Tracker: GE unit price (RuneLite {@link ItemManager#getItemPrice(int)}) × quantity.
 *
 * <p>Runs once each time the plugin is enabled, on a background thread (the Rs2* calls block).
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
	private static final int SELL_PRICE_GP = 1;
	private static final int GE_SLOT_COUNT = 8;
	private static final int OVERLAY_QUEUE_LIMIT = 5;

	@Inject
	private BankCleanerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BankCleanerOverlay overlay;

	private ExecutorService executor;
	private Future<?> task;
	private volatile boolean stopRequested;
	private int pendingSoldOffers;
	private final Object overlayLock = new Object();
	private String overlayPhase = "Starting";
	private OverlayItem overlayCurrentItem;
	private List<OverlayItem> overlayNextItems = Collections.emptyList();
	private String overlayMessage = "";
	private int overlayWithdrawn;
	private int overlayListed;
	private int overlayCollected;
	private int overlaySkipped;
	private int overlaySlotsUsed = -1;
	private int overlaySlotsTotal = GE_SLOT_COUNT;

	@Provides
	BankCleanerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankCleanerConfig.class);
	}

	@Override
	protected void startUp()
	{
		stopRequested = false;
		overlayManager.add(overlay);
		resetOverlayState("Starting");
		executor = Executors.newSingleThreadExecutor();
		task = executor.submit(this::run);
	}

	@Override
	protected void shutDown()
	{
		stopRequested = true;
		setOverlayPhase("Stopped", "Plugin disabled.");
		if (executor != null)
		{
			executor.shutdown();
			executor = null;
		}
		task = null;
		overlayManager.remove(overlay);
	}

	private void run()
	{
		try
		{
			report(execute());
		}
		catch (Throwable t)
		{
			log.error("[bankcleaner] failed", t);
			report("[bankcleaner] failed: " + t);
		}
	}

	/** Does the work and returns a human-readable report. */
	private String execute()
	{
		resetOverlayState("Starting");
		StringBuilder sb = new StringBuilder("[bankcleaner] selling bank items through the Grand Exchange at 1 gp:\n");
		pendingSoldOffers = 0;
		int totalWithdrawn = 0;
		int totalOffers = 0;
		int totalCollections = 0;
		long totalValue = 0;

		while (!shouldStop())
		{
			Rs2ItemModel inventoryItem = firstSellableInventoryItem();
			if (inventoryItem != null)
			{
				SellResult result = sellUntilBlocked(sb);
				totalOffers += result.offersPlaced;
				totalCollections += result.collections;
				if (result.stopReason != null)
				{
					appendSummary(sb, totalWithdrawn, totalOffers, totalCollections, totalValue);
					sb.append(result.stopReason);
					return sb.toString();
				}

				continue;
			}

			if (!openBank())
			{
				setOverlayPhase("Blocked", "Could not open a bank.");
				appendSummary(sb, totalWithdrawn, totalOffers, totalCollections, totalValue);
				sb.append("[bankcleaner] could not open a bank. Aborted.");
				return sb.toString();
			}
			depositInventoryBlockers(sb);

			List<Scored> ranked = rankedBankItems();
			setOverlayNextFromRanked(ranked);
			if (ranked.isEmpty())
			{
				if (Rs2Bank.isOpen())
				{
					Rs2Bank.closeBank();
				}
				if (pendingSoldOffers > 0 && Rs2GrandExchange.hasSoldOffer())
				{
					if (openGrandExchange() && collectOffersToBank(sb))
					{
						totalCollections++;
						pendingSoldOffers = 0;
					}
					else
					{
						setOverlayPhase("Blocked", "Final GE collect-to-bank failed.");
						appendSummary(sb, totalWithdrawn, totalOffers, totalCollections, totalValue);
						sb.append("[bankcleaner] no bank items remain, but final GE collect-to-bank failed.");
						return sb.toString();
					}
				}
				setOverlayPhase("Done", "No eligible bank items remain.");
				appendSummary(sb, totalWithdrawn, totalOffers, totalCollections, totalValue);
				sb.append("[bankcleaner] no eligible tradeable, priced, non-coin bank items remain.");
				return sb.toString();
			}

			int freeSlots = Rs2Inventory.emptySlotCount();
			if (freeSlots <= 0)
			{
				setOverlayPhase("Blocked", "Inventory full with no sellable items.");
				appendSummary(sb, totalWithdrawn, totalOffers, totalCollections, totalValue);
				sb.append("[bankcleaner] inventory is full but contains no eligible sellable items. Aborted.");
				return sb.toString();
			}

			WithdrawResult result = withdrawNextBatch(ranked, freeSlots, sb);
			totalWithdrawn += result.itemsWithdrawn;
			totalValue += result.totalValue;
			if (result.itemsWithdrawn == 0)
			{
				setOverlayPhase("Blocked", "No bank withdrawals landed.");
				appendSummary(sb, totalWithdrawn, totalOffers, totalCollections, totalValue);
				sb.append("[bankcleaner] no bank withdrawals landed. Aborted to avoid looping on the same items.");
				return sb.toString();
			}
		}

		setOverlayPhase("Stopped", "Plugin stopped.");
		appendSummary(sb, totalWithdrawn, totalOffers, totalCollections, totalValue);
		sb.append("[bankcleaner] stopped.");
		return sb.toString();
	}

	private List<Scored> rankedBankItems()
	{
		List<Rs2ItemModel> bank = Rs2Bank.bankItems();
		if (bank == null || bank.isEmpty())
		{
			return Collections.emptyList();
		}

		final List<Rs2ItemModel> candidates = bank.stream()
			.filter(it -> it.getId() != COINS_ID && it.getQuantity() > 0)
			.collect(Collectors.toList());

		List<Scored> ranked = Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			List<Scored> out = new ArrayList<>();
			for (Rs2ItemModel it : candidates)
			{
				ItemComposition comp = itemManager.getItemComposition(it.getId());
				if (comp.getPlaceholderTemplateId() != -1 || !comp.isGeTradeable())
				{
					continue;
				}
				int unit = itemManager.getItemPrice(it.getId());
				if (unit <= 0)
				{
					continue;
				}
				out.add(new Scored(it, (long) unit * it.getQuantity()));
			}
			return out;
		}).orElse(Collections.emptyList());

		ranked.sort((a, b) -> Long.compare(b.value, a.value));
		return ranked;
	}

	private WithdrawResult withdrawNextBatch(List<Scored> ranked, int freeSlots, StringBuilder sb)
	{
		setOverlayPhase("Withdrawing", "Preparing noted bank batch.");
		setOverlayNextFromRanked(ranked);
		if (!Rs2Bank.setWithdrawAsNote())
		{
			setOverlayPhase("Blocked", "Failed to set withdraw mode to Note.");
			sb.append("[bankcleaner] failed to set bank withdraw mode to Note.\n");
			return new WithdrawResult(0, 0);
		}

		sb.append("Withdrawing next noted batch:\n");
		int taken = 0;
		long totalValue = 0;
		for (Scored s : ranked)
		{
			if (taken >= freeSlots || shouldStop())
			{
				break;
			}

			int beforeSlots = Rs2Inventory.emptySlotCount();
			int beforeQuantity = Rs2Inventory.itemQuantity(s.item.getName(), true);
			setOverlayCurrent("Withdrawing", toOverlayItem(s.item), "Withdrawing noted batch.");
			Rs2Bank.withdrawAll(s.item.getId());
			boolean landed = Global.sleepUntil(() ->
				shouldStop()
					|| Rs2Inventory.emptySlotCount() < beforeSlots
					|| Rs2Inventory.itemQuantity(s.item.getName(), true) > beforeQuantity
					|| Rs2Inventory.isFull(), 3000);

			if (shouldStop())
			{
				break;
			}

			if (!landed)
			{
				setOverlayPhase("Skipped", "Withdrawal did not land: " + s.item.getName());
				sb.append(String.format("  skipped %-28s — withdrawal did not land%n", s.item.getName()));
				continue;
			}

			taken++;
			incrementOverlayWithdrawn();
			totalValue += s.value;
			sb.append(String.format("  #%2d  %-28s x%-7d  %,d gp%n", taken, s.item.getName(), s.item.getQuantity(), s.value));
		}

		if (Rs2Bank.isOpen())
		{
			Rs2Bank.closeBank();
		}

		setOverlayNextFromInventory("Listing", "Ready to list inventory.");
		return new WithdrawResult(taken, totalValue);
	}

	private SellResult sellUntilBlocked(StringBuilder sb)
	{
		SellResult result = new SellResult();

		setOverlayNextFromInventory("Opening GE", "Opening Grand Exchange.");
		if (!openGrandExchange())
		{
			setOverlayPhase("Blocked", "Could not open the Grand Exchange.");
			result.stopReason = "[bankcleaner] could not open the Grand Exchange. Aborted.";
			return result;
		}
		updateOverlaySlots();

		while (!shouldStop())
		{
			setOverlayNextFromInventory("Listing", "Listing inventory items.");
			Rs2ItemModel item = firstSellableInventoryItem();
			if (item == null)
			{
				setOverlayPhase("Listing", "No sellable inventory items.");
				return result;
			}

			if (Rs2GrandExchange.getAvailableSlotsCount() <= 0)
			{
				updateOverlaySlots();
				if (!waitForFullBatchAndCollect(sb, result))
				{
					return result;
				}
				continue;
			}

			GrandExchangeSlots slot = Rs2GrandExchange.getAvailableSlot();
			String itemName = item.getName();
			int quantity = item.getQuantity();
			setOverlayCurrent("Listing", toOverlayItem(item), "Offering at 1 gp.");
			sb.append(String.format("Offering %-28s x%-7d at %,d gp in slot %s%n",
				itemName, quantity, SELL_PRICE_GP, slot));

			if (!Rs2GrandExchange.sellItem(itemName, quantity, SELL_PRICE_GP))
			{
				if (!isGeSellable(item))
				{
					incrementOverlaySkipped(1, "Skipped non-GE item: " + itemName);
					sb.append(String.format("Skipped %-28s x%-7d — item is not GE-sellable%n", itemName, quantity));
					continue;
				}
				setOverlayPhase("Blocked", "GE offer failed: " + itemName);
				result.stopReason = "[bankcleaner] failed to place GE sell offer for " + itemName + ". Aborted.";
				return result;
			}
			result.offersPlaced++;
			pendingSoldOffers++;
			incrementOverlayListed();
			sb.append(String.format("Listed %-28s x%-7d in slot %s%n", itemName, quantity, slot));
			setOverlayNextFromInventory("Listing", "Listed " + itemName + ".");
			updateOverlaySlots();

			if (Rs2GrandExchange.getAvailableSlotsCount() <= 0)
			{
				if (!waitForFullBatchAndCollect(sb, result))
				{
					return result;
				}
			}
		}

		return result;
	}

	private boolean waitForFullBatchAndCollect(StringBuilder sb, SellResult result)
	{
		updateOverlaySlots();
		setOverlayPhase("Waiting", "Waiting for a completed offer.");
		sb.append("GE slots are full; collecting completed offers to bank.\n");
		while (!shouldStop() && !Rs2GrandExchange.hasSoldOffer())
		{
			Global.sleep(300);
		}
		if (shouldStop())
		{
			result.stopReason = "[bankcleaner] stopped while waiting for a completed GE offer.";
			return false;
		}

		if (!collectOffersToBank(sb))
		{
			setOverlayPhase("Blocked", "Collect-to-bank failed.");
			result.stopReason = "[bankcleaner] GE slots filled but collect-to-bank failed. Aborted.";
			return false;
		}

		result.collections++;
		return true;
	}

	private boolean collectOffersToBank(StringBuilder sb)
	{
		setOverlayPhase("Collecting", "Collecting completed offers to bank.");
		sb.append("Collecting completed GE offers to bank.\n");
		boolean collected = Rs2GrandExchange.collectAllToBank();
		Global.sleepUntil(() -> shouldStop() || Rs2GrandExchange.getAvailableSlotsCount() > 0 || !Rs2GrandExchange.hasSoldOffer(), 3000);
		if (collected)
		{
			incrementOverlayCollected();
			updateOverlaySlots();
			setOverlayNextFromInventory("Listing", "Collected completed offers.");
		}
		return collected;
	}

	private Rs2ItemModel firstSellableInventoryItem()
	{
		return Rs2Inventory.items()
			.filter(this::isSellableInventoryItem)
			.findFirst()
			.orElse(null);
	}

	private boolean isSellableInventoryItem(Rs2ItemModel item)
	{
		if (item == null || item.getId() == COINS_ID || item.getQuantity() <= 0)
		{
			return false;
		}

		int itemId = normalizedOfferItemId(item);
		return isGeSellable(itemId) && getUnitPrice(itemId) > 0;
	}

	private boolean isSkippedInventoryItem(Rs2ItemModel item)
	{
		if (item == null || item.getId() == COINS_ID || item.getQuantity() <= 0)
		{
			return false;
		}

		return !isSellableInventoryItem(item);
	}

	private boolean isGeSellable(Rs2ItemModel item)
	{
		return item != null && isGeSellable(normalizedOfferItemId(item));
	}

	private boolean isGeSellable(int itemId)
	{
		if (itemId <= 0 || itemId == COINS_ID)
		{
			return false;
		}

		return Microbot.getClientThread().runOnClientThreadOptional(() ->
		{
			ItemComposition comp = itemManager.getItemComposition(itemId);
			return comp != null && comp.getPlaceholderTemplateId() == -1 && comp.isGeTradeable();
		}).orElse(false);
	}

	private int normalizedOfferItemId(Rs2ItemModel item)
	{
		int unnoted = item.getUnNotedId();
		return unnoted > 0 ? unnoted : item.getId();
	}

	private int getUnitPrice(int itemId)
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() -> itemManager.getItemPrice(itemId)).orElse(0);
	}

	private void depositInventoryBlockers(StringBuilder sb)
	{
		List<Rs2ItemModel> blockers = Rs2Inventory.items(this::isSkippedInventoryItem)
			.collect(Collectors.toList());
		if (blockers.isEmpty())
		{
			return;
		}

		incrementOverlaySkipped(blockers.size(), "Depositing non-GE-sellable inventory.");
		setOverlayPhase("Skipping", "Depositing non-GE-sellable inventory.");
		sb.append("Depositing inventory items that are not GE-sellable:\n");
		for (Rs2ItemModel item : blockers)
		{
			sb.append(String.format("  %-28s x%-7d%n", item.getName(), item.getQuantity()));
		}
		Rs2Bank.depositAll(this::isSkippedInventoryItem);
		Global.sleepUntil(() -> shouldStop() || Rs2Inventory.items(this::isSkippedInventoryItem).findFirst().isEmpty(), 3000);
		setOverlayNextFromInventory("Banking", "Inventory blockers deposited.");
	}

	private boolean openBank()
	{
		setOverlayPhase("Opening bank", "Opening nearest bank.");
		return Rs2Bank.isOpen() || Rs2Bank.walkToBankAndUseBank() || Rs2Bank.openBank();
	}

	private boolean openGrandExchange()
	{
		setOverlayPhase("Opening GE", "Opening Grand Exchange.");
		if (Rs2Bank.isOpen())
		{
			Rs2Bank.closeBank();
			Global.sleepUntil(() -> shouldStop() || !Rs2Bank.isOpen(), 3000);
		}
		if (Rs2GrandExchange.isOpen())
		{
			return true;
		}

		Rs2GrandExchange.walkToGrandExchange();
		Rs2GrandExchange.openExchange();
		boolean opened = Global.sleepUntil(() -> shouldStop() || Rs2GrandExchange.isOpen(), 15000);
		if (shouldStop())
		{
			return false;
		}
		if (opened)
		{
			updateOverlaySlots();
		}
		return opened;
	}

	private boolean shouldStop()
	{
		return stopRequested || Thread.currentThread().isInterrupted();
	}

	private void appendSummary(StringBuilder sb, int withdrawn, int offers, int collections, long value)
	{
		sb.append(String.format("Summary: withdrew %d item stack(s), placed %d offer(s), collected %d time(s), planned GE value %,d gp.%n",
			withdrawn, offers, collections, value));
	}

	OverlayState getOverlayStateSnapshot()
	{
		synchronized (overlayLock)
		{
			return new OverlayState(
				overlayPhase,
				overlayCurrentItem,
				overlayNextItems,
				overlayMessage,
				overlayWithdrawn,
				overlayListed,
				overlayCollected,
				overlaySkipped,
				overlaySlotsUsed,
				overlaySlotsTotal);
		}
	}

	private void resetOverlayState(String phase)
	{
		synchronized (overlayLock)
		{
			overlayPhase = phase;
			overlayCurrentItem = null;
			overlayNextItems = Collections.emptyList();
			overlayMessage = "";
			overlayWithdrawn = 0;
			overlayListed = 0;
			overlayCollected = 0;
			overlaySkipped = 0;
			overlaySlotsUsed = -1;
			overlaySlotsTotal = GE_SLOT_COUNT;
		}
	}

	private void setOverlayPhase(String phase, String message)
	{
		synchronized (overlayLock)
		{
			overlayPhase = phase;
			overlayCurrentItem = null;
			overlayMessage = safeMessage(message);
		}
	}

	private void setOverlayCurrent(String phase, OverlayItem current, String message)
	{
		synchronized (overlayLock)
		{
			overlayPhase = phase;
			overlayCurrentItem = current;
			overlayMessage = safeMessage(message);
		}
	}

	private void setOverlayNextFromInventory(String phase, String message)
	{
		List<OverlayItem> next = Rs2Inventory.items()
			.filter(this::isSellableInventoryItem)
			.limit(OVERLAY_QUEUE_LIMIT)
			.map(this::toOverlayItem)
			.collect(Collectors.toList());
		synchronized (overlayLock)
		{
			overlayPhase = phase;
			overlayCurrentItem = null;
			overlayNextItems = Collections.unmodifiableList(next);
			overlayMessage = safeMessage(message);
		}
	}

	private void setOverlayNextFromRanked(List<Scored> ranked)
	{
		List<OverlayItem> next = ranked.stream()
			.limit(OVERLAY_QUEUE_LIMIT)
			.map(s -> toOverlayItem(s.item))
			.collect(Collectors.toList());
		synchronized (overlayLock)
		{
			overlayNextItems = Collections.unmodifiableList(next);
		}
	}

	private void incrementOverlayWithdrawn()
	{
		synchronized (overlayLock)
		{
			overlayWithdrawn++;
		}
	}

	private void incrementOverlayListed()
	{
		synchronized (overlayLock)
		{
			overlayListed++;
		}
	}

	private void incrementOverlayCollected()
	{
		synchronized (overlayLock)
		{
			overlayCollected++;
		}
	}

	private void incrementOverlaySkipped(int skipped, String message)
	{
		synchronized (overlayLock)
		{
			overlaySkipped += skipped;
			overlayMessage = safeMessage(message);
		}
	}

	private void updateOverlaySlots()
	{
		try
		{
			int available = Rs2GrandExchange.getAvailableSlotsCount();
			synchronized (overlayLock)
			{
				overlaySlotsTotal = GE_SLOT_COUNT;
				overlaySlotsUsed = Math.max(0, Math.min(GE_SLOT_COUNT, GE_SLOT_COUNT - available));
			}
		}
		catch (Exception ignored)
		{
			synchronized (overlayLock)
			{
				overlaySlotsTotal = GE_SLOT_COUNT;
				overlaySlotsUsed = -1;
			}
		}
	}

	private OverlayItem toOverlayItem(Rs2ItemModel item)
	{
		return new OverlayItem(normalizedOfferItemId(item), item.getName(), item.getQuantity());
	}

	private static String safeMessage(String message)
	{
		return message == null ? "" : message;
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

	private static final class WithdrawResult
	{
		final int itemsWithdrawn;
		final long totalValue;

		WithdrawResult(int itemsWithdrawn, long totalValue)
		{
			this.itemsWithdrawn = itemsWithdrawn;
			this.totalValue = totalValue;
		}
	}

	private static final class SellResult
	{
		int offersPlaced;
		int collections;
		String stopReason;
	}

	static final class OverlayState
	{
		final String phase;
		final OverlayItem current;
		final List<OverlayItem> nextItems;
		final String message;
		final int withdrawn;
		final int listed;
		final int collected;
		final int skipped;
		final int slotsUsed;
		final int slotsTotal;

		OverlayState(
			String phase,
			OverlayItem current,
			List<OverlayItem> nextItems,
			String message,
			int withdrawn,
			int listed,
			int collected,
			int skipped,
			int slotsUsed,
			int slotsTotal)
		{
			this.phase = phase;
			this.current = current;
			this.nextItems = nextItems;
			this.message = message;
			this.withdrawn = withdrawn;
			this.listed = listed;
			this.collected = collected;
			this.skipped = skipped;
			this.slotsUsed = slotsUsed;
			this.slotsTotal = slotsTotal;
		}

		String currentLabel()
		{
			return phase;
		}
	}

	static final class OverlayItem
	{
		final int itemId;
		final String name;
		final int quantity;

		OverlayItem(int itemId, String name, int quantity)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
		}
	}

	private void report(String text)
	{
		log.info(text);
	}
}
