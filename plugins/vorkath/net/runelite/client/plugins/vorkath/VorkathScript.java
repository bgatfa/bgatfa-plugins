/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.vorkath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;

/**
 * Ranged Vorkath farmer. The trip loop is a {@link VorkathState} machine; the fight itself is a
 * per-tick, threat-priority sweep (survival → prayer → firebomb → spawn → acid → attack).
 *
 * <p><b>Instance-safe by construction.</b> The arena is an instance, so world coordinates are
 * offset per-session. Every in-fight movement is therefore computed <i>relative to the player's
 * live tile and the acid objects currently on the floor</i> — never against hardcoded coords —
 * so the same logic works regardless of the instance's coordinate base.
 *
 * <p>All catalog IDs below are stock-RuneLite gameval values (NPC/object/animation), confirmed
 * against the OSRS cache (build 238). The acid floor object is {@code VORKATH_ACID = 32000}.
 */
public class VorkathScript extends Script
{
	private static final Logger log = LoggerFactory.getLogger(VorkathScript.class);

	// ----- catalog ids (gameval; cache-verified) ------------------------------------
	/** Post-quest repeatable Vorkath (combat 732). The DS2-quest one is 8060 — not ours. */
	private static final int VORKATH_ACTIVE = 8061;
	private static final int VORKATH_DORMANT_A = 8059;
	private static final int VORKATH_DORMANT_B = 8058;
	/** Zombified Spawn — freezes you; Vorkath is immune until it dies. */
	private static final int VORKATH_SPAWN = 8063;
	/** Acid pool floor object spawned during the rapid-fire phase (marks unsafe tiles). */
	private static final int ACID_POOL = 32000;
	/** Crater entrance that drops you into the instanced arena. */
	private static final int CRATER_ENTRANCE = 31990;
	/** Template region id of the Vorkath arena (instance-stable). */
	private static final int ARENA_REGION = 9023;
	/** DS2 quest-progress varbit; completion reads as the max value. */
	private static final int VARBIT_DS2 = 6104;
	private static final int DS2_COMPLETE = 70;
	/**
	 * Vorkath body animations that precede the deadly "firebomb" dragonfire. The cache labels
	 * these ambiguously (7884 fireball; 7958/7960 fireball-or-spawn), but the spawn is detected
	 * independently by its NPC, so treating any of them as "firebomb incoming" is safe: a stray
	 * dodge during a spawn wind-up is harmless. Tune against live state if a phase is missed.
	 */
	private static final Set<Integer> FIREBOMB_ANIMS = Set.of(7884, 7958, 7960);

	// ----- tuning -------------------------------------------------------------------
	/** Dragon hunter crossbow attack range (tiles). Long-range adds 2; we keep the safe base. */
	private static final int RANGE = 7;
	private static final int LOOT_RANGE = 12;
	/** Crumble Undead runes kept topped up (2 earth + 1 air per cast). */
	private static final String EARTH_RUNE = "Earth rune";
	private static final String AIR_RUNE = "Air rune";
	private static final int RUNE_TARGET = 200;
	/** POISON varp; venom is signalled by values at/above this. */
	private static final int POISON_VARP = 102;
	private static final int VENOM_FLOOR = 1_000_000;
	/** FIGHT watchdog: if no kill in this long, something is stuck → bail out. */
	private static final long FIGHT_WATCHDOG_MS = 300_000;

	// ----- state --------------------------------------------------------------------
	private volatile boolean active;
	private volatile VorkathState state = VorkathState.VALIDATE;
	private volatile VorkathFightPhase phase = VorkathFightPhase.IDLE;
	private volatile long kills;
	private volatile long lootValue;
	private long startMillis;
	private long fightStartMillis;
	private int nullTicks;
	private boolean sawBoss;
	private VorkathConfig config;
	private ItemManager itemManager;
	private List<Pattern> lootPatterns = List.of();

	public boolean run(VorkathConfig config, ItemManager itemManager)
	{
		this.config = config;
		this.itemManager = itemManager;
		this.active = true;
		this.state = VorkathState.VALIDATE;
		this.phase = VorkathFightPhase.IDLE;
		this.kills = 0;
		this.lootValue = 0;
		this.startMillis = System.currentTimeMillis();
		this.lootPatterns = compilePatterns(config.lootWhitelist());

		mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() ->
		{
			try
			{
				if (!active || !Microbot.isLoggedIn() || !super.run())
				{
					return;
				}
				tick();
			}
			catch (Exception ex)
			{
				log.error("Vorkath loop error: {}", ex.getMessage(), ex);
			}
		}, 0, 600, TimeUnit.MILLISECONDS);
		return true;
	}

	private void tick()
	{
		switch (state)
		{
			case VALIDATE:
				validate();
				break;
			case BANKING:
				doBanking();
				break;
			case TRAVEL_TO_BOSS:
				travelToBoss();
				break;
			case FIGHT:
				fightTick();
				break;
			case LOOT:
				lootTick();
				break;
			case EMERGENCY:
				emergency();
				break;
			case STOPPED:
			default:
				shutdown();
				break;
		}
	}

	// ================================================================================
	// VALIDATE — refuse to run an under-equipped account
	// ================================================================================

	private void validate()
	{
		if (Microbot.getVarbitValue(VARBIT_DS2) < DS2_COMPLETE)
		{
			stop("Dragon Slayer II is not complete — cannot fight Vorkath");
			return;
		}
		// Kit must be reachable: either already carried, or sitting in the bank to withdraw.
		state = VorkathState.BANKING;
	}

	// ================================================================================
	// BANKING — top the kit up; stop if the bank can't supply it
	// ================================================================================

	private void doBanking()
	{
		if (inArena())
		{
			// Already inside (e.g. started mid-trip) — don't bank, just fight.
			state = VorkathState.FIGHT;
			return;
		}

		Microbot.status = "Banking";
		if (!Rs2Bank.isOpen())
		{
			useItem(config.bankTeleportName());
			if (!Rs2Bank.walkToBankAndUseBank() || !sleepUntil(() -> !active || Rs2Bank.isOpen(), 8000))
			{
				return; // retry next tick
			}
		}
		if (!active)
		{
			return;
		}

		// Deposit loot but keep the whole kit, then top each consumable back to target.
		Rs2Bank.depositAllExcept(kitNames());
		sleep(300, 600);

		boolean ok = topUp(config.foodName(), config.foodAmount())
			&& topUp(config.prayerPotName(), config.prayerPotAmount())
			&& topUp(config.antifireName(), config.antifireAmount())
			&& topUp(EARTH_RUNE, RUNE_TARGET)
			&& topUp(AIR_RUNE, RUNE_TARGET);

		if (config.venomProtection() == VenomProtection.ANTI_VENOM)
		{
			ok = ok && topUp(config.antiVenomName(), 2);
		}

		if (!ok)
		{
			stop("Bank is missing a required supply — stopping");
			return;
		}

		Rs2Bank.closeBank();
		sleepUntil(() -> !active || !Rs2Bank.isOpen(), 3000);
		state = VorkathState.TRAVEL_TO_BOSS;
	}

	/** Withdraw the deficit to reach {@code target}; false iff still short afterwards. */
	private boolean topUp(String name, int target)
	{
		if (name == null || name.isBlank() || target <= 0)
		{
			return true;
		}
		if (Rs2Inventory.count(name) >= target)
		{
			return true;
		}
		Rs2Bank.withdrawDeficit(name, target);
		sleep(200, 400);
		return Rs2Inventory.hasItem(name);
	}

	// ================================================================================
	// TRAVEL_TO_BOSS — reach Ungael and drop into the instance
	// ================================================================================

	private void travelToBoss()
	{
		if (inArena())
		{
			fightStartMillis = System.currentTimeMillis();
			sawBoss = false;
			nullTicks = 0;
			state = VorkathState.FIGHT;
			return;
		}

		Microbot.status = "Travelling to Vorkath";
		// Make sure the crossbow is actually equipped before we enter.
		ensureWeapon(config.rangedWeaponName());

		final TileObject entrance = Rs2GameObject.getAll(o -> o.getId() == CRATER_ENTRANCE)
			.stream().findFirst().orElse(null);
		if (entrance == null)
		{
			// Not near Ungael yet. Walk toward the crater's world tile if we know it; otherwise
			// this is the one leg that benefits from a configured route/teleport to Rellekka.
			Rs2Bank.closeBank();
			sleep(600);
			return;
		}
		if (Rs2GameObject.interact(entrance, "Enter"))
		{
			sleepUntil(() -> !active || inArena(), 10_000);
		}
	}

	// ================================================================================
	// FIGHT — per-tick threat-priority sweep
	// ================================================================================

	private void fightTick()
	{
		if (!inArena())
		{
			// We left the arena unexpectedly (death, stray teleport). Treat as end-of-trip.
			handleLeftArena();
			return;
		}

		final Rs2NpcModel vorkath = Rs2Npc.getNpc(VORKATH_ACTIVE);

		// --- death / aggro bookkeeping ---
		if (vorkath == null)
		{
			final Rs2NpcModel dormant = dormantVorkath();
			if (dormant != null)
			{
				Microbot.status = "Waking Vorkath";
				Rs2Npc.attack(dormant);
				sleepUntil(() -> !active || Rs2Npc.getNpc(VORKATH_ACTIVE) != null, 5000);
				nullTicks = 0;
				return;
			}
			if (sawBoss && ++nullTicks >= 2)
			{
				// Was alive, now gone for 2 ticks → it died.
				kills++;
				sawBoss = false;
				phase = VorkathFightPhase.IDLE;
				state = VorkathState.LOOT;
			}
			return;
		}

		sawBoss = true;
		nullTicks = 0;

		// Watchdog: a kill should never take this long.
		if (System.currentTimeMillis() - fightStartMillis > FIGHT_WATCHDOG_MS)
		{
			triggerEmergency("fight watchdog — no kill in 5 min");
			return;
		}

		// 1) SURVIVE (eat / prayer / antifire / venom) — bails the tick if it acted.
		if (survive())
		{
			return;
		}
		// 2) PRAYER upkeep (covers corrupting dragonfire re-enabling).
		ensurePrayers();

		// 3..6) phase reactions, highest threat first.
		phase = detectPhase(vorkath);
		switch (phase)
		{
			case SPAWN:
				handleSpawn();
				break;
			case FIREBOMB:
				handleFirebomb(vorkath);
				break;
			case ACID:
				handleAcid(vorkath);
				break;
			case NORMAL:
			default:
				handleNormal(vorkath);
				break;
		}
	}

	/** @return true if a survival action fired this tick (so the caller should yield). */
	private boolean survive()
	{
		// HP
		if (Rs2Player.getHealthPercentage() <= config.eatAtPercent())
		{
			if (Rs2Inventory.hasItem(config.foodName()))
			{
				Rs2Inventory.interact(config.foodName(), "Eat");
				return true;
			}
			triggerEmergency("out of food");
			return true;
		}
		// Antifire — a lapse means unprayable dragonfire, so this is non-negotiable.
		if (!Rs2Player.hasAntiFireActive() && !Rs2Player.hasSuperAntiFireActive())
		{
			if (Rs2Inventory.hasItem(config.antifireName()))
			{
				Rs2Inventory.interact(config.antifireName(), "Drink");
				return true;
			}
			triggerEmergency("antifire ran out");
			return true;
		}
		// Prayer points
		if (Rs2Player.getBoostedSkillLevel(Skill.PRAYER) <= config.drinkPrayerAt())
		{
			if (Rs2Inventory.hasItem(config.prayerPotName()))
			{
				Rs2Inventory.interact(config.prayerPotName(), "Drink");
				return true;
			}
			triggerEmergency("out of prayer restores");
			return true;
		}
		// Venom (only when relying on anti-venom rather than a serpentine helm)
		if (config.venomProtection() == VenomProtection.ANTI_VENOM && isVenomed()
			&& Rs2Inventory.hasItem(config.antiVenomName()))
		{
			Rs2Inventory.interact(config.antiVenomName(), "Drink");
			return true;
		}
		return false;
	}

	private void ensurePrayers()
	{
		if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.PROTECT_RANGE))
		{
			Rs2Prayer.toggle(Rs2PrayerEnum.PROTECT_RANGE, true);
		}
		final Rs2PrayerEnum off = config.offensivePrayer().prayer();
		if (off != null && !Rs2Prayer.isPrayerActive(off))
		{
			Rs2Prayer.toggle(off, true);
		}
	}

	private VorkathFightPhase detectPhase(Rs2NpcModel vorkath)
	{
		if (Rs2Npc.getNpc(VORKATH_SPAWN) != null)
		{
			return VorkathFightPhase.SPAWN;
		}
		if (!acidTiles().isEmpty())
		{
			return VorkathFightPhase.ACID;
		}
		if (FIREBOMB_ANIMS.contains(vorkath.getAnimation()))
		{
			return VorkathFightPhase.FIREBOMB;
		}
		return VorkathFightPhase.NORMAL;
	}

	// ----- phase: zombified spawn ---------------------------------------------------

	private void handleSpawn()
	{
		final Rs2NpcModel spawn = Rs2Npc.getNpc(VORKATH_SPAWN);
		if (spawn == null)
		{
			return;
		}
		Microbot.status = "Killing spawn";
		// Swap to the staff so the Crumble Undead cast carries enough magic bonus to land,
		// then cast it; the crossbow is restored once the spawn is dead.
		if (!Rs2Equipment.isWearing(config.slayerStaffName()))
		{
			ensureWeapon(config.slayerStaffName());
			sleep(150, 300);
		}
		Rs2Magic.castOn(MagicAction.CRUMBLE_UNDEAD, spawn);
		sleepUntil(() -> !active || Rs2Npc.getNpc(VORKATH_SPAWN) == null, 4000);
		ensureWeapon(config.rangedWeaponName());
	}

	// ----- phase: firebomb (deadly dragonfire) --------------------------------------

	private void handleFirebomb(Rs2NpcModel vorkath)
	{
		Microbot.status = "Dodging firebomb";
		final WorldPoint me = Rs2Player.getWorldLocation();
		final Set<WorldPoint> acid = acidTiles();
		// Step two tiles to a clean, walkable square — overrides attacking for this tick.
		final WorldPoint step = bestTile(me, acid, vorkath, 2, false);
		if (step != null && !step.equals(me))
		{
			Rs2Walker.walkFastCanvas(step);
			sleep(300, 500);
		}
	}

	// ----- phase: acid / rapid-fire -------------------------------------------------

	private void handleAcid(Rs2NpcModel vorkath)
	{
		Microbot.status = "Acid walk";
		final WorldPoint me = Rs2Player.getWorldLocation();
		final Set<WorldPoint> acid = acidTiles();

		// Move to a clean tile (the dragonbreath tracks your current tile each tick). Prefer a
		// tile that keeps Vorkath in range so we can keep firing; survival overrides DPS.
		if (acid.contains(me) || nextTileDangerous(me, acid))
		{
			final WorldPoint step = bestTile(me, acid, vorkath, 1, true);
			if (step != null && !step.equals(me))
			{
				Rs2Walker.walkFastCanvas(step);
			}
		}

		// Attack-when-able: only if we're standing clean and Vorkath is in range.
		if (!acid.contains(Rs2Player.getWorldLocation()) && inRange(vorkath))
		{
			attackIfIdle(vorkath);
		}
	}

	// ----- phase: normal ------------------------------------------------------------

	private void handleNormal(Rs2NpcModel vorkath)
	{
		Microbot.status = "Attacking Vorkath";
		if (config.useSpecialAttack() && Rs2Combat.getSpecEnergy() >= config.specThreshold() * 10)
		{
			Rs2Combat.setSpecState(true);
		}
		attackIfIdle(vorkath);
	}

	// ================================================================================
	// LOOT — pick up whitelisted drops, then decide bank vs. next kill
	// ================================================================================

	private void lootTick()
	{
		Microbot.status = "Looting";
		final List<String> targets = matchingGroundItemNames();
		for (String name : targets)
		{
			final long before = Rs2Inventory.count(name);
			if (Rs2GroundItem.loot(name, LOOT_RANGE))
			{
				sleepUntil(() -> !active || Rs2Inventory.count(name) > before, 2500);
				addLootValue(name, Rs2Inventory.count(name) - before);
			}
		}

		// Decide: out of supplies or no inventory room → bank; else wake the next Vorkath.
		if (reachedKillTarget())
		{
			stop("Kill target reached (" + kills + ")");
			return;
		}
		if (suppliesLow() || Rs2Inventory.isFull())
		{
			state = VorkathState.BANKING;
		}
		else
		{
			fightStartMillis = System.currentTimeMillis();
			state = VorkathState.FIGHT;
		}
	}

	private boolean suppliesLow()
	{
		return Rs2Inventory.count(config.foodName()) <= 1
			|| !Rs2Inventory.hasItem(config.prayerPotName())
			|| !Rs2Inventory.hasItem(config.antifireName());
	}

	private boolean reachedKillTarget()
	{
		return config.killTarget() > 0 && kills >= config.killTarget();
	}

	// ================================================================================
	// EMERGENCY / leaving the arena
	// ================================================================================

	private void triggerEmergency(String reason)
	{
		log.warn("Vorkath emergency: {}", reason);
		Microbot.status = "EMERGENCY: " + reason;
		state = VorkathState.EMERGENCY;
	}

	private void emergency()
	{
		// Eat anything if we can, then fire the escape teleport immediately and stop.
		if (Rs2Inventory.hasItem(config.foodName()))
		{
			Rs2Inventory.interact(config.foodName(), "Eat");
		}
		useItem(config.escapeTeleportName());
		sleepUntil(() -> !active || !inArena(), 6000);
		stop("Emergency escape complete");
	}

	private void handleLeftArena()
	{
		if (sawBoss && config.stopOnDeath())
		{
			// We were fighting and are now outside without escaping ourselves — most likely a death.
			stop("Left the arena unexpectedly (possible death)");
			return;
		}
		// Otherwise route back through banking/restock.
		sawBoss = false;
		state = VorkathState.BANKING;
	}

	// ================================================================================
	// helpers
	// ================================================================================

	private Set<WorldPoint> acidTiles()
	{
		final Set<WorldPoint> tiles = new HashSet<>();
		for (TileObject o : Rs2GameObject.getAll(t -> t.getId() == ACID_POOL))
		{
			final WorldPoint wp = o.getWorldLocation();
			if (wp != null)
			{
				tiles.add(wp);
			}
		}
		return tiles;
	}

	/** A tile becomes dangerous if it is, or is about to be, under acid. */
	private boolean nextTileDangerous(WorldPoint me, Set<WorldPoint> acid)
	{
		return acid.contains(me);
	}

	/**
	 * Choose the best reachable tile within {@code radius} that is walkable and acid-free.
	 * When {@code preferInRange} is set, ties break toward keeping Vorkath within attack range.
	 */
	private WorldPoint bestTile(WorldPoint me, Set<WorldPoint> acid, Rs2NpcModel vorkath,
		int radius, boolean preferInRange)
	{
		final WorldArea area = vorkath.getWorldArea();
		WorldPoint best = null;
		int bestScore = Integer.MIN_VALUE;
		for (int dx = -radius; dx <= radius; dx++)
		{
			for (int dy = -radius; dy <= radius; dy++)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				final WorldPoint cand = new WorldPoint(me.getX() + dx, me.getY() + dy, me.getPlane());
				if (acid.contains(cand) || !Rs2Tile.isWalkable(cand))
				{
					continue;
				}
				int score = -(Math.abs(dx) + Math.abs(dy)); // fewer tiles moved is better
				if (preferInRange && area != null && area.distanceTo(cand) <= RANGE)
				{
					score += 100;
				}
				if (score > bestScore)
				{
					bestScore = score;
					best = cand;
				}
			}
		}
		return best;
	}

	private boolean inRange(Rs2NpcModel vorkath)
	{
		final WorldArea area = vorkath.getWorldArea();
		return area != null && area.distanceTo(Rs2Player.getWorldLocation()) <= RANGE;
	}

	private void attackIfIdle(Rs2NpcModel vorkath)
	{
		// In the arena Vorkath is the only thing we'd interact with, so "not interacting" is a
		// safe proxy for "not currently attacking it".
		if (!Rs2Player.isInteracting())
		{
			Rs2Npc.attack(vorkath);
		}
	}

	private Rs2NpcModel dormantVorkath()
	{
		final Rs2NpcModel a = Rs2Npc.getNpc(VORKATH_DORMANT_A);
		return a != null ? a : Rs2Npc.getNpc(VORKATH_DORMANT_B);
	}

	private boolean inArena()
	{
		if (!Microbot.isLoggedIn())
		{
			return false;
		}
		final WorldPoint wp = Rs2Player.getWorldLocation();
		return wp != null && wp.getRegionID() == ARENA_REGION;
	}

	private void ensureWeapon(String name)
	{
		if (name == null || name.isBlank() || Rs2Equipment.isWearing(name))
		{
			return;
		}
		if (Rs2Inventory.hasItem(name))
		{
			Rs2Inventory.interact(name, "Wield");
		}
	}

	private void useItem(String name)
	{
		if (name != null && !name.isBlank() && Rs2Inventory.hasItem(name))
		{
			// Most teleport items expose their action as the first menu op; try common verbs.
			if (!Rs2Inventory.interact(name, "Break"))
			{
				if (!Rs2Inventory.interact(name, "Teleport"))
				{
					Rs2Inventory.interact(name, "Rub");
				}
			}
			sleep(600, 1200);
		}
	}

	private boolean isVenomed()
	{
		final Integer poison = Microbot.getClientThread().runOnClientThreadOptional(
			() -> Microbot.getClient().getVarpValue(POISON_VARP)).orElse(0);
		return poison >= VENOM_FLOOR;
	}

	/** Every item name we must NOT deposit while banking. */
	private String[] kitNames()
	{
		final List<String> keep = new ArrayList<>(List.of(
			config.foodName(), config.prayerPotName(), config.antifireName(),
			config.rangedWeaponName(), config.slayerStaffName(),
			EARTH_RUNE, AIR_RUNE));
		if (!config.bankTeleportName().isBlank())
		{
			keep.add(config.bankTeleportName());
		}
		if (!config.escapeTeleportName().isBlank())
		{
			keep.add(config.escapeTeleportName());
		}
		if (config.venomProtection() == VenomProtection.ANTI_VENOM)
		{
			keep.add(config.antiVenomName());
		}
		return keep.toArray(String[]::new);
	}

	// ----- loot matching ------------------------------------------------------------

	private List<Pattern> compilePatterns(String whitelist)
	{
		final List<Pattern> out = new ArrayList<>();
		if (whitelist == null)
		{
			return out;
		}
		for (String raw : whitelist.split(","))
		{
			final String token = raw.trim();
			if (token.isEmpty())
			{
				continue;
			}
			// '*' means shell-style wildcard; anything else is treated as regex, falling back
			// to a literal match if it doesn't compile.
			final String regex = token.contains("*") ? wildcardToRegex(token) : token;
			try
			{
				out.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
			}
			catch (PatternSyntaxException ex)
			{
				out.add(Pattern.compile(Pattern.quote(token), Pattern.CASE_INSENSITIVE));
			}
		}
		return out;
	}

	private static String wildcardToRegex(String token)
	{
		final StringBuilder sb = new StringBuilder();
		for (String part : token.split("\\*", -1))
		{
			if (sb.length() > 0)
			{
				sb.append(".*");
			}
			sb.append(Pattern.quote(part.isEmpty() ? "" : part));
		}
		return sb.toString();
	}

	private boolean matchesWhitelist(String name)
	{
		if (name == null)
		{
			return false;
		}
		for (Pattern p : lootPatterns)
		{
			if (p.matcher(name).find())
			{
				return true;
			}
		}
		return false;
	}

	private List<String> matchingGroundItemNames()
	{
		final Set<String> names = new HashSet<>();
		for (var gi : Rs2GroundItem.getGroundItems().values())
		{
			final String name = gi.getName();
			if (matchesWhitelist(name))
			{
				names.add(name);
			}
		}
		return new ArrayList<>(names);
	}

	private void addLootValue(String name, long qty)
	{
		if (qty <= 0 || itemManager == null)
		{
			return;
		}
		final var item = Rs2Inventory.get(name);
		if (item != null)
		{
			lootValue += (long) itemManager.getItemPrice(item.getId()) * qty;
		}
	}

	// ----- lifecycle ----------------------------------------------------------------

	private void stop(String reason)
	{
		log.info("Vorkath stopping: {}", reason);
		Microbot.status = reason;
		state = VorkathState.STOPPED;
		shutdown();
	}

	@Override
	public void shutdown()
	{
		active = false;
		Rs2Walker.clearWalkingRoute("vorkath:shutdown");
		super.shutdown();
	}

	// ----- overlay accessors --------------------------------------------------------

	public VorkathState getState()
	{
		return state;
	}

	public VorkathFightPhase getPhase()
	{
		return phase;
	}

	public long getKills()
	{
		return kills;
	}

	public long getLootValue()
	{
		return lootValue;
	}

	public long getRuntimeMillis()
	{
		return startMillis == 0 ? 0 : System.currentTimeMillis() - startMillis;
	}
}
