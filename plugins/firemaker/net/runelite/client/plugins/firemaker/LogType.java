/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.firemaker;

/**
 * Logs that can be burned. {@link #getItemName()} is the exact in-game item name used for
 * banking and lighting; the label shown in the dropdown is the friendly tree name.
 */
public enum LogType
{
	NORMAL("Normal", "Logs"),
	OAK("Oak", "Oak logs"),
	WILLOW("Willow", "Willow logs"),
	TEAK("Teak", "Teak logs"),
	MAPLE("Maple", "Maple logs"),
	MAHOGANY("Mahogany", "Mahogany logs"),
	YEW("Yew", "Yew logs"),
	MAGIC("Magic", "Magic logs"),
	REDWOOD("Redwood", "Redwood logs");

	private final String label;
	private final String itemName;

	LogType(String label, String itemName)
	{
		this.label = label;
		this.itemName = itemName;
	}

	public String getItemName()
	{
		return itemName;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
