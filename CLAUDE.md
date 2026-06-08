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
package `net.runelite.client.plugins.loadouts`) and **RuneLite Dev MCP** (package
`dev.runelite.mcp` — a read-only MCP server **embedded in the plugin**, serving streamable
HTTP/JSON-RPC on `localhost:3000`; sources **fetched at build time** from
[runbunbun/runelite-dev-mcp](https://github.com/runbunbun/runelite-dev-mcp)).

## External (fetched) plugins

External plugin sources are **not** vendored, committed, or auto-discovered. They're cloned
fresh into `external/<…>/` (gitignored) by a per-plugin Gradle `fetch<Key>Source` task that
runs **automatically before `compileJava`**, so every build compiles the latest upstream HEAD —
no git submodule, no init step, no pinned-commit churn. Each checkout is mapped into the build
explicitly via the `externalPlugins` list in `build.gradle.kts` rather than auto-discovered.

- **Always latest:** `fetch<Key>Source` clones if the checkout is absent, otherwise
  `git fetch --depth 1` + `reset --hard` to upstream HEAD. It's a prerequisite of `compileJava`
  and the plugin's jar task, so `assemble` / `installPlugins` / `runClient` (the "RuneLite (dev)"
  run config) all pull the newest upstream with no extra step. This pairs with
  `runeliteVersion=latest.release`: newest plugin source against the newest client.
- **Offline:** if upstream is unreachable but a checkout already exists, the build warns and
  uses what's on disk; only a missing checkout with no network is a hard failure. Nothing to
  commit — the source is never tracked here.

**RuneLite Dev MCP** is the sole external plugin (`fetchDevmcpSource` → `external/runelite-dev-mcp/`,
gitignored; `src/main/java` → `dev/runelite/mcp/**` into `runelite-dev-mcp.jar`). Its MCP server is
**embedded in the Java plugin** as a `com.sun.net.httpserver` HTTP server — there's no separate Node
server to build, and it ships **no icon resource** (`resourceSubdir = null`). The plugin starts the
server on `localhost:3000` (config: port) when enabled. Because the transport is **streamable HTTP**
(not stdio), Claude Desktop — whose JSON config is stdio-only — reaches it via the `mcp-remote` shim:
`runelite-dev-mcp` → `npx -y mcp-remote http://localhost:3000/mcp` in
`~/Library/Application Support/Claude/claude_desktop_config.json`. Claude Code can instead connect
natively: `claude mcp add --transport http runelite-dev-mcp http://localhost:3000/mcp`. The server
only answers while RuneLite is running with the plugin enabled.

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
