/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.bankorganizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class BankTagLayoutPlanner
{
	List<BankTagLayoutConflict> conflicts(List<BankTagLayoutTab> tabs)
	{
		Map<Integer, List<Integer>> tabIndexesByItemId = new TreeMap<>();
		if (tabs == null)
		{
			return Collections.emptyList();
		}

		for (BankTagLayoutTab tab : tabs)
		{
			for (int itemId : tab.uniqueItemIds())
			{
				tabIndexesByItemId.computeIfAbsent(itemId, ignored -> new ArrayList<>()).add(tab.tabIndex());
			}
		}

		List<BankTagLayoutConflict> conflicts = new ArrayList<>();
		for (Map.Entry<Integer, List<Integer>> entry : tabIndexesByItemId.entrySet())
		{
			if (entry.getValue().size() > 1)
			{
				conflicts.add(new BankTagLayoutConflict(entry.getKey(), entry.getValue()));
			}
		}
		return conflicts;
	}

	BankTagLayoutPlan plan(BankSnapshot snapshot, List<BankTagLayoutTab> tabs)
	{
		if (tabs == null || tabs.isEmpty())
		{
			throw new IllegalArgumentException("No bank tag layouts are configured.");
		}

		Map<Integer, Target> targetByItemId = new HashMap<>();
		Set<Integer> activeTabIndexes = new HashSet<>();
		for (BankTagLayoutTab tab : tabs)
		{
			activeTabIndexes.add(tab.tabIndex());
			List<Integer> ids = tab.orderedItemIds();
			for (int slot = 0; slot < ids.size(); slot++)
			{
				targetByItemId.putIfAbsent(ids.get(slot), new Target(tab, slot));
			}
		}

		List<BankTagLayoutMoveAction> actions = new ArrayList<>();
		int matched = 0;
		int unlisted = 0;
		int unlistedActiveTabbed = 0;
		for (BankSnapshot.BankStack stack : snapshot.items())
		{
			Target target = targetByItemId.get(stack.itemId());
			if (target == null)
			{
				unlisted++;
				if (activeTabIndexes.contains(stack.tab()))
				{
					unlistedActiveTabbed++;
					actions.add(new BankTagLayoutMoveAction(
						stack.itemId(),
						stack.name(),
						stack.quantity(),
						"Main",
						stack.tab(),
						0,
						-1));
				}
				continue;
			}
			matched++;
			if (stack.tab() == target.tab.tabIndex())
			{
				continue;
			}
			actions.add(new BankTagLayoutMoveAction(
				stack.itemId(),
				stack.name(),
				stack.quantity(),
				target.tab.name(),
				stack.tab(),
				target.tab.tabIndex(),
				target.slot));
		}

		return new BankTagLayoutPlan(tabs, actions, matched, unlisted, unlistedActiveTabbed);
	}

	private static final class Target
	{
		private final BankTagLayoutTab tab;
		private final int slot;

		private Target(BankTagLayoutTab tab, int slot)
		{
			this.tab = tab;
			this.slot = slot;
		}
	}
}
