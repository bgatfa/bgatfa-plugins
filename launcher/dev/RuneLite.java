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
package dev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dev entry point: boots the real RuneLite client in developer mode so the plugins
 * side-loaded into {@code ~/.runelite/sideloaded-plugins} are picked up.
 *
 * <p>Run this from IntelliJ (the "RuneLite (dev)" run configuration). It <b>must</b> be
 * launched with assertions enabled (VM option {@code -ea}) — RuneLite's injected client
 * uses Java assertions as hooks and will not run correctly otherwise. The provided run
 * configuration already sets {@code -ea} and rebuilds + installs the plugin jars first.
 */
public final class RuneLite
{
	private RuneLite()
	{
	}

	public static void main(String[] args) throws Exception
	{
		final List<String> all = new ArrayList<>();
		Collections.addAll(all, "--developer-mode", "--insecure-write-credentials");
		Collections.addAll(all, args); // forward anything extra from the run config
		net.runelite.client.RuneLite.main(all.toArray(new String[0]));
	}
}
