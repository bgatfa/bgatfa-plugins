# Bank Organizer Primitive Notes

Date: 2026-06-09

Goal: validate real OSRS bank manipulation primitives against the live Microbot client before building the Bank Organizer plugin. Each note should capture the action, the verification signal, and the implementation implication.

## Baseline

- Bank is open.
- `/bank` reports `count=265`.
- `CURRENT_BANK_TAB` varbit `4150` initially read as `0`.
- Real tab count varbits:
  - `4171=16`
  - `4172=2`
  - `4173=1`
  - `4174=8`
  - `4175=5`
  - `4176=1`
- `Slice of cake` is present as item id `1895`, quantity `2`.
- Visible bank widget search located `Slice of cake` at `groupId=12`, `childId=12`, dynamic child `index=21`.
- Real bank tabs are under `groupId=12`, `childId=10`.
- Existing real tabs are dynamic children `11..16`.
- The current new-tab target is dynamic child `17`.
- Ignore the Bank Tags widget under `groupId=12`, `childId=9`; it is not a real bank tab.

## Primitive 1: Invoke Existing Real Tab

Action:

- Invoked `View tab` on real tab 6 with `groupId=12`, `childId=10`, `param0=16`, `identifier=1`.

Verification:

- Invoke response returned `invoked=true`.
- Reread `CURRENT_BANK_TAB` varbit `4150`; value changed from `0` to `6`.
- `/bank` still reported `count=265`.
- Searching visible widgets for `Crystal saw`, the tab 6 icon item, returned item id `9625`.

Implementation note:

- `WidgetInvokeHandler` can trigger real tab menu actions.
- In plugin code, prefer direct client APIs where possible, but the equivalent action is: operate on `ComponentID.BANK_TAB_CONTAINER`, dynamic child `10 + tabIndex`, action `View tab`.
- Always verify by rereading `VarbitID.BANK_CURRENTTAB` after the action.

## Next Primitive: Drag Item To New Real Tab

Attempt 1 action:

- Open all-items view.
- Find `Slice of cake` by item id `1895`.
- Read its `Rs2Bank.getItemBounds(slot)`.
- Read the new-tab widget bounds from `Rs2Bank.getTabs()` / `ComponentID.BANK_TAB_CONTAINER`.
- Drag from item bounds to the new-tab target.

Probe result:

- Temporary deployment `bank-organizer-probe-newtab` compiled and started after removing Lombok.
- The probe found `Slice of cake` at slot `21`, quantity `2`.
- The probe found the new-tab widget at dynamic child `17`.
- The probe read item bounds as `java.awt.Rectangle[x=169,y=1355,width=36,height=32]`.
- The probe read new-tab bounds as `java.awt.Rectangle[x=342,y=45,width=36,height=32]`.
- The probe called `Microbot.drag(itemBounds, newTabBounds)`.

Independent verification:

- `CURRENT_BANK_TAB` varbit `4150` was `0` after the probe.
- Real tab count varbit `4177` remained `0`.
- Widget search still showed only six real tabs plus the new-tab target.
- `/bank` still reported `count=265`.
- `Slice of cake` remained item id `1895`, quantity `2`, at visible search index `21`.

Outcome:

- The primitive did not create a new real tab.
- Treat this as a failed drag attempt, despite the probe storing `success=true`, because the probe only verified that it called `Microbot.drag`.
- Likely cause: `Rs2Bank.getItemBounds(slot)` can return an off-canvas rectangle when the item is outside the current scroll viewport. `Microbot.drag` silently returns when either rectangle is outside the canvas.

Implementation note:

- `BankActuator.dragItemToNewTab` must verify both source and target rectangles are within canvas before dragging.
- It also needs a way to scroll/search/open the relevant tab so the source item widget is actually visible.
- A drag primitive is only successful after rereading tab varbits/widgets; the drag call itself is not enough.

Expected verification for a successful retry:

- Number of non-empty real tabs increases from 6 to 7.
- New tab count varbit `4177` becomes non-zero, ideally `1`.
- `Slice of cake` remains in the bank with quantity `2`.
- Total bank stack count remains `265`.

Attempt 2 action:

- Patched the temporary probe to call `Rs2Bank.scrollBankToSlot(item.getSlot())` before reading bounds.
- Added canvas checks with `Rs2UiHelper.isRectangleWithinCanvas`.
- Reloaded `bank-organizer-probe-newtab`.
- Probe dragged `Slice of cake` from visible item bounds to the real new-tab target.

Probe result:

- `currentTabBefore=0`.
- `tab7CountBefore=0`.
- `scrollToSlot=true`.
- `itemBounds=java.awt.Rectangle[x=169,y=249,width=36,height=32]`.
- `newTabBounds=java.awt.Rectangle[x=342,y=45,width=36,height=32]`.
- `itemBoundsInCanvas=true`.
- `newTabBoundsInCanvas=true`.
- `tab7CountAfter=1`.
- `Slice of cake` remained present with quantity `2`.

Independent verification:

- `CURRENT_BANK_TAB` varbit `4150` stayed `0`.
- Real tab count varbits changed from `16,2,1,8,5,1,0` to `16,2,1,7,5,1,1`.
- Widget search showed real tab 7 at `groupId=12`, `childId=10`, dynamic child `17`, item id `1895`.
- The new-tab target moved from dynamic child `17` to `18`.
- `/bank` still reported `count=265`.
- `Slice of cake` moved from bank index `21` to bank index `32` after tab creation.

Outcome:

- Dragging an item to the real new-tab target is validated.

Implementation note:

- The real `BankActuator.dragItemToNewTab` must:
  - Open all-items view, or the source item's current real tab.
  - Scroll the source slot into view with `Rs2Bank.scrollBankToSlot(slot)`.
  - Reject the action if source or target bounds are not inside the canvas.
  - Drag with `Microbot.drag(sourceBounds, newTabBounds)`.
  - Verify by rereading the new tab's count varbit and tab widget icon/item id.

Earlier implementation note:

- The existing Agent Server widget describe/search endpoints expose widget identity but not bounds.
- The Bank Organizer plugin will need an isolated `BankActuator` using `Rs2Bank.getItemBounds(slot)` and tab widget bounds on the client thread.

Probe tooling note:

- Dynamic script deployment compiles with the runtime classpath but did not include Lombok for probe source.
- Keep temporary Agent Server probe plugins plain Java. The real sideloaded plugin may still use the repo's normal Lombok/Gradle compile path.

## Primitive 3: Collapse Temporary Real Tab

Initial MCP action:

- Invoked `Collapse tab` on `groupId=12`, `childId=10`, `param0=17`, `identifier=2`.

Verification:

- Invoke response returned `invoked=true`.
- `BANK_TAB_7` varbit `4177` stayed `1`.
- Widget search still showed real tab 7 with item id `1895`.

Outcome:

- Failed. The invoke response alone was not meaningful.

Discovery:

- A temporary collapse probe read the real widget action array for the tab as:
  - `[View tab, null, null, null, null, Collapse tab, Remove placeholders]`
- The Agent Server widget search output hides null action slots, so its action list made `Collapse tab` look like identifier `2`.
- The correct `CC_OP` identifier for `Collapse tab` is `6`.

Successful action:

- Invoked `Collapse tab` on `groupId=12`, `childId=10`, `param0=17`, `identifier=6`.

Verification:

- `BANK_TAB_7` varbit `4177` changed from `1` to `0`.
- Widget search no longer showed real tab 7; the new-tab target returned to dynamic child `17`.
- `/bank` still reported `count=265`.
- `Slice of cake` remained item id `1895`, quantity `2`.
- `Slice of cake` moved to the end of the all-items ordering after collapse.

Outcome:

- Collapsing one real bank tab is validated.

Implementation note:

- Do not infer widget action identifiers from the filtered action list returned by widget search.
- In plugin code, inspect `Widget.getActions()` directly and find the 1-based `CC_OP` identifier from the sparse action array.
- For `Collapse tab`, the current observed identifier is `6`.
- Always verify collapse by rereading the relevant tab count varbit and the tab widget list.

## Primitive 4: Drag Item To Existing Real Tab

Reason:

- Collapsing the temporary tab moved `Slice of cake` to the end of the all-items/main ordering.
- Tab 4 count stayed at `7`, while baseline tab 4 count was `8`.
- Moving `Slice of cake` back onto tab 4 would validate the existing-tab drag primitive and restore the tab count.

Action:

- Deployed temporary probe `bank-organizer-probe-existingtab`.
- Opened all-items view.
- Found `Slice of cake` by item id `1895`.
- Found existing tab 4 by its icon item id `1458` (`Law talisman`).
- Called `Rs2Bank.scrollBankToSlot(item.getSlot())`.
- Verified source and target bounds were inside the canvas.
- Dragged `Slice of cake` source bounds onto tab 4 bounds.

Probe result:

- `tab4CountBefore=7`.
- `scrollToSlot=true`.
- `itemSlot=265`.
- `itemBounds=java.awt.Rectangle[x=121,y=249,width=36,height=32]`.
- `tabBounds=java.awt.Rectangle[x=223,y=45,width=36,height=32]`.
- `tabIndex=14`.
- `itemBoundsInCanvas=true`.
- `tabBoundsInCanvas=true`.
- `tab4CountAfter=8`.
- `Slice of cake` remained present with quantity `2`.
- `sliceSlotAfter=26`.

Independent verification:

- `CURRENT_BANK_TAB` varbit `4150` stayed `0`.
- Real tab count varbits returned to `16,2,1,8,5,1,0`.
- `/bank` still reported `count=265`.
- Widget search located `Slice of cake` at bank index `26`, item id `1895`.
- Widget search showed six real tabs plus the new-tab target at dynamic child `17`.

Outcome:

- Dragging an item stack onto an existing real bank tab is validated.
- The temporary tab test was cleaned up and the original tab counts were restored.

Implementation note:

- `BankActuator.dragItemToExistingTab` should use the same guard pattern as `dragItemToNewTab`:
  - Open all-items view, or source item's current tab.
  - Scroll source slot into view.
  - Read the destination tab widget bounds from `Rs2Bank.getTabs()`.
  - Verify both rectangles are inside the canvas.
  - Drag with `Microbot.drag(sourceBounds, tabBounds)`.
  - Verify by rereading the destination tab count varbit and item quantity.

## Cleanup State

- Dynamic script deployment list returned `count=0`; all temporary probes are undeployed.
- Bank remains open.
- `/bank` still reports `count=265`.
- Final real tab count varbits are restored to:
  - `4171=16`
  - `4172=2`
  - `4173=1`
  - `4174=8`
  - `4175=5`
  - `4176=1`
  - `4177=0`
- `Slice of cake` remains item id `1895`, quantity `2`.
- No plugin implementation has been started yet; these notes are the validated primitive contract for the future `BankActuator`.

## Remaining Primitive Checklist

The core physical primitives are validated, but a full bank organizer still needs these functions confirmed before running an end-to-end rebuild:

1. Snapshot fidelity:
   - Capture every bank stack with item id, canonical name, quantity, all-items slot/index, and current tab assignment.
   - Confirm snapshot count equals `/bank count`.
   - Confirm duplicate item names with different ids are handled as separate stacks.

2. Tab ownership mapping:
   - Derive which items belong to which current real tab from tab count varbits and all-items ordering.
   - Verify the mapping against the existing real tab counts.

3. Collapse all tabs loop:
   - Validate collapsing multiple tabs from highest to lowest.
   - Verify after each collapse that total bank count and item quantities are unchanged.
   - Confirm tab count varbits shift/reset the way expected after each collapse.

4. Category planning:
   - Classify all 265 current stacks into exactly one category each.
   - Produce category counts and first-item tab seeds.
   - Confirm only non-empty categories create tabs and that no run can exceed nine real tabs.

5. Action planning after collapse:
   - Re-snapshot after collapsing existing tabs because item slots/order can change.
   - Rebind planned moves by item id/name/quantity rather than trusting stale pre-collapse slot numbers.

6. Tab creation sequence:
   - Create multiple category tabs in order.
   - Verify each new tab icon/category seed and count after creation.
   - Confirm the new-tab target dynamic child advances predictably after each tab is created.

7. Existing tab move loop:
   - Move several items into the same category tab, not just one.
   - Verify destination tab count increments and source item quantity remains unchanged after each move.
   - Re-locate each source stack before dragging, since slot numbers can change during bank rearrangement.

8. Stop/failure safety:
   - Confirm the worker stops cleanly if bank closes, player logs out, verification fails, or user clicks stop.
   - Confirm no withdraw/deposit/drop/sell/alch/destroy actions are reachable from the actuator.

9. Dry run:
   - Run the full classifier and planner without dragging.
   - Confirm planned stack total equals the live bank stack count.
   - Write category counts and planned actions to overlay/log output.

Conclusion:

- The current validated primitives are enough to start implementing `BankActuator` and the classifier/planner in isolation.
- They are not enough to safely run a full automatic bank rebuild yet.
- The highest-value next live test is a controlled multi-tab test: create two temporary category tabs from safe seed items, move two or three stacks into them, then collapse them back and verify the original bank count and quantities.

## MVP Build Order

Goal: reach a useful Bank Organizer MVP without jumping straight to full-bank mutation.

1. Snapshot reader:
   - Build a real `BankSnapshot` from the live bank.
   - Include item id, canonical name, quantity, all-items index, current tab counts, and current selected tab.
   - Acceptance: snapshot stack count equals `/bank count` and tab counts sum to the current bank count.

2. Classifier:
   - Implement deterministic category assignment for the nine MVP categories.
   - Start with curated overrides and practical name patterns.
   - Acceptance: every current bank stack gets exactly one category, with fixture tests for known examples.

3. Dry-run planner:
   - Generate planned category tabs and item move actions without touching the bank.
   - Use only non-empty categories and cap at nine real tabs.
   - Acceptance: planned stack total equals snapshot stack count, with readable category counts.

4. Overlay/control shell:
   - Add the plugin, config, overlay, `Organize Bank` action, dry-run toggle, stop button/state.
   - Wire it to snapshot + classifier + planner first.
   - Acceptance: enabling plugin does nothing dangerous; clicking dry run shows phase/counts/actions.

5. Actuator library from validated primitives:
   - Implement open bank, open main tab, locate slot bounds, drag to new tab, drag to existing tab, collapse tab, and verification helpers.
   - Keep these isolated from the full organizer state machine.
   - Acceptance: code paths match the primitives already proven live.

6. Controlled live primitive test mode:
   - Add a hidden/dev-only action to run the two-temp-tab test with safe seed items.
   - Create temporary tabs, move a small number of stacks, collapse back, and verify quantities/counts.
   - Acceptance: bank count unchanged, temporary tabs removed, item quantities unchanged.

7. Collapse-all implementation:
   - Collapse existing real tabs from highest to lowest with verification after each collapse.
   - Re-snapshot after collapse.
   - Acceptance: all stacks end in main/all-items view, total count and quantities unchanged.

8. Partial organizer run:
   - Create only one category tab, move only a small capped number of items, then stop.
   - Re-locate every item immediately before dragging.
   - Acceptance: category tab count matches expected moved stack count and no quantities change.

9. Full organizer run:
   - Collapse tabs, re-snapshot, create all non-empty category tabs, move all remaining category stacks.
   - Stop on any verification mismatch.
   - Acceptance: stack count unchanged, quantities unchanged, every non-empty category has a tab, and the run ends cleanly.

Recommended MVP boundary:

- Ship the first MVP at step 4 as a dry-run organizer with classification, category counts, and planned actions.
- Treat steps 5 and 6 as the bridge from useful planner to live bank mutation.
- Only after step 8 succeeds should full-bank organization be exposed as a normal action.

## MVP Steps 1-4 Implementation Checkpoint

Date: 2026-06-09

Implemented a new read-only `Bank Organizer` plugin under `plugins/bankorganizer`.

Included pieces:

- `BankSnapshotReader`
  - Requires the bank to be open.
  - Reads `Rs2Bank.bankItems()` on the client thread.
  - Canonicalizes item ids through `ItemManager`.
  - Captures item id, name, quantity, bank slot, all-items index, inferred current real tab, stackable/tradeable/ge-tradeable/equipable flags, current selected tab, and real tab count varbits.
  - Infers current tab ownership from all-items order plus real tab count varbits.

- `BankClassifier`
  - Assigns every stack to one of the nine MVP categories.
  - Uses explicit overrides for the known examples from the plan:
    - `Coins -> Currency`
    - `Barrows gloves -> Gear`
    - `Spirit seed -> Resources`
    - `Games necklace -> Teleports`
    - `Ghostspeak amulet -> Quest`
    - `Ghostly robe -> Storage`
  - Uses conservative name/equipable heuristics after overrides.
  - Falls back to `Misc`.

- `BankPlanner`
  - Builds non-empty category tabs in fixed MVP order.
  - Produces dry-run `BankMoveAction` entries with the first item in each category marked as the tab seed.
  - Honors configured excluded item ids/names for move actions.
  - Caps planned action count using `maxActionsPerRun`.

- Plugin/control shell
  - Adds `BankOrganizerPlugin`, `BankOrganizerConfig`, and `BankOrganizerOverlay`.
  - Plugin does nothing on startup.
  - Config exposes `Organize Bank`, `Stop`, `Show overlay`, `Dry run`, `Max actions per run`, and excluded ids/names.
  - `Organize Bank` runs snapshot -> classify -> plan only.
  - Live movement is intentionally not implemented yet; if `Dry run` is disabled, the plugin still only produces a dry-run result and reports that live mode is not implemented.
  - Overlay shows phase, stack count, planned stack count, category tab count, planned action count, current tab, main-tab stack count, and category counts.

Verification:

- `./gradlew assemble` succeeded.

## Step 14 Production Cleanup

Date: 2026-06-09

Removed temporary/test-facing Bank Organizer controls and code.

Removed config items:

- `Run Primitive Test`
- `Test Collapse Tabs`
- `Test Partial Organize`
- `Test Multi Organize`
- `Buy Test Items`
- `Test buy budget`

Removed temporary classes:

- `BankOrganizerTestItem`
- `BankOrganizerTestItems`
- `BankTestItemBuyer`

Removed plugin wiring:

- primitive test worker
- collapse test worker
- partial organize test worker
- multi organize test worker
- GE fixture buying worker
- config event handlers for deleted toggles

Removed actuator test code:

- slice-of-cake primitive test routine
- collapse-all test routine
- partial category test routine
- bounded multi-category test routine
- test-only result classes and helpers

Production config now contains:

- `Organize Bank`
- `Stop`
- `Show overlay`
- `Dry run`
- `Max actions per run`
- `Use bank tag layouts`
- `Layout tab 1` through `Layout tab 9`
- `Excluded item IDs`
- `Excluded item names`

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

## Step 22 Unlabeled Layout CSV Fields

Date: 2026-06-09

Removed the visible `CSV` label from each layout text field.

Behavior:

- Each layout pair now displays as `Tab N` boolean toggle followed by an unlabeled text field.
- Existing config keys remain unchanged.

Verification:

- `./gradlew assemble` succeeded with blank config item names.
- `./gradlew installPlugins` succeeded.

## Step 23 Active Tab Movement Scope

Date: 2026-06-09

Corrected layout planner movement scope for inactive tabs.

Rule:

- Active tabs are the only tabs the organizer cleans.
- Unlisted items are moved to main only when they are currently inside an active tab.
- Inactive tabs are left alone by default.
- If an item in an inactive tab is listed in an active tab's CSV, the organizer may move it into that active target tab.

Implementation:

- `BankTagLayoutPlanner` now tracks active target tab indexes.
- Main-tab cleanup actions are only generated for unlisted stacks whose current tab is active.
- Renamed the internal metric to `unlistedActiveTabbedStacks`.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

## Step 21 Compact Layout Tab Labels

Date: 2026-06-09

Simplified layout config labels.

Display shape:

- `Tab N` boolean toggle
- `CSV` text field beneath it

Implementation:

- Kept existing config keys stable (`layoutTabNActive`, `layoutTabN`) so saved values continue to map.
- Changed only the displayed `name` and descriptions.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

## Step 20 Active Layout Tabs

Date: 2026-06-09

Added an `Active` boolean for each layout tab.

Behavior:

- Each layout tab has a matching `Layout tab N active` toggle.
- Only active layout tabs are parsed, conflict-checked, and used for movement planning.
- Inactive tabs can keep example or draft CSVs without affecting the organizer.
- All active toggles default to `false`, including the example in `Layout tab 1`.
- If no layout tabs are active, the plugin blocks before opening the bank.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

## Step 19 Plain Bank Tag CSV Support

Date: 2026-06-09

Updated the layout parser to support both RuneLite CSV shapes.

Supported formats:

- Layout CSV: `banktags,1,Gathering,1511,layout,0,1511,1,1521,...`
- Plain bank tag CSV: `banktags,1,Teleports,30045,30045,30048,30051,...`

Behavior:

- If a `layout` section exists, item IDs are read from slot/item pairs and sorted by slot.
- If no `layout` section exists, item IDs are read directly from token 5 onward in listed order.
- Config descriptions now say `bank tag or layout CSV`.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.
- `./gradlew installPlugins` succeeded and installed `bankorganizer.jar` to `~/.runelite/sideloaded-plugins/`.
- Agent Server `/bank` reported the bank was closed during this checkpoint, so live dry-run execution in the client has not been performed yet.

Next MVP validation:

- Restart the client or let Hotswap load the installed jar.
- Enable `Bank Organizer`.
- Open the bank.
- Click `Organize Bank`.
- Confirm overlay/log reports a dry-run plan with planned stack total equal to live bank stack count.

## MVP Control Correction

Date: 2026-06-09

The first config used `ConfigButton` for `Organize Bank` and `Stop`, but those did not render as usable controls in this sideloaded plugin's config UI.

Changed both controls to momentary booleans:

- `organizeBank`
  - User turns the toggle on.
  - Plugin starts the dry-run planner.
  - Plugin resets the config value back to `false`.

- `stop`
  - User turns the toggle on.
  - Plugin requests stop/cancels the current worker task.
  - Plugin resets the config value back to `false`.

Verification:

- `./gradlew assemble installPlugins` succeeded after the change.
- Updated `bankorganizer.jar` was installed to `~/.runelite/sideloaded-plugins/`.

## MVP Dry-Run Live Validation

Date: 2026-06-09

User ran `Bank Organizer` with dry run enabled while the real bank was open.

Overlay result:

- Phase: `Dry Run`
- Stacks: `265 / 265`
- Category tabs: `9`
- Planned actions: `265`
- Current tab: `0`
- Main stacks: `232`
- Category counts:
  - `Currency=8`
  - `Gear=109`
  - `Resources=21`
  - `Consumables=22`
  - `Teleports=16`
  - `Tools=24`
  - `Quest=26`
  - `Storage=23`
  - `Misc=16`

Validation:

- Category counts sum to `265`.
- Planned stack total equals live bank stack count.
- Agent Server `/bank` also reported `open=true` and `count=265` immediately after the run.
- No live bank mutation was performed.

Implementation note:

- The step 1-4 MVP is validated enough to move on to the isolated `BankActuator` implementation and controlled live primitive test mode.
- Classification quality still needs review; `Gear=109` is expected to be broad with the current name/equipable heuristic.

## Step 5-6 Implementation Checkpoint

Date: 2026-06-09

Implemented the isolated `BankActuator` and a controlled live primitive test mode.

`BankActuator` primitives:

- `ensureBankOpen()`
  - Uses `Rs2Bank.isOpen()` / `Rs2Bank.openBank()`.

- `openMainTab()`
  - Calls `Rs2Bank.openMainTab()`.
  - Verifies `Rs2Bank.getCurrentTab() == 0`.

- `dragItemToSlot(itemId, targetSlot)`
  - Scrolls source slot into view.
  - Reads source/target item widget bounds.
  - Requires both rectangles to be inside the canvas before dragging.
  - Verifies the source item moved from its original slot.

- `dragItemToNewTab(itemId)`
  - Opens main tab.
  - Re-locates the source item.
  - Scrolls source slot into view.
  - Uses the current new-tab target dynamic child.
  - Requires source/target bounds inside the canvas.
  - Verifies the new tab count increased and item quantity stayed unchanged.

- `dragItemToExistingTab(itemId, tabIndex)`
  - Opens main tab.
  - Re-locates the source item.
  - Scrolls source slot into view.
  - Uses real tab dynamic child `10 + tabIndex`.
  - Requires source/target bounds inside the canvas.
  - Verifies destination tab count increased and item quantity stayed unchanged.

- `collapseTab(tabIndex)`
  - Finds `Collapse tab` from the sparse `Widget.getActions()` array.
  - Uses the 1-based `CC_OP` identifier from the actual sparse action slot.
  - Invokes the real tab widget under `ComponentID.BANK_TAB_CONTAINER`.
  - Verifies the target tab count decreased.

Controlled primitive test:

- Added config toggle `Run Primitive Test`.
- This is separate from `Organize Bank`.
- It performs real drags with `Slice of cake`:
  - snapshot baseline count, quantity, and original tab
  - drag `Slice of cake` to a temporary new tab
  - collapse that temporary tab
  - restore `Slice of cake` to its original real tab when applicable
  - verify final bank stack count and cake quantity match baseline
- The toggle resets back to `false` after being handled.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.
- Agent Server `/bank` still reported `open=true` and `count=265` after install.

Next validation:

- Reload/restart the client if Hotswap does not pick up the changed jar.
- Enable/open `Bank Organizer`.
- With the bank open, toggle `Run Primitive Test`.
- Confirm overlay reports `Primitive OK`.
- Confirm Agent Server `/bank count` remains `265` and `Slice of cake` quantity remains `2`.

## Step 5-6 Live Validation

Date: 2026-06-09

User ran the `Run Primitive Test` toggle from the real `Bank Organizer` plugin and reported that it worked.

Agent Server verification immediately after:

- `/bank open=true`
- `/bank count=265`
- `Slice of cake` remained item id `1895`, quantity `2`

Outcome:

- The actuator is now validated inside the sideloaded plugin, not only through temporary probe deployments.
- Safe primitives confirmed in-plugin:
  - drag to temporary new tab
  - collapse temporary tab
  - restore to an existing tab when applicable
  - verify total bank count and item quantity after the run

Implementation note:

- `Slice of cake` is currently visible at the end of the Agent Server `/bank` ordering after the primitive run. This is acceptable for the primitive validation because quantity/count safety held, but full organizer planning must continue to re-snapshot and re-locate items after every tab mutation.

## Step 7.1-7.2 Implementation Checkpoint

Date: 2026-06-09

Implemented collapse-all test mode and mandatory re-snapshot after collapse.

New config toggle:

- `Test Collapse Tabs`
  - Momentary boolean toggle.
  - This performs real bank tab mutation.
  - It collapses all existing real tabs from highest tab to lowest tab.
  - The toggle resets to `false` after the request is handled.

Collapse-all behavior:

- Requires/open-checks the bank.
- Captures a baseline `BankSnapshot`.
- Builds a baseline item quantity map from canonical item id to total quantity.
- Determines the current highest non-empty real tab.
- For each real tab from highest to lowest:
  - calls `collapseTab(tabIndex)`
  - verifies the collapse action itself
  - immediately captures a fresh `BankSnapshot`
  - verifies stack count still matches baseline
  - verifies every item quantity still matches baseline
  - stops with `Blocked` on any mismatch
- After the loop:
  - captures a final `BankSnapshot`
  - verifies total stack count and quantities again
  - verifies final tabbed stack count is `0`
  - reports the final post-collapse snapshot in the overlay

Overlay/log behavior:

- Running phase: `Collapse Test`
- Success phase: `Collapse OK`
- Failure phase: `Blocked`
- Overlay uses the final post-collapse snapshot so it should show all stacks in main after success.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.
- Agent Server `/bank` still reported `open=true` and `count=265` after install.

Next live validation:

- Reload/restart if needed.
- Open bank.
- Toggle `Test Collapse Tabs`.
- Expected success overlay: `Collapse OK`.
- Expected final state: all real tab counts are `0`, `/bank count=265`, and quantities unchanged.

## Step 7.1-7.2 Live Validation

Date: 2026-06-09

User ran `Test Collapse Tabs` from the real `Bank Organizer` plugin.

Observed behavior:

- Plugin collapsed all existing real bank tabs from right to left.
- User confirmed the visual behavior was correct.

Agent Server verification immediately after:

- `/bank open=true`
- `/bank count=265`
- `Slice of cake` remained item id `1895`, quantity `2`

Outcome:

- Collapse-all is validated in-plugin.
- The mandatory post-collapse re-snapshot path is validated enough to proceed to partial organizer runs.
- Current bank ordering reflects the collapsed-tab behavior; former tab contents are now in the main/all-items ordering.

Next implementation step:

- Add a capped partial organizer run.
- Start with one category, preferably `Currency`, and a very small stack cap such as `3`.
- Flow: snapshot collapsed bank -> create one category tab from first category seed -> move up to cap remaining category stacks -> verify after each drag -> stop.

## Step 8.1 Implementation Checkpoint

Date: 2026-06-09

Implemented a capped partial organizer test mode.

New config toggle:

- `Test Partial Organize`
  - Momentary boolean toggle.
  - Requires the bank to already be collapsed with no real tabs.
  - Creates one real category tab for `Currency`.
  - Moves up to `3` planned `Currency` stacks total.
  - Resets to `false` after the request is handled.

Partial organizer flow:

- Reads a fresh `BankSnapshot`.
- Runs the existing classifier/planner.
- Selects the first `3` planned actions for `Currency`.
- `BankActuator.runPartialCategoryTest(Currency, actions)`:
  - rejects the run if any real tabs already exist
  - captures baseline stack count and item quantities
  - drags the first Currency stack to the new-tab target, creating tab 1
  - verifies stack count/quantities unchanged
  - verifies tab 1 count is `1`
  - moves each remaining Currency stack to tab 1
  - re-snapshots and verifies stack count/quantities after every move
  - verifies tab 1 count increments after every move
  - returns final post-run snapshot for overlay display

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.
- Agent Server `/bank` still reported `open=true` and `count=265` after install.

Next live validation:

- Ensure the bank is collapsed with no real tabs.
- Toggle `Test Partial Organize`.
- Expected overlay phase: `Partial OK`.
- Expected final state:
  - one real tab exists
  - tab 1 count is `3`
  - `/bank count=265`
  - all item quantities unchanged

## Step 8.1 Live Validation

Date: 2026-06-09

User ran `Test Partial Organize`.

Observed behavior:

- It successfully moved the three planned `Currency` stacks.
- It created the real category tab and moved the next two stacks into it.
- User noticed each item move clicked the main tab / `View all items` first.

Agent Server verification immediately after:

- `/bank open=true`
- `/bank count=265`
- First visible `/bank` stacks are the three moved Currency test items:
  - `Wilderness agility ticket x2`
  - `Golden nugget x50`
  - `Mark of grace x84`

Outcome:

- Capped partial organizer run is validated.
- Count safety held after the run.

Optimization:

- Updated `BankActuator.openMainTab()` to be idempotent.
- If `Rs2Bank.getCurrentTab() == 0`, it now returns success without clicking the main tab.
- This should remove repeated `View all items` clicks when already in the all-items view.

Verification:

- `./gradlew assemble installPlugins` succeeded after the optimization.

## Step 9 Temporary GE Test Fixture Buyer

Date: 2026-06-09

Added a temporary Bank Organizer config toggle:

- `Buy Test Items`
  - Momentary boolean toggle.
  - Buys a fixed cheap 27-item fixture for testing category coverage.
  - Buys only missing fixture items; if a fixture item is already present in the bank, it skips that item.
  - Uses RuneLite `ItemManager#getItemPrice(itemId)` as guide price when available.
  - Places each buy offer at `ceil(guidePrice * 1.10)`.
  - Falls back to curated approximate prices if RuneLite guide price is unavailable.
  - Collects completed GE buy offers to bank.
  - Opens the bank afterward and verifies every fixture item is present.

Safety controls:

- `Test buy budget`
  - Default `250000` gp.
  - The buyer computes estimated maximum spend before placing offers.
  - If estimated spend exceeds the budget, it refuses to run.
- Requires all GE offer slots to be empty before buying starts.
  - This avoids collecting or interfering with unrelated player offers.
- Stops if:
  - user toggles `Stop`
  - bank cannot be opened for the initial snapshot
  - GE cannot be opened
  - no GE slot becomes available
  - a buy offer cannot be placed
  - offers do not finish/collect within the timeout
  - final bank verification reports missing fixture items

Fixture category intent:

- `Currency`: Pearl bolt tips, Pearl bolts, Oyster pearl
- `Gear`: Bronze dagger, Leather gloves, Wooden shield
- `Resources`: Potato seed, Oak logs, Bones
- `Consumables`: Attack potion(1), Apple pie, Waterskin(4)
- `Teleports`: Varrock teleport, Falador teleport, Ring of dueling(8)
- `Tools`: Tinderbox, Fishing rod, Secateurs
- `Quest`: Brass key, Ogre coffin key, Muddy key
- `Storage`: Empty sack, Bagged plant 1, Blighted teleport spell sack
- `Misc`: Feather, Bucket, Swamp tar

Verification:

- `./gradlew assemble` succeeded.

Next validation:

- Start from an empty or disposable test bank.
- Ensure the GE offer board is empty.
- Toggle `Buy Test Items`.
- Expected overlay phase: `Buy OK`.
- Expected final state: bank contains each fixture item, with no organizer tab movement performed by the buyer.

## Step 10 Bounded Multi-Category Organizer Test

Date: 2026-06-09

Added a new live test toggle:

- `Test Multi Organize`
  - Momentary boolean toggle.
  - Requires a collapsed bank with no real tabs.
  - Reads a fresh snapshot and planner output.
  - Creates one real tab for each non-empty category in `BankCategory` order.
  - Moves up to `3` stacks per category.
  - Uses the first stack in each category as that category's tab seed.
  - Moves remaining selected category stacks by dragging to that category's real tab icon.

Verification after every drag:

- Bank stack count unchanged.
- Item quantities unchanged.
- New category tab count starts at `1`.
- Existing category tab count increments by one for each moved stack.
- Final real tab count equals the number of category tabs created.

Safety behavior:

- Stops if the user toggles `Stop` or the worker is interrupted.
- Refuses to run if any real bank tab already exists.
- Refuses to continue after any failed drag or verification mismatch.

Purpose:

- This is the next primitive after `Test Partial Organize`.
- It exercises repeated real tab creation and moving to multiple destination tab icons.
- With the 27-item fixture bank, expected output is 9 tabs and 27 moved stacks.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

Next live validation:

- Use `Buy Test Items` or manually create the 27-item test fixture.
- Collapse all real tabs first.
- Toggle `Test Multi Organize`.
- Expected overlay phase: `Multi OK`.
- Expected final state:
  - 9 real category tabs if every fixture category is present
  - 3 stacks per category tab
  - total bank stack count unchanged
  - all item quantities unchanged

## Step 10.1 Live Multi-Category Validation

Date: 2026-06-09

User ran `Test Multi Organize`.

Agent Server verification immediately after:

- `/state loggedIn=true`
- `/bank open=true`
- `/bank count=292`
- Count matches the expected `265` original stacks plus `27` bought fixture stacks.
- All 27 fixture item stacks are present in the bank.

Observed first bank-order groups:

- `Currency`: Abyssal pearls, Tokkul, Mark of grace
- `Gear`: Rat pole, Fishbowl helmet, Pet rock
- `Resources`: Salve shard, Dramen branch, Long bone
- `Consumables`: Ice cooler, Waterskin(2), Blighted super restore(2)
- `Teleports`: Scroll of redirection, Ring of dueling(1), Ring of dueling(5)
- `Tools`: Candle lantern, Damp tinderbox, Ogre bellows
- `Quest`: Black prism, Commorb v2, House keys
- `Storage`: Forestry kit, Plank sack, Emissary hood
- `Misc`: Pigeon cage, Beaver, Clue scroll (beginner)

Outcome:

- Bounded multi-category organizer behavior is validated from `/bank` ordering.
- The run created/moved the expected first three stacks for each category from the full current bank, not just the bought fixture items.
- Stack count safety held at `292`.

Next implementation step:

- Add a full organize mode that:
  - requires a collapsed bank
  - creates every non-empty category tab
  - moves all remaining planned stacks to the correct tab
  - keeps the same after-each-action count/quantity verification
  - obeys `Max actions per run` for bounded rollout

## Step 11 Live Full Organize Implementation

Date: 2026-06-09

Implemented live full organize behind the existing `Organize Bank` toggle.

Control behavior:

- `Dry run = true`
  - `Organize Bank` keeps the existing snapshot/classify/plan-only behavior.
- `Dry run = false`
  - `Organize Bank` runs the live full organizer.

Live full organizer flow:

- Reads an initial snapshot and plan for overlay/log visibility.
- Collapses all existing real tabs highest-to-lowest using the validated collapse primitive.
- Verifies stack count and item quantities after every collapse.
- Re-snapshots the collapsed bank.
- Re-runs the planner from the collapsed layout.
- Creates every non-empty category tab represented in the bounded action plan.
- Moves every planned stack to its category tab, up to `Max actions per run`.
- Verifies after every move:
  - bank stack count unchanged
  - item quantities unchanged
  - destination tab count increments as expected
- Verifies final real tab count and moved-stack count.

Safety controls:

- Full live movement only runs when `Dry run` is off.
- `Stop` cancels the worker.
- `Max actions per run` limits the number of move actions included in the live plan.
- Any failed collapse, failed drag, bank-close issue, interrupt, count mismatch, quantity mismatch, or tab-count mismatch stops the run and reports `Blocked`.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

Next live validation:

- Restart Microbot to load the new sideloaded jar after install.
- Ensure `Max actions per run` is at least the current stack count for a true full run.
- Turn `Dry run` off.
- Toggle `Organize Bank`.
- Expected result for the current bank:
  - plugin collapses the existing 9 test tabs automatically
  - plugin creates category tabs again
  - plugin moves all planned stacks, currently expected around `292`
  - final overlay phase: `Organize OK`
  - `/bank count` remains unchanged

## Step 12 Delta Full Organize

Date: 2026-06-09

Changed live full organize from collapse/rebuild to delta movement.

Planner changes:

- `BankMoveAction` now records:
  - source tab
  - target tab
  - category
  - item ID/name/quantity
- Target tabs are inferred from non-empty categories in `BankCategory` order.
- Stacks already in their target tab are classified but skipped from move actions.
- `Max actions per run` now limits delta move actions, not total bank stacks.

Snapshot fix:

- Corrected bank tab inference for tabbed banks.
- Real-tab items occupy the first bank item-container slots, followed by main-tab items.
- This matters for delta planning because item `tab` must reflect the current real location.

Actuator changes:

- Added `openTab(tabIndex)` primitive.
- Added source-aware drags:
  - `dragItemFromTabToNewTab(itemId, sourceTab)`
  - `dragItemFromTabToExistingTab(itemId, sourceTab, targetTab)`
- Delta full organize:
  - does not collapse existing tabs
  - plans from the current bank state
  - skips already-correct items
  - opens the source tab for each planned item
  - drags the item to its target category tab
  - appends a missing target tab only when that tab is exactly the next appendable real tab
  - blocks on tab gaps or unsafe layouts

Safety checks retained:

- Stack count unchanged after every move.
- Item quantities unchanged after every move.
- Destination tab count increases during each drag primitive.
- Source tab count decreases when moving out of a real source tab.
- `Stop` interrupts the worker.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

Next live validation:

- Restart Microbot after install.
- Leave the existing organized tabs in place.
- Turn `Dry run` off.
- Toggle `Organize Bank`.
- Expected behavior:
  - no collapse at start
  - only wrong-tab stacks move
  - if already organized, overlay reports `Organize OK` with `0` moved stacks
  - `/bank count` remains unchanged

## Step 13 RuneLite Bank Tag Layout CSV Support

Date: 2026-06-09

Added optional user-defined layout support.

New config:

- `Use bank tag layouts`
  - When enabled, `Organize Bank` ignores the heuristic category planner.
  - Pasted layout CSVs become the source of truth for target real tabs.
  - Items not listed in any configured layout are left alone.
- `Layout tab 1` through `Layout tab 9`
  - Each accepts RuneLite bank tags layout CSV like:
    - `banktags,1,Gathering,1511,layout,0,1511,1,1521,...`

Parser behavior:

- Reads the tab name from token 3 (`Gathering` in the example).
- Reads the icon item ID from token 4 (`1511` in the example).
- Finds the `layout` section.
- Reads slot/item pairs after `layout`.
- Keeps only ordered item IDs, sorted by layout slot.
- Ignores invalid slot/item pairs.

Planner behavior:

- Builds a mapping from listed item ID to target real tab index.
- First occurrence of an item ID wins if duplicate IDs appear across layout tabs.
- Each current bank stack is checked against the layout map.
- If the stack is listed and already in its target tab, it is skipped.
- If listed and in the wrong tab, a delta move is planned.
- If unlisted, it is left alone.
- `Max actions per run` caps layout delta moves.

Actuator behavior:

- Does not collapse tabs.
- Opens each planned item's source tab.
- Drags the item to its configured target tab.
- Appends a missing layout tab only when the missing tab is exactly the next appendable real tab.
- Blocks on unsafe tab gaps.

Current limitation:

- The CSV order is parsed and retained, but physical in-tab slot ordering is not implemented yet.
- This step only uses the CSV to decide target real tabs.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

Next live validation:

- Paste one RuneLite layout CSV into `Layout tab 1`.
- Enable `Use bank tag layouts`.
- Keep `Dry run` on and toggle `Organize Bank`.
- Confirm the overlay planned move count looks sane.
- Then turn `Dry run` off and run a small bounded test with `Max actions per run`.

## Step 13.1 Layout Mode Unlisted Item Cleanup

Date: 2026-06-09

Updated bank tag layout mode cleanup behavior.

Rule:

- If an item is listed in any configured layout tab:
  - it belongs to that layout tab
  - if it is already there, skip it
  - if it is elsewhere, move it to that target tab
- If an item is not listed in any configured layout tab:
  - if it is already in the main tab, leave it alone
  - if it is in any real tab, move it out to the main tab

Implementation:

- `BankTagLayoutPlanner` now creates target-main actions for unlisted stacks that currently live in real tabs.
- `BankTagLayoutPlan` now tracks:
  - matched listed stacks
  - total unlisted stacks
  - unlisted tabbed stacks that should be ejected to main
- `BankActuator` added `dragItemFromTabToMainTab(itemId, sourceTab)`.
- Layout execution moves target-main actions before configured-tab actions.

Verification:

- `./gradlew assemble` succeeded.

## Step 15 Debug Cleanup

Date: 2026-06-09

Removed Bank Organizer logging/debug scaffolding.

Changes:

- Removed `Slf4j` annotations/imports from `BankOrganizerPlugin` and `BankActuator`.
- Removed dry-run/layout report builders that only wrote diagnostic logs.
- Removed live organizer success/failure log calls.
- Removed the stale `dry` plugin tag.
- Updated the bank-open error message so it applies to both dry runs and live runs.

Verification:

- Searched `plugins/bankorganizer` for logger/debug/test scaffolding; no production code matches remain.
- `./gradlew assemble installPlugins` succeeded.

## Step 16 Lifecycle-Driven Production Run

Date: 2026-06-09

Simplified Bank Organizer to run from plugin lifecycle only.

Removed config controls:

- `Organize Bank`
- `Stop`
- `Dry run`
- `Max actions per run`
- `Excluded item IDs`
- `Excluded item names`

Current behavior:

- Enabling the plugin immediately starts one live organize worker.
- The worker opens the bank before taking its first snapshot.
- Disabling the plugin interrupts the worker and removes the overlay.
- Heuristic mode plans every stack mismatch with no action cap and no exclusions.
- Bank tag layout mode plans every listed mismatch plus every unlisted tabbed stack that should move back to main, with no action cap.

Remaining config controls:

- `Show overlay`
- `Use bank tag layouts`
- `Layout tab 1` through `Layout tab 9`

Verification:

- `./gradlew assemble installPlugins` succeeded.

## Step 17 Default Bank Tag Layout Mode And File Cleanup

Date: 2026-06-09

Made bank tag layout mode the only organizer mode.

Changes:

- Removed the `Use bank tag layouts` config toggle.
- Enabling the plugin now always parses configured layout tabs and runs the layout delta organizer.
- `Layout tab 1` now defaults to the Gathering RuneLite bank tags layout CSV so users can see the expected format.
- Removed the unused heuristic/category organizer path.

Removed unnecessary files:

- `BankCategory.java`
- `BankClassifier.java`
- `BankMoveAction.java`
- `BankPlan.java`
- `BankPlanner.java`
- `ClassifiedBankItem.java`

Remaining config controls:

- `Show overlay`
- `Layout tab 1` through `Layout tab 9`

Verification:

- Searched `plugins/bankorganizer` for stale heuristic/category and `useBankTagLayouts` references; none remain.
- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.

## Step 18 Layout Conflict Preflight

Date: 2026-06-09

Added a hard preflight check for item IDs that appear in more than one configured layout tab.

Behavior:

- The plugin parses all configured layout tabs before opening the bank.
- If any item ID appears in multiple layout tabs, the organizer blocks immediately and performs no bank actions.
- The overlay shows conflict rows such as `Item ID 1511 -> tabs 1, 3`.
- The user must disable the plugin, fix the duplicated item IDs in config, then enable the plugin again.

Implementation:

- Added `BankTagLayoutConflict`.
- Added `BankTagLayoutPlanner.conflicts(...)`.
- Added `OverlayState.fromConflicts(...)`.
- Updated overlay detail rows so conflict rows render even before a bank snapshot exists.

Verification:

- `./gradlew assemble` succeeded.
- `./gradlew installPlugins` succeeded.
