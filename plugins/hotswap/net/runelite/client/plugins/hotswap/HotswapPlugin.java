/*
 * Copyright (c) 2026, bgatfa
 * All rights reserved. Redistribution and use in source and binary forms, with
 * or without modification, are permitted provided the copyright notice is kept.
 */
package net.runelite.client.plugins.hotswap;

import com.google.inject.Provides;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.util.ReflectUtil;

/**
 * Watches {@code ~/.runelite/sideloaded-plugins} and live-reloads any jar that changes,
 * so a {@code ./gradlew installPlugins} (which overwrites the jars in place) takes effect
 * without restarting the client — including structural edits (new classes/fields/lifecycle).
 *
 * <p>The trick is a classloader swap: when a jar changes we tear down its currently-loaded
 * plugin instances ({@link PluginManager#setPluginEnabled}/{@link PluginManager#stopPlugin}/
 * {@link PluginManager#remove}), drop the old {@link URLClassLoader}, then load the jar's classes
 * through a fresh loader and hand them to {@link PluginManager#loadPlugins} (which builds a new
 * Guice child injector). Reloaded plugins are left disabled so the user can re-enable them after
 * the swap settles. Because the classes come from a new loader, stock HotSpot has no problem —
 * nothing is being redefined, just newly loaded.
 *
 * <p>Caveats: it can't reload <i>itself</i> (its own jar is skipped); the config sidebar may show
 * a stale entry until reopened; and a reloaded plugin's {@code shutDown()} must cleanly deregister
 * everything (nav buttons, overlays, listeners) or state will leak.
 */
@Slf4j
@PluginDescriptor(
	name = "Hotswap",
	description = "Watches the sideloaded-plugins folder and hot-reloads changed jars (developer tool)",
	tags = {"dev", "developer", "reload", "hot", "swap", "sideload", "hotswap"},
	developerPlugin = true
)
public class HotswapPlugin extends Plugin
{
	/** The loader that defined the core client classes — parent for every plugin loader. */
	private static final ClassLoader CORE = Plugin.class.getClassLoader();

	private static final File SIDELOADED =
		new File(System.getProperty("user.home"), ".runelite/sideloaded-plugins");

	@Inject
	private PluginManager pluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private HotswapConfig config;

	/** jar name -> last signature (size:mtime) we consider already loaded/up to date. */
	private final Map<String, String> knownGood = new HashMap<>();
	/** jar name -> signature seen last tick, awaiting a second matching tick to confirm the copy settled. */
	private final Map<String, String> pending = new HashMap<>();
	/** jar name -> the live loader we created, so we can close the previous one on the next swap. */
	private final Map<String, URLClassLoader> loaders = new HashMap<>();
	/** jar name -> plugin class names it contributed, so we can tear them down on change/delete. */
	private final Map<String, Set<String>> jarPlugins = new HashMap<>();

	private volatile boolean running;
	private Thread watcher;

	@Provides
	HotswapConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HotswapConfig.class);
	}

	@Override
	protected void startUp()
	{
		knownGood.clear();
		pending.clear();
		jarPlugins.clear();

		// Snapshot what the client already side-loaded at boot so we don't reload it on start.
		// We still record each jar's plugin class names (without instantiating) so unload-on-delete
		// works for boot-loaded jars too.
		final File[] files = listJars();
		if (files != null)
		{
			for (File f : files)
			{
				knownGood.put(f.getName(), signature(f));
				jarPlugins.put(f.getName(), scanPluginClassNames(f));
			}
		}

		running = true;
		watcher = new Thread(this::watchLoop, "hotswap-watcher");
		watcher.setDaemon(true);
		watcher.start();
		log.info("Hotswap watching {}", SIDELOADED);
	}

	@Override
	protected void shutDown()
	{
		running = false;
		if (watcher != null)
		{
			watcher.interrupt();
			watcher = null;
		}
		// Leave reloaded plugins (and their loaders) running; we're just turning off the watcher.
	}

	private void watchLoop()
	{
		while (running)
		{
			try
			{
				tick();
			}
			catch (Throwable t)
			{
				log.warn("Hotswap tick failed", t);
			}

			try
			{
				Thread.sleep(Math.max(200, config.pollIntervalMillis()));
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private void tick()
	{
		final File[] files = listJars();
		final Map<String, String> current = new HashMap<>();
		if (files != null)
		{
			for (File f : files)
			{
				current.put(f.getName(), signature(f));
			}
		}

		// Deletions: jar that was present at boot/last tick is now gone.
		for (String name : new ArrayList<>(knownGood.keySet()))
		{
			if (!current.containsKey(name))
			{
				if (config.unloadOnDelete())
				{
					unload(name);
				}
				knownGood.remove(name);
				pending.remove(name);
			}
		}

		// Additions / modifications.
		if (files != null)
		{
			for (File f : files)
			{
				final String name = f.getName();
				final String sig = current.get(name);

				if (sig.equals(knownGood.get(name)))
				{
					pending.remove(name);
					continue;
				}

				if (sig.equals(pending.get(name)))
				{
					// Same bytes two ticks running -> the copy has settled, act on it.
					reload(f);
					pending.remove(name);
					knownGood.put(name, sig); // set regardless of success so a broken jar isn't retried until it changes again
				}
				else
				{
					pending.put(name, sig);
				}
			}
		}
	}

	/** Swap a changed/new jar: tear down its old instances, then load + start from a fresh loader. */
	private void reload(File jar)
	{
		final String name = jar.getName();
		URLClassLoader loader = null;
		try
		{
			loader = new LookupableLoader(new URL[]{jar.toURI().toURL()}, CORE);
			final List<Class<?>> classes = loadJarClasses(jar, loader);
			final Set<String> descriptors = classes.stream()
				.filter(HotswapPlugin::isPlugin)
				.map(Class::getName)
				.collect(Collectors.toCollection(LinkedHashSet::new));

			if (descriptors.isEmpty())
			{
				log.debug("{} contains no @PluginDescriptor classes, ignoring", name);
				loader.close();
				return;
			}

			if (descriptors.contains(getClass().getName()))
			{
				log.info("Skipping {} — a plugin can't hot-reload its own jar", name);
				loader.close();
				return;
			}

			final int[] disabled = {0};
			final int[] loaded = {0};
			onEdt(() ->
			{
				// Tear down every currently-loaded instance of these plugin classes (match by name,
				// since the old Class objects came from a now-defunct loader).
				for (Plugin p : new ArrayList<>(pluginManager.getPlugins()))
				{
					if (descriptors.contains(p.getClass().getName()))
					{
						if (pluginManager.isPluginEnabled(p) || pluginManager.isPluginActive(p))
						{
							pluginManager.setPluginEnabled(p, false);
							disabled[0]++;
						}
						try
						{
							pluginManager.stopPlugin(p);
						}
						catch (Exception ex)
						{
							log.warn("Failed stopping old {}", p.getClass().getSimpleName(), ex);
						}
						pluginManager.remove(p);
					}
				}

				try
				{
					for (Plugin p : pluginManager.loadPlugins(classes, null))
					{
						pluginManager.setPluginEnabled(p, false);
						loaded[0]++;
					}
				}
				catch (Exception ex)
				{
					log.error("loadPlugins failed for {}", name, ex);
				}

				// Tell the config sidebar the plugin set changed so it rebuilds its list items and
				// config proxies from scratch — otherwise it keeps pointing at the torn-down instances.
				eventBus.post(new ExternalPluginsChanged());
			});

			// Success: adopt the new loader and close the one it replaced.
			final URLClassLoader previous = loaders.put(name, loader);
			if (previous != null)
			{
				closeQuietly(previous);
			}
			jarPlugins.put(name, descriptors);
			log.info("Reloaded {} — {} plugin(s) loaded, {} disabled, none started", name, loaded[0], disabled[0]);
		}
		catch (Exception ex)
		{
			log.error("Failed to reload {}", name, ex);
			closeQuietly(loader);
		}
	}

	/** Stop + remove the plugins a (now-deleted) jar contributed, and release its loader. */
	private void unload(String name)
	{
		final Set<String> names = jarPlugins.getOrDefault(name, Collections.emptySet());
		if (!names.isEmpty())
		{
			onEdt(() ->
			{
				for (Plugin p : new ArrayList<>(pluginManager.getPlugins()))
				{
					if (names.contains(p.getClass().getName()))
					{
						if (pluginManager.isPluginEnabled(p) || pluginManager.isPluginActive(p))
						{
							pluginManager.setPluginEnabled(p, false);
						}
						try
						{
							pluginManager.stopPlugin(p);
						}
						catch (Exception ex)
						{
							log.warn("Failed stopping {}", p.getClass().getSimpleName(), ex);
						}
						pluginManager.remove(p);
					}
				}

				eventBus.post(new ExternalPluginsChanged());
			});
		}

		jarPlugins.remove(name);
		closeQuietly(loaders.remove(name));
		log.info("Unloaded {} — {} plugin(s)", name, names.size());
	}

	/** Plugin class names a jar defines, scanned through a throwaway loader (no instantiation). */
	private Set<String> scanPluginClassNames(File jar)
	{
		try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toURI().toURL()}, CORE))
		{
			return loadJarClasses(jar, cl).stream()
				.filter(HotswapPlugin::isPlugin)
				.map(Class::getName)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		}
		catch (Exception ex)
		{
			log.debug("Could not scan {}", jar.getName(), ex);
			return Collections.emptySet();
		}
	}

	/** Load only the jar's own classes (not the parent classpath) through {@code loader}. */
	private static List<Class<?>> loadJarClasses(File jar, ClassLoader loader) throws IOException
	{
		final List<Class<?>> out = new ArrayList<>();
		try (JarFile jf = new JarFile(jar))
		{
			final Enumeration<JarEntry> entries = jf.entries();
			while (entries.hasMoreElements())
			{
				final String entry = entries.nextElement().getName();
				if (!entry.endsWith(".class") || entry.contains("module-info") || entry.contains("package-info"))
				{
					continue;
				}

				final String className = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
				try
				{
					out.add(Class.forName(className, false, loader));
				}
				catch (Throwable t)
				{
					// Optional/unsatisfied classes inside the jar — fine to skip for discovery.
					log.debug("Skipping class {}", className, t);
				}
			}
		}
		return out;
	}

	private static boolean isPlugin(Class<?> clazz)
	{
		return clazz.isAnnotationPresent(PluginDescriptor.class) && clazz.getSuperclass() == Plugin.class;
	}

	private static void onEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
			return;
		}

		try
		{
			SwingUtilities.invokeAndWait(r);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (InvocationTargetException e)
		{
			log.error("Reload step threw on the EDT", e.getCause() != null ? e.getCause() : e);
		}
	}

	private static File[] listJars()
	{
		return SIDELOADED.listFiles((dir, n) -> n.endsWith(".jar"));
	}

	private static String signature(File f)
	{
		return f.length() + ":" + f.lastModified();
	}

	private static void closeQuietly(URLClassLoader cl)
	{
		if (cl != null)
		{
			try
			{
				cl.close();
			}
			catch (IOException ignored)
			{
			}
		}
	}

	/**
	 * Reload classloader that installs RuneLite's private-lookup helper, so a reloaded plugin's
	 * {@code @Subscribe} methods can have their EventBus lambda dispatchers built on Java 16+.
	 * Without it, {@code LambdaMetafactory} rejects the caller ("Invalid caller") and EventBus
	 * falls back to reflection, logging a WARN per subscriber on every reload. Mirrors RuneLite's
	 * {@code PluginHubClassLoader}.
	 */
	private static final class LookupableLoader extends URLClassLoader implements ReflectUtil.PrivateLookupableClassLoader
	{
		private MethodHandles.Lookup lookup;

		LookupableLoader(URL[] urls, ClassLoader parent)
		{
			super(urls, parent);
			ReflectUtil.installLookupHelper(this);
		}

		@Override
		public Class<?> defineClass0(String name, byte[] b, int off, int len) throws ClassFormatError
		{
			return super.defineClass(name, b, off, len);
		}

		@Override
		public MethodHandles.Lookup getLookup()
		{
			return lookup;
		}

		@Override
		public void setLookup(MethodHandles.Lookup lookup)
		{
			this.lookup = lookup;
		}
	}
}
