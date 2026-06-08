# bgatfa-plugins

Standalone authoring workspace for this account's **Microbot** plugins. Microbot is an
automation-focused fork of [RuneLite](https://runelite.net); plugins here are written
against its client API and built into thin, sideloadable jars. Neither client is vendored —
the build references a prebuilt Microbot client jar (see below).

## Targeting Microbot

The build compiles and launches against the **Microbot client**, not stock RuneLite, so
plugins can use the Microbot automation API (`net.runelite.client.plugins.microbot.*` —
`Rs2Walker`, `Rs2Bank`, `Rs2Npc`, …) and the client's built-in **Agent Server** MCP.

Rather than vendoring Microbot's sources, the build references its **shadow jar** — a single
self-contained fat artifact (full client runtime + Microbot API). Build it once in the
Microbot checkout, then everything here resolves against it:

```bash
(cd ../Microbot && ./gradlew :client:shadowJar)   # -> runelite-client/build/libs/client-<ver>-shaded.jar
```

- The checkout location defaults to the sibling `../Microbot`; override with
  `-PmicrobotDir=/abs/path/to/Microbot`.
- Referencing one fat jar (instead of `net.runelite:client` from Maven) sidesteps the
  coordinate collision between Microbot and stock RuneLite — both are
  `net.runelite:client:1.12.28` — and keeps this repo a thin authoring workspace.
- Rebuild the shadow jar when you pull new Microbot changes you want to compile against.

## Repository layout

- Gradle build lives at the repo root (`build.gradle.kts`, `settings.gradle.kts`,
  `gradle.properties`, `gradlew`, `gradle/wrapper/`).
- `plugins/` — one folder per plugin (sources + icons only). Each plugin folder already
  contains its full Java package path, e.g.
  `plugins/bankvaluer/net/runelite/client/plugins/bankvaluer/…`. Icon PNGs sit next to the
  classes; storyboard screenshots live in each plugin's `images/` folder.
- `launcher/` — a tiny `dev.RuneLite` main (own source set) that boots the Microbot client
  in developer mode. Never ships in the plugin jars.
- `README.md` — the public plugin showcase.

Current plugins: **Bank Value Tracker** (`plugins/bankvaluer`), **Loadout Snapshots**
(`plugins/loadouts`) and **Hotswap** (`plugins/hotswap`) — packages
`net.runelite.client.plugins.<key>`.

## Build & verify

```bash
./gradlew assemble        # builds the thin plugin jars in build/libs (one per plugins/<key>)
```

- Plugins `compileOnly` the Microbot shadow jar (located via `microbotDir`), so the Microbot
  API is on the compile classpath; the runtime is provided by the launched client.
- A jar is "loadable" when its `@PluginDescriptor` class resolves against the client classpath
  and extends `net.runelite.client.plugins.Plugin`; keep jars thin (plugin classes + icons
  only — shared libraries are provided by the client).

## External (fetched) plugins

The `ExternalPlugin` machinery in `build.gradle.kts` (per-plugin `fetch<Key>Source` task that
clones upstream into gitignored `external/<…>/` before `compileJava`) is **kept but unused** —
`val externalPlugins = emptyList()`. The former **RuneLite Dev MCP** external plugin was
removed: Microbot ships its own MCP (the Agent Server), so the read-only RuneLite Dev MCP is
no longer fetched or compiled. Re-enable fetching by adding an entry to the `externalPlugins`
list.

## Running (launch the Microbot client)

```bash
./gradlew runClient       # assembles + side-loads the plugins, then boots the Microbot client
```

`runClient` builds the plugin jars, copies them into `~/.runelite/sideloaded-plugins/` (via
`installPlugins`), and launches `net.runelite.client.RuneLite` (through the `launcher`
source set's `dev.RuneLite`) with:

- `--developer-mode` — Microbot classpath-scans `~/.runelite/sideloaded-plugins` for
  `@PluginDescriptor` classes (no manifest / `plugins.json` needed).
- `--insecure-write-credentials` — dumps Jagex Launcher auth tokens to disk for dev login.
- `-ea` (JVM) — **required**: the injected client uses Java assertions as hooks.
- macOS `--add-opens`/`--add-exports` for `java.desktop/com.apple.eawt` — mirrors the shadow
  jar's manifest (which only auto-applies under `java -jar`) so the fullscreen adapter loads
  when launched via classpath.

`runClient` launches on a **Java 17 toolchain** (`javaToolchains.launcherFor { 17 }`), not the
Gradle JVM — the Agent Server's UDS transport needs `java.net.StandardProtocolFamily.UNIX`
(Java 16+); on Java 11 the UDS bind fails and falls back to TCP. Side effect of JDK 16+:
RuneLite's `EventBus` can't build lambda dispatchers for *sideloaded* plugins'
`@Subscribe` methods (`LambdaConversionException: Invalid caller`), so it logs WARNs at
startup and **falls back to reflective dispatch** (`Subscriber.invoke` → `method.invoke`) —
handlers still fire; the WARNs are cosmetic.

`./gradlew installPlugins` alone just refreshes the sideloaded jars; restart the client to pick
them up.

## The Microbot MCP (Agent Server)

The "Microbot MCP" is the built-in **Agent Server** plugin
(`net.runelite.client.plugins.microbot.agentserver`) — an HTTP/JSON REST server embedded in
the client (not a native MCP server; an MCP client reaches it through a bridge that maps tool
calls onto its REST endpoints). It exposes ~30 endpoints for reading state and taking actions
(`/state`, `/walk`, `/bank`, `/npcs`, `/inventory`, `/dialogue`, `/scripts/deploy`, …).

- **Off by default** (`enabledByDefault = false`). Enable the **"Agent Server"** plugin in the
  running client (Microbot lists it prefixed, e.g. *…Agent Server*; tags `agent, ai, server`).
- **Transport:** TCP `127.0.0.1:8081` by default (config `agentServer.port`); `bindMode` can
  switch to a Unix domain socket at `~/.runelite/.agent.sock` — **requires the Java 17 launch**
  (UDS needs Java 16+; on Java 11 it falls back to TCP). Curl UDS with
  `--unix-socket ~/.runelite/.agent.sock http://localhost/state`.
- **Auth:** `X-Agent-Token` header; the token is auto-generated to `~/.runelite/.agent-token`
  when the plugin starts. Loopback-only.
- Smoke test once enabled:
  `curl -s -H "X-Agent-Token: $(cat ~/.runelite/.agent-token)" http://127.0.0.1:8081/state`

## Adding a plugin

Scaffold it — the build auto-discovers every folder under `plugins/` (no `build.gradle.kts`
edits needed):

```bash
./gradlew newPlugin -Pplugin=myplugin                                  # key only
./gradlew newPlugin -Pplugin=myplugin -Pname="My Plugin" -Pclass=MyPlugin
```

This generates `plugins/myplugin/net/runelite/client/plugins/myplugin/{MyPlugin}Plugin.java`
+ `…Config.java` and an `images/` folder. Re-sync Gradle and it builds/installs as
`myplugin.jar` alongside the rest. Then flesh out the plugin and add a `README.md` section.
(Prettier jar file names can be set via the `jarNameOverrides` map in `build.gradle.kts`.)

> Auto-discovery handles any package layout — a plugin folder is any subdir of `plugins/`
> that ships `.java`, and each thin jar is filtered to its own package root — so a plugin
> need not live under `net/runelite/client/plugins/<key>`.
