# bgatfa-plugins

Standalone home for the RuneLite plugins in this account. Plugins are written against the
[RuneLite](https://runelite.net) client and built into thin, sideloadable jars here — the
client itself is never vendored.

## Repository layout

- Gradle build lives at the repo root (`build.gradle.kts`, `settings.gradle.kts`,
  `gradle.properties`, `gradlew`, `gradle/wrapper/`).
- `plugins/` — one folder per plugin (sources + icons only). Each plugin folder already
  contains its full Java package path, e.g.
  `plugins/bankvaluer/net/runelite/client/plugins/bankvaluer/…`. Icon PNGs sit next to the
  classes; storyboard screenshots live in each plugin's `images/` folder.
- `README.md` — the public plugin showcase.

Current plugins: **Bank Value Tracker** (`plugins/bankvaluer`, package
`net.runelite.client.plugins.bankvaluer`), **Loadout Snapshots** (`plugins/loadouts`,
package `net.runelite.client.plugins.loadouts`) and **OSRS MCP Companion** (package
`com.osrscompanion` — a RuneLite plugin exposing live game state over a local HTTP API on
`127.0.0.1:8085`, bridged to AI assistants by a TypeScript MCP server; sources **fetched at
build time** from [Sleepywalker69/OSRS-MCP-Companion](https://github.com/Sleepywalker69/OSRS-MCP-Companion),
BSD 2-Clause).

## External (fetched) plugins

OSRS MCP Companion is **not** vendored, committed, or auto-discovered. Its upstream sources are
cloned fresh into `external/osrs-mcp-companion/` (gitignored) by a Gradle `fetchOsrsmcpSource`
task that runs **automatically before `compileJava`**, so every build compiles the latest
upstream `master` — no git submodule, no init step, no pinned-commit churn. The checkout is
mapped into the build explicitly via the `externalPlugins` list in `build.gradle.kts` (compiles
`src/main/java` → `com/osrscompanion/**` into `osrs-mcp-companion.jar`, with the icon copied
straight from `src/main/resources` — so upstream source ships unmodified). Repo-specific
docs/screenshots for it still live in `plugins/osrsmcp/` (no `.java` there, so the `plugins/`
scanner skips it).

- **Always latest:** `fetchOsrsmcpSource` clones if the checkout is absent, otherwise
  `git fetch --depth 1` + `reset --hard` to upstream HEAD. It's a prerequisite of `compileJava`
  and `osrsmcpJar`, so `assemble` / `installPlugins` / `runClient` (the "RuneLite (dev)" run
  config) all pull the newest upstream with no extra step. This pairs with
  `runeliteVersion=latest.release`: newest plugin source against the newest client.
- **Offline:** if upstream is unreachable but a checkout already exists, the build warns and
  uses what's on disk; only a missing checkout with no network is a hard failure. Nothing to
  commit — the source is never tracked here.
- The upstream `mcp-server/` (TypeScript) is not packaged into any jar. It's built by the Gradle
  `buildOsrsmcpServer` task (`npm install` once + `tsc` → `dist/index.js`), which `runClient`
  (the "RuneLite (dev)" run config) runs automatically against the freshly-fetched source. It's
  a **stdio** MCP server, so it's only ever *built* here — the MCP client (Claude Desktop, Claude
  Code) spawns it on demand; never run it standalone. `node`/`npm` are auto-detected from
  nvm/homebrew (override via `-PnodeBin=` or `NODE_BIN`). `.mcp.json` points Claude Code at its
  `dist/index.js`; Claude Desktop is registered in
  `~/Library/Application Support/Claude/claude_desktop_config.json` (absolute node + entry path).

> Auto-discovery handles any package layout — a plugin folder is any subdir of `plugins/`
> that ships `.java`, and each thin jar is filtered to its own package root — so a plugin
> need not live under `net/runelite/client/plugins/<key>`.

## Build & verify

```bash
./gradlew assemble        # builds bank-value-tracker.jar + loadout-snapshots.jar in build/libs
```

- The client to compile against is `runeliteVersion` in `gradle.properties`, resolved from the
  RuneLite Maven repo (`https://repo.runelite.net`).
- To build against a locally-installed client, run `./gradlew publishToMavenLocal` in your
  RuneLite checkout; `mavenLocal()` is already on the resolution path.
- A jar is "loadable" when its `@PluginDescriptor` class resolves against the client classpath
  and extends `net.runelite.client.plugins.Plugin`; keep jars thin (plugin classes + icons
  only — shared libraries are provided by the client).

## Installing (sideload)

`./gradlew installPlugins` copies the jars into `~/.runelite/sideloaded-plugins/`. Stock
RuneLite classpath-scans that folder for `@PluginDescriptor` classes when launched with
`--developer-mode` — no manifest / `plugins.json` is needed.

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
