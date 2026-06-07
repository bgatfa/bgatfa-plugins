/*
 * Lightweight, standalone build for the bgatfa RuneLite plugins.
 *
 * It does NOT vendor the RuneLite client. Instead it compiles the plugin sources
 * against the published RuneLite client (net.runelite:client from repo.runelite.net),
 * then packages each plugin as a thin, sideloadable jar (plugin classes + icon
 * resources only — every shared library is provided by the client at runtime).
 */

plugins {
    `java-library`
}

val runeliteVersion: String by project
val lombokVersion = "1.18.30" // matches the RuneLite repo's libs.versions.toml

repositories {
    // A locally-built client (./gradlew publishToMavenLocal in the RuneLite repo).
    mavenLocal()
    // Upstream RuneLite artifacts (client, runelite-api, rlawt, discord, flatlaf, …).
    maven {
        name = "runelite"
        url = uri("https://repo.runelite.net")
        content { includeGroupAndSubgroups("net.runelite") }
    }
    mavenCentral()
}

// Auto-discover plugins: every immediate subdirectory of plugins/ that ships Java
// sources is one plugin. Adding a plugin is just dropping a folder here (see the
// `newPlugin` task) — no edits to this build file are needed.
val pluginsRoot = file("plugins")
val pluginKeys: List<String> = (pluginsRoot.listFiles() ?: emptyArray())
    .filter { dir -> dir.isDirectory && dir.walkTopDown().any { it.isFile && it.extension == "java" } }
    .map { it.name }
    .sorted()

// External plugins: Java sources are NOT vendored here. They're cloned fresh from
// upstream into external/<...> at build time by a per-plugin `fetch<Key>Source` task
// (wired below as a compileJava prerequisite), so every build compiles the latest
// upstream HEAD — no git submodule, no init step, no pinned-commit churn. The checkout
// is gitignored. They don't follow the plugins/<key> = package-root convention, so we
// map their source layout explicitly rather than auto-discovering them. Repo-specific
// docs/screenshots still live in plugins/<key>/ (with no .java, so the scanner above
// skips that folder). The icon is copied straight from upstream's resources to the jar
// root, so upstream's pristine `/icon.png` lookup resolves with no source patch.
data class ExternalPlugin(
    val key: String,
    val gitUrl: String,
    val branch: String,
    val checkoutDir: String,         // where upstream is cloned (gitignored)
    val javaSubdir: String,          // java root, relative to checkoutDir
    val resourceSubdir: String?,     // resource root (icon), relative to checkoutDir
    val jarIncludes: List<String>,
) {
    val javaDir get() = "$checkoutDir/$javaSubdir"
    val resourceDir get() = resourceSubdir?.let { "$checkoutDir/$it" }
    val fetchTaskName get() = "fetch${key.replaceFirstChar { it.uppercaseChar() }}Source"
}

val externalPlugins = listOf(
    ExternalPlugin(
        key = "osrsmcp",
        gitUrl = "https://github.com/Sleepywalker69/OSRS-MCP-Companion.git",
        branch = "master",
        checkoutDir = "external/osrs-mcp-companion",
        javaSubdir = "src/main/java",
        resourceSubdir = "src/main/resources",
        jarIncludes = listOf("com/osrscompanion/**"),
    ),
)

// Clone-or-update each external plugin's upstream sources to the latest commit on its
// branch. Runs before compileJava (below), so `runClient` / `installPlugins` / `assemble`
// all pull the newest upstream automatically. Network-resilient: if upstream is
// unreachable but a checkout already exists, we warn and build against what's on disk;
// only a missing checkout with no network is a hard failure.
val externalFetchTasks = externalPlugins.map { ext ->
    tasks.register(ext.fetchTaskName) {
        group = "build setup"
        description = "Clones/updates '${ext.key}' upstream sources to the latest ${ext.branch} (${ext.gitUrl})."
        doLast {
            val dir = project.file(ext.checkoutDir)
            if (File(dir, ".git").exists()) {
                logger.lifecycle("[${ext.key}] Updating sources -> latest ${ext.gitUrl}@${ext.branch}")
                val fetched = project.exec {
                    commandLine("git", "-C", dir.path, "fetch", "--depth", "1", "origin", ext.branch)
                    isIgnoreExitValue = true
                }.exitValue == 0
                if (fetched) {
                    project.exec { commandLine("git", "-C", dir.path, "reset", "--hard", "FETCH_HEAD") }
                } else {
                    logger.warn("[${ext.key}] Could not reach ${ext.gitUrl}; building against the existing checkout.")
                }
            } else {
                logger.lifecycle("[${ext.key}] Cloning ${ext.gitUrl}@${ext.branch} -> ${ext.checkoutDir}")
                if (dir.exists()) dir.deleteRecursively()        // stale/empty non-git dir
                dir.parentFile?.mkdirs()
                val cloned = project.exec {
                    commandLine("git", "clone", "--depth", "1", "--branch", ext.branch, ext.gitUrl, dir.path)
                    isIgnoreExitValue = true
                }.exitValue == 0
                if (!cloned) throw GradleException(
                    "[${ext.key}] Failed to clone ${ext.gitUrl}@${ext.branch} into ${ext.checkoutDir}. " +
                    "Check your network connection and that `git` is on PATH, then re-run."
                )
            }
        }
    }
}

// Sources must exist before the shared compile reads them.
tasks.named("compileJava") { dependsOn(externalFetchTasks) }

// The package subtree(s) that hold a plugin's classes. All plugins compile into one
// shared output, so each per-plugin jar is filtered down to its own package paths.
// We derive those from the source tree (ignoring non-source dirs like images/), so any
// layout works — net.runelite.client.plugins.<key>, dev.runelite.mcp, … — and sibling
// plugins that share a prefix (bankvaluer vs loadouts) stay cleanly separated.
fun pluginIncludes(key: String): List<String> {
    val root = File(pluginsRoot, key)
    val pkgDirs = root.walkTopDown()
        .filter { it.isDirectory && it.listFiles { f -> f.isFile && f.extension == "java" }?.isNotEmpty() == true }
        .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
        .toList()
    // Keep only the shallowest roots (drop any package nested under another in the set).
    return pkgDirs
        .filter { d -> pkgDirs.none { other -> other != d && d.startsWith("$other/") } }
        .distinct()
        .map { "$it/**" }
}

// Optional prettier jar file names; any plugin not listed defaults to its folder name.
val jarNameOverrides = mapOf(
    "bankvaluer" to "bank-value-tracker",
    "loadouts" to "loadout-snapshots",
    "osrsmcp" to "osrs-mcp-companion",
)
fun jarBaseName(key: String) = jarNameOverrides[key] ?: key

// Each plugin folder already contains its full `net/runelite/...` package path, so we
// treat the folders themselves as source roots and keep one shared compile classpath.
// The PNG icons sit next to the classes and ship inside the jar.
sourceSets {
    main {
        java.setSrcDirs(pluginKeys.map { "plugins/$it" } + externalPlugins.map { it.javaDir })
        resources {
            // In-repo plugins keep icons next to their classes; external (fetched)
            // plugins ship their resources straight into their own jar below, so they're
            // intentionally NOT on the shared resource path.
            setSrcDirs(pluginKeys.map { "plugins/$it" })
            exclude("**/*.java")
        }
    }
    // Dev-only launcher: a tiny main() (package `dev`) that boots the real RuneLite
    // client. Its own source set so it never ships in the plugin jars, and so it can
    // depend on the FULL client runtime without polluting the plugins' compileOnly view.
    create("launcher") {
        java.setSrcDirs(listOf("launcher"))
    }
}

dependencies {
    // The client (and its transitive api deps: runelite-api, gson, guice, okhttp, slf4j, …) is
    // provided by the running client, so compile-only is correct.
    compileOnly("net.runelite:client:$runeliteVersion")

    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(11)
}

// --- Sideloadable plugin jars -------------------------------------------------

// The Swing preview harness is a desktop design tool, not part of the shipped plugin.
val previewHarness = listOf(
    "**/loadouts/LoadoutPreview*",
    "**/loadouts/PreviewIconSource*",
)

// One thin jar per discovered plugin, built from that plugin's package only.
val pluginJars = pluginKeys.map { key ->
    tasks.register<Jar>("${key}Jar") {
        description = "Builds the sideloadable '$key' plugin jar."
        archiveBaseName.set(jarBaseName(key))
        from(sourceSets.main.get().output) {
            pluginIncludes(key).forEach { include(it) }
            exclude(previewHarness)
        }
    }
} + externalPlugins.map { ext ->
    tasks.register<Jar>("${ext.key}Jar") {
        description = "Builds the sideloadable '${ext.key}' plugin jar (sources fetched from ${ext.gitUrl})."
        archiveBaseName.set(jarBaseName(ext.key))
        // Ensure upstream is present even for a direct `./gradlew osrsmcpJar` (the icon
        // copy below reads the freshly-fetched checkout, not the compile output).
        dependsOn(ext.fetchTaskName)
        // Classes come from the shared compile output (package-based); resources (the icon)
        // come straight from upstream so the jar root matches upstream's layout.
        from(sourceSets.main.get().output) {
            ext.jarIncludes.forEach { include(it) }
        }
        ext.resourceDir?.let { from(it) }
    }
}

// We ship one jar per plugin; disable the catch-all jar and wire ours into the lifecycle.
tasks.named<Jar>("jar") { enabled = false }
tasks.named("assemble") { dependsOn(pluginJars) }

// --- Install into the RuneLite sideload folder --------------------------------

// Stock RuneLite side-loads jars from ~/.runelite/sideloaded-plugins when the client
// is launched with --developer-mode (PluginManager.loadSideLoadPlugins). It classpath-
// scans each jar for @PluginDescriptor classes, so no manifest / plugins.json is needed.
// Dev loop: ./gradlew installPlugins -> restart the client with --developer-mode.
val installPlugins by tasks.registering {
    description = "Builds the plugin jars and copies them into ~/.runelite/sideloaded-plugins (run the client with --developer-mode)."
    group = "distribution"
    dependsOn(pluginJars)

    doLast {
        val pluginsDir = File(System.getProperty("user.home"), ".runelite/sideloaded-plugins")
        pluginsDir.mkdirs()

        pluginJars.forEach { provider ->
            val jar = provider.get()
            val name = jar.archiveBaseName.get()
            val dest = pluginsDir.resolve("$name.jar")
            jar.archiveFile.get().asFile.copyTo(dest, overwrite = true)
            logger.lifecycle("Installed $name -> $dest")
        }
    }
}

// --- Scaffold a new plugin ----------------------------------------------------

// Generates a ready-to-build plugin skeleton under plugins/<key>/ so you never hand-
// create the package path or boilerplate. Auto-discovery above picks it up on the next
// sync — no edits to this file. Usage:
//   ./gradlew newPlugin -Pplugin=myplugin
//   ./gradlew newPlugin -Pplugin=myplugin -Pname="My Plugin" -Pclass=MyPlugin
val newPlugin by tasks.registering {
    group = "scaffold"
    description = "Generates a new plugin skeleton: ./gradlew newPlugin -Pplugin=<key> [-Pname=\"Display Name\"] [-Pclass=Prefix]"

    doLast {
        // Read via gradleProperty (not findProperty) so -Pname / -Pclass don't collide
        // with Project bean properties (getName(), getClass()).
        val key = providers.gradleProperty("plugin").orNull?.trim().orEmpty()
        if (!key.matches(Regex("[a-z][a-z0-9]*"))) {
            throw GradleException(
                "Provide -Pplugin=<key>, a lowercase package/folder name (letters+digits, starting with a letter). " +
                "Example: ./gradlew newPlugin -Pplugin=myplugin"
            )
        }

        val displayName = providers.gradleProperty("name").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: key
        fun pascal(s: String) = Regex("[A-Za-z0-9]+").findAll(s)
            .joinToString("") { m -> m.value.replaceFirstChar { it.uppercaseChar() } }
        val prefix = providers.gradleProperty("class").orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: pascal(displayName).ifEmpty { pascal(key) }

        val pkgDir = File(pluginsRoot, "$key/net/runelite/client/plugins/$key")
        if (pkgDir.exists()) {
            throw GradleException("plugins/$key already exists — pick a different -Pplugin key.")
        }
        pkgDir.mkdirs()
        File(pluginsRoot, "$key/images").mkdirs()

        val header = """
            /*
             * Copyright (c) 2026, bgatfa
             * All rights reserved. Redistribution and use in source and binary forms, with
             * or without modification, are permitted provided the copyright notice is kept.
             */
        """.trimIndent()

        File(pkgDir, "${prefix}Plugin.java").writeText(
            header + "\n" + """
            package net.runelite.client.plugins.$key;

            import com.google.inject.Provides;
            import javax.inject.Inject;
            import lombok.extern.slf4j.Slf4j;
            import net.runelite.client.config.ConfigManager;
            import net.runelite.client.plugins.Plugin;
            import net.runelite.client.plugins.PluginDescriptor;

            @Slf4j
            @PluginDescriptor(
            	name = "$displayName",
            	description = "$displayName",
            	tags = {},
            	enabledByDefault = false
            )
            public class ${prefix}Plugin extends Plugin
            {
            	@Inject
            	private ${prefix}Config config;

            	@Provides
            	${prefix}Config provideConfig(ConfigManager configManager)
            	{
            		return configManager.getConfig(${prefix}Config.class);
            	}

            	@Override
            	protected void startUp()
            	{
            		log.info("$displayName started");
            	}

            	@Override
            	protected void shutDown()
            	{
            		log.info("$displayName stopped");
            	}
            }
            """.trimIndent() + "\n"
        )

        File(pkgDir, "${prefix}Config.java").writeText(
            header + "\n" + """
            package net.runelite.client.plugins.$key;

            import net.runelite.client.config.Config;
            import net.runelite.client.config.ConfigGroup;
            import net.runelite.client.config.ConfigItem;

            @ConfigGroup("$key")
            public interface ${prefix}Config extends Config
            {
            	@ConfigItem(
            		keyName = "greeting",
            		name = "Greeting",
            		description = "An example config item"
            	)
            	default String greeting()
            	{
            		return "Hello";
            	}
            }
            """.trimIndent() + "\n"
        )

        logger.lifecycle("Created plugin '$key':")
        logger.lifecycle("  plugins/$key/net/runelite/client/plugins/$key/${prefix}Plugin.java")
        logger.lifecycle("  plugins/$key/net/runelite/client/plugins/$key/${prefix}Config.java")
        logger.lifecycle("  plugins/$key/images/")
        logger.lifecycle("Re-sync Gradle; it builds as '${jarBaseName(key)}.jar' (./gradlew assemble | installPlugins).")
    }
}

// --- Build the OSRS MCP Companion node server ---------------------------------

// Resolve the directory holding the node/npm executables. The IDE's Gradle run can
// launch with a minimal PATH that omits nvm/homebrew (so a bare `npm` fails), while a
// terminal run usually has them on PATH. Probe order: explicit -PnodeBin / NODE_BIN,
// then nvm (most-recently-installed), homebrew, /usr/local. Returns null to fall back
// to whatever the inherited PATH already provides.
fun resolveNodeBinDir(): File? {
    (providers.gradleProperty("nodeBin").orNull ?: System.getenv("NODE_BIN"))?.let {
        val f = File(it)
        if (f.exists()) return if (f.isDirectory) f else f.parentFile
    }
    val nvm = File(System.getProperty("user.home"), ".nvm/versions/node")
        .listFiles { d -> File(d, "bin/node").exists() }
        ?.maxByOrNull { it.lastModified() }       // most recently installed version
        ?.let { File(it, "bin") }
    return (listOfNotNull(nvm) + listOf(File("/opt/homebrew/bin"), File("/usr/local/bin")))
        .firstOrNull { File(it, "node").exists() && File(it, "npm").exists() }
}

// The MCP server is TypeScript with a STDIO transport: it's spawned on demand by the MCP
// client (Claude Desktop) over stdin/stdout, so we BUILD it here — never run it (a
// standalone stdio server serves no client). Building it on the runClient path keeps the
// copy Claude Desktop launches in sync with the freshly-fetched upstream sources. Not
// packaged in any jar. npm install is gated on node_modules; tsc runs every time (fast).
val buildOsrsmcpServer by tasks.registering {
    group = "build setup"
    description = "Builds the OSRS MCP Companion node server (npm install + tsc -> dist/index.js) for MCP clients like Claude Desktop."
    dependsOn("fetchOsrsmcpSource")
    doLast {
        val serverDir = project.file("external/osrs-mcp-companion/mcp-server")
        if (!File(serverDir, "package.json").exists()) {
            throw GradleException("[osrsmcp] mcp-server/ not found — fetchOsrsmcpSource should have populated it.")
        }
        val nodeBin = resolveNodeBinDir()
        fun npm(vararg a: String) = project.exec {
            workingDir = serverDir
            commandLine(listOf("npm") + a)
            // Put the resolved node/npm dir first so npm (a node script) can find its node.
            if (nodeBin != null) {
                environment("PATH", nodeBin.path + File.pathSeparator + (System.getenv("PATH") ?: ""))
            }
            isIgnoreExitValue = true
        }.exitValue
        if (!File(serverDir, "node_modules").exists()) {
            logger.lifecycle("[osrsmcp] Installing MCP server deps")
            val code = if (File(serverDir, "package-lock.json").exists()) npm("ci") else npm("install")
            if (code != 0) throw GradleException(
                "[osrsmcp] npm install failed. Install Node.js (with npm), or point the build at it " +
                "with -PnodeBin=/path/to/node/bin (or the NODE_BIN env var)."
            )
        }
        logger.lifecycle("[osrsmcp] Building MCP server -> dist/index.js")
        if (npm("run", "build") != 0) {
            throw GradleException("[osrsmcp] MCP server build (tsc) failed — see output above.")
        }
        logger.lifecycle("[osrsmcp] MCP server ready: ${File(serverDir, "dist/index.js").path}")
    }
}

// --- Run the RuneLite client from the IDE -------------------------------------

// The launcher source set compiles + runs against the FULL client runtime: the
// client plus its transitive runtime deps (injected-client, lwjgl natives, logback,
// guice, …). compileOnly above is only for building the thin plugin jars.
dependencies {
    "launcherImplementation"("net.runelite:client:$runeliteVersion")
}

// Builds + side-loads the plugins, then launches the real client via our dev.RuneLite
// main. developer-mode makes RuneLite scan ~/.runelite/sideloaded-plugins so the jars
// installPlugins just copied are picked up; insecure-write-credentials dumps the Jagex
// Launcher auth tokens to disk for dev login. -ea is REQUIRED: RuneLite's injected
// client uses Java assertions as hooks and won't run correctly without it.
val runClient by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Launches RuneLite (developer mode) with these plugins side-loaded; also builds the OSRS MCP node server."
    dependsOn(installPlugins, buildOsrsmcpServer)
    mainClass.set("dev.RuneLite")
    classpath = sourceSets["launcher"].runtimeClasspath
    jvmArgs("-ea")
}
