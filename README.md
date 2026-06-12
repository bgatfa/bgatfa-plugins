A collection of plugins I've developed for RuneLite. Most useful plugin being the Hotswap plugin.

### Vorkath

Ranged Vorkath farmer. Prayer-flicks Protect from Missiles, keeps antifire up, and reacts to
each special in threat-priority order every tick: eats/restores first, dodges the deadly
firebomb, Crumble-Undeads the Zombified Spawn (swapping to a Slayer's staff so the cast lands),
and walks the acid-free path while attacking when a clean tile keeps Vorkath in range. Loots a
configurable wildcard/regex whitelist, tops the kit back up at the bank, and re-enters the
instance on a loop. All in-fight movement is computed relative to the player's live tile and the
acid objects on the floor, so it is instance-coordinate safe.
