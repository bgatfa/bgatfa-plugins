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

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Fetches daily GE price history from the OSRS Wiki real-time prices API. */
@Slf4j
@Singleton
class WikiPriceClient
{
	private static final HttpUrl BASE = HttpUrl.get("https://prices.runescape.wiki/api/v1/osrs/timeseries");
	private static final String USER_AGENT = "RuneLite Bank Value Tracker";

	private final OkHttpClient okHttpClient;
	private final Gson gson;

	@Inject
	WikiPriceClient(OkHttpClient okHttpClient, Gson gson)
	{
		this.okHttpClient = okHttpClient;
		this.gson = gson;
	}

	/**
	 * Asynchronously fetch ~1 year of daily price points for an item.
	 * Both callbacks are invoked on the Swing EDT.
	 */
	void timeseries(int itemId, Consumer<List<PricePoint>> onResult, Runnable onError)
	{
		final HttpUrl url = BASE.newBuilder()
			.addQueryParameter("id", Integer.toString(itemId))
			.addQueryParameter("timestep", "24h")
			.build();

		final Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Price history request failed for {}", itemId, e);
				SwingUtilities.invokeLater(onError);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						SwingUtilities.invokeLater(onError);
						return;
					}

					final TimeseriesResponse parsed = gson.fromJson(body.charStream(), TimeseriesResponse.class);
					final List<PricePoint> points = toPoints(parsed);
					SwingUtilities.invokeLater(() -> onResult.accept(points));
				}
				catch (Exception ex)
				{
					log.debug("Could not parse price history for {}", itemId, ex);
					SwingUtilities.invokeLater(onError);
				}
			}
		});
	}

	private static List<PricePoint> toPoints(TimeseriesResponse parsed)
	{
		final List<PricePoint> points = new ArrayList<>();
		if (parsed == null || parsed.data == null)
		{
			return points;
		}

		Integer lastHigh = null;
		Integer lastLow = null;
		for (Raw r : parsed.data)
		{
			Integer high = r.avgHighPrice != null ? r.avgHighPrice : (lastHigh != null ? lastHigh : r.avgLowPrice);
			Integer low = r.avgLowPrice != null ? r.avgLowPrice : (lastLow != null ? lastLow : high);
			if (high == null || low == null)
			{
				continue;
			}
			lastHigh = high;
			lastLow = low;
			points.add(new PricePoint(r.timestamp, high, low, r.highPriceVolume + r.lowPriceVolume));
		}
		return points;
	}

	private static class TimeseriesResponse
	{
		List<Raw> data;
	}

	private static class Raw
	{
		long timestamp;
		Integer avgHighPrice;
		Integer avgLowPrice;
		long highPriceVolume;
		long lowPriceVolume;
	}
}
