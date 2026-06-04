/*
 * Lightweight, standalone build for the bgatfa Microbot plugins.
 *
 * It does NOT vendor the Microbot client. Instead it compiles the plugin
 * sources against the published Microbot client exactly as if they were being
 * built from inside the Microbot repo, then packages each plugin as a thin,
 * sideloadable jar (plugin classes + icon resources only — every shared library
 * is provided by the client at runtime).
 */

plugins {
    `java-library`
}

val microbotClientVersion: String by project
val lombokVersion = "1.18.30" // matches the Microbot repo's libs.versions.toml

// Repository that serves the Microbot-flavoured client. Defaulted in
// gradle.properties; pass -PmicrobotRepoUrl= (empty) to build purely against a
// client previously installed with `./gradlew publishToMavenLocal`.
val microbotRepoUrl: String = (findProperty("microbotRepoUrl") as String?).orEmpty()

repositories {
    // A locally-built client (./gradlew publishToMavenLocal in the Microbot repo).
    mavenLocal()
    // The Microbot client + runelite-api (PluginDescriptor.Mocrosoft, Rs2* utils, …).
    if (microbotRepoUrl.isNotBlank()) {
        maven {
            name = "microbot"
            url = uri(microbotRepoUrl)
            isAllowInsecureProtocol = true
            content { includeGroupAndSubgroups("net.runelite") }
        }
    }
    // Upstream RuneLite artifacts (runelite-api, rlawt, discord, flatlaf, http-api, …).
    maven {
        name = "runelite"
        url = uri("https://repo.runelite.net")
        content { includeGroupAndSubgroups("net.runelite") }
    }
    mavenCentral()
}

// Each plugin folder already contains its full `net/runelite/...` package path,
// so we treat the folders themselves as source roots and keep one shared
// compile classpath. The PNG icons sit next to the classes and ship inside the jar.
sourceSets {
    main {
        java.setSrcDirs(listOf("bankvaluer", "loadouts"))
        resources {
            setSrcDirs(listOf("bankvaluer", "loadouts"))
            exclude("**/*.java")
        }
    }
}

dependencies {
    // The client (and its transitive api deps: runelite-api, gson, guice, okhttp,
    // slf4j, …) is provided by the running client, so compile-only is correct.
    compileOnly("net.runelite:client:$microbotClientVersion")

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

fun Jar.pluginPackage(pkg: String) {
    from(sourceSets.main.get().output) {
        include("net/runelite/client/plugins/microbot/$pkg/**")
        exclude(previewHarness)
    }
}

val bankValuerJar by tasks.registering(Jar::class) {
    description = "Builds the sideloadable Bank Value Tracker plugin jar."
    archiveBaseName.set("bank-value-tracker")
    pluginPackage("bankvaluer")
}

val loadoutsJar by tasks.registering(Jar::class) {
    description = "Builds the sideloadable Loadout Snapshots plugin jar."
    archiveBaseName.set("loadout-snapshots")
    pluginPackage("loadouts")
}

// We ship one jar per plugin; disable the catch-all jar and wire ours into the lifecycle.
tasks.named<Jar>("jar") { enabled = false }
tasks.named("assemble") { dependsOn(bankValuerJar, loadoutsJar) }
