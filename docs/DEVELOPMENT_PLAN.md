# JustBans — Development Plan

> Network-wide punishment system for a Velocity + Paper 26.2 network, built on
> MineAcademy Foundation for everything on the Paper side, with MariaDB as the
> source of truth and Redis Pub/Sub for real-time messaging (in-game ⇄ proxy ⇄ web panel).

Status of the repo when this was written: single Maven module generated from the Foundation
plugin template (`pl.kingdomcraft.justbans.JustBans extends SimplePlugin`, Foundation `6.10.2`,
`java.version` = `1.8`). Foundation class names in this document refer to the **Foundation 6.x
API** pinned in `pom.xml`.

---

## Table of contents

1. [Guiding decisions](#1-guiding-decisions)
2. [System architecture overview](#2-system-architecture-overview)
3. [Database schema](#3-database-schema)
4. [Redis protocol](#4-redis-protocol)
5. [Predefined reasons and screens](#5-predefined-reasons-and-screens)
6. [Commands and permissions](#6-commands-and-permissions)
7. [Implementation roadmap](#7-implementation-roadmap)
8. [Foundation library highlights](#8-foundation-library-highlights)
9. [Testing strategy](#9-testing-strategy)
10. [Risks, open decisions and future work](#10-risks-open-decisions-and-future-work)

---

## 1. Guiding decisions

These are the decisions everything else depends on. Each one is explained in the section
where it matters.

| # | Decision | Why |
|---|----------|-----|
| D1 | **Three Maven modules**: `justbans-common`, `justbans-velocity`, `justbans-paper`. | The brief asks for a Velocity module and a Paper module. Both have to write the same tables and speak the same Redis protocol, so that code goes in one platform-free module that both of them shade. If it were written twice, the two copies would drift apart. |
| D2 | **One write path**: every punishment, from an in-game command, a GUI, the proxy console or the web panel, goes through `PunishmentService` in `common`. | A web command then runs exactly like a typed command: same reason resolution, same validation, same broadcasts, same enforcement. |
| D3 | **The database is the source of truth. Redis only carries notifications.** | Offline punishments and restarts must not lose anything. If Redis goes down, punishments still work. The only effects are slower kicks and events that wait to be delivered. |
| D4 | **Velocity enforces against online players.** When the proxy receives a `PUNISHMENT_CREATED` event, it kicks the player, moves them off a server they are now banned from, and sends the broadcast. | Only the proxy knows where every player is. Paper servers just publish what happened and never have to work out where the target is. |
| D5 | **Paper/Foundation owns the staff UX** (commands, tab completion, menus, localization). Velocity only registers a small admin command for the proxy console. | This follows the "Foundation first" directive and avoids `/ban` being registered on both the proxy and the backend. |
| D6 | **Transactional outbox for events.** Each event row is written in the same DB transaction as the punishment, then published to Redis by a background thread. | If Redis is briefly down, no event is lost, and the web backend can replay missed events by id. |
| D7 | **Web commands are typed actions, never raw console commands.** The plugin never calls `dispatchCommand` with text received over Redis. | This rules out command injection and makes HMAC-signed, validated JSON the only way in. |
| D8 | **MariaDB (≥ 10.11 LTS) is the primary target. SQL stays portable enough for PostgreSQL.** | Both have a native `UUID` type. Supporting a second database fully doubles the test matrix, so PostgreSQL support is a follow-up (see §10). |
| D9 | **Java 21 bytecode** (`<release>21</release>`) for all modules. | Velocity 3.4+ needs Java 21, and Paper 26.x runs on a newer JDK that also runs Java 21 bytecode. The current `1.8` setting has to go. |

---

## 2. System architecture overview

### 2.1 Component diagram

```
                       ┌──────────────────────────────┐
                       │      Web Panel (later)       │
                       │  backend reads DB directly,  │
                       │  writes ONLY via Redis cmds  │
                       └───────┬──────────────▲───────┘
            justbans:commands  │              │ justbans:events
            (signed JSON)      │              │ justbans:command-results
                               ▼              │
┌─────────────────────────────────────────────┴───────────────────────────────┐
│                              Redis (Pub/Sub + keys)                          │
│  channels: justbans:events | justbans:commands | justbans:command-results    │
│  keys:     justbans:online (hash) | justbans:node:<id> (heartbeat, TTL)      │
└───────▲──────────────┬──────────────────────────────────▲──────────┬─────────┘
        │ publish      │ subscribe                        │ publish  │ subscribe
┌───────┴──────────────▼─────────────┐        ┌───────────┴──────────▼──────────┐
│        justbans-velocity           │        │   justbans-paper (×N servers)   │
│  • LoginEvent: network-ban gate    │        │   Foundation SimplePlugin       │
│  • ServerPreConnect: server-ban    │        │  • /ban /tempban /warn /kick …  │
│    gate + initial-server fallback  │        │  • GUI: /punish, /history       │
│  • Enforcer: kick / redirect /     │        │  • Local pre-login gate         │
│    broadcast / staff notify        │        │    (defence in depth)           │
│  • Tracks players → jb_players,    │        │  • Reason catalog from DB       │
│    justbans:online                 │        │  • PlaceholderAPI expansion     │
│  • Owns reasons.yml → jb_reasons   │        │                                 │
│  • Web command consumer            │        │                                 │
│  ┌──────────────────────────────┐  │        │  ┌───────────────────────────┐  │
│  │      justbans-common         │  │        │  │      justbans-common      │  │
│  │ PunishmentService, repos,    │  │        │  │ (same shaded library)     │  │
│  │ RedisBus, Outbox, Resolver   │  │        │  └───────────────────────────┘  │
│  └──────────────────────────────┘  │        │                                 │
└───────────────┬────────────────────┘        └──────────────┬──────────────────┘
                │ JDBC (HikariCP)                            │ JDBC (HikariCP)
                ▼                                            ▼
        ┌──────────────────────────────────────────────────────────────┐
        │                     MariaDB  (source of truth)               │
        │ jb_players · jb_player_names · jb_punishments · jb_events    │
        │ jb_reasons · jb_servers · jb_command_log · jb_schema_version │
        └──────────────────────────────────────────────────────────────┘
```

### 2.2 Who does what

| Concern | Velocity | Paper (Foundation) | Common |
|---|---|---|---|
| Network ban check on join | **Yes**: `LoginEvent`, UUID already known | Fallback only (`AsyncPlayerPreLoginEvent`) | `PunishmentRepository.findActiveBans` |
| Server-specific ban check | **Yes**: `ServerPreConnectEvent`, `PlayerChooseInitialServerEvent` | Fallback only, for its own server id | same |
| Staff commands / tab completion | Admin/console only (`/jbproxy`) | **Yes** (Foundation `SimpleCommand`) | `PunishmentService` |
| GUIs | No | **Yes** (Foundation `Menu`) | read models |
| Kicking/redirecting online players | **Yes** (reacts to events) | Only through Velocity | event payloads |
| Broadcasts and staff alerts | **Yes** (one place, so no duplicates) | No (optional local alert toggle) | `TemplateRenderer` |
| Player tracking (name ⇄ UUID) | **Yes**: writes `jb_players` | Reads only | `PlayerRepository`, `PlayerResolver` |
| Reason definitions | **Owner** of `reasons.yml`, syncs to `jb_reasons` | Reads `jb_reasons` | `ReasonDefinition` |
| Web commands | Consumer (default on) | Consumer (default off) | `WebCommandHandler` |
| Schema migrations | Runs them under a DB lock | Runs them under a DB lock | `Migrator` |

### 2.3 Key flows

**A. Staff runs `/tempban Notch cheats` on server `survival` while Notch is offline**

1. Foundation `SimpleCommand` parses the arguments on the main thread, then hands off with
   `Common.runAsync`.
2. `PlayerResolver` turns `Notch` into a UUID. It tries, in order: online on this server,
   then `jb_players` (by lower-case name, most recent `last_seen`), then the Mojang API
   (online-mode networks only), then an offline-mode UUID (v3) if configured. The result is
   cached.
3. `PunishmentService.issue(request)` then:
   - looks up reason `cheats` and gets its type, duration (or the escalation ladder) and text snapshot;
   - opens a transaction and runs `SELECT … FOR UPDATE` on the `jb_players` row, which
     serializes concurrent punishments for the same target;
   - checks for an existing active ban in that scope and refuses it unless `-f` (override) is given;
   - `INSERT jb_punishments` + `INSERT jb_events(PUNISHMENT_CREATED)` → `COMMIT`.
4. `OutboxPublisher` publishes the event to `justbans:events` and sets `published_at`.
5. Velocity receives it. Notch is offline, so nothing is enforced. The broadcast and staff
   alert still go out, unless the punishment is silent (`-s`).
6. The web backend receives the same event and updates its state.
7. Next time Notch connects, the proxy's `LoginEvent` finds the ban and denies the login
   with the rendered multi-line ban screen.

**B. Server-specific ban while the target is online on that server**

Velocity receives `PUNISHMENT_CREATED{scope: SERVER, target_server: "skyblock"}` and sees the
player is on `skyblock`. It calls `createConnectionRequest(fallback)` and sends the
server-ban message. If no fallback server is reachable, it disconnects the player with the
ban screen. From then on, `ServerPreConnectEvent` to `skyblock` is denied with a chat
message, and the player stays where they are.

**C. Web panel issues a ban**

1. The web backend publishes a signed `ISSUE_PUNISHMENT` command to `justbans:commands`.
2. Every node with `web-commands.enabled` checks the HMAC and the timestamp, then tries
   `INSERT INTO jb_command_log(request_id, …)`. The primary key guarantees exactly one
   node wins. The others drop the message.
3. The winning node builds a `PunishmentRequest` with `actor = WEB(<panel user id>, <name>)`
   and calls the same `PunishmentService.issue(...)` as flow A.
4. It publishes `justbans:command-results {request_id, status: OK, punishment_id}`, and
   from there flow A steps 4–7 happen as normal.

**D. Redis is down**

Commands still write to the DB. Event rows pile up unpublished in `jb_events`, and the
publisher retries with backoff. Bans still apply on join because Velocity checks the DB at
login. Only instant kicks of online players are delayed. Velocity also polls `jb_events`
every 5 s while Redis is unavailable (see §7, Phase 2.6).

---

## 3. Database schema

MariaDB syntax is shown. PostgreSQL differences are listed after each table. Times are
stored in UTC, millisecond precision, and written by the application using `Instant.now()`.
Run NTP on all hosts.

### 3.1 `jb_schema_version`

```sql
CREATE TABLE jb_schema_version (
    version     INT          NOT NULL PRIMARY KEY,
    description VARCHAR(200) NOT NULL,
    applied_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
```

Migrations are plain SQL files `common/src/main/resources/db/<dialect>/V{n}__{desc}.sql`,
applied by a small `Migrator` inside `GET_LOCK('justbans_migrate', 30)` (PostgreSQL:
`pg_advisory_lock`). Whichever node starts first runs them. A node whose code is older than
the DB schema refuses to start.

### 3.2 `jb_players`: offline support starts here

```sql
CREATE TABLE jb_players (
    uuid            UUID         NOT NULL PRIMARY KEY,
    last_name       VARCHAR(16)  NOT NULL,
    last_name_lower VARCHAR(16)  NOT NULL,
    first_seen      TIMESTAMP(3) NOT NULL,
    last_seen       TIMESTAMP(3) NOT NULL,
    last_server     VARCHAR(64)  NULL,
    last_ip         VARBINARY(16) NULL,          -- optional, see privacy note
    INDEX idx_players_name (last_name_lower, last_seen)
);
```

- Velocity upserts a row on every `PostLoginEvent` and sets `last_seen` on `DisconnectEvent`.
- When a punishment is issued against a UUID that has never joined (from the Mojang API or
  a raw UUID), a placeholder row is inserted. The `FOR UPDATE` lock in flow A needs a row
  to exist.
- Privacy: `last_ip` is off by default (`track-ips: false`). If IP bans are added later, it
  is turned on.

### 3.3 `jb_player_names`: name history

```sql
CREATE TABLE jb_player_names (
    uuid       UUID         NOT NULL,
    name       VARCHAR(16)  NOT NULL,
    name_lower VARCHAR(16)  NOT NULL,
    first_seen TIMESTAMP(3) NOT NULL,
    last_seen  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (uuid, name_lower),
    INDEX idx_names_lower (name_lower)
);
```

This lets staff find a player by an old name, and the resolver can warn when two UUIDs have
used the same name: "`Steve` is ambiguous, use the UUID".

### 3.4 `jb_servers`

```sql
CREATE TABLE jb_servers (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,  -- must equal the name in velocity.toml
    group_name     VARCHAR(64)  NULL,                  -- e.g. 'skyblock' for skyblock-1, skyblock-2
    display_name   VARCHAR(64)  NULL,
    last_heartbeat TIMESTAMP(3) NULL
);
```

Velocity upserts all registered servers on startup. Paper refuses to start if its configured
`Server_Name` is not listed here, so a typo cannot silently disable server bans. The web
panel uses this table for its server picker.

### 3.5 `jb_punishments`: the core table

```sql
CREATE TABLE jb_punishments (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    public_id        CHAR(8)      NOT NULL,             -- short code shown on ban screens/appeals, e.g. "K7Q2M9XA"
    type             ENUM('BAN','WARN','KICK') NOT NULL, -- MUTE reserved for the future
    scope_type       ENUM('NETWORK','SERVER','GROUP') NOT NULL DEFAULT 'NETWORK',
    scope_target     VARCHAR(64)  NOT NULL DEFAULT '*',  -- '*' for NETWORK, server id, or group name

    target_uuid      UUID         NOT NULL,
    target_name      VARCHAR(16)  NOT NULL,             -- snapshot at issue time

    actor_type       ENUM('PLAYER','CONSOLE','WEB','SYSTEM') NOT NULL,
    actor_uuid       UUID         NULL,                 -- player UUID, or null for console/system
    actor_ref        VARCHAR(64)  NULL,                 -- web panel user id, for WEB
    actor_name       VARCHAR(64)  NOT NULL,

    reason_id        VARCHAR(32)  NULL,                 -- 'cheats'; NULL for free-text reasons
    reason_text      VARCHAR(512) NOT NULL,             -- snapshot of display text
    note             VARCHAR(512) NULL,                 -- internal staff note, never shown to target

    created_at       TIMESTAMP(3) NOT NULL,
    expires_at       TIMESTAMP(3) NULL,                 -- NULL = permanent (BAN/WARN); KICK always NULL
    origin_server    VARCHAR(64)  NULL,                 -- where it was issued ('proxy', 'web', server id)
    silent           BOOLEAN      NOT NULL DEFAULT FALSE,
    ladder_step      SMALLINT     NULL,                 -- escalation step used, if any

    revoked_at       TIMESTAMP(3) NULL,                 -- pardon / unwarn
    revoked_by_type  ENUM('PLAYER','CONSOLE','WEB','SYSTEM') NULL,
    revoked_by_uuid  UUID         NULL,
    revoked_by_name  VARCHAR(64)  NULL,
    revoke_reason    VARCHAR(512) NULL,

    request_id       UUID         NULL,                 -- idempotency key for web commands

    UNIQUE KEY uq_public_id (public_id),
    UNIQUE KEY uq_request_id (request_id),
    INDEX idx_target_active (target_uuid, type, revoked_at, expires_at),
    INDEX idx_scope (scope_type, scope_target),
    INDEX idx_created (created_at),
    INDEX idx_actor (actor_uuid)
);
```

Modelling rules:

| Requirement | How it is represented |
|---|---|
| Network permban | `type=BAN, scope_type=NETWORK, expires_at=NULL` |
| Network tempban | `type=BAN, scope_type=NETWORK, expires_at=<t>` |
| Server permban/tempban | `type=BAN, scope_type=SERVER, scope_target='skyblock'` |
| Group ban (optional) | `scope_type=GROUP, scope_target='skyblock'` covers every server in that group |
| Warn | `type=WARN`. `expires_at` is set only when warn decay is configured |
| Kick | `type=KICK`. Never active. Stored so it shows in history and on the web panel |
| Pardon / unwarn | soft revoke: fill the `revoked_*` columns. Rows are never deleted |

**"Active" is calculated when queried, never stored:**

```sql
revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)
```

This means a tempban never needs a scheduled job to "unban" it. An optional sweeper only
emits `PUNISHMENT_EXPIRED` events for the web panel (Phase 6).

**Join-time query** (one indexed lookup per login):

```sql
SELECT * FROM jb_punishments
WHERE target_uuid = ? AND type = 'BAN' AND revoked_at IS NULL
  AND (expires_at IS NULL OR expires_at > ?);
```

Velocity caches the result per online player, so server switches need no DB call. The
cache is invalidated by events.

**One active ban per scope** is enforced in `PunishmentService` while holding the
`jb_players` row lock. MariaDB has no partial unique indexes, and because expiry is
calculated, a DB constraint could not express this rule anyway.

PostgreSQL notes: use `uuid`, `timestamptz`, `smallint`, `CHECK`/enum types instead of
`ENUM(...)`, `BIGSERIAL`, and `ON CONFLICT` instead of `ON DUPLICATE KEY` (hidden behind a
`SqlDialect` interface in `common`).

### 3.6 `jb_events`: audit log and transactional outbox

```sql
CREATE TABLE jb_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_type    VARCHAR(40)  NOT NULL,   -- PUNISHMENT_CREATED, PUNISHMENT_REVOKED, PUNISHMENT_UPDATED,
                                           -- PUNISHMENT_EXPIRED, PLAYER_KICKED, JOIN_DENIED, REASONS_UPDATED
    punishment_id BIGINT       NULL,
    payload       JSON         NOT NULL,   -- exactly what is published to Redis
    origin_node   VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL,
    published_at  TIMESTAMP(3) NULL,
    INDEX idx_unpublished (published_at, id),
    INDEX idx_punishment (punishment_id)
);
```

- The event row is written in the same transaction as the change. `OutboxPublisher` on each
  node publishes its own rows (`WHERE origin_node = ? AND published_at IS NULL ORDER BY id`).
- The `id` increases steadily and is included in the Redis envelope. If the web backend
  sees a gap, or restarts, it reads `SELECT … WHERE id > :lastSeen` straight from the DB.
  That makes Pub/Sub's at-most-once delivery safe to rely on.
- `JOIN_DENIED` (a banned player tried to join) is high-volume. It is rate-limited per UUID
  and can be switched off.
- Retention: optional cleanup of events older than N days. Punishment rows themselves are
  never deleted.

### 3.7 `jb_reasons`: catalog the web panel and Paper both read

```sql
CREATE TABLE jb_reasons (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,   -- 'cheats'
    display_name    VARCHAR(128) NOT NULL,
    allowed_types   VARCHAR(128) NOT NULL,               -- 'BAN,TEMPBAN,SERVER_BAN,WARN,KICK'
    definition      JSON         NOT NULL,               -- full definition: ladder, durations, templates, icon
    sort_order      INT          NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMP(3) NOT NULL
);
```

Velocity loads `reasons.yml`, upserts every reason, disables the ones that were removed
(rows are kept so history still makes sense), and emits `REASONS_UPDATED`. See §5 for why
Velocity owns this file.

### 3.8 `jb_command_log`: exactly-once handling of web commands

```sql
CREATE TABLE jb_command_log (
    request_id    UUID         NOT NULL PRIMARY KEY,
    action        VARCHAR(40)  NOT NULL,
    received_at   TIMESTAMP(3) NOT NULL,
    handled_by    VARCHAR(64)  NOT NULL,                 -- node id that claimed it
    status        ENUM('PROCESSING','OK','ERROR') NOT NULL,
    result        JSON         NULL,
    INDEX idx_received (received_at)
);
```

Inserting the row claims the command. If the insert fails with a duplicate key, another node
already has it. A web retry with the same `request_id` gets the stored result back instead of
running twice.

### 3.9 Entity overview

```
jb_players 1───* jb_player_names
     │
     └──1───* jb_punishments *───0..1 jb_reasons   (by reason_id, soft reference)
                    │
                    └──1───* jb_events
jb_servers  (scope_target references id or group_name, soft reference)
jb_command_log  (request_id ↔ jb_punishments.request_id)
```

Foreign keys: `jb_player_names.uuid → jb_players.uuid` and `jb_events.punishment_id →
jb_punishments.id` are real FKs. `reason_id` and `scope_target` are deliberately soft
references, so a removed reason or a decommissioned server never breaks history.

---

## 4. Redis protocol

The authoritative protocol spec is `docs/redis-protocol.md` (written in Phase 5), with JSON
Schemas in `docs/schemas/` and a reference client in `tools/web-client.py`. That is what the
web team builds against; the draft below is kept for context and may lag behind it.

### 4.1 Channels and keys

| Name | Type | Direction | Purpose |
|---|---|---|---|
| `justbans:events` | Pub/Sub | plugin → all | Punishment lifecycle events, cache invalidation, reasons reload |
| `justbans:commands` | Pub/Sub | web → plugin | Signed action requests |
| `justbans:command-results` | Pub/Sub | plugin → web | Result per `request_id` |
| `justbans:online` | Hash | Velocity writes | `uuid → {"name","server"}`. Used for network-wide tab completion on Paper and by the web panel's "online now" view |
| `justbans:node:<nodeId>` | String, TTL 30 s | each node | Heartbeat: `{type, version, startedAt}` |

The prefix `justbans:` can be changed in config, so several networks can share one Redis.

### 4.2 Envelope (every message)

```json
{
  "v": 1,
  "id": "8d3c1c9e-…",          // message UUID
  "type": "PUNISHMENT_CREATED",
  "ts": 1760430000123,          // epoch millis
  "node": "velocity-1",
  "seq": 18842,                 // jb_events.id (events only) — gap detection / replay
  "payload": { … },
  "sig": "base64(HMAC-SHA256(secret, v|id|type|ts|node|seq|canonical(payload)))"  // seq is empty when absent
}
```

### 4.3 Event payload, e.g. `PUNISHMENT_CREATED`

```json
{
  "punishment": {
    "id": 4211, "publicId": "K7Q2M9XA", "type": "BAN",
    "scope": { "type": "SERVER", "target": "skyblock" },
    "target": { "uuid": "069a79f4-…", "name": "Notch" },
    "actor":  { "type": "PLAYER", "uuid": "…", "name": "Admin" },
    "reason": { "id": "cheats", "text": "Cheating / hacked client" },
    "createdAt": 1760430000000, "expiresAt": 1763022000000,
    "silent": false, "originServer": "survival"
  }
}
```

The payload is a full snapshot, so consumers never have to query the DB to react.

### 4.4 Commands (web → plugin)

| `type` | Payload |
|---|---|
| `ISSUE_PUNISHMENT` | `requestId, actor{ref,name}, target{uuid?, name?}, kind (BAN/TEMPBAN/WARN/KICK), scope{type,target}, reasonId?, customReason?, duration? ("7d"), silent, note?, force` |
| `REVOKE_PUNISHMENT` | `requestId, actor, punishmentId` or `{target, kind, scope}`, `reason?` |
| `UPDATE_PUNISHMENT` | `requestId, actor, punishmentId, expiresAt?, reasonText?, note?` *(Phase 6)* |
| `RELOAD_REASONS` | `requestId, actor` (handled by Velocity only) |
| `PING` | `requestId`, a health check that replies with the node list |

Result:

```json
{ "requestId": "…", "status": "OK", "punishmentId": 4211, "publicId": "K7Q2M9XA" }
{ "requestId": "…", "status": "ERROR", "error": "ALREADY_BANNED", "message": "Notch already has an active network ban (#K1…)" }
```

Error codes form a stable enum shared with in-game messages: `UNKNOWN_PLAYER`,
`UNKNOWN_REASON`, `REASON_TYPE_NOT_ALLOWED`, `ALREADY_BANNED`, `NOT_BANNED`,
`TARGET_EXEMPT`, `INVALID_DURATION`, `UNKNOWN_SERVER`, `BAD_SIGNATURE`, `EXPIRED_REQUEST`,
`INTERNAL`.

### 4.5 Security rules

- Redis requires AUTH, with an ACL user per role (the plugin user and the web user, each
  limited to the `justbans:*` channels and keys). Use TLS (`rediss://`) whenever Redis is
  not on a private network.
- Every command must carry a valid HMAC from the shared `web-secret`. Commands with
  `|now − ts| > 30 s` are rejected, which stops replays together with the `jb_command_log`
  primary key.
- Payloads are size-capped (16 KB) and strictly parsed: unknown `type` values are rejected
  and every field is validated as untrusted input.
- The plugin also signs its outgoing events, so the web backend can verify them.

---

## 5. Predefined reasons and screens

### 5.1 Where the definitions live

Almost every screen is rendered on the proxy: the login ban screen, the server-ban
message, kick screens and network broadcasts. Paper only needs the catalog of reason ids,
allowed types, default durations and GUI icons. The web panel needs the same catalog for its
dropdowns. So:

- **`reasons.yml` lives on Velocity** and is loaded with Configurate into the common
  `ReasonDefinition` record.
- Velocity syncs it to `jb_reasons` and publishes `REASONS_UPDATED`.
- **Paper reads `jb_reasons`** at startup and on that event, and keeps an immutable
  in-memory catalog that its Foundation commands and menus use.

The alternative was a Foundation-loaded copy of `reasons.yml` on every Paper server. That
means N copies of the file drifting apart, and the screens are rendered on Velocity anyway.
The one thing it bought was using Foundation's `ConfigItems` for reasons. Paper-only files
(GUI layout, staff messages) still use Foundation config classes (§8).

### 5.2 `reasons.yml` format (MiniMessage, rendered by Velocity's Adventure)

```yaml
defaults:
  templates:
    ban-screen:
      - "<red><bold>You are banned from KingdomCraft</bold></red>"
      - ""
      - "<gray>Reason: <white><reason></white>"
      - "<gray>Banned by: <white><actor></white>"
      - "<gray>Expires: <white><expires></white> <dark_gray>(<remaining>)</dark_gray>"
      - ""
      - "<gray>Appeal at <aqua>discord.gg/kingdomcraft</aqua> with ID <yellow>#<id></yellow>"
    server-ban-message:
      - "<red>You are banned from <white><server></white> until <white><expires></white>: <reason>"
    kick-screen:
      - "<red>You were kicked: <white><reason></white>"
    warn-message:
      - "<gold>⚠ You have been warned: <white><reason></white> <gray>(<warn_count>/3)"
    broadcast:
      - "<red><target></red> <gray>was <type_past> by <red><actor></red> <gray>for <white><reason></white><gray>.</gray>"
    staff-alert:
      - "<dark_gray>[JB]</dark_gray> <gray><actor> → <target>: <type> <scope> <duration> (<reason>) <click:run_command:'/history <target>'><aqua>[history]</aqua></click>"

reasons:
  cheats:
    display: "Cheating / hacked client"
    types: [TEMPBAN, BAN, SERVER_TEMPBAN, SERVER_BAN]
    duration: 30d                     # default when type is TEMPBAN and no duration is given
    ladder: [7d, 30d, perm]           # optional: Nth offence with this reason_id picks step N
    icon: DIAMOND_SWORD               # used by the Paper GUI
    templates:                        # optional per-reason overrides of any default template
      ban-screen:
        - "<dark_red><bold>Cheating detected</bold></dark_red>"
        - "<gray>Reason: <white><reason></white>  <gray>Expires: <white><expires></white>"
        - "<gray>Appeal ID: <yellow>#<id></yellow>"
  spam:
    display: "Spamming chat"
    types: [WARN, KICK, TEMPBAN]
    duration: 1h
    icon: PAPER
  toxicity:
    display: "Toxic behaviour"
    types: [WARN, TEMPBAN]
    ladder: [WARN, WARN, 1d, 7d]      # ladder steps may also change the type

warn-escalation:                      # optional, applied after each WARN
  - { active-warns: 3, action: TEMPBAN, duration: 1d,  reason: "Reached 3 warnings" }
  - { active-warns: 5, action: BAN,                    reason: "Reached 5 warnings" }

custom-reasons:
  allowed: true                       # free text when the first word is not a known id
  permission: justbans.reason.custom
```

Placeholders: `<target>`, `<actor>`, `<reason>`, `<id>` (public id), `<type>`,
`<type_past>`, `<scope>`, `<server>`, `<created>`, `<expires>` (or "never"), `<remaining>`,
`<duration>`, `<warn_count>`. They are passed to MiniMessage as `TagResolver`s, never by
string concatenation, so a reason string cannot inject tags.

Paper renders reason-based screens only in its fallback pre-login check (§7 Phase 3.6).
`jb_reasons.definition` already holds the template JSON, and Paper uses the same Adventure
MiniMessage API, so it renders identical output.

---

## 6. Commands and permissions

All of these are Foundation `SimpleCommand`s on Paper, unless marked as proxy. Flags:
`-s` silent (no public broadcast), `-f` force (replace an existing active ban).
`<player>` accepts a current name, a past name, or a raw UUID.

| Command | Description | Permission |
|---|---|---|
| `/ban <player> <reason…> [-s] [-f]` | Network permban (or ladder step) | `justbans.ban` |
| `/tempban <player> <reason> [duration] [-s] [-f]` | Network tempban; duration defaults to the reason's | `justbans.tempban` |
| `/serverban <player> <server\|group> <reason…>` | Server-specific permban | `justbans.serverban` |
| `/servertempban <player> <server\|group> <reason> [duration]` | Server-specific tempban | `justbans.servertempban` |
| `/warn <player> <reason…> [-s]` | Warn (escalation may follow) | `justbans.warn` |
| `/kick <player> <reason…> [-s]` | Network kick (target must be online on the network) | `justbans.kick` |
| `/unban <player> [server\|group] [note…]` | Pardon; aliases `/pardon` | `justbans.unban` |
| `/unwarn <player> <id\|last> [note…]` | Revoke a warn | `justbans.unwarn` |
| `/punish <player>` | Opens the punishment GUI | `justbans.gui` |
| `/history <player>` | Opens the history GUI (paged); text output for console | `justbans.history` |
| `/check <player>` | Shows active bans and warn count | `justbans.check` |
| `/justbans <reload\|lookup <id>\|perms\|debug>` | Admin group | `justbans.admin` |
| `/jbproxy <ban\|unban\|lookup\|reload> …` (proxy) | Proxy-console admin | `justbans.proxy.admin` |

Extra permissions: `justbans.reason.custom`, `justbans.duration.unlimited` (otherwise capped by
`justbans.duration.max.<n>` tiers), `justbans.notify` (staff alerts, checked on Velocity),
`justbans.silent.view`, `justbans.exempt` (cannot be punished; for offline targets this is
checked through the LuckPerms API with an offline user load).

---

## 7. Implementation roadmap

Each phase ends with something that runs and can be tested.

### Phase 0: Project restructuring and dev environment (≈ 1–2 days)

1. **Convert to a multi-module reactor**
   ```
   JustBans/
   ├── pom.xml                      (parent, packaging=pom, dependencyManagement, Java 21)
   ├── justbans-common/             (no Bukkit/Velocity deps)
   ├── justbans-velocity/           (velocity-api provided; shades common + deps)
   └── justbans-paper/              (paper-api provided + Foundation; shades common + deps)
   ```
   Move the existing `src/`, `templates/` and `build.xml` into `justbans-paper/`.
2. **Fix template gotchas while moving:**
   - Change `java.version` from `1.8` to `<maven.compiler.release>21</maven.compiler.release>`.
   - The shade relocation `${project.groupId}.${project.artifactId}.lib` would become
     `pl.kingdomcraft.justbans-paper.lib`, which is not a valid package. Hard-code it to
     `pl.kingdomcraft.justbans.lib`.
   - Keep `<name>JustBans</name>` in the paper module, because `plugin.yml` resource
     filtering uses `${project.name}`.
   - Uncomment `paper-api` (the 26.2 build from `repo.papermc.io`) and put it above
     Foundation, as the template comment says, so Paper/Adventure APIs win over the Spigot
     API that Foundation bundles.
   - Add the shaded third-party deps to the `<artifactSet><includes>` (HikariCP, MariaDB
     driver, Jedis, commons-pool2, Caffeine, Gson if not provided) and relocate each under
     `pl.kingdomcraft.justbans.libs.*`. On Paper, `libraries:` in `plugin.yml` could replace
     shading, but shading keeps both modules identical and works offline.
   - Delete the template's demo code (the cow-explosion `@EventHandler`) and
     `messages_tr.yml`. Add `messages_pl.yml` next to `messages_en.yml`.
   - Rename `Command_Aliases` in `settings.yml` from `[plugintemplate, template, plt]` to
     `[justbans, jb]`.
3. **Common dependencies:** HikariCP 6.x, MariaDB Connector/J 3.x, Jedis 5.x, Caffeine 3.x,
   Gson. On Velocity, Configurate YAML 4 (shaded and relocated).
4. **`docker-compose.yml` dev stack** in `dev/`: MariaDB 11.4, Redis 7 (with ACL file),
   Velocity, and two Paper 26.2 servers (`lobby` and `survival`) with modern forwarding.
   Plugin jars are mounted from `*/target`.
5. **CI** (GitHub Actions): `mvn -B verify` on push and PR, Testcontainers enabled, plugin
   jars uploaded as artifacts.
6. Confirm that Foundation 6.10.2 starts cleanly on Paper 26.2 (`Remain`/`CompMaterial` are
   the usual break points on new MC versions). If it does not, bump Foundation first.

**Done when:** `mvn verify` builds three jars, and both plugins boot (doing nothing) in the
docker stack.

### Phase 1: Common core: domain, database, Redis (≈ 5–7 days)

1. **Domain model** (immutable records): `Punishment`, `PunishmentType`, `Scope`,
   `Actor` (`PLAYER/CONSOLE/WEB/SYSTEM`), `PlayerRecord`, `ReasonDefinition`, `Ladder`,
   `PunishmentRequest`, `RevokeRequest`, `Result<T>` with the shared `ErrorCode` enum (§4.4).
2. **`DurationParser`**: `30d`, `1d12h`, `90m`, `perm`/`permanent`. Rejects zero, negative
   and absurd values (> 10 y unless `perm`). Has a pure `format(Duration)`. Unit-tested
   heavily. Both platforms use it, so `30d` means the same thing everywhere.
3. **`Database`**: a HikariCP pool from a `DatabaseConfig` record, a `SqlDialect`
   (MariaDB first), and an async executor (a bounded fixed pool, so the Paper main thread
   or the Velocity netty threads never touch JDBC).
4. **`Migrator`** with `V1__init.sql` containing every table from §3, run under the
   advisory lock.
5. **Repositories** (hand-written JDBC, no ORM): `PlayerRepository` (upsert, findByName,
   findByUuid, nameHistory), `PunishmentRepository` (insert, findActiveBans, findActiveWarns,
   history paged, revoke, lookupByPublicId), `EventRepository` (append, unpublished,
   markPublished, since(id)), `ReasonRepository`, `ServerRepository`, `CommandLogRepository`.
6. **`PlayerResolver`**: name/UUID resolution as in flow A. The Mojang client uses
   `java.net.http.HttpClient` with timeouts and a Caffeine cache, plus an offline-mode UUID
   v3 strategy controlled by `uuid-mode: online|offline`.
7. **`PunishmentService`**, the single write path (D2):
   - `issue(PunishmentRequest)`: resolve the reason, apply the ladder, check exemption
     (through a platform-provided `ExemptionChecker` interface), lock the target, check for
     duplicates, insert the punishment and the event, commit, and return `Result<Punishment>`.
   - `revoke(RevokeRequest)`: pardon or unwarn, plus a `PUNISHMENT_REVOKED` event.
   - `afterWarn` hook → `warn-escalation` rules issue a follow-up punishment with
     actor `SYSTEM`.
   - Every method returns a `CompletableFuture`. None of them touch platform APIs.
8. **Messaging**: `RedisBus` (a `JedisPool` for publishing, plus one dedicated subscriber
   thread with a reconnect loop and exponential backoff), the `Envelope` codec (Gson),
   `HmacSigner`, `Channels`, and `OutboxPublisher` (drains `jb_events` on commit and every
   2 s as a safety net).
9. **`WebCommandHandler`**: verify signature and time → claim the row in `jb_command_log` →
   map to `PunishmentService` → publish the result.
10. **`TemplateRenderer`**: placeholders → MiniMessage `TagResolver`s, plus a
    `RemainingTimeFormatter`.

**Done when:** Testcontainers tests (MariaDB + Redis) cover issuing a punishment against an
offline UUID, both sides of a duplicate-ban race, expiry being calculated correctly, pardon,
ladder progression, outbox delivery after a Redis restart, and a duplicate web command
running only once.

### Phase 2: Velocity module (≈ 4–6 days)

1. **Bootstrap**: `@Plugin(id = "justbans")` main class. Guice injects `ProxyServer`,
   `Logger` and `@DataDirectory`. `config.yml` (DB, Redis, node id, policies) and
   `reasons.yml` are loaded with Configurate. Then: start the pool, run migrations, start
   the Redis subscriber, upsert `jb_servers` from `proxy.getAllServers()`, and start the
   heartbeat.
2. **Player tracking**: `PostLoginEvent` → upsert `jb_players` and `jb_player_names`, and
   `HSET justbans:online`. `ServerPostConnectEvent` → update the hash.
   `DisconnectEvent` → `last_seen` and `HDEL`. On startup the hash is rebuilt from the
   current player list.
3. **Login gate**: `LoginEvent`, returning an `EventTask.async(...)` so the netty thread is
   not blocked. It loads the active bans for the UUID into `ActiveBanCache` (Caffeine,
   keyed by UUID). An active NETWORK ban → `ComponentResult.denied(renderedBanScreen)` plus
   a `JOIN_DENIED` event (rate-limited).
   - **Failure policy**: `on-database-error: allow | deny` (default `allow`, with loud
     logging and a staff alert). This is a deliberate operator choice (§10).
4. **Server-ban gate**:
   - `ServerPreConnectEvent`: if the cache has an active ban whose scope matches the
     target server or its group, use `ServerResult.denied()` and send the server-ban
     message. The player stays where they are.
   - `PlayerChooseInitialServerEvent`: if the default initial server is banned for this
     player, pick the first non-banned server from `try` in `velocity.toml`. If there is
     none, disconnect with the screen.
   - `KickedFromServerEvent`: if a backend kicked the player because of a JustBans
     server ban (Paper fallback), use `RedirectPlayer` to send them to a fallback with the
     message instead of disconnecting them.
5. **`Enforcer`** (subscribes to `justbans:events`):
   - `PUNISHMENT_CREATED` BAN NETWORK → `player.disconnect(screen)` if online.
   - BAN SERVER/GROUP → redirect if they are currently on an affected server.
   - KICK → disconnect. WARN → send the warn message, and optionally a title or sound.
   - Unless silent: broadcast to all players. Staff alert to holders of `justbans.notify`
     (Velocity permission checks go through LuckPerms-Velocity).
   - `PUNISHMENT_REVOKED` / `PUNISHMENT_UPDATED` → invalidate the cache entry.
   - Messages this node published itself are processed too. Enforcement does not care who
     published.
6. **Redis-down mode**: while the subscriber is disconnected, poll
   `EventRepository.since(lastSeenId)` every 5 s, so enforcement keeps working with only a
   short delay.
7. **Reasons**: load `reasons.yml` → validate (unknown types, bad durations, template
   parse errors fail loudly with the line number) → sync to `jb_reasons` →
   `REASONS_UPDATED`. Available through `/jbproxy reload` and the `RELOAD_REASONS` web
   command.
8. **Web command consumer**: `WebCommandHandler` with `web-commands.enabled: true`.
9. **`/jbproxy`** using a Velocity `BrigadierCommand`: `ban`, `unban`, `lookup`, `reload`.
   These are for console use when no backend is up. They call the same service.

**Done when:** in the docker stack, a ban inserted directly through a service call blocks
login. A server ban keeps the player out of `survival` while `lobby` still works. A ban event
kicks an online player within about 100 ms. Killing Redis still lets bans apply (with a
delay of up to 5 s).

### Phase 3: Paper module on Foundation (≈ 5–7 days)

1. **`JustBans extends SimplePlugin`**
   - `onPluginStart()`: build `Database`, `RedisBus` and the services from `Settings`. Start
     them asynchronously. Check that `Settings.Server.NAME` exists in `jb_servers`.
   - `onReloadablesStart()`: reload the reason catalog from `jb_reasons`, and re-subscribe
     handlers. Foundation calls this again on `/justbans reload`.
   - `onPluginPreReload()` / `onPluginStop()`: close the Redis subscriber and the Hikari
     pool, as the template comment already suggests.
2. **`Settings extends SimpleSettings`** with nested static classes loaded by Foundation:
   `Server` (`Name`, `Group`), `Database` (`Host`, `Port`, `Name`, `User`, `Password`,
   `Pool_Size`), `Redis` (`Uri`, `Prefix`, `Web_Secret`), `Web_Commands` (`Enabled`),
   `Punishments` (`Default_Silent`, `Local_Alerts`), `Menu` (titles, sizes). Keep
   `getConfigVersion()` in sync with the `Version:` key.
3. **`Localization extends SimpleLocalization`** → `localization/messages_en.yml` and
   `messages_pl.yml`. These hold staff-facing feedback only ("Banned {target} (#{id})",
   errors keyed by `ErrorCode`). Player-facing screens come from `reasons.yml`.
4. **`ReasonCatalog`**: an immutable snapshot from `jb_reasons`, swapped atomically on
   `REASONS_UPDATED`. It feeds tab completion and the GUI.
5. **Commands** (all `@AutoRegister`, `final`, with a no-arg constructor):
   - `abstract PunishmentCommand extends SimpleCommand` holds the shared logic: flag
     parsing (`-s`, `-f`), target resolution off the main thread with `Common.runAsync`,
     reason/duration parsing, building `Actor.player(sender)` or `Actor.console()`,
     mapping `Result` to `tellSuccess`/`tellError` from `Localization`, and `tabComplete()`
     (names from `justbans:online` plus recent `jb_players`, reason ids allowed for this
     command, server ids from `jb_servers`, and duration suggestions).
   - Concrete classes: `BanCommand`, `TempBanCommand`, `ServerBanCommand`,
     `ServerTempBanCommand`, `WarnCommand`, `KickCommand`, `UnbanCommand`, `UnwarnCommand`,
     `CheckCommand`, `HistoryCommand`, `PunishCommand`.
   - `JustBansCommandGroup extends SimpleCommandGroup` with `ReloadCommand`,
     `PermsCommand`, `DebugCommand` (Foundation built-ins) and a custom `LookupSubCommand`.
   - `setCooldown` on punishment commands, to prevent double clicks or double submits.
6. **Local fallback gate**: a listener on `AsyncPlayerPreLoginEvent` (already async, so a
   blocking JDBC call is fine here) that denies when there is an active NETWORK ban or a ban
   for this server or its group. Turn it on with `Settings.Punishments.Local_Gate` (default
   true). It covers a proxy-side failure or misconfiguration.
7. **Bukkit API events** for other plugins: `PunishmentIssuedEvent` and
   `PunishmentRevokedEvent` extend Foundation `SimpleEvent`. They are fired on the main
   thread when this server sees the Redis event.
8. **`ExemptionChecker`** implementation: online players are checked with
   `hasPermission`. Offline players go through the LuckPerms API (`HookManager` for
   detection, and `UserManager.loadUser` async).

**Done when:** every command in §6 works against online and offline (never-joined-this-server)
targets, tab completion shows network-online names, `/justbans reload` reloads cleanly, and
the console can run every command.

### Phase 4: GUIs (≈ 3–4 days)

1. **`PunishMenu extends Menu`** (`/punish <player>`): a header with the target's head
   (`ItemCreator` skull), active ban and warn count, then one button per reason
   (`ItemCreator.of(CompMaterial.fromString(icon))`) filtered by the staff member's
   permissions.
2. **`ReasonActionMenu`**: pick the type (network/server ban, tempban, warn, kick) and
   then the duration. Use `MenuQuantitable`-style ± buttons, or presets from the ladder.
   Server selection goes through a `MenuPagged<ServerRecord>`.
3. **Confirmation**: a summary item plus a confirm button. There is an optional "add
   note" `ButtonConversation` → `SimplePrompt` for free-text notes. On confirm it calls
   `PunishmentService`, then uses `animateTitle("&aBan issued")` and closes.
4. **`HistoryMenu extends MenuPagged<Punishment>`**: colour-coded items (active, expired,
   revoked, kick). Clicking a punishment opens `PunishmentDetailMenu` with Revoke (needs
   permission, plus a confirm) and Copy ID (a `SimpleComponent` chat link) buttons.
   `ButtonReturnBack` is used throughout.
5. Menu titles, items and lore live in `menus.yml` (Foundation `YamlConfig`), so the GUI
   can be translated or reskinned without code changes.

**Done when:** a staff member can do everything from §6 except reload through the GUI, and
menu pages load asynchronously without freezing the main thread (data is fetched with
`Common.runAsync`, and the menu is opened with `Common.runLater(0, …)`).

### Phase 5: Web panel readiness (≈ 2–3 days)

The plugin side is functionally complete after Phase 2/3. This phase freezes the contract.

1. Write `docs/redis-protocol.md`: channels, envelope, every event and command type, error
   codes, signing algorithm with a test vector, and replay via `jb_events`.
2. Publish JSON Schemas for each message in `docs/schemas/` and validate the plugin's own
   output against them in tests.
3. Add a small reference client in `tools/` (a Python or Node script) that signs and sends
   commands and prints events. It serves as an executable example for the web team and as
   a smoke test.
4. Document read-only DB access for the web backend: a dedicated DB user with `SELECT` on
   `jb_*`, and the rule that it **never writes**. All writes go through commands, so
   events, broadcasts and enforcement always happen.
5. Add a `PING` command and expose node heartbeats.

**Done when:** the reference client can ban, pardon, warn and kick a player and gets
matching results and events. Replaying from a stored `seq` returns exactly the missed events.

### Phase 6: Hardening and release (≈ 3–5 days)

1. Permissions: `justbans.duration.max.*` tiers, exemption through LuckPerms weight
   ("cannot punish someone with a higher weight"), per-staff rate limits.
2. `UPDATE_PUNISHMENT` (edit duration/reason) in game (`/justbans edit <id>`) and over web.
3. Expiry sweeper: a single node, elected with a Redis `SET NX` lock, emits
   `PUNISHMENT_EXPIRED` events.
4. PlaceholderAPI `SimpleExpansion`: `%justbans_warns%`, `%justbans_banned_servers%`,
   `%justbans_last_punishment%`.
5. Metrics and debug: Foundation `Debugger` sections (`database`, `redis`, `enforce`), a
   `LagCatcher` around DB calls on Paper, a `/justbans debug` dump, and bStats on both
   platforms.
6. Load test: 500 simulated logins per minute against the login gate (p99 < 20 ms DB time),
   and 1 000 events per second through Redis.
7. Documentation: install guide (DB/Redis setup, ACLs, `Server_Name` must match
   `velocity.toml`, enable modern forwarding), config reference, permission list.
8. Tag `v1.0.0` and attach the release jars from CI.

**Status of Phase 6** (kept here so the plan stays honest):

| # | Item | State |
|---|---|---|
| 1 | Duration tiers, LuckPerms weight rule, per-staff rate limits | Weights exist (`Use_Weights`); duration tiers and rate limits **not done** |
| 2 | `UPDATE_PUNISHMENT` in game and over web | Done (`PunishmentService.updateBlocking`, `/justbans edit`, web command, `PUNISHMENT_UPDATED`) |
| 3 | Expiry sweeper with Redis `SET NX` election | Done (`ExpirySweeper`, migration V2, `PUNISHMENT_EXPIRED`) |
| 4 | PlaceholderAPI expansion | Done (`%justbans_warns%`, `_banned_servers%`, `_last_punishment%`, `_active_ban_expiry%`), cache-only |
| 5 | Debug sections, `LagCatcher`, bStats | Done on Paper (`database`, `redis`, `enforce`, `sweeper`; main-thread DB call guard); bStats on both platforms, **inert until the bStats ids are filled in**. No `/justbans debug` dump yet. |
| 6 | Load test | **Not done** |
| 7 | Documentation | `INSTALL.md` and `docs/redis-protocol.md` done; a full config reference is not written |
| 8 | Tag `v1.0.0`, CI release jars | **Not done** |

### Rough total: 4–5 weeks for one developer.

---

## 8. Foundation library highlights

These are Paper-side tasks, with the Foundation 6.x component that handles each one.
Nothing in this list should be written by hand.

| Task | Foundation component | Notes |
|---|---|---|
| Plugin lifecycle and reload | `SimplePlugin` (`onPluginStart`, `onReloadablesStart`, `onPluginPreReload`, `onPluginStop`) | The reload hooks are where the Redis subscriber and Hikari pool are closed and reopened |
| Auto registration | `@AutoRegister` | Commands, command group, listeners, `SimpleExpansion`. No manual `getCommand()` |
| Main config | `SimpleSettings` | Static nested classes (`Database`, `Redis`, `Server`, …), auto-loaded and auto-updated from the default file |
| Localization | `SimpleLocalization` + `localization/messages_<lang>.yml` | Staff messages in EN/PL, selected by `Locale:` in settings |
| Extra config files | `YamlConfig` | `menus.yml` (GUI layout) |
| Commands | `SimpleCommand` | `checkArgs`, `checkPerm`, `checkConsole`, `checkBoolean`, `findPlayer`, `tellSuccess/Error`, `returnTell`, `setCooldown`, `setUsage`, `setDescription`, `tabComplete()` + `completeLastWord(...)` |
| Command group | `SimpleCommandGroup`, `SimpleSubCommand` | `/justbans …` |
| Built-in admin subcommands | `ReloadCommand`, `PermsCommand`, `DebugCommand` | `PermsCommand` lists the permissions declared on a `Permissions` class with `@Permission`/`@PermissionGroup` annotations, so the permission list documents itself |
| Async ⇄ main thread | `Common.runAsync`, `Common.runLater` | DB/Redis work off-thread, Bukkit API calls back on main |
| Chat output | `Messenger` (`success/error/info/warn`), `Common.tell`, `SimpleComponent` | Clickable IDs and history links in staff messages |
| Paginated console/chat output | `ChatPaginator` | `/history` for console and non-GUI users |
| Variables in messages | `Variables` / `Replacer` | `{target}`, `{id}`, … in localization strings |
| Time display | `TimeUtil` (formatting) | Parsing uses the common `DurationParser` so proxy and backend agree |
| GUIs | `Menu`, `MenuPagged<T>`, `MenuQuantitable`, `Button`, `ButtonMenu`, `ButtonReturnBack`, `ButtonConversation`, `ItemCreator`, `CompMaterial`, `Menu#animateTitle` | All of Phase 4 |
| Chat input | `SimplePrompt` / `SimpleConversation` | Free-text notes and custom reasons from the GUI |
| Custom Bukkit events | `SimpleEvent` | `PunishmentIssuedEvent`, `PunishmentRevokedEvent` |
| PlaceholderAPI | `SimpleExpansion` | `%justbans_*%` |
| Plugin hooks | `HookManager` | Detect LuckPerms/PlaceholderAPI before using them |
| Version-safe API | `Remain` | Online players, titles, sounds across versions |
| Validation and errors | `Valid`, `FoException`, `Common.error` | Config validation with readable stack traces |
| Diagnostics | `Debugger` (sections from `Debug: [...]`), `LagCatcher` (`Log_Lag_Over_Milis`) | Already wired through the template's `settings.yml` |

**Where Foundation is deliberately not used, and why:**

| Concern | Not used | Instead | Reason |
|---|---|---|---|
| Punishment storage | `SimpleDatabase` | `justbans-common` JDBC + HikariCP | The same repository code has to run on Velocity, where Foundation 6 does not exist. With two DB layers for one schema, the copies would drift (D1) |
| Proxy messaging | `BungeeListener` / plugin messaging channel | Redis | Plugin messaging needs a player on the server to carry the message, cannot reach the web panel, and loses messages on empty servers |
| Reason definitions | `ConfigItems` | `jb_reasons` (Velocity-owned) | One source shared by proxy, backends and web (§5.1) |
| Duration parsing | `TimeUtil.toTicks` / `SimpleTime` | common `DurationParser` | Must parse identically on Velocity |

If a later Foundation release ships a Velocity platform module, look at it again. It could
let the proxy commands and configs use the same patterns. Until then, the Velocity module
uses the plain Velocity API.

---

## 9. Testing strategy

| Layer | Tooling | What |
|---|---|---|
| Unit (common) | JUnit 5, AssertJ | `DurationParser`, ladder selection, scope matching, template rendering, HMAC vectors, envelope codec |
| Integration (common) | Testcontainers (MariaDB, Redis) | Repositories, migrations, `PunishmentService` concurrency (two threads banning the same target), outbox after Redis restart, exactly-once web commands |
| Paper | MockBukkit (if it supports the Paper 26.x API by then) or the docker stack | Command parsing and tab completion; Foundation menus are checked manually |
| Velocity | Unit tests with a mocked `ProxyServer` for the enforcer and gates | Decision logic split out from event classes so it can be tested in isolation |
| End-to-end | `dev/docker-compose.yml` + the `tools/` reference client | Script: ban offline → join denied; server ban → redirect; web pardon → join allowed |

---

## 10. Risks, open decisions and future work

**Decisions for the team to confirm**

1. **DB failure policy at login**: `allow` (default, favours availability) or `deny`
   (favours safety on anti-cheat-heavy networks).
2. **PostgreSQL**: ship MariaDB-only for v1 (recommended), or test both from the start.
3. **Velocity also registering `/ban` etc.** (for staff on servers without the Paper
   module): off by default. The Paper module is the intended UX.
4. **IP bans and alt detection**: out of scope for v1. The schema is ready for it
   (`jb_players.last_ip`, `jb_punishments` gets a `target_ip` column in a future migration).
   It needs a privacy and GDPR decision.

**Known risks**

| Risk | Mitigation |
|---|---|
| Foundation 6.10.2 not fully compatible with Paper 26.2 | Checked in Phase 0. If it breaks, bump Foundation before writing any feature code |
| Pub/Sub messages lost during a disconnect | Outbox plus `seq` replay from `jb_events` (D6); Velocity polling fallback (Phase 2.6) |
| Two proxies (future) both enforcing | Enforcement is idempotent: kicking or redirecting twice does nothing extra. Web commands are claimed in `jb_command_log` |
| Name ⇄ UUID ambiguity for offline targets | Name history table, "ambiguous" error, raw UUID accepted everywhere |
| Clock skew between nodes | NTP required. Expiry uses app time consistently; documented |
| Misconfigured `Server_Name` | Paper refuses to start if the name is not in `jb_servers` |

**Future features the schema already allows**: mutes (`type=MUTE` plus a Paper chat
listener), IP bans, appeals (`public_id` already printed on screens), punishment edits
history (`jb_events`), Discord webhooks (one more `justbans:events` subscriber), and
importers from LiteBans/AdvancedBan into `jb_punishments`.
