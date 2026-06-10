/*
 * Lightweight, standalone build for the bgatfa RuneLite plugins.
 *
 * It does NOT vendor the RuneLite client. Instead it compiles the plugin sources
 * against the published RuneLite client (net.runelite:client from repo.runelite.net),
 * then packages each plugin as a thin, sideloadable jar (plugin classes + icon
 * resources only — every shared library is provided by the client at runtime).
 */

import java.io.ByteArrayOutputStream

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

// --- Microbot client ----------------------------------------------------------
//
// We compile the plugins against — and launch — the Microbot client (a RuneLite fork)
// instead of stock RuneLite, so plugins can use the Microbot automation API
// (net.runelite.client.plugins.microbot.* — Rs2Walker, Rs2Bank, Rs2Npc, …) and the
// built-in Agent Server MCP that ships inside the client.
//
// Rather than vendoring Microbot's sources, we reference its self-contained shadow jar:
// a single fat artifact (full client runtime + Microbot API) built once in the Microbot
// checkout with `./gradlew :client:shadowJar`. That sidesteps any maven coordinate
// collision with stock RuneLite (both are net.runelite:client:1.12.28) and keeps this
// repo a thin, plugin-authoring workspace. Point at a different checkout with
// -PmicrobotDir=/abs/path; defaults to the sibling ../Microbot.
val microbotDir = (findProperty("microbotDir") as String?) ?: "../Microbot"
val microbotShadowJar = provider {
    val libs = file("$microbotDir/runelite-client/build/libs")
    libs.listFiles { f -> f.isFile && f.name.endsWith("-shaded.jar") }
        ?.maxByOrNull { it.lastModified() }
        ?: throw GradleException(
            "Microbot client shadow jar not found under $libs.\n" +
            "Build it once in the Microbot checkout:\n" +
            "    (cd $microbotDir && ./gradlew :client:shadowJar)\n" +
            "or point at another checkout with -PmicrobotDir=/abs/path/to/Microbot."
        )
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

// (none) — the Microbot client ships its own built-in Agent Server MCP, so the external
// RuneLite Dev MCP is no longer fetched/compiled. The ExternalPlugin machinery is kept for
// any future upstream plugin; add an entry here to re-enable fetching.
val externalPlugins = emptyList<ExternalPlugin>()

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
)
fun jarBaseName(key: String) = jarNameOverrides[key] ?: key

// --- Publish selected plugins into Microbot-Hub ------------------------------

// These tasks port a local plugin into the Microbot-Hub source/resource layout,
// validate that plugin in the Hub checkout, and optionally commit/push it.
// They are deliberately opt-in per plugin so experimental/local helpers never get
// published just because they live under plugins/.
data class HubPluginPublication(
    val key: String,
    val taskStem: String,
    val defaultVersion: String = "1.0.0",
    val minClientVersion: String = "2.0.61",
)

val hubPluginPublications = listOf(
    HubPluginPublication("bankorganizer", "BankOrganizer"),
    HubPluginPublication("bankcleaner", "BankCleaner"),
)

fun hubBoolProperty(name: String, default: Boolean): Boolean =
    providers.gradleProperty(name).orNull?.toBooleanStrictOrNull() ?: default

fun runCommand(
    command: List<String>,
    workingDir: File = projectDir,
    ignoreExitValue: Boolean = false,
): String {
    val output = ByteArrayOutputStream()
    val result = exec {
        this.workingDir = workingDir
        commandLine(command)
        standardOutput = output
        errorOutput = output
        isIgnoreExitValue = true
    }
    val text = output.toString(Charsets.UTF_8.name()).trim()
    if (!ignoreExitValue && result.exitValue != 0) {
        throw GradleException("Command failed (${command.joinToString(" ")}):\n$text")
    }
    return text
}

fun pascalWords(value: String): String =
    Regex("[A-Za-z0-9]+").findAll(value)
        .joinToString("") { match -> match.value.replaceFirstChar { it.uppercaseChar() } }

fun readJavaPackages(files: List<File>): List<String> {
    val packageRegex = Regex("""(?m)^package\s+([A-Za-z0-9_.]+);""")
    return files.mapNotNull { file ->
        packageRegex.find(file.readText())?.groupValues?.get(1)
    }
}

fun commonPackagePrefix(packages: List<String>): String {
    if (packages.isEmpty()) {
        return ""
    }
    var prefix = packages.first()
    packages.drop(1).forEach { pkg ->
        while (prefix.isNotEmpty() && pkg != prefix && !pkg.startsWith("$prefix.")) {
            prefix = prefix.substringBeforeLast('.', "")
        }
    }
    return prefix
}

fun findAnnotationClose(content: String, annotationStart: Int): Int {
    val open = content.indexOf('(', annotationStart)
    if (open < 0) {
        throw GradleException("Could not find @PluginDescriptor opening parenthesis.")
    }
    var depth = 0
    var inString = false
    var escaped = false
    for (i in open until content.length) {
        val ch = content[i]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) {
                    return i
                }
            }
        }
    }
    throw GradleException("Could not find @PluginDescriptor closing parenthesis.")
}

fun readDescriptorString(descriptorArgs: String, field: String, fallback: String): String =
    Regex("""\b${Regex.escape(field)}\s*=\s*"([^"]*)"""")
        .find(descriptorArgs)
        ?.groupValues
        ?.get(1)
        ?: fallback

fun readDescriptorTags(descriptorArgs: String): String =
    Regex("""\btags\s*=\s*\{([^}]*)}""", RegexOption.DOT_MATCHES_ALL)
        .find(descriptorArgs)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "\"microbot\""

fun localAssetExists(pluginDir: File, fileName: String): Boolean =
    pluginDir.walkTopDown().any {
        it.isFile && it.name.equals(fileName, ignoreCase = true) &&
            (it.parentFile.name == "images" || it.parentFile.name == "assets")
    }

fun copyHubPluginToCheckout(publication: HubPluginPublication, hubDir: File) {
    val pluginDir = File(pluginsRoot, publication.key)
    if (!pluginDir.isDirectory) {
        throw GradleException("Plugin '${publication.key}' does not exist at ${pluginDir.path}.")
    }

    val javaFiles = pluginDir.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .toList()
    if (javaFiles.isEmpty()) {
        throw GradleException("Plugin '${publication.key}' has no Java sources.")
    }

    val packages = readJavaPackages(javaFiles)
    val expectedStandalonePackage = "net.runelite.client.plugins.${publication.key}"
    val oldBasePackage = if (packages.isNotEmpty() && packages.all { it == expectedStandalonePackage || it.startsWith("$expectedStandalonePackage.") }) {
        expectedStandalonePackage
    } else {
        commonPackagePrefix(packages)
    }
    if (oldBasePackage.isBlank()) {
        throw GradleException("Could not infer Java package for plugin '${publication.key}'.")
    }

    val hubBasePackage = "net.runelite.client.plugins.microbot.${publication.key}"
    val hubJavaRoot = hubDir.resolve("src/main/java/net/runelite/client/plugins/microbot")
    val hubResourceRoot = hubDir.resolve("src/main/resources/net/runelite/client/plugins/microbot")
    val hubPluginJavaDir = hubJavaRoot.resolve(publication.key)
    val hubPluginResourceDir = hubResourceRoot.resolve(publication.key)

    if (!hubPluginJavaDir.canonicalPath.startsWith(hubJavaRoot.canonicalPath + File.separator)) {
        throw GradleException("Refusing to write outside Hub plugin source root: $hubPluginJavaDir")
    }
    if (!hubPluginResourceDir.canonicalPath.startsWith(hubResourceRoot.canonicalPath + File.separator)) {
        throw GradleException("Refusing to write outside Hub plugin resource root: $hubPluginResourceDir")
    }

    hubPluginJavaDir.deleteRecursively()
    hubPluginResourceDir.deleteRecursively()
    hubPluginJavaDir.mkdirs()

    val pluginClassFile = javaFiles.singleOrNull { it.name.endsWith("Plugin.java") }
        ?: throw GradleException("Expected exactly one *Plugin.java under ${pluginDir.path}.")
    val pluginClassName = pluginClassFile.name.removeSuffix(".java")

    javaFiles.forEach { sourceFile ->
        var content = sourceFile.readText()
            .replace(oldBasePackage, hubBasePackage)

        if (sourceFile == pluginClassFile) {
            content = configureHubPluginDescriptor(
                content = content,
                className = pluginClassName,
                pluginDir = pluginDir,
                publication = publication,
            )
        }

        val currentPackage = Regex("""(?m)^package\s+([A-Za-z0-9_.]+);""")
            .find(content)
            ?.groupValues
            ?.get(1)
            ?: hubBasePackage
        val packagePath = currentPackage.removePrefix(hubBasePackage)
            .trimStart('.')
            .replace('.', File.separatorChar)
        val targetDir = if (packagePath.isBlank()) hubPluginJavaDir else hubPluginJavaDir.resolve(packagePath)
        targetDir.mkdirs()
        targetDir.resolve(sourceFile.name).writeText(content)
    }

    writeHubPluginDocs(publication, pluginDir, hubPluginResourceDir, pluginClassName)
}

fun configureHubPluginDescriptor(
    content: String,
    className: String,
    pluginDir: File,
    publication: HubPluginPublication,
): String {
    val descriptorStart = content.indexOf("@PluginDescriptor(")
    if (descriptorStart < 0) {
        throw GradleException("Missing @PluginDescriptor in $className.")
    }
    val descriptorEnd = findAnnotationClose(content, descriptorStart)
    val descriptorArgs = content.substring(content.indexOf('(', descriptorStart) + 1, descriptorEnd)
    val displayName = readDescriptorString(descriptorArgs, "name", pascalWords(publication.key))
    val description = readDescriptorString(descriptorArgs, "description", displayName)
    val tags = readDescriptorTags(descriptorArgs)
    val iconUrl = if (localAssetExists(pluginDir, "icon.png")) {
        "https://bgatfa.github.io/Microbot-Hub/${className}/assets/icon.png"
    } else {
        ""
    }
    val cardUrl = if (localAssetExists(pluginDir, "card.png")) {
        "https://bgatfa.github.io/Microbot-Hub/${className}/assets/card.png"
    } else {
        ""
    }

    val descriptor = """
        @PluginDescriptor(
        	name = PluginConstants.BGA + "$displayName",
        	description = "$description",
        	tags = {$tags},
        	authors = {"bgatfa"},
        	version = ${className}.version,
        	minClientVersion = "${publication.minClientVersion}",
        	iconUrl = "$iconUrl",
        	cardUrl = "$cardUrl",
        	enabledByDefault = PluginConstants.DEFAULT_ENABLED,
        	isExternal = PluginConstants.IS_EXTERNAL
        )
    """.trimIndent()

    var updated = content.substring(0, descriptorStart) + descriptor + content.substring(descriptorEnd + 1)
    if (!updated.contains("import net.runelite.client.plugins.microbot.PluginConstants;")) {
        updated = updated.replaceFirst(
            Regex("""(?m)^(package\s+[A-Za-z0-9_.]+;\s*)$"""),
            "$1\nimport net.runelite.client.plugins.microbot.PluginConstants;\n"
        )
    }
    if (!Regex("""\bstatic\s+(?:final\s+)?String\s+version\s*=""").containsMatchIn(updated)) {
        updated = updated.replaceFirst(
            Regex("""(?s)(public\s+class\s+${Regex.escape(className)}[^{]*\{)(\s*)"""),
            "$1\n\tpublic static final String version = \"${publication.defaultVersion}\";\n$2"
        )
    }
    return updated
}

fun writeHubPluginDocs(
    publication: HubPluginPublication,
    pluginDir: File,
    hubPluginResourceDir: File,
    pluginClassName: String,
) {
    val docsDir = hubPluginResourceDir.resolve("docs")
    val assetsDir = docsDir.resolve("assets")
    assetsDir.mkdirs()

    val localReadme = listOf(
        pluginDir.resolve("README.md"),
        pluginDir.resolve("images/README.md"),
    ).firstOrNull { it.isFile }

    if (localReadme != null) {
        localReadme.copyTo(docsDir.resolve("README.md"), overwrite = true)
    } else {
        val title = Regex("([a-z])([A-Z])").replace(pluginClassName.removeSuffix("Plugin"), "$1 $2")
        val readmeLines = mutableListOf(
            "# $title",
            "",
            if (publication.key == "bankorganizer") {
                "Organizes bank tabs from configured item layouts."
            } else {
                "Processes bank items for Grand Exchange liquidation."
            },
            "",
            "## Setup",
            "",
            "Enable the plugin from Microbot Hub, configure the available options, and start it only after confirming the bank state is ready.",
        )
        if (publication.key == "bankcleaner") {
            readmeLines += listOf(
                "",
                "## Safety",
                "",
                "This plugin sells eligible bank items through the Grand Exchange. Review the configuration and exclusions before starting it.",
            )
        }
        docsDir.resolve("README.md").writeText(readmeLines.joinToString("\n") + "\n")
    }

    listOf(pluginDir.resolve("images"), pluginDir.resolve("assets")).forEach { assetSource ->
        if (assetSource.isDirectory) {
            copy {
                from(assetSource) {
                    include("**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif")
                }
                into(assetsDir)
            }
        }
    }
}

fun prepareHubCheckout(hubDir: File, hubGitUrl: String, hubBranch: String) {
    if (hubDir.resolve(".git").isDirectory) {
        val dirty = runCommand(listOf("git", "status", "--porcelain"), hubDir)
        if (dirty.isNotBlank() && !hubBoolProperty("hubAllowDirty", false)) {
            throw GradleException(
                "Hub checkout has uncommitted changes at ${hubDir.path}.\n" +
                "Commit/stash them first, or re-run with -PhubAllowDirty=true if you want the task to proceed."
            )
        }
        runCommand(listOf("git", "fetch", "origin", hubBranch), hubDir)
        runCommand(listOf("git", "checkout", hubBranch), hubDir)
        runCommand(listOf("git", "pull", "--ff-only", "origin", hubBranch), hubDir)
    } else {
        if (hubDir.exists() && hubDir.listFiles()?.isNotEmpty() == true) {
            throw GradleException("${hubDir.path} exists but is not a git checkout.")
        }
        hubDir.parentFile?.mkdirs()
        runCommand(listOf("git", "clone", "--branch", hubBranch, hubGitUrl, hubDir.path))
    }
}

hubPluginPublications.forEach { publication ->
    val pluginClassName = "${publication.taskStem}Plugin"
    tasks.register("publish${publication.taskStem}ToHub") {
        group = "publishing"
        description = "Ports ${publication.key} into bgatfa/Microbot-Hub, validates it, and optionally commits/pushes it."

        doLast {
            val hubGitUrl = providers.gradleProperty("hubGitUrl").orNull
                ?: "https://github.com/bgatfa/Microbot-Hub.git"
            val hubDir = file(providers.gradleProperty("hubDir").orNull ?: "../Microbot-Hub")
            val hubBranch = providers.gradleProperty("hubBranch").orNull ?: "development"
            val hubRemote = providers.gradleProperty("hubRemote").orNull ?: "origin"
            val hubCommit = hubBoolProperty("hubCommit", true)
            val hubPush = hubBoolProperty("hubPush", true)
            val hubMicrobotClientVersion = providers.gradleProperty("hubMicrobotClientVersion").orNull
            val hubMicrobotClientPath = providers.gradleProperty("hubMicrobotClientPath").orNull
                ?: if (hubMicrobotClientVersion.isNullOrBlank()) {
                    runCatching { microbotShadowJar.get().absolutePath }.getOrNull()
                } else {
                    null
                }

            prepareHubCheckout(hubDir, hubGitUrl, hubBranch)
            copyHubPluginToCheckout(publication, hubDir)

            val gradlew = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "gradlew.bat" else "./gradlew"
            val compileTaskName = "compile${publication.key.replaceFirstChar { it.uppercaseChar() }}Java"
            val clientArg = when {
                !hubMicrobotClientPath.isNullOrBlank() -> "-PmicrobotClientPath=$hubMicrobotClientPath"
                !hubMicrobotClientVersion.isNullOrBlank() -> "-PmicrobotClientVersion=$hubMicrobotClientVersion"
                else -> "-PmicrobotClientVersion=${publication.minClientVersion}"
            }
            runCommand(
                listOf(
                    gradlew,
                    "clean",
                    compileTaskName,
                    "-PpluginList=$pluginClassName",
                    clientArg,
                ),
                hubDir,
            )

            val sourcePath = "src/main/java/net/runelite/client/plugins/microbot/${publication.key}"
            val resourcePath = "src/main/resources/net/runelite/client/plugins/microbot/${publication.key}"
            runCommand(listOf("git", "add", sourcePath, resourcePath), hubDir)

            val staged = runCommand(listOf("git", "diff", "--cached", "--name-only"), hubDir)
            if (staged.isBlank()) {
                logger.lifecycle("[${publication.key}] Hub checkout already matches the generated plugin.")
            } else if (hubCommit) {
                runCommand(listOf("git", "commit", "-m", "Publish ${publication.taskStem} plugin"), hubDir)
                logger.lifecycle("[${publication.key}] Committed Hub changes in ${hubDir.path}.")
            } else {
                logger.lifecycle("[${publication.key}] Changes are staged in ${hubDir.path}; commit disabled by -PhubCommit=false.")
            }

            if (hubPush) {
                runCommand(listOf("git", "push", hubRemote, hubBranch), hubDir)
                logger.lifecycle("[${publication.key}] Pushed to $hubRemote/$hubBranch.")
            } else {
                logger.lifecycle("[${publication.key}] Push disabled by -PhubPush=false; changes remain local in ${hubDir.path}.")
            }
        }
    }
}

tasks.register("publishBankClearToHub") {
    group = "publishing"
    description = "Alias for publishBankCleanerToHub."
    dependsOn("publishBankCleanerToHub")
}

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
    // The Microbot client (full runtime + Microbot API, bundled in its shadow jar) is
    // provided at runtime by the launched client, so compile-only is correct. This is what
    // exposes net.runelite.client.plugins.microbot.* (Rs2Walker, Rs2Bank, …) to plugins.
    compileOnly(files(microbotShadowJar))

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
        // Ensure upstream is present even for a direct `./gradlew <key>Jar` (the optional icon
        // copy below reads the freshly-fetched checkout, not the compile output).
        dependsOn(ext.fetchTaskName)
        // Classes come from the shared compile output (package-based); any resources (an icon)
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

// --- Run the RuneLite client from the IDE -------------------------------------

// The launcher source set compiles + runs against the FULL client runtime: the
// client plus its transitive runtime deps (injected-client, lwjgl natives, logback,
// guice, …). compileOnly above is only for building the thin plugin jars.
dependencies {
    "launcherImplementation"(files(microbotShadowJar))
}

// Builds + side-loads the plugins, then launches the real client via our dev.RuneLite
// main. developer-mode makes RuneLite scan ~/.runelite/sideloaded-plugins so the jars
// installPlugins just copied are picked up; insecure-write-credentials dumps the Jagex
// Launcher auth tokens to disk for dev login. -ea is REQUIRED: RuneLite's injected
// client uses Java assertions as hooks and won't run correctly without it.
val runClient by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Launches RuneLite (developer mode) with these plugins side-loaded."
    dependsOn(installPlugins)
    mainClass.set("dev.RuneLite")
    classpath = sourceSets["launcher"].runtimeClasspath
    // Launch on Java 17 (not the Gradle JVM). The Agent Server's UDS transport needs
    // java.net.StandardProtocolFamily.UNIX, which only exists on Java 16+; on Java 11 the
    // UDS bind fails and the server falls back to TCP. Java 17 + the args below mirror
    // Microbot's own `run` task, which is how Microbot launches the client.
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    )
    // -ea is REQUIRED: RuneLite's injected client uses Java assertions as hooks.
    // The --add-opens/--add-exports mirror the Microbot shadow jar's manifest (which
    // only auto-applies under `java -jar`) so the macOS eawt fullscreen adapter loads
    // when we launch via classpath instead.
    jvmArgs(
        "-ea",
        "--add-opens", "java.desktop/com.apple.eawt=ALL-UNNAMED",
        "--add-opens", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
        "--add-exports", "java.desktop/com.apple.eawt=ALL-UNNAMED",
        "--add-exports", "java.desktop/com.apple.eawt.event=ALL-UNNAMED",
    )
}
