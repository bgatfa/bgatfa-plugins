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
package net.runelite.client.plugins.bankvaluer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Adds a button next to the bank's close button that reorders the real bank item icons by value —
 * highest to lowest, left-to-right, top-to-bottom. Left-click cycles through three modes, and the
 * icon reflects the current one: a bank icon (normal order), a coins icon (sort by GE value), and a
 * high-alchemy icon (sort by HA value). Because we only move the existing widgets, withdraw and
 * right-click menus keep working natively.
 *
 * <p>The bank rebuilds its item layout on every scroll/deposit/search/tab change, so the sort is
 * re-applied each time {@code BANKMAIN_FINISHBUILDING} fires. The Bank mode re-runs the bank's own
 * build (native order). If a Bank Tags custom layout is active on the open tab, value-sort overrides
 * it while enabled.
 */
@Singleton
class BankValueSort
{
	/** Real bank item slots are this tall; tab separators differ, which lets us skip them. */
	private static final int BANK_ITEM_SLOT_HEIGHT = 32;

	private enum Sort
	{
		NONE, GE, HA
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private BankValueTrackerConfig config;

	private Sort sort = Sort.NONE;
	/** Our injected button; may be stale after a bank reload, so we re-validate it's still attached. */
	private Widget button;

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING)
		{
			return;
		}

		ensureButton();
		if (sort != Sort.NONE)
		{
			applySort();
		}
	}

	/**
	 * Re-apply the current mode to an already-open bank after a relevant config toggle (e.g. "hide
	 * untradeable" or the minimum-value threshold). We just re-run the bank build; the
	 * {@code BANKMAIN_FINISHBUILDING} handler then reconciles the button's visibility and re-applies
	 * the active sort with the new settings. No-ops when the bank is closed (rebuildBank null-guards).
	 */
	void refresh()
	{
		clientThread.invoke(this::rebuildBank);
	}

	/** Hide our button, restore the native bank order, and forget the sort (on plugin shutdown). */
	void reset()
	{
		sort = Sort.NONE;
		clientThread.invoke(() ->
		{
			hideButton();
			rebuildBank();
		});
	}

	/** Left-click cycles Bank -> GE -> HA -> Bank. */
	private void cycle()
	{
		sort = next(sort);
		// Re-run the bank's own build: when sorting, our post-fired handler re-applies it;
		// in Bank mode the native order stands.
		rebuildBank();
	}

	private static Sort next(Sort s)
	{
		switch (s)
		{
			case NONE:
				return Sort.GE;
			case GE:
				return Sort.HA;
			default:
				return Sort.NONE;
		}
	}

	/** Re-run the bank item layout via the script the client itself uses to rebuild it. */
	private void rebuildBank()
	{
		final Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null)
		{
			return;
		}
		final Object[] args = items.getOnInvTransmitListener();
		if (args != null)
		{
			client.runScript(args);
		}
	}

	private void ensureButton()
	{
		// Anchor to the bank's top-right close (X) button so we sit just to its left and follow
		// it regardless of bank width / resizing.
		final Widget close = findCloseButton();
		if (close == null || close.getParent() == null)
		{
			return;
		}
		final Widget parent = close.getParent();
		final int gap = 4;

		if (!isAttached(button, parent))
		{
			// Our field is stale after a hot-reload or a disable/enable, so re-find a button we
			// created earlier (by its action text) instead of stacking a duplicate.
			button = findOurButton(parent);
		}
		if (!isAttached(button, parent))
		{
			button = parent.createChild(-1, WidgetType.GRAPHIC);
		}

		// Configure idempotently. This also normalises a button left by an older build — clearing
		// a leftover item graphic and re-binding the click to this plugin instance.
		button.setName(""); // empty -> the menu shows only the action, no target text
		button.setItemId(-1); // drop any legacy item icon so ONLY the sprite renders
		button.setSpriteTiling(false); // scale the sprite to the button bounds
		button.setHasListener(true);
		button.setOnOpListener((JavaScriptCallback) e -> cycle());
		button.setHidden(false);
		// Icon + action reflect the current mode; clicking advances to the next.
		button.setSpriteId(spriteFor(sort));
		button.setAction(1, nextActionLabel(sort));
		button.setAction(2, null); // clear extra actions from the old 3-option version
		button.setAction(3, null);
		// Match the X's size and anchoring so we line up with it.
		button.setOriginalWidth(close.getOriginalWidth());
		button.setOriginalHeight(close.getOriginalHeight());
		button.setXPositionMode(close.getXPositionMode());
		button.setYPositionMode(close.getYPositionMode());
		button.setOriginalY(close.getOriginalY());
		if (close.getXPositionMode() == WidgetPositionMode.ABSOLUTE_RIGHT)
		{
			// OriginalX is distance from the right edge, so a larger value sits further left.
			button.setOriginalX(close.getOriginalX() + close.getOriginalWidth() + gap);
		}
		else
		{
			button.setOriginalX(close.getOriginalX() - close.getOriginalWidth() - gap);
		}
		button.revalidate();

		// Collapse any duplicate buttons left by older builds so only one icon shows.
		final Widget[] children = parent.getChildren();
		if (children != null)
		{
			for (Widget c : children)
			{
				if (c != null && c != button && isOurs(c))
				{
					c.setHidden(true);
					c.revalidate();
				}
			}
		}
	}

	/** A button we created previously, identified by its action text (survives hot-reloads). */
	private Widget findOurButton(Widget parent)
	{
		final Widget[] children = parent.getChildren();
		if (children == null)
		{
			return null;
		}
		for (Widget c : children)
		{
			if (c != null && isOurs(c))
			{
				return c;
			}
		}
		return null;
	}

	private static boolean isOurs(Widget c)
	{
		final String[] actions = c.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if (a != null && (a.equals("Restore order") || a.startsWith("Sort by")))
			{
				return true;
			}
		}
		return false;
	}

	private static int spriteFor(Sort s)
	{
		switch (s)
		{
			case GE:
				return SpriteID.ICON_COINS;
			case HA:
				return SpriteID.Magicon.HIGH_LEVEL_ALCHEMY;
			default:
				return SpriteID.Mapfunction.BANK;
		}
	}

	private static String nextActionLabel(Sort s)
	{
		switch (s)
		{
			case NONE:
				return "Sort by GE value";
			case GE:
				return "Sort by HA value";
			default:
				return "Restore order";
		}
	}

	private void hideButton()
	{
		final Widget close = findCloseButton();
		if (close == null || close.getParent() == null)
		{
			return;
		}
		final Widget[] children = close.getParent().getChildren();
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			if (c != null && isOurs(c))
			{
				c.setHidden(true);
				c.revalidate();
			}
		}
	}

	/** True if {@code w} is still a live child of {@code parent} (false after a bank reload). */
	private boolean isAttached(Widget w, Widget parent)
	{
		if (w == null)
		{
			return false;
		}
		final Widget[] children = parent.getChildren();
		if (children == null)
		{
			return false;
		}
		for (Widget c : children)
		{
			if (c == w)
			{
				return true;
			}
		}
		return false;
	}

	/** Locate the bank window's close (X) button by its "Close" action, scanning the frame subtree. */
	private Widget findCloseButton()
	{
		Widget found = searchClose(client.getWidget(InterfaceID.Bankmain.FRAME));
		if (found == null)
		{
			found = searchClose(client.getWidget(InterfaceID.Bankmain.UNIVERSE));
		}
		return found;
	}

	private Widget searchClose(Widget w)
	{
		if (w == null || w == button)
		{
			return null; // null guard / never match our own button
		}
		final String[] actions = w.getActions();
		if (actions != null)
		{
			for (String a : actions)
			{
				if ("Close".equalsIgnoreCase(a))
				{
					return w;
				}
			}
		}
		for (Widget[] set : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (set == null)
			{
				continue;
			}
			for (Widget c : set)
			{
				final Widget r = searchClose(c);
				if (r != null)
				{
					return r;
				}
			}
		}
		return null;
	}

	/**
	 * Reorder the items by value. The bank lays out item widgets by their child index (a fixed
	 * grid), so we don't move widgets — instead we read each occupied slot's display, sort those by
	 * value, and write them back so slot 0 shows the most valuable item, etc. The withdraw op still
	 * carries each widget's original slot index, so {@link #onMenuOptionClicked} remaps it to the
	 * displayed item's real bank index to keep withdraws correct.
	 */
	private void applySort()
	{
		final Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null)
		{
			return;
		}
		final Widget[] children = items.getChildren();
		if (children == null)
		{
			return;
		}

		// "slots" = every visual item position we may rewrite (real items AND placeholders).
		// "realItems" = the real items only, which we pack into those positions by value.
		final List<Widget> slots = new ArrayList<>();
		final List<Display> realItems = new ArrayList<>();
		for (Widget c : children)
		{
			if (c == null || c.isSelfHidden())
			{
				continue;
			}
			final int id = c.getItemId();
			if (id <= 0 || id == ItemID.BLANKOBJECT || id == ItemID.BANK_FILLER)
			{
				continue;
			}
			if (c.getOriginalHeight() != BANK_ITEM_SLOT_HEIGHT)
			{
				continue; // tab separators and other non-item children
			}
			slots.add(c);
			final ItemComposition comp = itemManager.getItemComposition(id);
			if (comp.getPlaceholderTemplateId() == -1 && !isHidden(id, c.getItemQuantity(), comp))
			{
				realItems.add(Display.capture(c)); // a real, shown item (not a placeholder/hidden)
			}
		}

		if (slots.isEmpty() || realItems.isEmpty())
		{
			return;
		}
		// Nothing to do for a single item with no leftover slots to blank; but if "hide untradeable"
		// left extra slots (slots > realItems), fall through so those get blanked even below 2 items.
		if (realItems.size() < 2 && realItems.size() == slots.size())
		{
			return;
		}

		// Most valuable first.
		realItems.sort((a, b) -> Long.compare(value(b), value(a)));

		// The children array is NOT in on-screen order, so order the target slots by visual reading
		// position (top-to-bottom, left-to-right), pack the real items in by value, and blank any
		// leftover slots (e.g. placeholder positions) so only real items show.
		final Integer[] order = new Integer[slots.size()];
		for (int i = 0; i < order.length; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, (a, b) ->
		{
			final Widget wa = slots.get(a);
			final Widget wb = slots.get(b);
			if (wa.getOriginalY() != wb.getOriginalY())
			{
				return Integer.compare(wa.getOriginalY(), wb.getOriginalY());
			}
			return Integer.compare(wa.getOriginalX(), wb.getOriginalX());
		});

		for (int k = 0; k < order.length; k++)
		{
			final Widget w = slots.get(order[k]);
			if (k < realItems.size())
			{
				realItems.get(k).applyTo(w);
			}
			else
			{
				blank(w);
			}
			w.revalidate();
		}
	}

	/** Clear a slot so it renders empty — used for leftover placeholder positions while sorting. */
	private static void blank(Widget w)
	{
		w.setItemId(-1);
		w.setItemQuantity(0);
		w.setName("");
		w.clearActions();
		w.setOpacity(0);
	}

	/** Point a reordered widget's withdraw back at the displayed item's real bank slot. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (sort == Sort.NONE || event.getParam1() != InterfaceID.Bankmain.ITEMS)
		{
			return;
		}
		final MenuEntry menu = event.getMenuEntry();
		final Widget w = menu.getWidget();
		if (w == null || w.getItemId() <= 0)
		{
			return;
		}
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			return;
		}
		final int idx = bank.find(w.getItemId());
		if (idx > -1 && menu.getParam0() != idx)
		{
			menu.setParam0(idx);
		}
	}

	/**
	 * Mirror the side panel's filters (see BankValueTrackerPanel#isExcluded and the minValue check) so
	 * the bank sort hides the same items, with their slots blanked rather than packed:
	 * <ul>
	 *   <li>"Hide under gp (GE)" / "(HA)" — per-view threshold: the GE sort drops items whose total GE
	 *       value (price × qty) is below the GE minimum, the HA sort drops those below the HA minimum.
	 *       Only called while sorting, so {@code sort} is GE or HA here.</li>
	 *   <li>"Hide untradeable & worthless" — when on, drop untradeables and items worth nothing on
	 *       both GE and HA. Tradeability is judged by {@code isTradeable()} alone: {@code getItemPrice()}
	 *       is NOT a tradeability signal — it synthesizes a non-zero value for untradeable combination
	 *       items (e.g. a slayer helmet ~1.2M from its components, marks of grace ~7.6k), so OR-ing it
	 *       in would leak those untradeables past this filter.</li>
	 * </ul>
	 */
	private boolean isHidden(int id, int quantity, ItemComposition comp)
	{
		final int canonical = itemManager.canonicalize(id);
		final int ge = itemManager.getItemPrice(canonical);
		final long qty = Math.max(1, quantity);
		final boolean underThreshold = sort == Sort.HA
			? (long) comp.getHaPrice() * qty < config.minValueHa()
			: ge * qty < config.minValue();
		if (underThreshold)
		{
			return true;
		}
		if (config.hideUntradeable())
		{
			return !comp.isTradeable() || (ge == 0 && comp.getHaPrice() == 0);
		}
		return false;
	}

	private long value(Display d)
	{
		final int id = itemManager.canonicalize(d.itemId);
		final long qty = Math.max(1, d.quantity);
		if (sort == Sort.HA)
		{
			return (long) itemManager.getItemComposition(id).getHaPrice() * qty;
		}
		return (long) itemManager.getItemPrice(id) * qty;
	}

	/**
	 * A snapshot of a bank slot widget's item display, moved as a unit when reordering. Carries the
	 * withdraw actions too, so a real item dropped into a former placeholder slot still withdraws.
	 */
	private static final class Display
	{
		private final int itemId;
		private final int quantity;
		private final int quantityMode;
		private final int opacity;
		private final String name;
		private final String[] actions;

		private Display(int itemId, int quantity, int quantityMode, int opacity, String name, String[] actions)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.quantityMode = quantityMode;
			this.opacity = opacity;
			this.name = name;
			this.actions = actions;
		}

		static Display capture(Widget w)
		{
			final String[] a = w.getActions();
			return new Display(w.getItemId(), w.getItemQuantity(), w.getItemQuantityMode(), w.getOpacity(), w.getName(),
				a == null ? null : a.clone());
		}

		void applyTo(Widget w)
		{
			w.setItemId(itemId);
			w.setItemQuantity(quantity);
			w.setItemQuantityMode(quantityMode);
			w.setOpacity(opacity);
			w.setName(name);
			w.clearActions();
			if (actions != null)
			{
				for (int i = 0; i < actions.length; i++)
				{
					if (actions[i] != null && !actions[i].isEmpty())
					{
						w.setAction(i, actions[i]);
					}
				}
			}
		}
	}
}
