/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

/**
 * How venom from Vorkath's venomous dragonfire is handled. The OSRS wiki notes dragonfire
 * protection blocks the damage but <i>not</i> the venom infliction, so one of these is required.
 */
public enum VenomProtection
{
	/** Wear a Serpentine/Toxic/Tanzanite helm — full venom immunity, no inventory action. */
	SERPENTINE_HELM,
	/** Carry anti-venom+ and sip it when venom becomes active. */
	ANTI_VENOM
}
