# Installing JustBans

JustBans has two jars that talk through one **MariaDB** database (the source of truth) and one **Redis** (instant notifications and the web panel):

| Jar | Goes on | Does |
|---|---|---|
| `JustBans-Velocity-<version>.jar` | the Velocity proxy | login and server gates, enforcement, reasons from `reasons.yml` |
| `JustBans-Paper-<version>.jar` | every Paper server | `/ban /tempban /warn /kick /unban /check /punish /history /justbans`, menus, placeholders |

Requirements: **Java 25**, **Velocity 3.x**, **Paper 26.x**, **MariaDB 10.7+** (it needs the native `UUID` type), **Redis 6+**. Optional: LuckPerms (permissions, offline exemptions), PlaceholderAPI.

Build the jars with `mvn package` (they are in `justbans-velocity/target/` and `justbans-paper/target/`).

## 1. One shared secret

Every Redis message is signed with a secret that must be **identical** on the proxy, on every Paper server and in the web panel. Make one and keep it private (at least 16 characters, 32+ recommended):

```sh
openssl rand -base64 36
```

A node with a different secret does not crash, it just logs `invalid signature` warnings and ignores the other nodes.

## 2. MariaDB

```sql
CREATE DATABASE justbans CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'justbans'@'10.0.0.%' IDENTIFIED BY '<long random password>';
GRANT ALL PRIVILEGES ON justbans.* TO 'justbans'@'10.0.0.%';
```

The plugin creates and upgrades its own tables (`jb_*`) on startup, so the user needs full rights on this one schema. Use the same database for the proxy and all Paper servers, and a host pattern that matches only your servers.

The **web panel must not use this account.** Give it a separate read-only user and let it change data only through Redis. See [docs/redis-protocol.md](docs/redis-protocol.md#2-database-access-read-only) for the exact `GRANT SELECT` statements.

## 3. Redis

Set a password, or better one ACL user per role, and do not expose Redis to the internet (use TLS, `rediss://`, if it is not on a private network). The `justbans:` prefix is configurable, so several networks can share a server.

```
# The plugins (proxy and Paper servers):
ACL SETUSER justbans-plugin on >PLUGIN_PASSWORD resetchannels &justbans:* ~justbans:* -@all +@pubsub +set +hset +hget +hdel +hgetall +multi +exec +del +ping +auth

# The web panel (only if you have one):
ACL SETUSER justbans-web on >WEB_PASSWORD resetchannels &justbans:events &justbans:command-results &justbans:commands ~justbans:online -@all +subscribe +unsubscribe +publish +hget +hgetall +ping +auth
```

`+set` is used for the lock that elects which node announces expired punishments. If Redis is down, punishments and bans keep working (the database decides); only instant notifications pause.

## 4. Velocity (the proxy)

1. Copy `JustBans-Velocity-<version>.jar` to `plugins/` and start the proxy once. It writes `plugins/justbans/config.yml` and `reasons.yml` and then stops itself with a configuration error until you fill in the secret.
2. Edit `plugins/justbans/config.yml`:
   - `node-id`: unique per proxy (default `velocity-1`); it must differ from every Paper node.
   - `web-secret`: the secret from step 1.
   - `database:` host, port, name, user, password from step 2.
   - `redis:` host, port, and `user` / `password` if you set them in step 3.
   - `server-groups:` and `fallback-servers:` if you use group bans or want banned players redirected.
3. Edit `reasons.yml` to define your reasons, then restart the proxy (later changes: `/jbproxy reload`).
4. Make sure the proxy forwards real player data to the servers: in `velocity.toml` set `player-info-forwarding-mode = "modern"` and put the contents of `forwarding.secret` into each Paper server (below).

On Velocity, permissions come from a permission plugin such as LuckPerms: `justbans.notify` (staff alerts), `justbans.exempt` (cannot be punished), `justbans.proxy.admin` (`/jbproxy`).

## 5. Paper (every game server)

1. Copy `JustBans-Paper-<version>.jar` to `plugins/` and start the server once to create `plugins/JustBans/settings.yml`.
2. For modern forwarding, in `config/paper-global.yml` set `proxies.velocity.enabled: true`, `online-mode: true`, and `secret` to the proxy's `forwarding.secret`.
3. Edit `plugins/JustBans/settings.yml`:

   ```yaml
   Server:
     Name: "survival"      # MUST be this server's name in the proxy's velocity.toml, spelled the same
     Group: ""             # optional: the same group as in the proxy's server-groups
   Database:
     Host: "10.0.0.5"
     Name: "justbans"
     User: "justbans"
     Password: "..."
   Redis:
     Host: "10.0.0.6"
     Password: "..."
     Web_Secret: "..."     # the secret from step 1
   ```

   **`Server.Name` must match the proxy's `[servers]` name exactly** (for example `survival` in `[servers] survival = "10.0.0.10:25565"`). Server bans are stored and matched by that name (also by this server's own login check), so a mismatch means bans for this server are not recognised here. The console warns at startup if the name is not one the proxy registered.
4. Restart the server (later changes: `/justbans reload`).

## 6. Check that it works

- The consoles print `Ready: node ...` (Paper) and `JustBans started as node ...` (Velocity), with no `NOT running` lines.
- Ban and pardon a test player with `/ban Name cheats` and `/unban Name`. Keep the player online to see the instant kick.
- If you have the web panel, run the smoke test from any machine that can reach Redis:

  ```sh
  pip install redis
  export JUSTBANS_REDIS_URL='redis://:PASSWORD@10.0.0.6:6379/0' JUSTBANS_SECRET='<the secret>'
  python3 tools/web-client.py ping
  ```

  It prints a pong from the node that answered. A timeout almost always means a wrong secret, wrong `redis.prefix`, or `web-commands.enabled: false` on the proxy.

## 7. Permissions (Paper)

| Permission | Allows |
|---|---|
| `justbans.ban`, `justbans.tempban`, `justbans.warn`, `justbans.kick` | the matching commands |
| `justbans.unban`, `justbans.unwarn` | pardon and remove a warn |
| `justbans.check`, `justbans.history` | `/check`, `/history` |
| `justbans.gui` | the `/punish` menu |
| `justbans.edit` | `/justbans edit <id> <duration\|reason\|note> <value>` |
| `justbans.reason.custom` | free-text reasons instead of configured ones |
| `justbans.exempt` | cannot be punished |
| `justbans.admin` | `/justbans reload` |

## 8. PlaceholderAPI

With PlaceholderAPI installed the following work in scoreboards, tab lists and chat formats, and never wait for the database:

| Placeholder | Value |
|---|---|
| `%justbans_warns%` | number of the player's active warns |
| `%justbans_banned_servers%` | servers or groups the player is banned from |
| `%justbans_last_punishment%` | the most recent punishment, formatted by `Placeholders.Last_Punishment_Format` |
| `%justbans_active_ban_expiry%` | time left on the soonest-ending active ban, or `permanent` |

## 9. Troubleshooting

- **Turn on debug output** with `Debug: [database, redis]` in `settings.yml` (sections: `database`, `redis`, `enforce`, `sweeper`; `["*"]` for all) and `/justbans reload`. `Log_Lag_Over_Milis` reports slow operations.
- **Nothing happens when banning on Paper**: look for `JustBans could not start` in the console, usually a wrong database or Redis password.
- **Bans do not kick instantly**: the proxy and the Paper server use different Redis servers, databases or `Prefix` values, or the secrets differ.
- **`Failed to validate connection ... closed connection` (HikariCP) or `Read timed out` from Redis in the log**: a firewall or the database/Redis server dropped a quiet connection. JustBans pings idle database connections (`Database.Keepalive_Millis` / `database.keepalive-ms`, default 60 s) and replaces them after `Max_Lifetime_Millis` (default 10 min); keep that below the shortest idle limit between you and the server (MariaDB `wait_timeout`, firewall timeouts) and keep the keepalive at 30000 or more. Redis connections that die are dropped and retried automatically. Other plugins using the same database server show the same warning when the limit is shorter than their own pool settings.
- **Optional statistics**: JustBans can send anonymous usage statistics to [bStats](https://bstats.org); turn it off with `Statistics.Enabled: false` (Paper) and `metrics: false` (Velocity), or globally in bStats' own config. Nothing is sent until the plugin's bStats id is set in the build.
