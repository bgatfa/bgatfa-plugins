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

import lombok.Value;

/** Immutable snapshot of a single bank slot with resolved prices. */
@Value
public class BankItem
{
	int id;
	String name;
	int quantity;
	int gePrice;  // GE price per item
	int haPrice;  // high-alch value per item
	boolean members;

	public long geValue()
	{
		return (long) gePrice * quantity;
	}

	public long haValue()
	{
		return (long) haPrice * quantity;
	}
}
