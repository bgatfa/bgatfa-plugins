/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

import javax.annotation.Nullable;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;

/** Which damage-boosting prayer to keep on alongside Protect from Missiles. */
public enum OffensivePrayer
{
	RIGOUR(Rs2PrayerEnum.RIGOUR),
	EAGLE_EYE(Rs2PrayerEnum.EAGLE_EYE),
	NONE(null);

	@Nullable
	private final Rs2PrayerEnum prayer;

	OffensivePrayer(@Nullable Rs2PrayerEnum prayer)
	{
		this.prayer = prayer;
	}

	/** The backing prayer, or {@code null} for {@link #NONE}. */
	@Nullable
	public Rs2PrayerEnum prayer()
	{
		return prayer;
	}
}
