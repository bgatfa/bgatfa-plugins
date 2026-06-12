/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

/** The active special Vorkath is doing this tick — surfaced on the overlay. */
public enum VorkathFightPhase
{
	IDLE("Idle"),
	NORMAL("Normal"),
	ACID("Acid walk"),
	SPAWN("Zombified spawn"),
	FIREBOMB("Firebomb dodge");

	private final String label;

	VorkathFightPhase(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}
}
