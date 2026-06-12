/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

/**
 * Top-level trip states. The intra-fight reactions (acid / spawn / firebomb / prayer / eat)
 * are <i>not</i> states — they are evaluated every tick inside {@link VorkathState#FIGHT}
 * in threat-priority order. Keeping them out of the enum is deliberate: Vorkath can overlap
 * a spawn with normal attacks, so the fight is a per-tick priority sweep, not a sequence.
 */
public enum VorkathState
{
	/** Pre-flight: verify DS2 done, levels, and that the kit is obtainable. */
	VALIDATE,
	/** Travel to a bank and top the kit back up. */
	BANKING,
	/** Leave the bank, walk to Ungael and enter the instanced crater. */
	TRAVEL_TO_BOSS,
	/** Inside the arena: wake a dormant Vorkath, then run the fight loop. */
	FIGHT,
	/** Vorkath is dead — grab whitelisted drops, then decide bank-vs-next-kill. */
	LOOT,
	/** Consumables exhausted or watchdog tripped — teleport out and stop. */
	EMERGENCY,
	/** Terminal: nothing more to do (target reached, out of supplies, or died). */
	STOPPED
}
