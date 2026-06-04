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

/** Abbreviated gp value formatting (1.55B / 38M / 312K / 9,200) for longs. */
final class BankValueFormat
{
	static String gp(long v)
	{
		boolean neg = v < 0;
		long a = Math.abs(v);
		String s;
		if (a >= 1_000_000_000L)
		{
			s = trim(a / 1e9) + "B";
		}
		else if (a >= 1_000_000L)
		{
			s = trim(a / 1e6) + "M";
		}
		else if (a >= 100_000L)
		{
			s = (a / 1000) + "K";
		}
		else
		{
			s = String.format("%,d", a);
		}
		return neg ? "-" + s : s;
	}

	/** Quantity: commas under 100k, else abbreviated. */
	static String qty(long v)
	{
		if (v >= 10_000_000L)
		{
			return trim(v / 1e6) + "M";
		}
		if (v >= 100_000L)
		{
			return (v / 1000) + "K";
		}
		return String.format("%,d", v);
	}

	/** Parse a user-entered amount supporting k/m/b suffixes, e.g. "1.5m", "40k", "1b", "1,600,000". */
	static long parseGp(String input)
	{
		if (input == null)
		{
			return -1;
		}
		String s = input.trim().toLowerCase().replace(",", "").replace(" ", "").replace("gp", "");
		if (s.isEmpty())
		{
			return -1;
		}
		double mult = 1;
		char last = s.charAt(s.length() - 1);
		if (last == 'k')
		{
			mult = 1_000;
		}
		else if (last == 'm')
		{
			mult = 1_000_000;
		}
		else if (last == 'b')
		{
			mult = 1_000_000_000;
		}
		if (mult != 1)
		{
			s = s.substring(0, s.length() - 1);
		}
		try
		{
			return (long) (Double.parseDouble(s) * mult);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static String trim(double d)
	{
		String s = String.format("%.2f", d);
		if (s.endsWith("0"))
		{
			s = s.substring(0, s.length() - 1);
		}
		if (s.endsWith("0"))
		{
			s = s.substring(0, s.length() - 1);
		}
		if (s.endsWith("."))
		{
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	private BankValueFormat()
	{
	}
}
