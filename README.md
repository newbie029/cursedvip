# CursedVIP

Time-based VIP ranks for Spigot/Paper. Sell or give VIP with one-time keys, let players stack several VIP tiers, and let the plugin move players back to their default group when time runs out.

- Minecraft **1.13 – latest** (Spigot, Paper). Folia is not supported.
- Java **8 – 25**.
- Storage: `data.yml` or MySQL/MariaDB (shared between servers).

## Requirements

- [Vault](https://www.spigotmc.org/resources/vault.34315/)
- A permission plugin with Vault support, e.g. LuckPerms
- Optional: [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)

## Install

1. Put the CursedVIP jar (and Vault) in `plugins/` and start the server.
2. Edit `plugins/CursedVIP/config.yml`:
   - `vip_groups`: your VIP groups, in queue order. **Each group must exist in your permission plugin.** The console warns at startup about groups that do not.
   - `default_group`: the group players fall back to.
   - `MySQL`: set `enable: true` and the connection details to share VIP data between servers.
3. Give players `cursedvip.user` (see [Permissions](#permissions)). It defaults to OP only, so normal players cannot use `/usekey` until you grant it:
   ```
   /lp group default permission set cursedvip.user true
   ```
4. Restart the server (switching storage needs a restart; other settings work with `/cursedvip reload`).

## How VIP time works

- A player can own several VIP groups. One is **active** and its time runs (also while offline). The others wait in a **queue** and their time is frozen.
- When the active VIP ends, the next queued group (in `vip_groups` order) starts. When none is left, the player goes back to `default_group`.
- `/changevip <group>` switches the active VIP; the remaining time of the old one goes back to the queue.
- With `restore_vip_on_join: true` (default), every login puts the player back in their active VIP group, even if an admin changed their groups by hand.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/usekey <key>` | Redeem a VIP key | `cursedvip.usekey` |
| `/viptime` | Show your VIPs and time left | `cursedvip.viptime` |
| `/changevip <group>` | Switch your active VIP | `cursedvip.changevip` |
| `/cursedvip help` | Show commands | `cursedvip.help` |
| `/cursedvip newkey <group> <days>` | Create a key | `cursedvip.newkey` |
| `/cursedvip keys` | List unused keys | `cursedvip.keys` |
| `/cursedvip delkey <key>` | Delete a key | `cursedvip.delkey` |
| `/cursedvip delkeys` | Delete all keys | `cursedvip.delkeys` |
| `/cursedvip give <player> <group> <days>` | Give VIP to an online player | `cursedvip.givevip` |
| `/cursedvip add <group> <days>` | Add days to everyone who owns a group | `cursedvip.addvip` |
| `/cursedvip remove <player> [group]` | Remove all VIPs, or one group | `cursedvip.removevip` |
| `/cursedvip changedays <player> <group> <days>` | Set the days left of a group | `cursedvip.changedays` |
| `/cursedvip info <player>` | Show a player's VIPs | `cursedvip.info` |
| `/cursedvip resetcooldown <player>` | Reset the `/changevip` cooldown | `cursedvip.resetcooldown` |
| `/cursedvip reload` | Reload config and language | `cursedvip.reload` |

`/cursedvip` aliases: `/cv`, `/vips`, `/vip`. Player names must be typed in full.

## Permissions

| Permission | Gives |
|---|---|
| `cursedvip.user` | `usekey`, `viptime`, `changevip`, `help` |
| `cursedvip.admin` | Every command, plus `cursedvip.changebypass` |
| `cursedvip.changebypass` | Ignore the `/changevip` cooldown |

All permissions default to OP.

## Placeholders

| Placeholder | Example |
|---|---|
| `%CursedVIP_is_vip%` | `Yes` / `No` |
| `%CursedVIP_active_vip%` | `VIP2` / `Not VIP` |
| `%CursedVIP_days_left%` | `12` (rounded up) |
| `%CursedVIP_time_left%` | `12 days and 3 hour(s)` |
| `%CursedVIP_expires%` | `24/11/2026 18:30` (`date_format`) |

## Upgrading from 1.x

Your data is converted automatically on the first start, nothing is reset.

1. Stop **every** server that uses CursedVIP (all servers at once if they share MySQL).
2. Replace the jar and start the server.
3. Check the console for `Migration done: ...`.

Backups: `data-v1-backup.yml` (flatfile). On MySQL the old tables (`cursedvip_vips`, `cursedvip_changes`, `cursedvip_log`) are left untouched; drop them once every server runs 2.x. Going back to 1.x after the migration needs these backups.

If MySQL cannot be reached at startup, CursedVIP disables itself instead of running without your data. Fix the connection and restart.

See [CHANGELOG.md](CHANGELOG.md) for everything that changed.
