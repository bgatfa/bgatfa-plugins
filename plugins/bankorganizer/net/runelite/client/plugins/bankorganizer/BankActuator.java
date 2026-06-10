/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.bankorganizer;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

final class BankActuator
{
	private static final int BANK_GROUP_ID = 12;
	private static final int BANK_INSERT_BUTTON_CHILD_ID = 17;
	private static final int BANK_TAB_CONTAINER_DYNAMIC_MAIN_INDEX = 10;
	private static final int[] TAB_COUNT_VARBITS = {
		Varbits.BANK_TAB_ONE_COUNT,
		Varbits.BANK_TAB_TWO_COUNT,
		Varbits.BANK_TAB_THREE_COUNT,
		Varbits.BANK_TAB_FOUR_COUNT,
		Varbits.BANK_TAB_FIVE_COUNT,
		Varbits.BANK_TAB_SIX_COUNT,
		Varbits.BANK_TAB_SEVEN_COUNT,
		Varbits.BANK_TAB_EIGHT_COUNT,
		Varbits.BANK_TAB_NINE_COUNT
	};

	private final Client client;
	private final BankSnapshotReader snapshotReader;

	@Inject
	BankActuator(Client client, BankSnapshotReader snapshotReader)
	{
		this.client = client;
		this.snapshotReader = snapshotReader;
	}

	boolean ensureBankOpen()
	{
		if (Rs2Bank.isOpen())
		{
			return true;
		}
		return Rs2Bank.openBank() && Global.sleepUntil(Rs2Bank::isOpen, 5000);
	}

	boolean isBankInsertMode()
	{
		return bankRearrangeMode() == 1;
	}

	ActuatorResult ensureBankInsertMode()
	{
		if (!ensureBankOpen())
		{
			return ActuatorResult.fail("Bank is not open.");
		}
		if (isBankInsertMode())
		{
			return ActuatorResult.ok("Bank rearrange mode is already Insert.");
		}

		if (!Rs2Widget.clickWidget(BANK_GROUP_ID, BANK_INSERT_BUTTON_CHILD_ID))
		{
			return ActuatorResult.fail("Could not click bank Insert mode.");
		}

		boolean verified = Global.sleepUntil(this::isBankInsertMode, 2500);
		return verified
			? ActuatorResult.ok("Bank rearrange mode set to Insert.")
			: ActuatorResult.fail("Bank rearrange mode did not switch to Insert.");
	}

	ActuatorResult openMainTab()
	{
		if (!ensureBankOpen())
		{
			return ActuatorResult.fail("Bank is not open.");
		}
		if (Rs2Bank.getCurrentTab() == 0)
		{
			return ActuatorResult.ok("Main tab already open.");
		}
		if (!Rs2Bank.openMainTab())
		{
			return ActuatorResult.fail("Could not invoke main tab.");
		}
		boolean verified = Global.sleepUntil(() -> Rs2Bank.getCurrentTab() == 0, 2500);
		return verified ? ActuatorResult.ok("Main tab opened.") : ActuatorResult.fail("Main tab did not become active.");
	}

	ActuatorResult openTab(int tabIndex)
	{
		if (!ensureBankOpen())
		{
			return ActuatorResult.fail("Bank is not open.");
		}
		if (tabIndex == 0)
		{
			return openMainTab();
		}
		if (tabIndex < 1 || tabIndex > 9 || tabCount(tabIndex) <= 0)
		{
			return ActuatorResult.fail("Tab " + tabIndex + " does not exist.");
		}
		if (Rs2Bank.getCurrentTab() == tabIndex)
		{
			return ActuatorResult.ok("Tab " + tabIndex + " already open.");
		}
		if (!Rs2Bank.openTab(tabIndex))
		{
			return ActuatorResult.fail("Could not invoke tab " + tabIndex + ".");
		}
		boolean verified = Global.sleepUntil(() -> Rs2Bank.getCurrentTab() == tabIndex, 2500);
		return verified ? ActuatorResult.ok("Tab " + tabIndex + " opened.") : ActuatorResult.fail("Tab " + tabIndex + " did not become active.");
	}

	ActuatorResult dragItemFromTabToNewTab(int itemId, int sourceTab)
	{
		if (!ensureBankOpen())
		{
			return ActuatorResult.fail("Bank is not open.");
		}

		ActuatorResult sourceTabOpen = openTab(sourceTab);
		if (!sourceTabOpen.success())
		{
			return sourceTabOpen;
		}

		Rs2ItemModel item = findBankItem(itemId);
		if (item == null)
		{
			return ActuatorResult.fail("Item " + itemId + " is not in the bank.");
		}

		int beforeRealTabs = realTabCount();
		if (beforeRealTabs >= 9)
		{
			return ActuatorResult.fail("All nine real bank tabs already exist.");
		}
		int newTabIndex = beforeRealTabs + 1;
		int beforeCount = tabCount(newTabIndex);
		int beforeSourceCount = sourceTab > 0 ? tabCount(sourceTab) : 0;

		if (!Rs2Bank.scrollBankToSlot(item.getSlot()))
		{
			return ActuatorResult.fail("Could not scroll source item into view.");
		}

		Rectangle source = Rs2Bank.getItemBounds(item.getSlot());
		Rectangle target = tabBounds(newTabTargetDynamicIndex());
		if (!inCanvas(source) || !inCanvas(target))
		{
			return ActuatorResult.fail("Source or new-tab bounds were outside the canvas.");
		}

		int originalQuantity = item.getQuantity();
		Microbot.drag(source, target);
		boolean verified = Global.sleepUntil(() ->
			tabCount(newTabIndex) > beforeCount
				&& (sourceTab <= 0 || tabCount(sourceTab) < beforeSourceCount)
				&& quantityFor(itemId) == originalQuantity, 5000);
		return verified
			? ActuatorResult.ok("Dragged item to new tab " + newTabIndex + ".")
			: ActuatorResult.fail("New-tab drag was not verified.");
	}

	ActuatorResult dragItemFromTabToMainTab(int itemId, int sourceTab)
	{
		if (!ensureBankOpen())
		{
			return ActuatorResult.fail("Bank is not open.");
		}
		if (sourceTab <= 0)
		{
			return ActuatorResult.ok("Item is already in main tab.");
		}
		if (sourceTab > 9 || tabCount(sourceTab) <= 0)
		{
			return ActuatorResult.fail("Source tab " + sourceTab + " does not exist.");
		}

		ActuatorResult sourceTabOpen = openTab(sourceTab);
		if (!sourceTabOpen.success())
		{
			return sourceTabOpen;
		}

		Rs2ItemModel item = findBankItem(itemId);
		if (item == null)
		{
			return ActuatorResult.fail("Item " + itemId + " is not in the bank.");
		}

		BankSnapshot beforeSnapshot = snapshotReader.read();
		int beforeMainCount = beforeSnapshot.mainTabCount();
		int beforeSourceCount = tabCount(sourceTab);
		int originalQuantity = item.getQuantity();
		if (!Rs2Bank.scrollBankToSlot(item.getSlot()))
		{
			return ActuatorResult.fail("Could not scroll source item into view.");
		}

		Rectangle source = Rs2Bank.getItemBounds(item.getSlot());
		Rectangle target = tabBounds(BANK_TAB_CONTAINER_DYNAMIC_MAIN_INDEX);
		if (!inCanvas(source) || !inCanvas(target))
		{
			return ActuatorResult.fail("Source or main tab bounds were outside the canvas.");
		}

		Microbot.drag(source, target);
		boolean verified = Global.sleepUntil(() -> {
			if (tabCount(sourceTab) >= beforeSourceCount || quantityFor(itemId) != originalQuantity)
			{
				return false;
			}
			try
			{
				return snapshotReader.read().mainTabCount() > beforeMainCount;
			}
			catch (Throwable ignored)
			{
				return false;
			}
		}, 5000);
		return verified
			? ActuatorResult.ok("Dragged item to main tab.")
			: ActuatorResult.fail("Main-tab drag was not verified.");
	}

	ActuatorResult dragItemFromTabToExistingTab(int itemId, int sourceTab, int tabIndex)
	{
		if (!ensureBankOpen())
		{
			return ActuatorResult.fail("Bank is not open.");
		}
		if (tabIndex < 1 || tabIndex > 9 || tabCount(tabIndex) <= 0)
		{
			return ActuatorResult.fail("Destination tab " + tabIndex + " does not exist.");
		}
		if (sourceTab == tabIndex)
		{
			return ActuatorResult.ok("Item is already in destination tab " + tabIndex + ".");
		}

		ActuatorResult sourceTabOpen = openTab(sourceTab);
		if (!sourceTabOpen.success())
		{
			return sourceTabOpen;
		}

		Rs2ItemModel item = findBankItem(itemId);
		if (item == null)
		{
			return ActuatorResult.fail("Item " + itemId + " is not in the bank.");
		}

		int beforeCount = tabCount(tabIndex);
		int beforeSourceCount = sourceTab > 0 ? tabCount(sourceTab) : 0;
		int originalQuantity = item.getQuantity();
		if (!Rs2Bank.scrollBankToSlot(item.getSlot()))
		{
			return ActuatorResult.fail("Could not scroll source item into view.");
		}

		Rectangle source = Rs2Bank.getItemBounds(item.getSlot());
		Rectangle target = tabBounds(BANK_TAB_CONTAINER_DYNAMIC_MAIN_INDEX + tabIndex);
		if (!inCanvas(source) || !inCanvas(target))
		{
			return ActuatorResult.fail("Source or destination tab bounds were outside the canvas.");
		}

		Microbot.drag(source, target);
		boolean verified = Global.sleepUntil(() ->
			tabCount(tabIndex) > beforeCount
				&& (sourceTab <= 0 || tabCount(sourceTab) < beforeSourceCount)
				&& quantityFor(itemId) == originalQuantity, 5000);
		return verified
			? ActuatorResult.ok("Dragged item to existing tab " + tabIndex + ".")
			: ActuatorResult.fail("Existing-tab drag was not verified.");
	}

	FullOrganizeResult runBankTagLayoutDelta(BankTagLayoutPlan plan)
	{
		if (!ensureBankOpen())
		{
			return FullOrganizeResult.fail("Bank is not open.", null, 0, 0);
		}
		if (plan == null)
		{
			return FullOrganizeResult.fail("No layout plan was provided.", safeSnapshot(), 0, 0);
		}
		ActuatorResult insertMode = ensureBankInsertMode();
		if (!insertMode.success())
		{
			return FullOrganizeResult.fail(insertMode.message(), safeSnapshot(), 0, 0);
		}

		BankSnapshot baseline = snapshotReader.read();
		int originalCount = baseline.stackCount();
		Map<Integer, Integer> originalQuantities = quantityMap(baseline);
		int createdTabs = 0;
		int moved = 0;
		int sorted = 0;
		List<String> steps = new ArrayList<>();

		Map<Integer, List<BankTagLayoutMoveAction>> actionsByTab = layoutActionsByTargetTab(plan.actions());
		List<BankTagLayoutMoveAction> mainActions = actionsByTab.get(0);
		if (mainActions != null && !mainActions.isEmpty())
		{
			for (BankTagLayoutMoveAction action : mainActions)
			{
				if (Thread.currentThread().isInterrupted())
				{
					return FullOrganizeResult.fail("Layout organize interrupted.", safeSnapshot(), createdTabs, moved);
				}

				ActuatorResult move = dragItemFromTabToMainTab(action.itemId(), action.sourceTab());
				steps.add("Main: " + move.message());
				if (!move.success())
				{
					return FullOrganizeResult.fail(joinSteps(steps), safeSnapshot(), createdTabs, moved);
				}
				moved++;

				BankSnapshot afterMove = snapshotReader.read();
				String verificationError = verifySnapshotUnchanged(originalCount, originalQuantities, afterMove);
				if (verificationError != null)
				{
					return FullOrganizeResult.fail("After moving " + action.name() + " to main: "
						+ verificationError, afterMove, createdTabs, moved);
				}
			}
		}

		for (BankTagLayoutTab tab : plan.tabs())
		{
			List<BankTagLayoutMoveAction> actions = actionsByTab.get(tab.tabIndex());
			if (actions == null || actions.isEmpty())
			{
				continue;
			}
			if (Thread.currentThread().isInterrupted())
			{
				return FullOrganizeResult.fail("Layout organize interrupted.", safeSnapshot(), createdTabs, moved);
			}

			int startIndex = 0;
			if (tabCount(tab.tabIndex()) <= 0)
			{
				int appendTab = realTabCount() + 1;
				if (tab.tabIndex() != appendTab)
				{
					return FullOrganizeResult.fail("Cannot create missing layout tab " + tab.tabIndex()
						+ " (" + tab.name() + ") because the next appendable tab is " + appendTab + ".",
						safeSnapshot(), createdTabs, moved);
				}

				BankTagLayoutMoveAction seed = actions.get(0);
				ActuatorResult createTab = dragItemFromTabToNewTab(seed.itemId(), seed.sourceTab());
				steps.add(tab.name() + ": " + createTab.message());
				if (!createTab.success())
				{
					return FullOrganizeResult.fail(joinSteps(steps), safeSnapshot(), createdTabs, moved);
				}
				createdTabs++;
				moved++;
				startIndex = 1;

				BankSnapshot afterSeed = snapshotReader.read();
				String verificationError = verifySnapshotUnchanged(originalCount, originalQuantities, afterSeed);
				if (verificationError != null)
				{
					return FullOrganizeResult.fail("After creating layout tab " + tab.name() + ": "
						+ verificationError, afterSeed, createdTabs, moved);
				}
				if (tabCount(tab.tabIndex()) != 1)
				{
					return FullOrganizeResult.fail("Expected layout tab " + tab.tabIndex()
						+ " count 1 after seed, got " + tabCount(tab.tabIndex()) + ".", afterSeed, createdTabs, moved);
				}
			}

			for (int i = startIndex; i < actions.size(); i++)
			{
				if (Thread.currentThread().isInterrupted())
				{
					return FullOrganizeResult.fail("Layout organize interrupted.", safeSnapshot(), createdTabs, moved);
				}

				BankTagLayoutMoveAction action = actions.get(i);
				ActuatorResult move = dragItemFromTabToExistingTab(action.itemId(), action.sourceTab(), tab.tabIndex());
				steps.add(tab.name() + ": " + move.message());
				if (!move.success())
				{
					return FullOrganizeResult.fail(joinSteps(steps), safeSnapshot(), createdTabs, moved);
				}
				moved++;

				BankSnapshot afterMove = snapshotReader.read();
				String verificationError = verifySnapshotUnchanged(originalCount, originalQuantities, afterMove);
				if (verificationError != null)
				{
					return FullOrganizeResult.fail("After moving " + action.name() + ": "
						+ verificationError, afterMove, createdTabs, moved);
				}
			}
		}

		for (BankTagLayoutTab tab : plan.tabs())
		{
			if (Thread.currentThread().isInterrupted())
			{
				return FullOrganizeResult.fail("Layout sort interrupted.", safeSnapshot(), createdTabs, moved + sorted);
			}

			SortResult sort = sortTabByLayout(tab, originalCount, originalQuantities);
			if (!sort.success())
			{
				return FullOrganizeResult.fail(sort.message(), safeSnapshot(), createdTabs, moved + sorted);
			}
			sorted += sort.moves();
		}

		BankSnapshot finalSnapshot = snapshotReader.read();
		String verificationError = verifySnapshotUnchanged(originalCount, originalQuantities, finalSnapshot);
		if (verificationError != null)
		{
			return FullOrganizeResult.fail(verificationError, finalSnapshot, createdTabs, moved);
		}

		return FullOrganizeResult.ok("Layout delta moved " + moved + " stacks, sorted " + sorted
			+ " positions, and created " + createdTabs
			+ " missing tabs. Bank count/quantities verified.", finalSnapshot, createdTabs, moved + sorted);
	}

	private SortResult sortTabByLayout(
		BankTagLayoutTab tab,
		int originalCount,
		Map<Integer, Integer> originalQuantities)
	{
		if (tabCount(tab.tabIndex()) <= 0)
		{
			return SortResult.ok("Tab " + tab.tabIndex() + " does not exist; no sort needed.", 0);
		}

		ActuatorResult open = openTab(tab.tabIndex());
		if (!open.success())
		{
			return SortResult.fail(open.message(), 0);
		}

		ActuatorResult insertMode = ensureBankInsertMode();
		if (!insertMode.success())
		{
			return SortResult.fail(insertMode.message(), 0);
		}

		int moves = 0;
		for (int targetPosition = 0; ; targetPosition++)
		{
			BankSnapshot snapshot = snapshotReader.read();
			List<BankSnapshot.BankStack> tabStacks = tabStacks(snapshot, tab.tabIndex());
			List<Integer> desiredPresent = desiredPresentItemIds(tab, tabStacks);
			if (targetPosition >= desiredPresent.size())
			{
				return SortResult.ok("Sorted tab " + tab.tabIndex() + ".", moves);
			}

			int desiredItemId = desiredPresent.get(targetPosition);
			if (targetPosition >= tabStacks.size())
			{
				return SortResult.fail("Tab " + tab.tabIndex() + " has fewer stacks than expected while sorting.", moves);
			}
			if (tabStacks.get(targetPosition).itemId() == desiredItemId)
			{
				continue;
			}

			int sourcePosition = indexOfItemId(tabStacks, desiredItemId);
			if (sourcePosition < 0)
			{
				return SortResult.fail("Could not find item " + desiredItemId + " in tab " + tab.tabIndex()
					+ " while sorting.", moves);
			}
			if (sourcePosition < targetPosition)
			{
				return SortResult.fail("Sorting prefix drifted in tab " + tab.tabIndex() + ".", moves);
			}

			BankSnapshot.BankStack source = tabStacks.get(sourcePosition);
			BankSnapshot.BankStack target = tabStacks.get(targetPosition);
			ActuatorResult drag = dragItemWithinOpenTab(source, target);
			if (!drag.success())
			{
				return SortResult.fail("Tab " + tab.tabIndex() + ": " + drag.message(), moves);
			}
			moves++;

			int verifiedPrefixLength = targetPosition + 1;
			boolean sortedPrefix = Global.sleepUntil(() -> {
				BankSnapshot afterMove = snapshotReader.read();
				return verifySnapshotUnchanged(originalCount, originalQuantities, afterMove) == null
					&& isOrderedPrefix(afterMove, tab, verifiedPrefixLength);
			}, 5000);
			if (!sortedPrefix)
			{
				BankSnapshot afterMove = snapshotReader.read();
				String unchangedError = verifySnapshotUnchanged(originalCount, originalQuantities, afterMove);
				if (unchangedError != null)
				{
					return SortResult.fail("After sorting tab " + tab.tabIndex() + ": " + unchangedError, moves);
				}
				return SortResult.fail("Tab " + tab.tabIndex() + " order was not verified after moving "
					+ source.name() + ".", moves);
			}
		}
	}

	private static ActuatorResult dragItemWithinOpenTab(BankSnapshot.BankStack sourceStack, BankSnapshot.BankStack targetStack)
	{
		if (!Rs2Bank.scrollBankToSlot(sourceStack.slot()))
		{
			return ActuatorResult.fail("Could not scroll source item into view.");
		}

		Rectangle source = Rs2Bank.getItemBounds(sourceStack.slot());
		Rectangle target = Rs2Bank.getItemBounds(targetStack.slot());
		if (!inCanvas(source) || !inCanvas(target))
		{
			return ActuatorResult.fail("Source or target slot bounds were outside the canvas.");
		}

		Microbot.drag(source, target);
		return ActuatorResult.ok("Inserted " + sourceStack.name() + " before " + targetStack.name() + ".");
	}

	int realTabCount()
	{
		int count = 0;
		for (int i = 1; i <= 9; i++)
		{
			if (tabCount(i) > 0)
			{
				count = i;
			}
		}
		return count;
	}

	int tabCount(int tabIndex)
	{
		if (tabIndex < 1 || tabIndex > TAB_COUNT_VARBITS.length)
		{
			return 0;
		}
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
			client.getVarbitValue(TAB_COUNT_VARBITS[tabIndex - 1])).orElse(0);
	}

	int bankRearrangeMode()
	{
		return Microbot.getClientThread().runOnClientThreadOptional(() ->
			client.getVarbitValue(Varbits.BANK_REARRANGE_MODE)).orElse(-1);
	}

	int quantityFor(int itemId)
	{
		Rs2ItemModel item = findBankItem(itemId);
		return item == null ? 0 : item.getQuantity();
	}

	private static Rs2ItemModel findBankItem(int itemId)
	{
		return Rs2Bank.bankItems().stream()
			.filter(item -> item.getId() == itemId)
			.min(Comparator.comparingInt(Rs2ItemModel::getSlot))
			.orElse(null);
	}

	private int newTabTargetDynamicIndex()
	{
		return BANK_TAB_CONTAINER_DYNAMIC_MAIN_INDEX + realTabCount() + 1;
	}

	private static Widget tabWidget(int dynamicIndex)
	{
		List<Widget> tabs = Rs2Bank.getTabs();
		if (dynamicIndex < 0 || dynamicIndex >= tabs.size())
		{
			return null;
		}
		return tabs.get(dynamicIndex);
	}

	private static Rectangle tabBounds(int dynamicIndex)
	{
		Widget tab = tabWidget(dynamicIndex);
		return tab == null ? null : tab.getBounds();
	}

	private static boolean inCanvas(Rectangle rectangle)
	{
		return rectangle != null && Rs2UiHelper.isRectangleWithinCanvas(rectangle);
	}

	private static String joinSteps(List<String> steps)
	{
		return String.join(" ", steps);
	}

	private BankSnapshot safeSnapshot()
	{
		try
		{
			return snapshotReader.read();
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private static Map<Integer, Integer> quantityMap(BankSnapshot snapshot)
	{
		Map<Integer, Integer> quantities = new HashMap<>();
		for (BankSnapshot.BankStack stack : snapshot.items())
		{
			quantities.merge(stack.itemId(), stack.quantity(), Integer::sum);
		}
		return quantities;
	}

	private static Map<Integer, List<BankTagLayoutMoveAction>> layoutActionsByTargetTab(List<BankTagLayoutMoveAction> actions)
	{
		Map<Integer, List<BankTagLayoutMoveAction>> actionsByTab = new HashMap<>();
		for (BankTagLayoutMoveAction action : actions)
		{
			actionsByTab.computeIfAbsent(action.targetTab(), ignored -> new ArrayList<>()).add(action);
		}
		return actionsByTab;
	}

	private static List<BankSnapshot.BankStack> tabStacks(BankSnapshot snapshot, int tabIndex)
	{
		List<BankSnapshot.BankStack> stacks = new ArrayList<>();
		for (BankSnapshot.BankStack stack : snapshot.items())
		{
			if (stack.tab() == tabIndex)
			{
				stacks.add(stack);
			}
		}
		stacks.sort(Comparator.comparingInt(BankSnapshot.BankStack::allItemsIndex));
		return stacks;
	}

	private static List<Integer> desiredPresentItemIds(BankTagLayoutTab tab, List<BankSnapshot.BankStack> tabStacks)
	{
		Set<Integer> present = new HashSet<>();
		for (BankSnapshot.BankStack stack : tabStacks)
		{
			present.add(stack.itemId());
		}

		List<Integer> desired = new ArrayList<>();
		Set<Integer> added = new HashSet<>();
		for (int itemId : tab.orderedItemIds())
		{
			if (present.contains(itemId) && added.add(itemId))
			{
				desired.add(itemId);
			}
		}
		return desired;
	}

	private static int indexOfItemId(List<BankSnapshot.BankStack> stacks, int itemId)
	{
		for (int i = 0; i < stacks.size(); i++)
		{
			if (stacks.get(i).itemId() == itemId)
			{
				return i;
			}
		}
		return -1;
	}

	private static boolean isOrderedPrefix(BankSnapshot snapshot, BankTagLayoutTab tab, int prefixLength)
	{
		List<BankSnapshot.BankStack> stacks = tabStacks(snapshot, tab.tabIndex());
		List<Integer> desired = desiredPresentItemIds(tab, stacks);
		if (desired.size() < prefixLength || stacks.size() < prefixLength)
		{
			return false;
		}
		for (int i = 0; i < prefixLength; i++)
		{
			if (stacks.get(i).itemId() != desired.get(i))
			{
				return false;
			}
		}
		return true;
	}

	private static String verifySnapshotUnchanged(int expectedCount, Map<Integer, Integer> expectedQuantities, BankSnapshot snapshot)
	{
		if (snapshot.stackCount() != expectedCount)
		{
			return "bank stack count changed from " + expectedCount + " to " + snapshot.stackCount() + ".";
		}
		Map<Integer, Integer> actualQuantities = quantityMap(snapshot);
		if (!expectedQuantities.equals(actualQuantities))
		{
			return "item quantities changed.";
		}
		return null;
	}

	static final class ActuatorResult
	{
		private final boolean success;
		private final String message;

		private ActuatorResult(boolean success, String message)
		{
			this.success = success;
			this.message = message;
		}

		static ActuatorResult ok(String message)
		{
			return new ActuatorResult(true, message);
		}

		static ActuatorResult fail(String message)
		{
			return new ActuatorResult(false, message);
		}

		boolean success()
		{
			return success;
		}

		String message()
		{
			return message;
		}
	}

	private static final class SortResult
	{
		private final boolean success;
		private final String message;
		private final int moves;

		private SortResult(boolean success, String message, int moves)
		{
			this.success = success;
			this.message = message;
			this.moves = moves;
		}

		static SortResult ok(String message, int moves)
		{
			return new SortResult(true, message, moves);
		}

		static SortResult fail(String message, int moves)
		{
			return new SortResult(false, message, moves);
		}

		boolean success()
		{
			return success;
		}

		String message()
		{
			return message;
		}

		int moves()
		{
			return moves;
		}
	}

	static final class FullOrganizeResult
	{
		private final boolean success;
		private final String message;
		private final BankSnapshot finalSnapshot;
		private final int createdTabs;
		private final int movedStacks;

		private FullOrganizeResult(boolean success, String message, BankSnapshot finalSnapshot, int createdTabs, int movedStacks)
		{
			this.success = success;
			this.message = message;
			this.finalSnapshot = finalSnapshot;
			this.createdTabs = createdTabs;
			this.movedStacks = movedStacks;
		}

		static FullOrganizeResult ok(String message, BankSnapshot finalSnapshot, int createdTabs, int movedStacks)
		{
			return new FullOrganizeResult(true, message, finalSnapshot, createdTabs, movedStacks);
		}

		static FullOrganizeResult fail(String message, BankSnapshot finalSnapshot, int createdTabs, int movedStacks)
		{
			return new FullOrganizeResult(false, message, finalSnapshot, createdTabs, movedStacks);
		}

		boolean success()
		{
			return success;
		}

		String message()
		{
			return message;
		}

		BankSnapshot finalSnapshot()
		{
			return finalSnapshot;
		}

		int createdTabs()
		{
			return createdTabs;
		}

		int movedStacks()
		{
			return movedStacks;
		}
	}
}
