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

import lombok.Data;

/**
 * A price alert on a GE item. Serialized to/from config as JSON, so it is a
 * plain mutable bean with a no-arg constructor.
 */
@Data
public class PriceAlert
{
	private int itemId;
	private String itemName;
	/** true = fire when price rises to/above threshold (sell), false = at/below (buy). */
	private boolean above;
	private long threshold;
	private boolean enabled = true;
	/** true once fired; cleared again only when the price crosses back (see repeatAlerts). */
	private boolean triggered;

	public PriceAlert()
	{
	}

	public PriceAlert(int itemId, String itemName, boolean above, long threshold)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.above = above;
		this.threshold = threshold;
	}

	public String describe()
	{
		return (above ? "Sell above " : "Buy below ") + BankValueFormat.gp(threshold);
	}

	/** Whether the current price satisfies this alert's condition. */
	public boolean isMet(long price)
	{
		return above ? price >= threshold : price <= threshold;
	}
}
