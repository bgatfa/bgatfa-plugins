# Firemaker — build notes

Iterative log: read game state → act → read, building a plugin that withdraws maple
logs from a bank and lights them. Driven live via the Agent Server (UDS).

## Goal
Light maple logs taken from the bank, in a loop: bank → withdraw logs → close → light
all → return to bank.

## Environment (observed)
- Agent Server is bound to a **Unix domain socket**, not TCP 8081. Talk to it with:
  `curl --unix-socket ~/.runelite/.agent.sock -H "X-Agent-Token: $(cat ~/.runelite/.agent-token)" http://localhost/<path>`
  (helper script at `/tmp/ag.sh METHOD PATH [body]`).
- Endpoints seen: `/state`, `/inventory`, `/bank`, `/npcs`, `/objects`, `/walk`,
  `/dialogue`, `/scripts`, `/scripts/deploy`.
- Character "Need Diaries" started at (3167, 3489, 0) = **Grand Exchange**, inv empty.
  ⚠️ GE is a **no-fire zone** — fires can't be lit there. Plugin must light at a bank
  that allows fires (e.g. Varrock West) or wherever the player is standing if legal.

## Microbot API to use (from ../Microbot source)
- Item IDs: `ItemID.MAPLE_LOGS = 1517`, `ItemID.TINDERBOX = 590`.
- `Rs2Bank.isOpen()`, `openBank()`, `closeBank()`, `walkToBank()`, `getNearestBank()`,
  `withdrawX(id, n)`, `withdrawAll(id)`, `hasBankItem`, `depositAll()`.
- `Rs2Inventory.combine(tinderbox, logs)` = the "use tinderbox on logs" light action.
  `Rs2Inventory.hasItem(id)`, `count(id)`, `isFull()`.
- `Rs2Player.isAnimating()`, `isMoving()`, `getWorldLocation()`.
- `Global.sleep`, `sleepUntil(cond, timeout)`.
- Plugin/Script idiom: `XxxPlugin extends Plugin` injects `XxxScript extends Script`;
  script runs `scheduledExecutorService.scheduleWithFixedDelay(loop, 0, 600ms)`,
  guards on `Microbot.isLoggedIn()`, cancels future in `shutdown()`.

## Firemaking behaviour assumptions (to verify live)
- Modern OSRS: using tinderbox on logs lights a fire on the current tile, then the
  character **auto-walks one tile west** (east if blocked) and keeps lighting the next
  log until out of logs or path blocked. So one `combine()` may burn many logs.
- Loop will: if logs in inv and idle → `combine()`; re-issue when idle but logs remain
  (handles blocked auto-walk). When no logs → re-bank.

## State machine
`BANKING` (no logs in inv) → walk/open bank, withdraw tinderbox if missing, withdraw
maple logs (fill), close bank → `LIGHTING` (logs in inv) → combine + wait → repeat.

## Live findings

### Action endpoint shapes (confirmed from source)
- `POST /bank/open`, `POST /bank/close`
- `POST /bank/withdraw {"name":..,"quantity":n}` — **quantity ≤ 0 ⇒ withdrawAll**.
- `POST /bank/deposit {"all":true}` or `{"name":..}`.
- `POST /inventory/interact {"action":..,"name"|"id":..}` — **single-item only**.
  There is **no item-on-item endpoint**, so the light (tinderbox→logs) cannot be done
  over plain REST; it needs `Rs2Inventory.combine` (in the plugin, or a deployed script).

### Iter 1 — bank @ GE (3167,3489)
- `POST /bank/open` → `{opened:true}`. GE has bank booths, so banking works at GE.
- Bank holds: **Maple logs ×300 (id 1517)**, **Tinderbox ×1 (id 590)**, also Oak logs,
  **Damp tinderbox (4073)**, Abyssal lantern.
- `withdraw {"name":"Tinderbox",quantity:1}` → got **id 590** (the real one), NOT the
  Damp tinderbox, despite non-exact match. Name match prefers the exact hit. Still, I'll
  pin the plugin to **id 590** to be safe.
- `withdraw {"name":"Maple logs",quantity:0}` (all) → filled the remaining 27 slots.
  Result: inventory = 1 tinderbox + 27 maple logs, full. ✅ banking step validated.
- **Maple logs `actions` = `["Drop"]` only** — no "Light" option on the log itself.
  Confirms the design: lighting = use tinderbox on logs (`combine`), not an interact.

### Iter 2 — walked out of GE
- `/walk {x:3094,y:3490,wait:true}` → `state:ARRIVED` at (3093,3491) = **Edgeville bank**,
  fire-legal and adjacent to the bank. Still holding 27 maple + tinderbox. ✅ walk works.

### Iter 3 — deploy real plugin via `/scripts/deploy` → COMPILE FAIL (useful!)
- The dynamic compiler uses the client's `java.class.path` but **does NOT run the Lombok
  annotation processor**: `package lombok.extern.slf4j does not exist`, and `Script`'s
  Lombok `log` field is `private` so subclasses can't see it.
- Fix: make the plugin **Lombok-free** — drop `@Slf4j`, declare a plain
  `org.slf4j.Logger log = LoggerFactory.getLogger(..)`. (Gradle build tolerates either;
  this keeps it deployable live via the Agent Server too.)

### Iter 4 — deploy succeeds, light VALIDATED ✅ (Edgeville)
Deployed Lombok-free plugin via `/scripts/deploy`. Observed live:
- Firemaking XP: 1,623,322 → 1,624,132 over ~10s = **+810 xp = 6 maple logs** (135 xp ea).
- Inventory maple: 27 → 21 in step with the XP.
- Player **auto-walked** (3093,3491) → (3086,3495) → … lighting a trail of fires.
- Screenshot chatbox: *"You attempt to light the logs"* / *"The fire catches and the
  logs begin to burn."* alternating — this is **normal** firemaking timing (variable
  ticks per light), NOT the plugin spamming.
- ⇒ `Rs2Inventory.combine(tinderbox, logs)` lights, the client auto-walks and keeps
  lighting; one combine burns many logs. The loop's "re-combine when idle & logs remain"
  is the right shape.

### How to read game chat via Agent Server
- No `/chat` endpoint. Best signals: **`/skills`** (Firemaking xp delta = logs lit) and
  **`/screenshot`** (GET → PNG bytes; read the chatbox directly). Used both here.

### Verdict
End-to-end validated live: bank → withdraw tinderbox(590)+maple logs(1517) → close →
combine-light with auto-walk → repeat. Pin tinderbox to id 590. Plugin is Lombok-free so
it builds via Gradle **and** deploys live via the Agent Server.

### Iter 5 — bugs found (client had to be force-closed)
1. **Disabling the plugin doesn't stop it.** `super.shutdown()` cancels the scheduled
   future, but (a) long blocking calls in a mid-flight iteration (`walkToBankAndUseBank`,
   3s `sleepUntil`s) don't observe cancellation promptly, and (b) the **Rs2Walker route**
   and the game's own auto-walk-light keep going. Need an authoritative stop: a `volatile
   active` flag checked at the top of the loop + between steps, AND `Rs2Walker.clearWalkingRoute(..)`
   (+ shorter waits) in `shutdown()`.
2. **Relocating to any walkable tile is wrong — you can't light fires in buildings.** The
   naive "hop to nearest walkable tile" walked into the Edgeville bank building (walkable
   but a no-fire zone), so it got stuck combining with no XP/log change (observed: fm_xp
   frozen at 1624132, maple stuck at 21). Walkability ≠ lightability. **No-fire regions
   (banks, GE, building interiors) are not detectable from collision flags.**
   → Need a **valid lane/path**: an outdoor run of tiles known to be lightable. Real
   firemaking auto-walks WEST (then EAST if west blocked), so the fix is to stand at the
   east end of a clear OUTDOOR east-west lane and light westward, constraining movement to
   that lane so we never wander indoors. Lane location is user/location-specific → config.
3. `/scripts/deploy` won't re-load a class once it's been registered this session (even
   after undeploy) — "already loaded natively". Live re-test needs a renamed copy or a
   client restart. The built jar loads cleanly on restart via the dev-mode scan.

### Mechanics verified against the OSRS wiki (Firemaking)
- **Post-light movement order: West → East → South → North.** "walk one step west if
  there is room; otherwise east. If both blocked, south. If all three blocked, north."
  ⇒ if we keep the **westward** lane clear, west always wins and the fallbacks never
  trigger, so the player walks a straight predictable line. Validating west suffices.
- **No auto-chaining: "each log requires individual action."** The multi-log burn seen in
  Iter 4 was the plugin's loop re-issuing `combine` each idle tick + the engine's single
  westward step after each fire — NOT the engine auto-continuing. Our per-cycle combine is
  the correct model.
- **Blockers that prevent lighting:** occupied ground (existing fires/objects), **plants
  and ferns, tiles adjacent to growing vines, doorways, closed doors** (except Draynor).
  ⚠️ Several of these (plants/ferns) are *walkable* tiles → a walkable+outdoor check can't
  catch them. The **failed-light → reroute** backstop (no log consumed ⇒ abandon lane) is
  therefore essential, not optional.
- Maple logs = **135 xp** each (matches the +135/log measured live).

### Design (implemented)
- **Config = one knob: log type** (`LogType` dropdown — Normal…Redwood, default Maple).
  Nothing else is user-facing.
- **Anchor is auto-discovered**: the tile the player stands on when the plugin is enabled
  becomes the east end of the lane (captured on the first loop iteration, before banking).
- A "lane" = anchor + `LANE_LENGTH` (15) tiles due **west**. Every lane tile must be
  **outdoors** (`tileSettings & TILE_FLAG_UNDER_ROOF == 0`) **and walkable**. Light along
  it; the engine steps west after each fire. If a light fails (plant/fern, doorway, someone
  else's fire) the lane is abandoned and we **reroute** to a parallel lane offset
  ±1..`LANE_SHIFT_MAX` (6) in Y. Out of logs ⇒ bank ⇒ walk back to a clear lane near anchor.
- LANE_LENGTH / LANE_SHIFT_MAX are internal constants, not config.

### Iter 6 — enum config crashes the config panel (NPE: Name is null)
Opening the Firemaker config threw `NullPointerException: Name is null` at
`Enum.valueOf` ← `ConfigPanel.createComboBox`. Cause: RuneLite builds an enum dropdown by
calling `Enum.valueOf(type, configManager.getConfiguration(group, key))` on the **raw
stored value**, catching only `IllegalArgumentException` — so an **un-persisted enum
default** (stored value `null`) throws an uncaught NPE. Defaults normally get written by
`loadDefaultPluginConfiguration` at load, but our hot-deploy/undeploy churn bypassed that.
- **Immediate unblock:** set it live via `POST /settings/plugin {group:firemaker,
  key:logType, value:MAPLE}` (Agent Server). Confirmed stored value was null beforehand.
- **Permanent fix:** in `@Provides provideConfig`, if `getConfiguration(GROUP,"logType")`
  is null, `setConfiguration(GROUP,"logType", LogType.MAPLE.name())` before returning the
  proxy — so the default is always persisted before the panel can read it.

### Deployment state
`firemaker.jar` built + installed to `~/.runelite/sideloaded-plugins/`. The **running**
client loaded whatever jar existed at its launch; the latest build (single-dropdown config)
needs **one client restart** to load. Then: log in, stand at a clear outdoor lane east-end,
enable **Firemaker**, pick the log. Live verification of lane-validation / reroute / clean
stop still PENDING (client at login screen during this session).

### Possible refinements (not yet needed)
- First light attempt on the bank tile can no-op until the auto-walk frees a tile; the
  3s "started" wait + retry handles it, but could pre-step away from the bank booth.
- `walkToBank()` will path back to nearest bank when out of logs — untested this session
  (we stayed in inventory-has-logs mode). Worth a future bank-return observation.
