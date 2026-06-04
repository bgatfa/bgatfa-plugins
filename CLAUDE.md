# bgatfa-plugins

Standalone home for the Microbot plugins in this account. Plugins are written against the
[Microbot](https://github.com/chsami/microbot) client and built into thin, sideloadable jars
here — the client itself is never vendored.

## Repository layout

- `microbot/` — self-contained Gradle build plus one folder per plugin. Each plugin folder
  already contains its full Java package path, e.g.
  `microbot/bankvaluer/net/runelite/client/plugins/microbot/bankvaluer/…`. Icon PNGs sit next
  to the classes; storyboard screenshots live in each plugin's `images/` folder.
- `docs/` — reference guides for authoring Microbot plugins (mirrored from the Microbot repo).
- `README.md` — the public plugin showcase.

Current plugins: **Bank Value Tracker** (`microbot/bankvaluer`, package
`net.runelite.client.plugins.microbot.bankvaluer`) and **Loadout Snapshots**
(`microbot/loadouts`, package `net.runelite.client.plugins.microbot.loadouts`).

## Build & verify

```bash
cd microbot
./gradlew assemble        # builds bank-value-tracker.jar + loadout-snapshots.jar in build/libs
```

- The client to compile against is `microbotClientVersion` in `microbot/gradle.properties`,
  resolved from the Microbot Maven repo (`microbotRepoUrl`).
- To build against a locally-installed client, run `./gradlew publishToMavenLocal` in your
  Microbot checkout, then build here with `-PmicrobotRepoUrl=`.
- A jar is "loadable" when its `@PluginDescriptor` class resolves against the client classpath
  and extends `net.runelite.client.plugins.Plugin`; keep jars thin (plugin classes + icons
  only — shared libraries are provided by the client).

## Adding a plugin

1. Create `microbot/<plugin>/net/runelite/client/plugins/microbot/<plugin>/…` and put sources +
   icons there.
2. Add the folder as a source root in `microbot/build.gradle.kts` (the `sourceSets` block) and
   register a `Jar` task for it (mirror `bankValuerJar` / `loadoutsJar`).
3. Drop showcase images in `microbot/<plugin>/images/` and add a section to `README.md`.

## Deeper guides

- **Script authoring & threading:** [`docs/script-authoring.md`](docs/script-authoring.md)
- **State machines** (use for 3+ phase scripts): [`docs/state-machines.md`](docs/state-machines.md)
- **Architecture:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md), [`docs/decisions/`](docs/decisions/)
- **Setup:** [`docs/development.md`](docs/development.md), [`docs/installation.md`](docs/installation.md)
