# MASTER PROMPT: build the JustBans Web Panel

> **To the agent reading this:** this document is your complete specification. It was written for you by the team that owns the Minecraft plugin
> ("JustBans"). You have **no access** to the plugin's source code or to any Minecraft server, and you will not get any. Everything you need is
> in this file: the database schema, the Redis protocol, the signing algorithm with test vectors, JSON Schemas, signed example messages, a working
> Python reference client, and a task list. If something you need is not in here, do **not** guess and do **not** invent protocol fields: follow
> the "When the contract is silent or seems wrong" rule in [Part 1](#part-1-your-mission-and-the-rules-you-must-not-break).

**Contract status:** the Redis protocol is **version 1 and frozen**. Only additive changes will ever be made without a version bump, so you must ignore
unknown fields and unknown event/command types rather than failing on them.

## Contents

- [Part 1: Your mission and the rules you must not break](#part-1-your-mission-and-the-rules-you-must-not-break)
- [Part 2: Architecture philosophy](#part-2-architecture-philosophy-mariadb-is-for-reading-redis-is-for-writing)
- [Part 3: The database (READ-ONLY)](#part-3-the-database-read-only)
- [Part 4: Redis](#part-4-redis-channels-keys-and-access)
- [Part 5: The Redis protocol, envelope and HMAC signing](#part-5-the-redis-protocol-envelope-and-hmac-signing)
- [Part 6: The Python reference client](#part-6-the-python-reference-client-complete-working-code)
- [Part 7: Task directives: what you must build](#part-7-task-directives-what-you-must-build)
- [Appendix A: JSON Schemas](#appendix-a-json-schemas)
- [Appendix B: Signed example messages (test fixtures)](#appendix-b-signed-example-messages-test-fixtures)
- [Appendix C: Development seed data](#appendix-c-development-seed-data)

---

## Part 1: Your mission and the rules you must not break

### What JustBans is

JustBans is a network-wide punishment system for a Minecraft server network (one Velocity proxy plus several Paper game servers). Staff
ban, tempban, warn, kick and pardon players in game. Your job is to build a **web panel** that lets staff do the same from a browser, and see
everything that happens, in near real time.

### What you are delivering

A complete, production-quality web application (backend + frontend) in a new repository, with tests, Docker-based local development, and a README.
The full feature list and acceptance criteria are in [Part 7](#part-7-task-directives-what-you-must-build). Language and framework are your choice;
**recommended: Python 3.12 + FastAPI** (the reference client in Part 6 is Python and its signing code can be reused as-is) with a lightweight
frontend (server-rendered templates with HTMX, or a small Vite SPA). Whatever you choose, justify it in one paragraph in `README.md`.

### The seven rules

1. **Read from MariaDB, write through Redis. Never the other way around.** The web backend connects to the JustBans database with a **READ-ONLY** account.
   It must not run `INSERT`, `UPDATE`, `DELETE`, DDL or anything else that changes a `jb_*` table. Not even "just this once", not even in tests against the
   production schema (use a scratch database for tests). Every change to a punishment happens by publishing a **signed command** on Redis.
2. **Every message you publish is signed** (HMAC-SHA256, [Part 5](#part-5-the-redis-protocol-envelope-and-hmac-signing)) and every message you receive is verified before you act on it.
3. **The shared secret (`web-secret`) never leaves the backend.** Never send it to the browser, never log it, never put it in an error message, a URL or the frontend bundle.
   The browser never talks to Redis or MariaDB directly; it only talks to your backend.
4. **A retry reuses the `requestId`.** Commands are idempotent per `requestId`. A new `requestId` for a retry of the same user action can create a second punishment.
5. **Treat all text from the database and from Redis as untrusted.** Player names, reasons, notes and actor names are typed by humans. Escape on output; never build HTML, SQL
   or shell strings by concatenation.
6. **You do not decide anything the plugin decides.** The plugin validates reasons, durations, exemptions (players who cannot be punished), duplicate bans and permissions-in-game. You
   may validate input for usability (fast feedback), but the plugin's answer is authoritative: show its error codes faithfully.
7. **Authentication and authorisation of your own users is entirely your responsibility.** The plugin only checks the HMAC; it cannot tell which panel user is behind a command.
   Anyone who can make your backend publish a command can ban any non-exempt player. Build the role model in Part 7 and enforce it **on the server**.

### Facts you cannot verify, so build to the contract

- You cannot run the real plugin. Part 7 therefore requires you to write a **fake plugin simulator** that speaks the same protocol, so that you can test the whole panel end to end.
  It must follow this document exactly. If a test passes against your simulator but your reading of the contract is wrong, the panel will fail in production, so re-read the
  relevant section when writing it.
- The reference client in Part 6 and the test vectors in Part 5.4 are **known good**: they were produced from, and checked against, the plugin's Java implementation (and against a
  Node.js and an OpenSSL implementation). If your signer disagrees with the vectors, **your signer is wrong**.
- Names you will see in this document that belong to the plugin's internals (node ids like `velocity-1`, the `justbans:` key prefix, reason ids like `cheats`) are examples. Real values
  come from configuration and from the database.

### When the contract is silent or seems wrong

- Do not invent fields, channels, command types or database writes.
- Choose the most conservative behaviour (refuse the action, show a clear error), record the question in `CONTRACT_ISSUES.md` in your repository (what you needed, what the contract says,
  what you did instead), and continue with the rest of the work. The owner will answer those in bulk.
- Record other design choices in `DECISIONS.md` (one short entry each).

---

## Part 2: Architecture philosophy: MariaDB is for reading, Redis is for writing

```
                         +----------------------------+
                         |  MariaDB  (SOURCE OF TRUTH)|
                         |  jb_punishments, jb_events |
                         +----^-----------^-----------+
          writes (plugin only)|           | READ-ONLY SELECTs
                              |           |
 +----------------+   +-------+-----+   +-+----------------------+       +-----------+
 | Paper servers  |<->|  JustBans   |   |   YOUR WEB BACKEND     |<----->|  Browser  |
 | + Velocity     |   |  plugins    |   |  (read-only DB access) |       |  (staff)  |
 +-------^--------+   +------^------+   +-----------+------------+       +-----------+
         |                   |                      |
         |         signed commands (web -> plugin)  |
         |        +----------+----------------------+
         |        |  Redis Pub/Sub  justbans:commands          (you publish)
         +--------+                 justbans:command-results   (you subscribe)
                  |                 justbans:events            (you subscribe)
                  |  Redis hash     justbans:online            (you read)
                  +----------------------------------------------------------
```

**Why it is built this way**

1. **MariaDB is the single source of truth.** Every punishment, and every *event* that describes a change, is a row in the database, written by the plugin in one transaction. If Redis
   loses a message, nothing is lost: the database still has the fact and you can catch up from it.
2. **Redis is a nudge and a request channel, not storage.** Pub/Sub is "at most once" per delivery: a subscriber that is down or reconnecting misses messages. So Redis tells you *that something
   happened* (events) and carries *requests to do something* (commands), but whenever you need to be sure, you read the database.
3. **All writes go through the plugin, via signed Redis commands.** When the plugin executes a command it runs the full pipeline exactly as if an admin typed it in game: validation,
   exemption checks, duplicate-ban checks and locking, the database write, the audit event, the public broadcast, kicking or redirecting the player *right now* if they are online, and
   cache invalidation on every server. If the panel wrote the database directly, none of that would happen: the player would stay online, nobody would be told, caches would serve stale
   data for up to a minute, and two admins could create conflicting bans.
4. **Hence the split:** *reads* (history, search, dashboards, catching up on events) = SQL against MariaDB with a read-only account. *Writes* (issue, pardon, edit) = signed commands on Redis.
   *Live data* = events on Redis (with the database as the fallback) and the `justbans:online` hash.
5. **Eventual consistency, by design.** After you publish a command the plugin answers with a `COMMAND_RESULT` (typically within tens of milliseconds) and the new row is already committed when
   that result is published. Reading the database right after an `OK` result sees the change (use the `punishmentId` in the result). Events can arrive slightly before or after the result.

**What a command costs you in correctness terms:** nothing is applied until you receive an `OK` result. A timeout means "unknown outcome", not "failed" (see Part 5, section 6.1).

---
## Part 3: The database (READ-ONLY)

> **The web backend MUST connect to the JustBans database with a READ-ONLY account (`SELECT` only) and MUST NOT write to any `jb_*` table.
> The plugin owns the schema, creates it and migrates it. Do not create, alter or drop anything in that database, and do not run migrations from the panel.**

Why this is non-negotiable: a row written behind the plugin's back skips events (nobody is kicked, no broadcast, caches stay stale), skips validation and locking (duplicate bans,
exempt targets, races), and corrupts the audit trail and the event numbering (`jb_events.id`) that every consumer uses to catch up. Your *own* application data (panel users, sessions, audit log of
panel actions, the `seq` cursor) goes in a **separate** database that you own, e.g. PostgreSQL or SQLite, never in the JustBans one.

### Connection

- MariaDB 10.7 or newer (the schema uses the native `UUID` type). Use a driver that understands it (the `mariadb` Python connector, `asyncmy`, `aiomysql`, Node `mariadb`/`mysql2` all return it as a string such as
  `069a79f4-44e9-4726-a5be-fca90e38aaf5`).
- **All timestamps are UTC.** The plugin stores and reads every `TIMESTAMP(3)` as UTC. Run `SET time_zone = '+00:00'` on every connection, or configure it in the pool, so values come back
  as UTC. Convert to the viewer's time zone only at the edge (in the browser).
- Use a connection pool, short transactions, `READ COMMITTED` or autocommit, and set sensible timeouts. Never hold a connection while talking to Redis.
- Create the read-only account like this (column-level grant keeps player IP addresses out of the web tier; **never select or show `jb_players.last_ip`**):

```sql
CREATE USER 'justbans_web'@'10.0.0.%' IDENTIFIED BY '<long random password>' REQUIRE SSL;

GRANT SELECT ON justbans.jb_punishments  TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_events       TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_reasons      TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_servers      TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_player_names TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_command_log  TO 'justbans_web'@'10.0.0.%';
GRANT SELECT (uuid, last_name, last_name_lower, first_seen, last_seen, last_server)
      ON justbans.jb_players TO 'justbans_web'@'10.0.0.%';
-- Deliberately NOT granted: INSERT/UPDATE/DELETE/DDL anywhere, jb_schema_version, and the last_ip column.
```

On startup your backend must **verify** it really is read-only (for example by running `SHOW GRANTS FOR CURRENT_USER()` and refusing to start, or at least logging a loud warning, if it finds
`INSERT`, `UPDATE`, `DELETE`, `ALL PRIVILEGES`, `ALTER`, `DROP` or `CREATE`). Do not attempt a write to find out.

### The schema (exact, as created by the plugin)

This is the real `SHOW CREATE TABLE` output of the current schema (migrations V1 + V2). Use it to build your queries and to create a **scratch** database for development and tests
(with a privileged account, never the panel's account).

```sql
CREATE TABLE `jb_players` (
  `uuid` uuid NOT NULL,
  `last_name` varchar(16) NOT NULL,
  `last_name_lower` varchar(16) NOT NULL,
  `first_seen` timestamp(3) NOT NULL,
  `last_seen` timestamp(3) NOT NULL,
  `last_server` varchar(64) DEFAULT NULL,
  `last_ip` varbinary(16) DEFAULT NULL,
  PRIMARY KEY (`uuid`),
  KEY `idx_players_name` (`last_name_lower`,`last_seen`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;

CREATE TABLE `jb_player_names` (
  `uuid` uuid NOT NULL,
  `name` varchar(16) NOT NULL,
  `name_lower` varchar(16) NOT NULL,
  `first_seen` timestamp(3) NOT NULL,
  `last_seen` timestamp(3) NOT NULL,
  PRIMARY KEY (`uuid`,`name_lower`),
  KEY `idx_names_lower` (`name_lower`),
  CONSTRAINT `fk_names_player` FOREIGN KEY (`uuid`) REFERENCES `jb_players` (`uuid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;

CREATE TABLE `jb_servers` (
  `id` varchar(64) NOT NULL,
  `group_name` varchar(64) DEFAULT NULL,
  `display_name` varchar(64) DEFAULT NULL,
  `last_heartbeat` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;

CREATE TABLE `jb_punishments` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `public_id` char(8) NOT NULL,
  `type` enum('BAN','WARN','KICK') NOT NULL,
  `scope_type` enum('NETWORK','SERVER','GROUP') NOT NULL DEFAULT 'NETWORK',
  `scope_target` varchar(64) NOT NULL DEFAULT '*',
  `target_uuid` uuid NOT NULL,
  `target_name` varchar(16) NOT NULL,
  `actor_type` enum('PLAYER','CONSOLE','WEB','SYSTEM') NOT NULL,
  `actor_uuid` uuid DEFAULT NULL,
  `actor_ref` varchar(64) DEFAULT NULL,
  `actor_name` varchar(64) NOT NULL,
  `reason_id` varchar(32) DEFAULT NULL,
  `reason_text` varchar(512) NOT NULL,
  `note` varchar(512) DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL,
  `expires_at` timestamp(3) NULL DEFAULT NULL,
  `origin_server` varchar(64) DEFAULT NULL,
  `silent` tinyint(1) NOT NULL DEFAULT 0,
  `ladder_step` smallint(6) DEFAULT NULL,
  `revoked_at` timestamp(3) NULL DEFAULT NULL,
  `revoked_by_type` enum('PLAYER','CONSOLE','WEB','SYSTEM') DEFAULT NULL,
  `revoked_by_uuid` uuid DEFAULT NULL,
  `revoked_by_name` varchar(64) DEFAULT NULL,
  `revoke_reason` varchar(512) DEFAULT NULL,
  `expiry_event_at` timestamp(3) NULL DEFAULT NULL,
  `request_id` uuid DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_public_id` (`public_id`),
  UNIQUE KEY `uq_request_id` (`request_id`),
  KEY `idx_target_active` (`target_uuid`,`type`,`revoked_at`,`expires_at`),
  KEY `idx_scope` (`scope_type`,`scope_target`),
  KEY `idx_created` (`created_at`),
  KEY `idx_actor` (`actor_uuid`),
  KEY `idx_expiry_sweep` (`expiry_event_at`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;

CREATE TABLE `jb_events` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `event_type` varchar(40) NOT NULL,
  `punishment_id` bigint(20) DEFAULT NULL,
  `payload` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`payload`)),
  `origin_node` varchar(64) NOT NULL,
  `created_at` timestamp(3) NOT NULL,
  `published_at` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_unpublished` (`published_at`,`id`),
  KEY `idx_punishment` (`punishment_id`),
  CONSTRAINT `fk_events_punishment` FOREIGN KEY (`punishment_id`) REFERENCES `jb_punishments` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;

CREATE TABLE `jb_reasons` (
  `id` varchar(32) NOT NULL,
  `display_name` varchar(128) NOT NULL,
  `allowed_types` varchar(128) NOT NULL,
  `definition` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`definition`)),
  `sort_order` int(11) NOT NULL DEFAULT 0,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `updated_at` timestamp(3) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;

CREATE TABLE `jb_command_log` (
  `request_id` uuid NOT NULL,
  `action` varchar(40) NOT NULL,
  `received_at` timestamp(3) NOT NULL,
  `handled_by` varchar(64) NOT NULL,
  `status` enum('PROCESSING','OK','ERROR') NOT NULL,
  `result` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`result`)),
  PRIMARY KEY (`request_id`),
  KEY `idx_received` (`received_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
;
```

### What the tables mean

| Table | Meaning |
|---|---|
| `jb_punishments` | One row per ban, warn or kick. The core table. Rows are never deleted. |
| `jb_players` | Every player the network has seen: `uuid`, current name, first/last seen, last server. (`last_ip` exists but you are not allowed to read it.) |
| `jb_player_names` | Name history per UUID, so you can find a player by an old name. |
| `jb_servers` | Servers the proxy knows (`id` = name in the proxy config) and their optional `group_name`. Use it to populate scope pickers. |
| `jb_reasons` | The catalogue of predefined reasons (edited in the plugin's config file, **not** by the panel). |
| `jb_events` | Append-only audit log and outbox. Each row is one event; its `id` is the `seq` carried by the matching Redis event. |
| `jb_command_log` | One row per web command that reached a plugin node: `request_id`, `action`, `status` (`PROCESSING`/`OK`/`ERROR`), and the stored `result` JSON. |

### Semantics you must get right

**Enumerations**

- `jb_punishments.type`: `BAN`, `WARN`, `KICK`. (`TEMPBAN` is not a type: a tempban is a `BAN` with a non-null `expires_at`.)
- `scope_type`: `NETWORK` (then `scope_target = '*'`), `SERVER` (`scope_target` = a `jb_servers.id`), `GROUP` (`scope_target` = a `jb_servers.group_name`).
- `actor_type` / `revoked_by_type`: `PLAYER` (an in-game staff member, `actor_uuid` set), `CONSOLE`, `WEB` (`actor_ref` = the id of the panel user who did it, `actor_name` = display name), `SYSTEM`.

**Status of a punishment is derived, not stored.** Nothing changes in the row when time passes. Evaluate against "now" every time you read:

| Status | Condition |
|---|---|
| `REVOKED` | `revoked_at IS NOT NULL` (pardoned, or a warn removed) |
| `KICK` | `type = 'KICK'` (instantaneous; never active) |
| `EXPIRED` | not revoked, `expires_at IS NOT NULL AND expires_at <= NOW(3)` |
| `ACTIVE` | not revoked, not a kick, and (`expires_at IS NULL` or `expires_at > NOW(3)`) |

```sql
SELECT p.*,
       CASE WHEN p.revoked_at IS NOT NULL THEN 'REVOKED'
            WHEN p.type = 'KICK'          THEN 'KICK'
            WHEN p.expires_at IS NOT NULL AND p.expires_at <= NOW(3) THEN 'EXPIRED'
            ELSE 'ACTIVE' END AS status
FROM jb_punishments p;
```

Permanent = `expires_at IS NULL` (for bans and warns). `PUNISHMENT_EXPIRED` events are only a notification; never wait for one to treat a ban as over.

**Other column notes**

- `public_id` is the 8-character code players see (`K7Q2M9XA`, alphabet `A-H J-N P-Z 2-9`, no `0 O 1 I`). Show it everywhere; accept it in search. Use the numeric `id` for commands.
- `reason_id` is the configured reason (may be NULL for a free-text reason); `reason_text` is the text that was shown to the player (a snapshot, it does not change when the configuration changes unless an admin edits it).
- `note` is an **internal staff note**. It is never sent to players and never published in Redis events. Only show it to panel roles that are allowed to (Part 7).
- `origin_server` is where it was issued: a server id, `proxy`, or `web` for punishments issued through Redis commands.
- `ladder_step` is set when a reason with an escalation ladder chose the outcome (see "Reasons" below).
- `silent`: no public broadcast was made.
- `expiry_event_at`: bookkeeping of the expiry announcement. Ignore it.
- `request_id` (UUID, unique) links a punishment to the web command that created it.
- `target_name` is the player's name at the time; the current name is in `jb_players.last_name`.

**`jb_events`**

- `id` (auto-increment) = the `seq` of the Redis event. `event_type` = the Redis `type`. `payload` = the same JSON object as the Redis `payload` (it is *not* signed in the database; you read it over your own authenticated connection).
  `created_at` = the Redis `ts`; `origin_node` = the Redis `node`; `punishment_id` links to `jb_punishments.id` (nullable, e.g. `REASONS_UPDATED`).
- Event types in the table: `PUNISHMENT_CREATED`, `PUNISHMENT_REVOKED`, `PUNISHMENT_UPDATED`, `PUNISHMENT_EXPIRED`, `JOIN_DENIED` (a banned player tried to join), `REASONS_UPDATED`.
  Ids are not gap-free (a rolled-back transaction burns an id) and arrive out of order across nodes. See Part 5, section 9.
- `published_at IS NULL` rows are valid events that have not reached Redis yet.

**`jb_reasons`**

- `allowed_types` is a comma-separated list of the *kinds* the reason may be used with (`BAN`, `TEMPBAN`, `SERVER_BAN`, `SERVER_TEMPBAN`, `WARN`, `KICK`). Use it to filter the reason dropdown by the chosen kind. Only rows with `enabled = TRUE` can be used.
- `definition` is JSON: `{"types":[...kinds...], "defaultDurationSeconds": 2592000 | null, "icon": "DIAMOND_SWORD" | null, "ladder": [ {"type":"WARN"|"BAN"|"KICK","durationSeconds": n | null}, ... ], "templates": {...}}`.
  Ignore `icon` and `templates` (in-game presentation). `defaultDurationSeconds` is what a temporary kind uses when you send no `duration`. A non-empty `ladder` means the reason **escalates**: the Nth offence with that reason uses step N
  (for example warn, then 1-day ban, then 7-day ban), so the punishment actually created may be a different `type`/duration than the `kind` you requested. **Always show the user what was actually created** (from the result's `punishmentId` and a fresh read), never what you asked for.
- `display_name` is what gets stored as the punishment's `reason_text` when you use the reason.

**`jb_command_log`**: after a command timed out you can look up `WHERE request_id = ?`. `status = 'OK'` or `'ERROR'` with `result` (the same JSON as the `COMMAND_RESULT` payload) tells you what happened; `PROCESSING` means a node claimed it and has not finished; no row means no plugin node ever received it.

### Query recipes (all read-only)

Always bind parameters; never format values into SQL strings.

```sql
-- Punishment history page (keyset pagination on id; filters are optional, add the WHERE parts you need)
SELECT p.id, p.public_id, p.type, p.scope_type, p.scope_target, p.target_uuid, p.target_name,
       p.actor_type, p.actor_ref, p.actor_name, p.reason_id, p.reason_text, p.created_at, p.expires_at,
       p.revoked_at, p.revoke_reason, p.origin_server, p.silent,
       CASE WHEN p.revoked_at IS NOT NULL THEN 'REVOKED' WHEN p.type = 'KICK' THEN 'KICK'
            WHEN p.expires_at IS NOT NULL AND p.expires_at <= NOW(3) THEN 'EXPIRED' ELSE 'ACTIVE' END AS status
FROM jb_punishments p
WHERE p.id < :cursor                                   -- omit for the first page
  AND p.type = :type                                   -- optional
  AND p.target_uuid = :uuid                            -- optional
  AND p.reason_id = :reason                            -- optional
  AND p.created_at >= :from AND p.created_at < :to     -- optional
ORDER BY p.id DESC
LIMIT 51;                                              -- ask for page size + 1 to know if there is a next page

-- Filter by status: add one of
--   ACTIVE : p.type <> 'KICK' AND p.revoked_at IS NULL AND (p.expires_at IS NULL OR p.expires_at > NOW(3))
--   EXPIRED: p.revoked_at IS NULL AND p.expires_at IS NOT NULL AND p.expires_at <= NOW(3)
--   REVOKED: p.revoked_at IS NOT NULL

-- Find by public id
SELECT * FROM jb_punishments WHERE public_id = :code;

-- Active bans / warns of one player
SELECT * FROM jb_punishments
WHERE target_uuid = :uuid AND type IN ('BAN','WARN') AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW(3))
ORDER BY id;

-- Player search by (current or past) name prefix, newest activity first
SELECT pl.uuid, pl.last_name, pl.last_seen, pl.last_server
FROM jb_players pl
WHERE pl.last_name_lower LIKE CONCAT(LOWER(:prefix), '%')
   OR pl.uuid IN (SELECT n.uuid FROM jb_player_names n WHERE n.name_lower LIKE CONCAT(LOWER(:prefix), '%'))
ORDER BY pl.last_seen DESC LIMIT 10;
-- (escape % and _ in user input before putting it in a LIKE pattern)

-- Name history of a player
SELECT name, first_seen, last_seen FROM jb_player_names WHERE uuid = :uuid ORDER BY last_seen DESC;

-- Dashboard counters
SELECT COUNT(*) FROM jb_punishments WHERE type = 'BAN'  AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW(3));   -- active bans
SELECT COUNT(*) FROM jb_punishments WHERE type = 'WARN' AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW(3));   -- active warns
SELECT COUNT(*) FROM jb_punishments WHERE created_at >= NOW(3) - INTERVAL 24 HOUR;                                                 -- issued in the last 24 h
SELECT COUNT(*) FROM jb_punishments WHERE type = 'BAN' AND revoked_at IS NULL AND expires_at > NOW(3) AND expires_at <= NOW(3) + INTERVAL 24 HOUR;  -- expiring soon

-- Timeline of one punishment
SELECT id, event_type, payload, origin_node, created_at FROM jb_events WHERE punishment_id = :id ORDER BY id;

-- Catch up on events after a cursor (see Part 5, section 9)
SELECT id, event_type, punishment_id, payload, origin_node, created_at
FROM jb_events WHERE id > :last_seq ORDER BY id LIMIT 500;

-- What became of a command whose answer never arrived
SELECT request_id, action, status, result, received_at, handled_by FROM jb_command_log WHERE request_id = :request_id;

-- Reasons usable in the issue form, and servers/groups for the scope picker
SELECT id, display_name, allowed_types, definition FROM jb_reasons WHERE enabled = TRUE ORDER BY sort_order;
SELECT id, group_name, display_name FROM jb_servers ORDER BY id;
```

JSON columns come back as strings in some drivers: parse them. Indexes exist on `(target_uuid, type, revoked_at, expires_at)`, `(scope_type, scope_target)`, `created_at`, `actor_uuid`, `public_id`, `request_id`, `jb_events(punishment_id)` and `jb_players(last_name_lower, last_seen)`; a filter on
something else (for example `actor_name`) scans, so combine it with a date range or a limit.

---

## Part 4: Redis: channels, keys and access

All names carry a configurable prefix, **default `justbans:`**. Make it an environment variable (`JB_REDIS_PREFIX`) and build every name from it.

| Name | Kind | Direction | What you do with it |
|---|---|---|---|
| `justbans:commands` | Pub/Sub channel | **you → plugin** | **Publish** signed commands (`ISSUE_PUNISHMENT`, `REVOKE_PUNISHMENT`, `UPDATE_PUNISHMENT`, `PING`, `RELOAD_REASONS`) |
| `justbans:command-results` | Pub/Sub channel | plugin → you | **Subscribe**: one signed `COMMAND_RESULT` per command (broadcast; match by `requestId`) |
| `justbans:events` | Pub/Sub channel | plugin → everyone | **Subscribe**: signed lifecycle events (`PUNISHMENT_CREATED`, ...) |
| `justbans:online` | Hash | Velocity writes | **Read** with `HGETALL`/`HGET`: field = player UUID, value = JSON **string** `{"name":"Notch","server":"lobby"}` |
| `justbans:lock:expiry-sweeper` | String | plugins only | Internal. Do not touch. |

Notes:

- Use **separate connections** for subscribing and for publishing/reading: a connection in subscribed mode cannot run other commands. Subscribe to `command-results` **before** publishing a command (Pub/Sub does not buffer).
- Production design: keep **one** long-lived subscriber connection (for events and results) with auto-reconnect and exponential backoff, and resolve pending commands from a shared `requestId -> future` map; do not open a subscription per request (the reference client does, for simplicity).
- `justbans:online` is maintained by the proxy: entries are added on join, removed on leave, and rebuilt on proxy start. After a proxy crash it can briefly contain stale entries. Treat it as "best effort live view". Values are JSON strings: parse each (skip entries that fail to parse).
- Redis requires authentication. Use a dedicated ACL user for the panel (the plugins use another) and TLS (`rediss://`) when Redis is not on a private network:

```
ACL SETUSER justbans-web on >WEB_PASSWORD resetchannels &justbans:events &justbans:command-results &justbans:commands ~justbans:online -@all +subscribe +unsubscribe +publish +hget +hgetall +ping +auth
```

  (Redis cannot separate publish from subscribe permission per channel, so this user could technically publish on `justbans:events`; forged events are harmless because the plugins verify the signature and your code does too.)
  Your backend should work with a plain `redis://:password@host:6379/0` URL as well as `rediss://`.

---

## Part 5: The Redis protocol, envelope and HMAC signing

This part is the complete, authoritative protocol specification (version 1). Section numbers (3 to 12) are kept so cross-references inside it work. Signed example messages for every
message type are in [Appendix B](#appendix-b-signed-example-messages-test-fixtures) and JSON Schemas in [Appendix A](#appendix-a-json-schemas); **use both in your tests**.

Quick orientation before the details:

1. You publish a JSON **envelope** `{v, id, type, ts, node, payload, sig}` on `justbans:commands`. `sig` is a Base64 HMAC-SHA256 over `v|id|type|ts|node|seq|canonical(payload)` (seq is empty for commands).
2. The plugin verifies it, executes the command exactly once, and publishes a signed `COMMAND_RESULT` envelope on `justbans:command-results` carrying your `payload.requestId`.
3. The plugin publishes a signed event on `justbans:events` for every change (including ones made in game), numbered by `seq` (= `jb_events.id`).

### 3. Channels and keys (summary; details in Part 4)

All names start with a configurable prefix, default `justbans:` (`redis.prefix` in the proxy config,
`Redis.Prefix` on Paper). Use the same prefix everywhere.

| Name | Kind | Direction | Purpose |
|---|---|---|---|
| `justbans:events` | Pub/Sub channel | plugin → everyone | Punishment lifecycle events, `REASONS_UPDATED` |
| `justbans:commands` | Pub/Sub channel | web → plugin | Signed action requests |
| `justbans:command-results` | Pub/Sub channel | plugin → web | One `COMMAND_RESULT` per command (broadcast; match on `requestId`) |
| `justbans:online` | Hash | Velocity writes | field = player UUID, value = JSON string `{"name":"Notch","server":"lobby"}`. Informational "online now" view. Read with `HGETALL`. |
| `justbans:lock:expiry-sweeper` | String with TTL | plugins | Elects the node that announces expired punishments ([section 8](#8-events-plugin--everyone)). Internal: web backends neither read nor write it. |
| `justbans:node:<nodeId>` | String with TTL | — | Reserved for node heartbeats. **Not implemented**; use `PING` ([section 6.5](#65-ping)). |

Publish and subscribe use **separate connections**: a connection in subscribed mode cannot issue
other commands. Subscribe to `command-results` *before* publishing a command, because Pub/Sub does not buffer.

### 4. Envelope

Every message on every channel is one JSON object (UTF-8, at most **16 384 characters**; longer
messages are dropped):

```json
{
  "v": 1,
  "id": "6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10",
  "type": "PING",
  "ts": 1767225600000,
  "node": "web-1",
  "seq": 18842,
  "payload": { },
  "sig": "NGxZ/e4tqNzxw1H1mtRp+wRST/LQiCBYQHUgzzBWEuQ="
}
```

| Field | Type | Meaning |
|---|---|---|
| `v` | integer | Protocol version. Must be `1`; anything else is dropped. |
| `id` | string (UUID) | Message id. The receiver remembers ids for 10 minutes and ignores repeats. Generate a fresh random UUID per command. Events use a stable id derived from `seq` (UUIDv3 of `justbans-event-<seq>`), so a re-published event has the same id. |
| `type` | string | Message type, upper snake case. |
| `ts` | integer | Creation time, epoch **milliseconds** UTC. |
| `node` | string | Sender id. For web commands pick any stable name (`web-1`). It is covered by the signature but not interpreted. |
| `seq` | integer, optional | Events only: the `jb_events.id`. Absent (not `null`) on commands and results. |
| `payload` | object | Type-specific body. Always an object. |
| `sig` | string | Base64 HMAC-SHA256, see below. Required on every message. |

Messages that are not valid JSON, not an object, have a missing/mistyped field, or have `v != 1` are
dropped with a log line. Unknown extra fields are ignored. Note that a JSON writer may escape `=` as
`=` (the Java side does); that is ordinary JSON and any parser decodes it, it does not affect the signature.

### 5. Signing (HMAC-SHA256)

#### 5.1 Algorithm

```
secret    = web-secret from the plugin config (UTF-8 bytes, at least 16 characters)
signing   = v "|" id "|" type "|" ts "|" node "|" seq "|" canonical(payload)
sig       = Base64( HMAC-SHA256( secret, UTF-8(signing) ) )       -- standard alphabet, '=' padding
```

- `|` is a literal pipe. Numbers are written in plain decimal.
- `seq` is the **empty string** when the envelope has no `seq` (so a command has two pipes in a row before the payload: `...|web-1||{...}`).
- `sig` itself is never part of the signed text.
- Verification: rebuild the signing string from the **parsed** message (do not sign the raw received
  text, key order and whitespace are not preserved), compute the MAC, compare in **constant time**.

#### 5.2 Canonical JSON

`canonical(payload)` must be byte-for-byte identical on both sides, so it is fully specified:

1. No whitespace anywhere.
2. Object keys sorted ascending by **UTF-16 code unit** (Java `String.compareTo`, JavaScript default `sort()`).
   This differs from "by code point" only for characters outside the Basic Multilingual Plane; Python needs an explicit key.
3. Strings: escape only `"` → `\"`, `\` → `\\`, and the control characters `\b \f \n \r \t` in their short form;
   every other character below U+0020 as `\u` + **four lower-case hex digits** (`\u0001`, `\u001f`). Everything else,
   including `/`, DEL (U+007F), U+2028 and all non-ASCII text, is written **as-is** (never `\uXXXX`-escaped). Output is UTF-8.
4. Numbers: written exactly as in the JSON text. **Use integers only** (no floats, no exponents, no `1.0`).
   Keep integers below 2^53 so JavaScript clients stay exact.
5. `true`, `false`, `null` as literals. Arrays keep their order.
6. `null` is signed as the literal `null`, so a sender must also *send* it (never drop it from the wire text while signing it). Apart from the two
   documented cases of `UPDATE_PUNISHMENT`, omit optional fields instead of sending `null`; the plugin treats both the same.

#### 5.3 Reference implementations

Python (the full client is in Part 6) and Node.js give identical results. Node:

```js
import { createHmac } from 'node:crypto';

function canonical(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']';
  if (typeof v === 'object') {
    return '{' + Object.keys(v).sort().map(k => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}';
  }
  if (typeof v === 'number' && !Number.isInteger(v)) throw new Error('floats are not allowed');
  return JSON.stringify(v);   // JSON.stringify escapes exactly the characters rule 3 lists
}

const signingString = e => `${e.v}|${e.id}|${e.type}|${e.ts}|${e.node}|${e.seq ?? ''}|${canonical(e.payload)}`;
const sign = (secret, e) => createHmac('sha256', secret).update(signingString(e), 'utf8').digest('base64');
```

#### 5.4 Test vectors

Secret (UTF-8): `justbans-test-secret-0123456789`

**Vector 1, a command (no `seq`, escapes, non-ASCII):**

```
v=1  id=6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10  type=PING  ts=1767225600000  node=web-1
payload={"requestId":"0b7d8c2e-52a1-4f7e-8d2a-3a0c9f1b6e44","note":"café \"quoted\" \ <U+0001>"}
```

The `note` value is: `café "quoted" \ ` followed by the control character U+0001. Signing string (note the empty `seq`
between the two pipes, keys sorted, `\u0001` as six literal characters):

```
1|6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10|PING|1767225600000|web-1||{"note":"café \"quoted\" \\ \u0001","requestId":"0b7d8c2e-52a1-4f7e-8d2a-3a0c9f1b6e44"}
```

Signature: `NGxZ/e4tqNzxw1H1mtRp+wRST/LQiCBYQHUgzzBWEuQ=`

**Vector 2, an event (with `seq`):**

```
v=1  id=6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b11  type=PUNISHMENT_REVOKED  ts=1767225601234  node=velocity-1  seq=42
payload={"punishment":{"id":7,"publicId":"A1B2C3D4"}}
```

```
1|6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b11|PUNISHMENT_REVOKED|1767225601234|velocity-1|42|{"punishment":{"id":7,"publicId":"A1B2C3D4"}}
```

Signature: `zs8ngliPqgtlX+fq8Vq3jk7zzvRWFdJ6MiGRbNmMVls=`

Cross-check from a shell (should print vector 1's signature):

```sh
printf '%s' '1|6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10|PING|1767225600000|web-1||{"note":"café \"quoted\" \\ \u0001","requestId":"0b7d8c2e-52a1-4f7e-8d2a-3a0c9f1b6e44"}' \
  | openssl dgst -sha256 -hmac 'justbans-test-secret-0123456789' -binary | base64
```

These vectors are asserted by the plugin's own Java test-suite, by `python3 web-client.py selftest` (Part 6), and were
reproduced independently with Node.js and OpenSSL. The messages in Appendix B are complete signed
messages made with the same secret; the plugin's tests verify them too. **Your signer must reproduce both signatures and verify every message in Appendix B.**

#### 5.5 Freshness and replay

- A command whose `ts` differs from the receiving node's clock by more than **30 seconds** (either direction;
  `web-commands.max-skew-seconds`, default 30) is answered with `EXPIRED_REQUEST`. **Run NTP on the web host.**
- Replays are stopped by the `requestId` claim in `jb_command_log` ([section 6.1](#61-exactly-once-and-retries)), which
  outlives the skew window, and by the 10-minute `id` memory.
- Short secrets can be brute-forced offline from one captured message: the plugin refuses to start with fewer than
  16 characters. Use 32+ random characters.

### 6. Commands (web → plugin)

Publish a signed envelope on `justbans:commands`. Every command payload **must contain `requestId`**
(a UUID you generate); a command without a valid one is dropped without an answer.

Supported `type` values:

| `type` | Purpose | Handled by |
|---|---|---|
| `ISSUE_PUNISHMENT` | ban / tempban / server ban / warn / kick | every node with web commands enabled |
| `REVOKE_PUNISHMENT` | pardon a ban, remove a warn | same |
| `UPDATE_PUNISHMENT` | change the expiry, reason text or staff note | same |
| `PING` | health check | same |
| `RELOAD_REASONS` | re-read `reasons.yml` on the proxy | Velocity only |

Anything else is answered `INVALID_REQUEST`. Nothing received over Redis is ever executed as console text.
Schemas: `issue-punishment` (Appendix A), `revoke-punishment` (Appendix A),
`update-punishment` (Appendix A), `command-result` (Appendix A).

#### 6.1 Exactly-once and retries

On receipt every node verifies the signature, then tries to *claim* `requestId` by inserting it into
`jb_command_log` (primary key). The node whose insert succeeds runs the command and publishes the result; every other node
ignores the message. So even with a proxy and several Paper servers listening, a command runs **once**.

If you do not get a result (timeout, restart), **retry with the same `requestId`** (a new envelope `id` and `ts` are fine):
the owning node re-publishes the stored result instead of running the command again. Never generate a new `requestId` for a
retry of the same user action, that would create a second punishment. Timeout suggestion: 10 s.

Recommended deployment: enable web commands on the proxy only (`web-commands.enabled: true`) and leave `Web_Commands.Enabled: false`
on Paper servers (the default).

#### 6.2 `ISSUE_PUNISHMENT`

| Field | Req. | Notes |
|---|---|---|
| `requestId` | yes | UUID, idempotency key |
| `actor.name` | yes | Shown in game and in history, ≤ 64 chars, e.g. `Web: alice` |
| `actor.ref` | no | Your panel's user id, stored for auditing, ≤ 64 chars |
| `target.uuid` / `target.name` | one of | Prefer `uuid`. A `name` is resolved from the players the network has seen (`UNKNOWN_PLAYER` if unknown or ambiguous). A bare unknown `uuid` is accepted. |
| `kind` | yes | `BAN`, `TEMPBAN`, `SERVER_BAN`, `SERVER_TEMPBAN`, `WARN`, `KICK`. `BAN`/`TEMPBAN` with a `SERVER`/`GROUP` `scope` mean the server variants. |
| `scope` | for server kinds | `{"type":"NETWORK"\|"SERVER"\|"GROUP","target":"<server id or group>"}`. Omit for the whole network. Unknown servers/groups give `UNKNOWN_SERVER`. |
| `reasonId` | one of | A reason id from `reasons.yml` (`jb_reasons`, readable from the database). Takes precedence over `customReason`. Must allow the `kind` (`REASON_TYPE_NOT_ALLOWED`). |
| `customReason` | one of | Free text ≤ 512 chars, used when there is no `reasonId`. |
| `duration` | no | `30d`, `1d12h`, `90m`, `2w 3d`, `perm` (lower case; units `s m h d w mo y`; max 10 years). Omit to use the reason's default. TEMPBAN kinds need a finite duration, BAN kinds must be permanent. |
| `silent` | no | `true` suppresses the public broadcast (staff are still alerted). |
| `note` | no | Internal staff note ≤ 512 chars. Never shown to the player and never included in events. |
| `force` | no | Replace an existing active ban in the same scope instead of failing with `ALREADY_BANNED`. It does **not** bypass exemptions. |

Example (complete signed message: `examples/issue-punishment.json` (Appendix B)):

```json
{"v":1,"id":"a1f3c5d7-0000-4000-8000-000000000001","type":"ISSUE_PUNISHMENT","ts":1767225600000,"node":"web-1",
 "payload":{"requestId":"b9a3f4c0-6a1e-4f0e-9d58-7c2f5e8a1d33","actor":{"name":"Web: alice","ref":"42"},
  "target":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5"},"kind":"TEMPBAN","reasonId":"cheats","duration":"7d",
  "silent":false,"note":"seen flying, ticket 1187"},
 "sig":"ekTWdVz1ONSZ2mM42lchm1c5l8QGZsxcE/mMSSrn4Wg="}
```

#### 6.3 `REVOKE_PUNISHMENT`

Identify the punishment either by `punishmentId` (exact, from an event or a previous result) **or** by `target` + `kind`:

| Field | Req. | Notes |
|---|---|---|
| `requestId`, `actor` | yes | as above |
| `reason` | no | Why it is lifted, ≤ 512 chars, kept in history |
| `punishmentId` | one of | `jb_punishments.id` |
| `target` | one of | `{uuid}` or `{name}` as above |
| `kind` | with `target` | `BAN` (default; also `TEMPBAN`) lifts the ban in `scope`; `WARN` removes the most recent active warn |
| `scope` | no | Which ban to lift; network when omitted |

Kicks cannot be revoked. Revoking something that is not active gives `NOT_ACTIVE`, a player without a ban gives `NOT_BANNED`.

#### 6.4 `UPDATE_PUNISHMENT`

Edits an existing punishment. Send `punishmentId` and any of the three editable fields; fields that are **absent** stay as they are.

| Field | Req. | Notes |
|---|---|---|
| `requestId`, `actor` | yes | as above |
| `punishmentId` | yes | `jb_punishments.id` |
| `expiresAt` | no | New **absolute** expiry in epoch milliseconds, or JSON `null` for permanent. Must be in the future (to end a punishment now, pardon it) and at most 10 years away. Only while the punishment is active; kicks have no expiry. |
| `reasonText` | no | New reason shown to the player, 1 to 512 characters. The configured `reasonId` of the punishment is kept. |
| `note` | no | New internal staff note ≤ 512 characters. An empty string or JSON `null` removes it. |

- `expiresAt: null` and `note: null` are the only places where `null` means something. Send them as real JSON `null`; the signature covers `null` exactly like any other value ([section 5.2](#52-canonical-json)).
- The reason and note can be corrected on any punishment, including pardoned and expired ones. An expiry change on one of those answers `NOT_ACTIVE`; a past or too-distant `expiresAt` answers `INVALID_DURATION`; no field at all answers `INVALID_REQUEST`.
- Asking for the values the punishment already has succeeds (`OK`) and emits no event.
- A successful edit appends a `PUNISHMENT_UPDATED` event ([section 8](#8-events-plugin--everyone)); the `OK` result carries `punishmentId` and `publicId`.
- The same operation is available in game as `/justbans edit <id> <duration|reason|note> <value>` (permission `justbans.edit`).

Example: `examples/update-punishment.json` (Appendix B). Schema: `update-punishment` (Appendix A).

#### 6.5 `PING`

Payload: just `requestId`. Reply payload adds `"node":"<id of the answering node>","pong":true`. Use it for connectivity checks,
for testing the secret, and as a liveness probe of the command path. Because exactly one node answers each request, it proves
*a* node is alive, not every node.

#### 6.6 `RELOAD_REASONS`

Payload: `requestId`. The proxy re-reads `reasons.yml`, syncs `jb_reasons`, and answers with `"reloaded": <number of reasons>`.
Every node then reloads its catalogue on the resulting `REASONS_UPDATED` event. A file with errors gives `INVALID_REQUEST`
and leaves the previous reasons active.

#### 6.7 `COMMAND_RESULT`

Published on `justbans:command-results`, signed by the node that ran the command. Results are broadcast to all subscribers: match
`payload.requestId` with the ones you are waiting for and ignore the rest.

```json
{"requestId":"b9a3f4c0-…","action":"ISSUE_PUNISHMENT","status":"OK","punishmentId":4211,"publicId":"K7Q2M9XA"}
{"requestId":"c1d2e3f4-…","action":"ISSUE_PUNISHMENT","status":"ERROR","error":"ALREADY_BANNED","message":"Notch already has an active ban in this scope (#K7Q2M9XA)"}
```

- `status` is `OK` or `ERROR`. On `ERROR`, `error` is a stable code ([section 7](#7-error-codes)) and `message` is English text for
  logs. **Branch on `error`, never parse `message`.**
- `OK` results of `ISSUE_PUNISHMENT`/`REVOKE_PUNISHMENT` carry `punishmentId` (use it for later `REVOKE_PUNISHMENT`) and `publicId`.
- The matching `PUNISHMENT_CREATED`/`PUNISHMENT_REVOKED` event is published right after the database commit; the result and the
  event can arrive in either order.

### 7. Error codes

Stable; the same codes are used in game. New codes may be added (treat unknown ones as a generic failure).

| Code | Meaning | Retry as-is? |
|---|---|---|
| `UNKNOWN_PLAYER` | Name/UUID not known to the network, or a name matches several players (use the UUID) | no |
| `UNKNOWN_REASON` | `reasonId` is not in `jb_reasons` | no |
| `REASON_TYPE_NOT_ALLOWED` | The reason does not allow this `kind` | no |
| `ALREADY_BANNED` | Active ban in this scope already exists (`force` replaces it) | no |
| `NOT_BANNED` | Nothing to pardon in this scope | no |
| `UNKNOWN_PUNISHMENT` | No punishment with that id | no |
| `NOT_ACTIVE` | The punishment is expired, revoked, or a kick; or the player has no active warns | no |
| `TARGET_EXEMPT` | The target is exempt from punishments (permission `justbans.exempt`); `force` does not override it | no |
| `INVALID_DURATION` | Malformed, zero, over 10 years, missing for a TEMPBAN, or given for a permanent kind | no |
| `INVALID_REQUEST` | Malformed payload, missing field, unsupported command type, missing reason, bad scope | no |
| `UNKNOWN_SERVER` | `scope` names a server or group that is not registered | no |
| `EXPIRED_REQUEST` | `ts` is outside the allowed skew window. Check clocks. Nothing was executed. | yes, but with a **new** `requestId`* |
| `INTERNAL` | Unexpected error on the node, see its log | maybe |
| `BAD_SIGNATURE` | Reserved. Messages with a bad signature are **dropped silently**, so a wrong secret shows up as a timeout. | — |

\* The claim happens before the freshness check, so the `EXPIRED_REQUEST` result is stored under that `requestId` and replayed on a retry.
After fixing the clock, use a **new** `requestId` (nothing was executed, so this is safe).

### 8. Events (plugin → everyone)

Published on `justbans:events` by the node that caused the change, signed, with `seq` set. The payload is a
**full snapshot** (`punishment.schema.json` (Appendix A)), so consumers rarely need to query anything.
Fields that are `NULL` in the database are omitted. Timestamps are epoch milliseconds.

| `type` | When | Payload |
|---|---|---|
| `PUNISHMENT_CREATED` | A ban, warn or kick was committed (kicks included, `punishment.type` = `KICK`) | `{"punishment":{…}}` |
| `PUNISHMENT_REVOKED` | A ban was pardoned or a warn removed | `{"punishment":{…, "revoked":{"at","by","reason"}}}` |
| `JOIN_DENIED` | A banned player tried to connect (at most one per player per minute; can be disabled with `join-denied-events`) | `{"punishment":{…}}` of the ban that blocked them |
| `REASONS_UPDATED` | `reasons.yml` was synced | `{"reasons":["cheats","spam",…]}` (ids now defined) |
| `PUNISHMENT_UPDATED` | An edit changed the expiry, reason text or note | `{"punishment":{…}, "update":{"by":{actor},"changed":["expiresAt","reasonText","note"]}}`; `punishment` is the state after the edit and never contains the note's text |
| `PUNISHMENT_EXPIRED` | A ban or warn's expiry passed (announced by the expiry sweeper, normally within a minute) | `{"punishment":{…}}` |
| `PLAYER_KICKED` | Reserved name, **never published in v1** (a kick is a `PUNISHMENT_CREATED` with `type` `KICK`) | — |

**Computing "active".** A punishment is active while `revoked` is absent and `expiresAt` is absent or in the future, evaluated against *now* when you read it.
`PUNISHMENT_EXPIRED` is a notification, not a state change: no database row changes when time passes, so never wait for the event to treat a ban as over.

**The expiry sweeper.** Every node (proxy and Paper servers, `expiry-sweeper` / `Expiry_Sweeper` in their config) periodically tries
`SET justbans:lock:expiry-sweeper <nodeId> NX PX <interval × 0.9>`. The node that gets the lock announces everything that expired since the last sweep, the
others skip that round. Each expiry is claimed with a conditional update inside the transaction that appends its event, so there is exactly one
`PUNISHMENT_EXPIRED` per punishment even if two sweepers race. Pardoned, permanent and kick punishments never expire-announce, and punishments that had already
expired when the sweeper was introduced (database migration V2) are marked as announced without an event. If Redis is down no sweep runs; the backlog is announced when it returns.

Complete signed examples: `punishment-created.json` (Appendix B),
`punishment-revoked.json` (Appendix B).

```json
{"v":1,"id":"bb260029-82b9-367b-93a0-94a6dca03ec8","type":"PUNISHMENT_CREATED","ts":1767225600030,"node":"velocity-1","seq":18842,
 "payload":{"punishment":{"id":4211,"publicId":"K7Q2M9XA","type":"BAN","scope":{"type":"NETWORK","target":"*"},
  "target":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"},
  "actor":{"type":"WEB","ref":"42","name":"Web: alice"},"reason":{"id":"cheats","text":"Cheating / hacked client"},
  "createdAt":1767225600030,"expiresAt":1767830400030,"silent":false,"originServer":"web"}},
 "sig":"4SYoCSdNdn7xmdHRv2OvFD00EBeE1jtZc4c1vpsgvLQ="}
```

`scope.target` is `"*"` for network scope, otherwise a server id (as in `velocity.toml`) or a server group.
`actor.type` is `PLAYER`, `CONSOLE`, `WEB` or `SYSTEM`; `actor.ref` is whatever the issuer supplied (your panel user id for `WEB`).

### 9. The `seq` mechanism and catching up

**What `seq` is.** The plugin stores every event as a row in `jb_events` in the same transaction as the change. `seq` is that row's
auto-increment `id`; the envelope's `payload` is the row's `payload`, `type` its `event_type`, `node` its `origin_node` and `ts` its `created_at`.
Each node publishes its own rows from an outbox, oldest first, after commit, and retries until Redis accepts them.

**What `seq` guarantees, and what it does not.**

- It is **unique and increasing in commit order of allocation**, so it is a resume cursor: "everything with `id > N`".
- It is **not gap-free**. A rolled-back transaction burns an id, so seeing 41 then 43 does not by itself mean 42 was lost.
- Order across nodes is **not guaranteed**: node A and node B publish independently, so 43 can arrive before 42. Within one node, order is preserved.
- Delivery is **at least once** in practice: an event may arrive twice (re-publish after an outage), and may be missed (you were
  disconnected, Redis restarted). Both are handled the same way: de-duplicate and catch up from the database.

**Consumer algorithm.** Keep a durable cursor `lastSeq` (the highest `seq` you fully processed) and make processing idempotent
(for example upsert by `punishment.id`, or skip a `seq` you already stored).

1. *Startup / reconnect:* subscribe to `justbans:events` first and buffer incoming messages, then run
   ```sql
   SELECT id, event_type, punishment_id, payload, origin_node, created_at
   FROM jb_events WHERE id > :lastSeq ORDER BY id LIMIT 500;   -- repeat, advancing :lastSeq, until it returns no rows
   ```
   process those rows (read-only, see [section 2](#part-3-the-database-read-only)), then process the buffered live messages, skipping any
   `seq` already handled.
2. *Live:* verify the signature, drop a repeated `id` or already-processed `seq`, process, advance `lastSeq` to `max(lastSeq, seq)`.
3. *Suspected gap:* if `seq > lastSeq + 1`, wait a moment (about 2 s) for the missing numbers to arrive from another node. Anything still
   missing: run the query above (`id > lastSeq`). Rows that exist are real events you missed; ids with no row were rolled back and can be ignored.
   Do the same after any Redis reconnect.
4. *Rows that are not yet published* (`published_at IS NULL`) are valid events too; the database is the truth, publication is only the notification.

Caveats: the `payload` column is the same JSON as on Redis but is **not signed** (you read it over your own authenticated database
connection instead). Retention of old `jb_events` rows is a planned housekeeping job; if your cursor is older than the retained range,
rebuild from `jb_punishments` instead of replaying.

### 10. Redis security

- Require authentication and give each role its own ACL user restricted to the `justbans:` namespace. Example (adjust the prefix and passwords):
  ```
  ACL SETUSER justbans-plugin on >PLUGIN_PASSWORD resetchannels &justbans:* ~justbans:* -@all +@pubsub +set +hset +hget +hdel +hgetall +multi +exec +del +ping +auth
  ACL SETUSER justbans-web    on >WEB_PASSWORD    resetchannels &justbans:events &justbans:command-results &justbans:commands ~justbans:online -@all +subscribe +unsubscribe +publish +hget +hgetall +ping +auth
  ```
  Redis channel ACLs do not distinguish publish from subscribe, so the web user *can* technically publish on `justbans:events`.
  That is harmless: it cannot sign, and the plugins drop unsigned/forged events. Never share `web-secret` with anything that should only read.
  (These two ACL lines were tested against Redis 7.0 with Jedis, the plugin's client, and redis-py, the reference client. Clients that send `CLIENT SETINFO`/`HELLO` on connect fall back gracefully when it is denied.)
- Use TLS (`rediss://`, `redis.ssl: true`) whenever Redis is not on a private network. Do not expose Redis to the internet.
- `web-secret` is the only thing that authorises a ban. Keep it in a secrets manager, rotate it by updating every node and the web backend
  together (a node with a different secret shows up as "invalid signature" warnings in the server log), and never log signed messages together with it.
- Treat everything in `payload` strings (player names, reasons, notes) as untrusted text: escape before putting it in HTML.
- Rate-limit and authorise in the web backend: it holds the power to issue commands as any staff member. The plugin checks the signature, not who the panel user is.

### 11. Versioning and compatibility

- `v` is the protocol major version. Additive changes (new optional payload fields, new event or error codes, new command types) keep `v = 1`:
  **clients must ignore unknown fields and unknown event types**, and must not rely on field order.
- A breaking change bumps `v`; nodes drop messages with a version they do not know.
- The JSON Schemas (Appendix A) describe version 1. They are intentionally strict on commands (`additionalProperties: false`) to catch typos
  before sending; the plugin itself tolerates unknown fields.

### 12. Known limitations

These are deliberate scope limits of version 1, listed so the web team does not build on them:

- **No node heartbeats.** `justbans:node:<nodeId>` is reserved but not written. `PING` and `jb_servers.last_heartbeat` are the only liveness signals.
- **`PLAYER_KICKED`** is a reserved event name and is never published; kicks arrive as `PUNISHMENT_CREATED` with `type` `KICK`.
- **Expiry announcements need Redis.** The sweeper elects its node through Redis; while Redis is down no `PUNISHMENT_EXPIRED` is emitted (bans still expire correctly, because that is computed from the clock).
- **Stuck commands.** If the node that claimed a `requestId` crashes before answering, that request stays `PROCESSING` and retries are ignored. Use a new
  `requestId` after verifying (via the database) that the punishment was not created.
- **Target resolution is local.** Names resolve against players the network has seen; there is no Mojang lookup. Use UUIDs.
- **Event retention** (deleting old `jb_events` / `jb_command_log` rows) is not scheduled yet.
- **`RELOAD_REASONS` is Velocity-only.** If a Paper server has web commands enabled and wins the claim, it answers `INVALID_REQUEST`. Keep web commands on the proxy.
- **Bad signatures are silent.** There is no `BAD_SIGNATURE` reply; check the node log when a command times out.
- **Exemptions on the proxy** only cover players who are online on it; offline exempt players are only protected by Paper-side commands (LuckPerms lookup).

---

## Part 6: The Python reference client (complete, working code)

This is the plugin team's smoke-test client, embedded verbatim (only file paths mentioned in comments and usage text were adjusted to point into this document). It is a working example of how to **format, sign and send** a command, how to wait for the matching result, and how to
listen to events with signature checks, de-duplication and `seq` tracking. It depends only on the Python standard library and `redis-py` (`pip install redis`). Its first-class citizens are:

- `canonical_json`, `signing_string`, `sign`, `verify`, `new_envelope`, `encode`, `decode`: the signing core. **Reuse or port these into your backend and unit-test them with the vectors in Part 5.4** (the file's `selftest` command does exactly that).
- `Client.send_command`: publish + wait for the result (one subscription per call: fine for a smoke test, replace with a shared subscriber in the panel, see Part 7).
- `Client.listen`: verify, de-duplicate and track `seq`.
- Command builders in `main()` show the exact payload shapes for `ISSUE_PUNISHMENT`, `REVOKE_PUNISHMENT`, `UPDATE_PUNISHMENT` and `PING`.

Usage (environment variables `JUSTBANS_REDIS_URL`, `JUSTBANS_SECRET`, `JUSTBANS_PREFIX`, `JUSTBANS_NODE`):

```sh
python3 web-client.py selftest                 # no Redis needed: checks canonical JSON and HMAC against the published test vectors
python3 web-client.py ping                     # sign a PING, wait for the PONG
python3 web-client.py listen                   # print verified events
python3 web-client.py ban --player Notch --reason cheats --actor-name "Web: alice" --actor-ref 42
python3 web-client.py pardon --player Notch --reason "Appeal accepted" --actor-name "Web: alice"
python3 web-client.py edit --punishment-id 4211 --actor-name "Web: alice" --in 14d --reason-text "Flying near spawn"
```

Save it as `web-client.py` and run `selftest` first. If `selftest` fails in your environment, stop and investigate before writing anything else.

````python
#!/usr/bin/env python3
"""
JustBans reference web client: a smoke test for the Redis protocol (Part 5 of the handoff document).

It shows everything a web backend has to get right, in one dependency-light file:
  * canonical JSON and the HMAC-SHA256 signature (sign / verify),
  * publishing a signed command on <prefix>commands,
  * waiting for the matching COMMAND_RESULT on <prefix>command-results,
  * listening to <prefix>events with signature checks, de-duplication and `seq` tracking.

Requirements:   python 3.9+ and redis-py     (pip install redis)

Configuration (environment variables, each can be overridden by the matching flag):
  JUSTBANS_REDIS_URL   redis://[user:password@]host:port/db   (default redis://127.0.0.1:6379/0; rediss:// for TLS)
  JUSTBANS_SECRET      the `web-secret` from the plugin config (>= 16 characters)
  JUSTBANS_PREFIX      channel prefix, default "justbans:"
  JUSTBANS_NODE        id this client puts in the `node` field, default "web-1"

Usage:
  # 1. No Redis needed: checks this client against the published test vectors.
  python3 web-client.py selftest

  # 2. Connectivity + signature check against a running network. Prints the PONG, exit code 0 on success.
  export JUSTBANS_SECRET='the-same-secret-as-in-config.yml'
  python3 web-client.py ping

  # 3. Watch events as they happen (Ctrl+C to stop). Verifies signatures and reports `seq` jumps.
  python3 web-client.py listen

  # 4. Issue / revoke punishments. These really change data: use a test player.
  python3 web-client.py ban --player Notch --reason hacking --actor-name "Web: alice" --actor-ref 42
  python3 web-client.py ban --player Notch --kind TEMPBAN --duration 7d --custom-reason "Griefing"
  python3 web-client.py pardon --player Notch --reason "Appeal accepted" --actor-name "Web: alice"
  python3 web-client.py pardon --punishment-id 1234 --actor-name "Web: alice"
  python3 web-client.py edit --punishment-id 1234 --actor-name "Web: alice" --in 14d --reason-text "Flying near spawn"
  python3 web-client.py edit --punishment-id 1234 --actor-name "Web: alice" --permanent --clear-note

Exit codes: 0 success, 1 the plugin answered with status ERROR, 2 no answer in time, 3 bad usage / local failure.

This file only ever WRITES through Redis. The web backend must not write to the JustBans database;
give it a read-only database user (see Part 3 of the handoff document).
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid

PROTOCOL_VERSION = 1
MAX_MESSAGE_LENGTH = 16 * 1024  # the plugin drops anything longer (characters, not bytes)
MIN_SECRET_LENGTH = 16

# ----------------------------------------------------------------------------------------------
# Canonical JSON (the signed form of `payload`)
# ----------------------------------------------------------------------------------------------

_SHORT_ESCAPES = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t"}


def _canonical_string(value):
    out = ['"']
    for ch in value:
        short = _SHORT_ESCAPES.get(ch)
        if short is not None:
            out.append(short)
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))  # lower-case hex, exactly four digits
        else:
            out.append(ch)  # everything else verbatim, never \uXXXX-escaped
    out.append('"')
    return "".join(out)


def _utf16_key(key):
    # Java sorts keys by UTF-16 code unit; Python's default sorts by code point. They differ for characters
    # outside the BMP, so sort on the UTF-16 big-endian bytes (byte order == code unit order).
    return key.encode("utf-16-be", "surrogatepass")


def canonical_json(value):
    """Deterministic JSON text: no whitespace, keys sorted by UTF-16 code unit, minimal string escaping."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        # Floats have no single textual form across languages. The protocol uses integers only.
        raise TypeError("floats are not allowed in signed payloads; use integers or strings")
    if isinstance(value, str):
        return _canonical_string(value)
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(canonical_json(item) for item in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value.keys(), key=_utf16_key)
        return "{" + ",".join(_canonical_string(k) + ":" + canonical_json(value[k]) for k in keys) + "}"
    raise TypeError("cannot serialize %r" % type(value))


# ----------------------------------------------------------------------------------------------
# Envelope: signing string, signature, (de)serialization
# ----------------------------------------------------------------------------------------------

def signing_string(env):
    """`v|id|type|ts|node|seq|canonical(payload)`; `seq` is the empty string when absent."""
    seq = env.get("seq")
    return "%d|%s|%s|%d|%s|%s|%s" % (
        env["v"], env["id"], env["type"], env["ts"], env["node"], "" if seq is None else seq, canonical_json(env["payload"]))


def sign_text(secret, text):
    mac = hmac.new(secret.encode("utf-8"), text.encode("utf-8"), hashlib.sha256).digest()
    return base64.b64encode(mac).decode("ascii")  # standard alphabet, with '=' padding


def sign(secret, env):
    signed = dict(env)
    signed["sig"] = sign_text(secret, signing_string(env))
    return signed


def verify(secret, env):
    sig = env.get("sig")
    if not isinstance(sig, str):
        return False
    return hmac.compare_digest(sign_text(secret, signing_string(env)), sig)  # constant time


def new_envelope(type_, node, payload, seq=None, now_ms=None, message_id=None):
    env = {
        "v": PROTOCOL_VERSION,
        "id": message_id or str(uuid.uuid4()),
        "type": type_,
        "ts": int(time.time() * 1000) if now_ms is None else now_ms,
        "node": node,
        "payload": payload,
    }
    if seq is not None:
        env["seq"] = seq
    return env


def encode(env):
    text = json.dumps(env, ensure_ascii=False, separators=(",", ":"))
    if len(text) > MAX_MESSAGE_LENGTH:
        raise ValueError("message is %d characters, the plugin drops anything over %d" % (len(text), MAX_MESSAGE_LENGTH))
    return text


def decode(text):
    """Parses a received message. Returns the envelope dict, or None if it is not a well-formed envelope."""
    if len(text) > MAX_MESSAGE_LENGTH:
        return None
    try:
        env = json.loads(text)
    except ValueError:
        return None
    if not isinstance(env, dict) or env.get("v") != PROTOCOL_VERSION or not isinstance(env.get("payload"), dict):
        return None
    for key, kind in (("id", str), ("type", str), ("node", str)):
        if not isinstance(env.get(key), kind) or not env[key]:
            return None
    if not isinstance(env.get("ts"), int) or isinstance(env.get("ts"), bool):
        return None
    return env


# ----------------------------------------------------------------------------------------------
# Test vectors (identical to Part 5, section 5.4 of the handoff document and to the plugin's Java tests)
# ----------------------------------------------------------------------------------------------

VECTOR_SECRET = "justbans-test-secret-0123456789"
VECTOR_ENVELOPE = {
    "v": 1,
    "id": "6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10",
    "type": "PING",
    "ts": 1767225600000,
    "node": "web-1",
    "payload": {"requestId": "0b7d8c2e-52a1-4f7e-8d2a-3a0c9f1b6e44", "note": "café \"quoted\" \\ \u0001"},
}
VECTOR_SIGNING_STRING = ('1|6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10|PING|1767225600000|web-1||'
                         '{"note":"café \\"quoted\\" \\\\ \\u0001","requestId":"0b7d8c2e-52a1-4f7e-8d2a-3a0c9f1b6e44"}')
VECTOR_SIGNATURE = "NGxZ/e4tqNzxw1H1mtRp+wRST/LQiCBYQHUgzzBWEuQ="

VECTOR_EVENT = {
    "v": 1,
    "id": "6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b11",
    "type": "PUNISHMENT_REVOKED",
    "ts": 1767225601234,
    "node": "velocity-1",
    "seq": 42,
    "payload": {"punishment": {"id": 7, "publicId": "A1B2C3D4"}},
}
VECTOR_EVENT_SIGNATURE = "zs8ngliPqgtlX+fq8Vq3jk7zzvRWFdJ6MiGRbNmMVls="


def selftest():
    failures = []

    def check(name, actual, expected):
        if actual != expected:
            failures.append("%s\n    expected: %s\n    actual:   %s" % (name, expected, actual))

    check("canonical: sorted keys, no whitespace", canonical_json({"b": 1, "a": [True, None, "x"]}), '{"a":[true,null,"x"],"b":1}')
    check("canonical: escapes", canonical_json("a\"b\\c\n\t\x01\x1f\x7f/"), '"a\\"b\\\\c\\n\\t\\u0001\\u001f\x7f/"')
    check("canonical: non-ASCII verbatim", canonical_json("café ☃"), '"café ☃"')
    check("canonical: UTF-16 key order", canonical_json({"\U0001f600": 1, "￮": 2}), '{"\U0001f600":1,"￮":2}')
    check("signing string", signing_string(VECTOR_ENVELOPE), VECTOR_SIGNING_STRING)
    check("signature", sign_text(VECTOR_SECRET, signing_string(VECTOR_ENVELOPE)), VECTOR_SIGNATURE)
    check("signature with seq", sign_text(VECTOR_SECRET, signing_string(VECTOR_EVENT)), VECTOR_EVENT_SIGNATURE)

    signed = sign(VECTOR_SECRET, VECTOR_ENVELOPE)
    check("verify accepts", verify(VECTOR_SECRET, decode(encode(signed))), True)
    tampered = json.loads(encode(signed))
    tampered["payload"]["note"] = "changed"
    check("verify rejects tampered payload", verify(VECTOR_SECRET, tampered), False)
    check("verify rejects wrong secret", verify(VECTOR_SECRET + "x", signed), False)
    check("verify rejects missing sig", verify(VECTOR_SECRET, VECTOR_ENVELOPE), False)

    if failures:
        print("SELFTEST FAILED")
        for failure in failures:
            print(" - " + failure)
        return 3

    print("selftest ok: canonical JSON and HMAC-SHA256 match the published test vectors")
    return 0


# ----------------------------------------------------------------------------------------------
# Redis I/O
# ----------------------------------------------------------------------------------------------

class Client:
    def __init__(self, url, secret, prefix, node):
        try:
            import redis  # imported late so `selftest` works without redis-py
        except ImportError:
            sys.exit("redis-py is required for this command: pip install redis")

        if len(secret) < MIN_SECRET_LENGTH:
            sys.exit("the secret must be at least %d characters (it must equal web-secret in the plugin config)" % MIN_SECRET_LENGTH)

        self.redis = redis.Redis.from_url(url, decode_responses=True)
        self.secret = secret
        self.prefix = prefix
        self.node = node

    @property
    def commands(self):
        return self.prefix + "commands"

    @property
    def results(self):
        return self.prefix + "command-results"

    @property
    def events(self):
        return self.prefix + "events"

    def send_command(self, type_, payload, timeout):
        """Publishes a signed command and waits for its COMMAND_RESULT. Returns the result payload, or None on timeout."""
        request_id = payload.setdefault("requestId", str(uuid.uuid4()))

        # Subscribe BEFORE publishing, otherwise a fast answer is missed (Pub/Sub does not buffer).
        pubsub = self.redis.pubsub(ignore_subscribe_messages=True)
        pubsub.subscribe(self.results)
        pubsub.get_message(timeout=1)  # consume the subscribe confirmation

        envelope = sign(self.secret, new_envelope(type_, self.node, payload))
        receivers = self.redis.publish(self.commands, encode(envelope))

        if receivers == 0:
            print("warning: nobody is subscribed to %s; is a JustBans node running on this Redis (and prefix)?" % self.commands, file=sys.stderr)

        deadline = time.monotonic() + timeout
        try:
            while time.monotonic() < deadline:
                message = pubsub.get_message(timeout=max(0.0, min(0.5, deadline - time.monotonic())))
                if message is None or message.get("type") != "message":
                    continue

                reply = decode(message["data"])
                if reply is None or reply["type"] != "COMMAND_RESULT":
                    continue
                if not verify(self.secret, reply):
                    print("ignored a result with an invalid signature (wrong secret on some node?)", file=sys.stderr)
                    continue
                if reply["payload"].get("requestId") != request_id:
                    continue  # somebody else's result, results are broadcast

                return reply["payload"]
        finally:
            pubsub.close()

        return None

    def listen(self, show_all):
        pubsub = self.redis.pubsub(ignore_subscribe_messages=True)
        pubsub.subscribe(self.events)
        print("listening on %s (Ctrl+C to stop)" % self.events)

        seen_ids = set()
        highest_seq = None

        try:
            for message in pubsub.listen():
                if message.get("type") != "message":
                    continue

                envelope = decode(message["data"])
                if envelope is None:
                    print("! malformed message ignored")
                    continue
                if not verify(self.secret, envelope):
                    print("! INVALID SIGNATURE from node %r, ignored" % envelope["node"])
                    continue
                if envelope["id"] in seen_ids:
                    continue  # duplicate delivery (events are re-published after a Redis outage)
                seen_ids.add(envelope["id"])

                seq = envelope.get("seq")
                note = ""
                if seq is not None:
                    if highest_seq is not None and seq > highest_seq + 1:
                        note = "  [seq jumped %d -> %d: read jb_events with id > %d from the database to catch up]" % (highest_seq, seq, highest_seq)
                    highest_seq = seq if highest_seq is None else max(highest_seq, seq)

                if show_all:
                    print(json.dumps(envelope, ensure_ascii=False, indent=2) + note)
                else:
                    summary = envelope["payload"].get("punishment", {})
                    print("%s seq=%s from=%s %s %s%s" % (
                        envelope["type"], seq, envelope["node"], summary.get("publicId", ""), summary.get("target", {}).get("name", ""), note))
        except KeyboardInterrupt:
            print()
        finally:
            pubsub.close()


# ----------------------------------------------------------------------------------------------
# Command line
# ----------------------------------------------------------------------------------------------

def target_object(args):
    if args.uuid:
        return {"uuid": args.uuid}
    if args.player:
        return {"name": args.player}
    sys.exit("give --player NAME or --uuid UUID")


def actor_object(args):
    actor = {"name": args.actor_name}
    if args.actor_ref:
        actor["ref"] = args.actor_ref
    return actor


_UNIT_MS = {"s": 1000, "m": 60_000, "h": 3_600_000, "d": 86_400_000, "w": 7 * 86_400_000}


def parse_duration_ms(text):
    """Tiny duration parser for the --in convenience flag: 90m, 12h, 7d, 2w, 1d12h. (The plugin itself also knows mo and y.)"""
    import re
    if not re.fullmatch(r"(\d+[smhdw])+", text):
        sys.exit("cannot read duration %r: use for example 90m, 12h, 7d, 2w or 1d12h" % text)
    return sum(int(n) * _UNIT_MS[u] for n, u in re.findall(r"(\d+)([smhdw])", text))


def report(result, started):
    if result is None:
        print("no answer within the timeout. Is the plugin running, on this Redis and prefix, with web-commands enabled?", file=sys.stderr)
        return 2

    print(json.dumps(result, ensure_ascii=False, indent=2))
    if result.get("status") == "OK":
        print("OK in %d ms" % ((time.monotonic() - started) * 1000))
        return 0

    return 1


def build_parser():
    parser = argparse.ArgumentParser(description="JustBans reference web client (see the module docstring for examples)")
    parser.add_argument("--redis-url", default=os.environ.get("JUSTBANS_REDIS_URL", "redis://127.0.0.1:6379/0"))
    parser.add_argument("--secret", default=os.environ.get("JUSTBANS_SECRET", ""))
    parser.add_argument("--prefix", default=os.environ.get("JUSTBANS_PREFIX", "justbans:"))
    parser.add_argument("--node", default=os.environ.get("JUSTBANS_NODE", "web-1"))
    parser.add_argument("--timeout", type=float, default=10.0, help="seconds to wait for a result")

    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("selftest", help="verify canonical JSON + HMAC against the test vectors (no Redis needed)")
    sub.add_parser("ping", help="send PING and wait for the PONG")

    listen = sub.add_parser("listen", help="print verified events")
    listen.add_argument("--raw", action="store_true", help="print full envelopes")

    edit = sub.add_parser("edit", help="change the expiry, reason text or note of a punishment (UPDATE_PUNISHMENT)")
    edit.add_argument("--punishment-id", type=int, required=True)
    edit.add_argument("--actor-name", required=True)
    edit.add_argument("--actor-ref")
    expiry = edit.add_mutually_exclusive_group()
    expiry.add_argument("--expires-at", type=int, help="new expiry as epoch milliseconds")
    expiry.add_argument("--in", dest="expires_in", help="new expiry this long from now, e.g. 7d, 12h (a convenience of this client)")
    expiry.add_argument("--permanent", action="store_true", help="make the punishment permanent (sends expiresAt: null)")
    edit.add_argument("--reason-text", help="new reason shown to the player")
    note = edit.add_mutually_exclusive_group()
    note.add_argument("--note", help="new internal staff note")
    note.add_argument("--clear-note", action="store_true", help="remove the staff note")

    for name in ("ban", "pardon"):
        cmd = sub.add_parser(name)
        cmd.add_argument("--player")
        cmd.add_argument("--uuid")
        cmd.add_argument("--actor-name", required=True, help="shown in-game and in history, e.g. 'Web: alice'")
        cmd.add_argument("--actor-ref", help="your panel's user id, stored with the punishment")
        cmd.add_argument("--reason", help="for ban: a reason id from reasons.yml; for pardon: free text")

    ban = sub.choices["ban"]
    ban.add_argument("--kind", default="BAN", choices=["BAN", "TEMPBAN", "WARN", "KICK"])
    ban.add_argument("--custom-reason", help="free text instead of a configured reason id")
    ban.add_argument("--duration", help="30d, 1d12h, 90m or perm; omit to use the reason's default")
    ban.add_argument("--server", help="restrict to one server id (network-wide when omitted)")
    ban.add_argument("--silent", action="store_true")
    ban.add_argument("--note", help="internal staff note, never shown to the player")
    ban.add_argument("--force", action="store_true", help="replace an existing active ban instead of failing with ALREADY_BANNED")

    pardon = sub.choices["pardon"]
    pardon.add_argument("--punishment-id", type=int, help="numeric id of the punishment; otherwise the player's network ban is lifted")
    pardon.add_argument("--kind", default="BAN", choices=["BAN", "WARN"])
    pardon.add_argument("--server", help="lift a server-scoped ban instead of the network one")

    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)

    if args.command == "selftest":
        return selftest()

    client = Client(args.redis_url, args.secret, args.prefix, args.node)
    started = time.monotonic()

    try:
        if args.command == "listen":
            client.listen(args.raw)
            return 0

        if args.command == "ping":
            return report(client.send_command("PING", {}, args.timeout), started)

        if args.command == "ban":
            payload = {"actor": actor_object(args), "target": target_object(args), "kind": args.kind, "silent": args.silent, "force": args.force}
            if args.reason:
                payload["reasonId"] = args.reason
            if args.custom_reason:
                payload["customReason"] = args.custom_reason
            if args.duration:
                payload["duration"] = args.duration
            if args.note:
                payload["note"] = args.note
            if args.server:
                payload["scope"] = {"type": "SERVER", "target": args.server}
            return report(client.send_command("ISSUE_PUNISHMENT", payload, args.timeout), started)

        if args.command == "edit":
            payload = {"actor": actor_object(args), "punishmentId": args.punishment_id}
            if args.permanent:
                payload["expiresAt"] = None  # JSON null: the one place where null means something
            elif args.expires_at is not None:
                payload["expiresAt"] = args.expires_at
            elif args.expires_in:
                payload["expiresAt"] = int(time.time() * 1000) + parse_duration_ms(args.expires_in)
            if args.reason_text:
                payload["reasonText"] = args.reason_text
            if args.clear_note:
                payload["note"] = ""
            elif args.note is not None:
                payload["note"] = args.note
            if len(payload) == 2:
                sys.exit("nothing to change: give --expires-at/--in/--permanent, --reason-text or --note/--clear-note")
            return report(client.send_command("UPDATE_PUNISHMENT", payload, args.timeout), started)

        if args.command == "pardon":
            payload = {"actor": actor_object(args)}
            if args.reason:
                payload["reason"] = args.reason
            if args.punishment_id is not None:
                payload["punishmentId"] = args.punishment_id
            else:
                payload["target"] = target_object(args)
                payload["kind"] = args.kind
                if args.server:
                    payload["scope"] = {"type": "SERVER", "target": args.server}
            return report(client.send_command("REVOKE_PUNISHMENT", payload, args.timeout), started)

    except (OSError, ValueError, TypeError) as ex:
        print("error: %s" % ex, file=sys.stderr)
        return 3
    except Exception as ex:  # redis.exceptions.RedisError (connection refused, NOAUTH, ...) is not an OSError
        if type(ex).__module__.startswith("redis"):
            print("redis error: %s" % ex, file=sys.stderr)
            return 3
        raise

    return 3


if __name__ == "__main__":
    sys.exit(main())
````

---

## Part 7: Task directives: what you must build

Build the panel in the order of the milestones at the end of this part. Each requirement has an identifier (`R1`, `R2`, ...); your `README.md` must include a table mapping every requirement to the code and test that
satisfy it, and say honestly which ones are not done.

### 7.1 Technical baseline

- **Backend:** recommended Python 3.12, FastAPI, `redis-py` (asyncio), SQLAlchemy Core or a thin async MariaDB driver for the read-only JustBans database, and a **separate** application database (SQLite for development, PostgreSQL for production
  via an environment variable) for panel users, sessions, the panel audit log and the event cursor. Node.js (Fastify/NestJS + `ioredis` + `mysql2`) is acceptable if you port the signing code and pass the same test vectors.
- **Frontend:** any modern approach (HTMX/Jinja or a Vite SPA with React/Vue/Svelte). It must be responsive, keyboard accessible, and usable on a phone. No framework is mandated; no CDN scripts in production builds (bundle your assets).
- **Configuration only through environment variables** (document them all in `README.md` and ship `.env.example`). Minimum set:

| Variable | Meaning |
|---|---|
| `JB_DB_HOST`, `JB_DB_PORT`, `JB_DB_NAME`, `JB_DB_USER`, `JB_DB_PASSWORD` | the **read-only** JustBans database account |
| `JB_REDIS_URL` | `redis://[user:password@]host:port/db` or `rediss://...` |
| `JB_REDIS_PREFIX` | default `justbans:` |
| `JB_WEB_SECRET` | the shared HMAC secret (at least 16 characters; refuse to start if shorter) |
| `JB_NODE_ID` | this panel instance's `node` value in envelopes, default `web-1`; must be unique per running instance |
| `JB_COMMAND_TIMEOUT_SECONDS` | default `10` |
| `APP_DATABASE_URL` | the panel's own database |
| `APP_SECRET_KEY` | signs session cookies (different from `JB_WEB_SECRET`) |
| `APP_BOOTSTRAP_ADMIN_USER`, `APP_BOOTSTRAP_ADMIN_PASSWORD` | creates the first admin on an empty user table |
| `APP_BASE_URL`, `APP_LOG_LEVEL` | as named |

- Provide `docker-compose.yml` for local development: MariaDB 11 (with the schema from Part 3 and the seed from [Appendix C](#appendix-c-development-seed-data) loaded by a privileged init account, plus the read-only account created exactly as in Part 3), Redis 7,
  your fake plugin simulator (R30), and the panel. `docker compose up` must give a working panel with demo data and a logged-in-able admin, with no manual steps beyond copying `.env.example`.

### 7.2 Requirements

#### Authentication, roles and auditing (your own users)

- **R1. Login.** Username + password (Argon2id or bcrypt), server-side sessions or signed cookies (`HttpOnly`, `Secure`, `SameSite=Lax` or stricter), CSRF protection on every state-changing request, login rate limiting with lockout/backoff, logout.
- **R2. Roles** enforced on the server for every endpoint (the UI only hides things):

| Role | May |
|---|---|
| `viewer` | read history, players, dashboard, online players; **cannot see staff notes** |
| `moderator` | everything `viewer` may, see notes, issue `WARN`, `KICK`, `TEMPBAN` (and `SERVER_TEMPBAN`), edit the reason/note of punishments they issued, revoke warns |
| `senior` | everything `moderator` may, plus permanent `BAN`/`SERVER_BAN`, revoke any ban, edit any punishment's expiry/reason/note |
| `admin` | everything, plus manage panel users, send `RELOAD_REASONS`, use the health/diagnostics page |

  Make the permission matrix data-driven (one table in code), not scattered `if`s, and unit-test it.
- **R3. Panel audit log** (in the panel's own database): every command attempt with panel user, UTC time, command type, the `requestId`, the full payload (**without** any secret), and the final outcome (`OK`, an error code, `TIMEOUT`). Viewable by admins, filterable, append-only.
- **R4. Actor identity.** In every command send `actor.name = "Web: <panel username>"` (max 64 chars) and `actor.ref = <stable panel user id as a string>` (max 64 chars). This is what staff see in game and in history.

#### Reading data (MariaDB, read-only)

- **R5. Dashboard.** Cards: active bans, active warns, punishments in the last 24 h and 7 days, bans expiring in the next 24 h, players online now (from Redis). A recent-activity list (the last 20 events, live-updating, R11). A small chart of punishments per day for 30 days and
  a top-10 reasons list. Every number must come from the queries in Part 3 and be explainable.
- **R6. Punishment history.** Server-side paginated table (page size 25/50/100, keyset or offset pagination, total count optional), sortable by creation time, with filters: status (`active`, `expired`, `revoked`, `kick`, `all`), type, scope (network/server/group + value), target name or UUID, actor (name or type, e.g. "only web"), reason id, date range, and a free-text box that
  recognises a public id (`K7Q2M9XA`), a UUID, or a player-name prefix. Filters are reflected in the URL (shareable). Empty state, loading state and error state for every table. CSV export of the current filter (role `senior`+; cap at 10 000 rows; never include notes unless the role may see them).
- **R7. Punishment detail page.** All fields in a readable layout (status badge, remaining time countdown, scope, actor with link to their other actions, reason id + text, origin, silent flag, ladder step, revoke info), the staff note (role permitting), the **event timeline** from `jb_events` for that punishment, and
  action buttons that depend on role and state (revoke, edit).
- **R8. Player profile.** Reachable by UUID or name: current name, name history, first/last seen, last server, an "online now on <server>" badge (Redis), active bans and warns with remaining time, the full history table, and quick actions (warn, kick when online, ban). Never display or request `last_ip`.
- **R9. Reasons and servers (read-only).** A page listing reasons (id, display name, allowed kinds, default duration, ladder rendered readably, enabled) and servers/groups. State clearly on the page that reasons are edited in the plugin's configuration file. Admins also get a "Reload reasons" button that sends `RELOAD_REASONS` (R20).

#### Live data (Redis)

- **R10. Online players page.** Read `HGETALL justbans:online`, parse each JSON value, show name, UUID, server; filter by server; search by name; sort; show the total. Refresh automatically every 5 seconds (polling your own endpoint or server-sent events), keep the scroll position and any open row menu, and show "updated N s ago". An empty hash is
  a normal state ("nobody online"). Join with the database to flag online players that currently have an active ban or warns. Row actions: open profile, warn, kick, ban. If Redis is unreachable show a clear degraded banner rather than an empty list.
- **R11. Live event feed.** The backend keeps one long-lived subscription to `justbans:events`, verifies each message's signature, drops unsigned/invalid/duplicate ones (by `id`, and by `seq`), and fans valid events out to browsers over SSE or WebSocket (authenticated, role-filtered: strip notes; they are not in events anyway). The UI shows a live activity feed
  and updates open tables/detail pages when an event concerns what is displayed (for example a toast "K7Q2M9XA was revoked" and the row's status changes without a reload).
- **R12. Never miss an event: the `seq` cursor.** Persist the highest fully processed `seq` in the panel database. On startup, on every Redis reconnect, and whenever a `seq` jump larger than 1 is seen (after waiting about 2 s for out-of-order arrivals), read `jb_events WHERE id > :cursor ORDER BY id LIMIT 500` in a loop
  (Part 3 recipe) and process those rows as if they had arrived live, de-duplicating by `seq`. Processing must be idempotent. Rows with ids that never appear are rolled-back transactions and can be ignored. Test this explicitly (R31).

#### Writing (signed commands)

- **R13. One command module.** All publishing goes through a single `CommandBus` abstraction used by every endpoint:
  1. builds the envelope (fresh UUIDv4 `id`, current epoch-millisecond `ts`, `node = JB_NODE_ID`, no `seq`, payload including `requestId`),
  2. signs it ([Part 5, section 5](#5-signing-hmac-sha256)),
  3. registers a future for `requestId` **before** publishing,
  4. publishes on `<prefix>commands`,
  5. waits up to `JB_COMMAND_TIMEOUT_SECONDS` for the matching, signature-verified `COMMAND_RESULT` from the one long-lived results subscription,
  6. returns a typed result `{status: OK|ERROR|TIMEOUT, error?, message?, punishmentId?, publicId?, raw}`.
  It must work with several panel instances running at once (results are broadcast to everyone; each instance resolves only its own pending requests) and must not leak futures (clean up on timeout).
- **R14. Idempotent retries.** The `requestId` is created once per user *intention* (when the form is rendered / the button is first clicked) and travels with the request. A "Retry" after a `TIMEOUT`, a network error between browser and backend, or a double click re-sends with the **same** `requestId` (new envelope `id` and `ts` are fine), so the plugin returns the stored result instead of executing twice.
  Make the HTTP API itself idempotent on `requestId` (reuse the stored outcome if the same `requestId` is submitted again). Exception: after `EXPIRED_REQUEST` nothing was executed, so generate a **new** `requestId`. Do not generate a new one for `ERROR` results the user did not change anything about.
- **R15. Timeout handling.** On `TIMEOUT` never show "failed". Show "No answer yet: the outcome is unknown", offer Retry (same `requestId`) and, in parallel, query `jb_command_log WHERE request_id = ?` (and `jb_punishments WHERE request_id = ?`) to report what actually happened when possible. Wrong secret, wrong prefix, or no plugin node with web commands enabled all look like timeouts: make the error text list those causes.
- **R16. Issue a punishment** (`ISSUE_PUNISHMENT`). A form with:
  - **Target:** autocomplete over `jb_players` / `jb_player_names` (Part 3 recipe) showing name, UUID and last seen; also accept a pasted UUID. Always send `target.uuid` when you know it (a name is only resolved if exactly one known player has it; an unknown or ambiguous name returns `UNKNOWN_PLAYER`).
  - **Kind:** `BAN`, `TEMPBAN`, `WARN`, `KICK`; plus **scope** (network / server / group) populated from `jb_servers`; choosing a server or group scope sends `scope` and the kind is treated as a server ban by the plugin.
  - **Reason:** a dropdown of enabled reasons filtered by the chosen kind (`allowed_types`), **or** a free-text custom reason (1 to 512 chars) where the role allows it. Send `reasonId` *or* `customReason`.
  - **Duration:** shown for `TEMPBAN` (required unless the reason has a default, then prefilled and overridable) and optionally `WARN`; validated client- and server-side against `^([0-9]+(mo|y|w|d|h|m|s)\s*)+$` or `perm`, max 10 years; quick-pick buttons (1h, 1d, 7d, 30d).
  - **Options:** `silent`, an internal `note` (only if the role may see notes), and `force` (only roles that may replace bans; explain that it replaces an existing active ban in the same scope and does **not** bypass exemptions).
  - A confirmation step summarising the action ("Tempban Notch on network for 7 days, reason Cheating").
  - Result handling per R14/R15, and a success view that links to the created punishment, using the `punishmentId` from the result and a fresh read of the database (show what was **actually** created: a reason with an escalation ladder can turn a requested ban into a warn).
- **R17. Revoke** (`REVOKE_PUNISHMENT`). From the detail page or a list row: by `punishmentId` with an optional reason (≤ 512 chars). Enabled only for punishments that are `ACTIVE` (kicks cannot be revoked). Confirmation dialog. Show `NOT_ACTIVE` / `UNKNOWN_PUNISHMENT` clearly (someone else may have just done it). A "pardon player's network ban" shortcut by `target + kind` is optional.
- **R18. Edit** (`UPDATE_PUNISHMENT`). From the detail page: change the expiry (date-time picker in the viewer's time zone converted to epoch **milliseconds** UTC; "make permanent" sends JSON `null`), the reason text, and the note (empty or `null` removes it). Send **only the fields that changed**. Expiry editing is only offered for `ACTIVE` bans and warns; the new expiry must be in the future.
  Reason text and note can also be fixed on ended punishments. Show `INVALID_DURATION`, `NOT_ACTIVE`, `INVALID_REQUEST` verbatim-but-friendly. Note the null-handling rule in section 5.2 (null is signed as `null` and must be *sent* as `null`).
- **R19. Error code mapping.** A single table mapping every error code in section 7 to a user-friendly message and a suggested next step (for example `TARGET_EXEMPT`: "This player is exempt from punishments"). Unknown codes fall back to the plugin's English `message`. Log the raw result at debug level.
- **R20. Admin utilities.** `PING` button with measured round-trip latency and the answering `node`; `RELOAD_REASONS` (admins only; the plugin answers `reloaded: N`; it is handled by the proxy only).
- **R21. Rate limiting.** Per panel user: a sensible cap on commands per minute (configurable, default 30) with a clear error; plus a global cap, so a compromised account cannot mass-ban. Bulk actions (multi-select) are allowed up to 25 items per request, each with its own `requestId`, executed sequentially, reporting per-item outcomes.

#### Health, security and operations

- **R22. Health page and endpoints.** `/healthz` (process up) and `/readyz` (can reach MariaDB and Redis, and the DB account is read-only). An admin page showing: DB and Redis connectivity, last processed `seq`, event lag, last `PING` result, and a clock-skew hint (commands older or newer than 30 s are rejected with `EXPIRED_REQUEST`; instruct operators to run NTP and compare with the `ts` of recent plugin messages).
- **R23. Security hardening.** Escape everything on output (player names and reasons are attacker-controlled), strict Content-Security-Policy, no inline secrets, secure headers, parameterised queries only, no raw HTML from the database, upload nothing, log without secrets, constant-time signature comparison, reject envelopes larger than 16 384 characters, never reflect raw internal errors to the browser, and never send `JB_WEB_SECRET`
  or the Redis/DB credentials to any response. Provide a short `SECURITY.md` listing your threat model and mitigations.
- **R24. Time handling.** Store and compute in UTC; render in the user's time zone with a visible indicator; relative times ("in 2d 3h") update live without refetching.
- **R25. Observability.** Structured logs (JSON) with request id, user and `requestId`; metrics endpoint (Prometheus format) with command counts by outcome, command latency, events processed, event lag, DB and Redis errors.
- **R26. Graceful degradation.** Redis down: history and profiles still work (DB), writes are disabled with an explanation, online list shows a banner. DB down: writes could still work but the UI cannot show results, so show a degraded banner; do not crash. Both reconnect automatically.

#### Testing (mandatory, not optional)

- **R27. Signer unit tests** using the exact vectors in Part 5.4 (both vectors, signing strings and signatures), plus: canonical JSON edge cases (key ordering by UTF-16 code unit including a key with a character outside the Basic Multilingual Plane, control characters as lowercase `\u00xx`, `/` and DEL and non-ASCII unescaped, integers only, `null`, nested arrays, empty objects),
  verification rejecting tampered payloads, a wrong secret, a missing `sig`, and signatures compared in constant time. Also verify **every** message in Appendix B with secret `justbans-test-secret-0123456789`.
- **R28. Contract tests.** Every message your code can publish must validate against the matching JSON Schema in Appendix A (use `jsonschema` or `ajv`), including the strict `additionalProperties: false` command schemas. Every message you consume in tests (Appendix B and your simulator's) must also validate.
- **R29. Query tests** against a real MariaDB 10.11+ (container) loaded with the Part 3 schema and the Appendix C seed: status derivation, filters, pagination, player search, cursor catch-up, that the application's DB account **cannot** write (assert that an `INSERT` as that account is rejected), and that `last_ip` is not selectable.
- **R30. Fake plugin simulator** (`tools/fake_plugin.py` or similar, also used by `docker compose`). It must behave like the real plugin node for everything the panel uses:
  verify the signature of every command (drop silently if invalid); drop commands without a valid `requestId`; claim `requestId` exactly once across **several simulated nodes** (the first claim wins, a repeat from the same node re-publishes the stored result, others stay silent); reject `ts` more than 30 s from its clock with `EXPIRED_REQUEST` (stored under that requestId);
  implement `PING`, `ISSUE_PUNISHMENT`, `REVOKE_PUNISHMENT`, `UPDATE_PUNISHMENT` with the validation rules and error codes of Part 5 (unknown player, unknown reason, reason not allowed for the kind, already banned, not banned, exemptions via a configurable exempt list, invalid duration, unknown server, not active, invalid request);
  write the resulting rows into a scratch MariaDB (the simulator is the one component allowed to write, using its own privileged account) **and** append the matching `jb_events` rows, then publish signed `COMMAND_RESULT` and events with `seq = jb_events.id`; emit `PUNISHMENT_EXPIRED` for expired bans; occasionally deliver events twice or out of order and skip a publish
  (leave the row in `jb_events` unpublished) when told to, so catch-up (R12) can be tested; maintain `justbans:online`. Document its limits: it proves the panel follows the contract, not that the contract matches the real plugin.
- **R31. End-to-end tests** with Redis + MariaDB + simulator + panel (Playwright or your framework's client): login; issue a ban and see it in history within 2 s; double submit creates exactly one punishment; timeout then retry yields one punishment; revoke; edit expiry/reason/note; role restrictions (viewer cannot see notes or issue; moderator cannot permanent-ban); online list updates; live feed shows an event made "in game" by the simulator;
  event gap triggers catch-up; wrong secret on the simulator produces the documented timeout guidance; Redis outage shows the degraded state and recovers.
- **R32. CI.** A pipeline definition (GitHub Actions or similar) running lint, type checks, unit, contract and integration tests.

### 7.3 Things you must never do (checklist to self-review against)

- [ ] write to any `jb_*` table, or connect to the JustBans database with a writable account in the application (the simulator and test fixtures are the only exception, against scratch databases)
- [ ] publish an unsigned message, or act on an unverified one
- [ ] log, display or transmit `JB_WEB_SECRET`, or put it in the frontend
- [ ] use floating-point numbers in payloads (integers and strings only), or drop a `null` that was signed
- [ ] create a new `requestId` for a retry of the same user action
- [ ] display `jb_players.last_ip`, or `note` to roles that may not see it
- [ ] assume a requested punishment equals the created one (escalation ladders), or treat a timeout as a failure
- [ ] wait for `PUNISHMENT_EXPIRED` to decide a ban is over, or rely on Pub/Sub without database catch-up
- [ ] invent protocol fields, channels, command types or database columns

### 7.4 Milestones (deliver in this order; each ends with passing tests)

1. **Foundation:** repository, docker-compose, config, DB read-only verification, Redis connectivity, signer + all unit tests (R27), JSON Schema contract test harness (R28), login and roles (R1 to R4).
2. **Read path:** R5 to R9, with query tests (R29) and seed data.
3. **Command path:** CommandBus (R13 to R15), simulator (R30), issue/revoke/edit (R16 to R19), audit log, rate limiting.
4. **Live path:** online players (R10), event subscription, cursor and catch-up (R11, R12), live UI updates.
5. **Operations and polish:** R20 to R26, E2E tests (R31), CI (R32), docs.

### 7.5 Definition of done

- `docker compose up` starts a working panel with demo data; `README.md` explains setup, configuration, architecture (one diagram), the requirement-to-code table, how to run every test suite, and how to connect to a real JustBans network (create the read-only DB account, create the Redis ACL user, copy `web-secret`, run the `PING` button).
- All of R1 to R32 are implemented or explicitly listed in `README.md` under "Not done" with a reason.
- All tests pass in CI; the signer passes the published vectors; every Appendix B message verifies.
- `CONTRACT_ISSUES.md` and `DECISIONS.md` exist (empty is fine if there was nothing to record).
- A final self-review against section 7.3 is pasted into the last pull request or commit message.

When you finish, reply with: what you built, the requirement table, anything you could not verify without the real plugin, and every entry of `CONTRACT_ISSUES.md`.

---
## Appendix A: JSON Schemas

JSON Schema (draft 2020-12). Each schema has an `$id` such as `https://kingdomcraft.pl/justbans/schemas/envelope.schema.json`; `$ref`s between them are relative (`"envelope.schema.json"`) and resolve against that `$id`, so register all of them
in your validator's registry/store under those ids (the `$id`s are identifiers, not URLs you can fetch). `PUNISHMENT_REVOKED` and `JOIN_DENIED` events use the shape of `punishment-created` with the `type` constant changed (for revoked events `punishment.revoked` is present). Command schemas are intentionally strict (`additionalProperties: false`) to catch typos; **your code must still ignore unknown fields in messages it receives.**

### `envelope.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/envelope.schema.json",
  "title": "JustBans Redis envelope",
  "description": "The wrapper around every message on the justbans:events, justbans:commands and justbans:command-results channels. Every message on the wire carries `sig`. See Part 5, section 5 of the handoff document for how `sig` is computed.",
  "type": "object",
  "required": ["v", "id", "type", "ts", "node", "payload", "sig"],
  "properties": {
    "v": {
      "const": 1,
      "description": "Protocol version. Messages with any other value are dropped."
    },
    "id": {
      "type": "string",
      "format": "uuid",
      "description": "Message UUID, used for de-duplication. Events use a stable id derived from `seq`, so a re-published event has the same id."
    },
    "type": {
      "type": "string",
      "pattern": "^[A-Z][A-Z0-9_]*$",
      "description": "Message type, e.g. PUNISHMENT_CREATED, ISSUE_PUNISHMENT, COMMAND_RESULT."
    },
    "ts": {
      "type": "integer",
      "minimum": 0,
      "description": "Creation time in epoch milliseconds (UTC). Commands whose ts is more than max-skew-seconds (default 30) away from the plugin's clock are rejected with EXPIRED_REQUEST."
    },
    "node": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64,
      "description": "Id of the sending node: `node-id` of a plugin instance, or any stable name the web backend picks (e.g. web-1)."
    },
    "seq": {
      "type": "integer",
      "minimum": 1,
      "description": "Only on events: the jb_events.id of the event. A resume cursor for catching up from the database, not a gap-free counter. Absent on commands and results."
    },
    "payload": {
      "type": "object",
      "description": "Message body, depends on `type`."
    },
    "sig": {
      "type": "string",
      "pattern": "^[A-Za-z0-9+/]{43}=$",
      "description": "Standard Base64 (with padding) of the 32-byte HMAC-SHA256 over v|id|type|ts|node|seq|canonical(payload)."
    }
  }
}
```

### `punishment.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/punishment.schema.json",
  "title": "JustBans punishment snapshot",
  "description": "The punishment object carried by PUNISHMENT_CREATED, PUNISHMENT_REVOKED and JOIN_DENIED events. Fields that are null in the database are omitted. The internal staff note is never included.",
  "type": "object",
  "required": ["id", "publicId", "type", "scope", "target", "actor", "reason", "createdAt", "silent"],
  "properties": {
    "id": { "type": "integer", "minimum": 1, "description": "jb_punishments.id. Use this for REVOKE_PUNISHMENT." },
    "publicId": { "type": "string", "pattern": "^[A-HJ-NP-Z2-9]{8}$", "description": "The short id players see (#K9NCEJGD)." },
    "type": { "enum": ["BAN", "WARN", "KICK"] },
    "scope": {
      "type": "object",
      "required": ["type", "target"],
      "properties": {
        "type": { "enum": ["NETWORK", "SERVER", "GROUP"] },
        "target": { "type": "string", "minLength": 1, "maxLength": 64, "description": "\"*\" for NETWORK, otherwise a server id or server group name." }
      },
      "additionalProperties": false
    },
    "target": {
      "type": "object",
      "required": ["uuid", "name"],
      "properties": {
        "uuid": { "type": "string", "format": "uuid" },
        "name": { "type": "string", "minLength": 1, "maxLength": 16, "description": "Name at the time of the punishment." }
      },
      "additionalProperties": false
    },
    "actor": { "$ref": "#/$defs/actor" },
    "reason": {
      "type": "object",
      "required": ["text"],
      "properties": {
        "id": { "type": "string", "maxLength": 32, "description": "Configured reason id; absent for custom reasons." },
        "text": { "type": "string", "maxLength": 512 }
      },
      "additionalProperties": false
    },
    "createdAt": { "type": "integer", "minimum": 0, "description": "Epoch milliseconds." },
    "expiresAt": { "type": "integer", "minimum": 0, "description": "Epoch milliseconds. Absent for permanent bans, warns and kicks." },
    "silent": { "type": "boolean" },
    "originServer": { "type": "string", "maxLength": 64, "description": "Server the command ran on; \"web\" for punishments issued through Redis." },
    "ladderStep": { "type": "integer", "minimum": 0, "description": "Escalation step that picked the duration, if the reason has a ladder." },
    "revoked": {
      "type": "object",
      "description": "Present only once the punishment has been pardoned or unwarned.",
      "required": ["at", "by"],
      "properties": {
        "at": { "type": "integer", "minimum": 0 },
        "by": { "$ref": "#/$defs/actor" },
        "reason": { "type": "string", "maxLength": 512 }
      },
      "additionalProperties": false
    }
  },
  "additionalProperties": false,
  "$defs": {
    "actor": {
      "type": "object",
      "required": ["type", "name"],
      "properties": {
        "type": { "enum": ["PLAYER", "CONSOLE", "WEB", "SYSTEM"] },
        "uuid": { "type": "string", "format": "uuid", "description": "Only for PLAYER actors." },
        "ref": { "type": "string", "maxLength": 64, "description": "External reference, e.g. the web panel user id of a WEB actor." },
        "name": { "type": "string", "minLength": 1, "maxLength": 64 }
      },
      "additionalProperties": false
    }
  }
}
```

### `issue-punishment.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/issue-punishment.schema.json",
  "title": "ISSUE_PUNISHMENT command",
  "description": "Sent by the web backend on justbans:commands to ban, tempban, warn or kick a player. The plugin answers with a COMMAND_RESULT carrying the same requestId. The schema is deliberately strict (no unknown fields) so typos are caught on the web side; the plugin itself ignores unknown fields.",
  "allOf": [{ "$ref": "envelope.schema.json" }],
  "type": "object",
  "properties": {
    "type": { "const": "ISSUE_PUNISHMENT" },
    "seq": false,
    "payload": {
      "type": "object",
      "required": ["requestId", "actor", "target", "kind"],
      "properties": {
        "requestId": {
          "type": "string",
          "format": "uuid",
          "description": "Idempotency key. Generate one per user action and reuse it when retrying: the command executes at most once, a retry returns the stored result."
        },
        "actor": {
          "type": "object",
          "required": ["name"],
          "properties": {
            "name": { "type": "string", "minLength": 1, "maxLength": 64, "description": "Shown in-game and in history, e.g. \"Web: alice\"." },
            "ref": { "type": "string", "maxLength": 64, "description": "Your panel's user id; stored with the punishment for auditing." }
          },
          "additionalProperties": false
        },
        "target": {
          "type": "object",
          "description": "A player the network has seen before. Prefer uuid; a name is resolved from jb_players and fails with UNKNOWN_PLAYER if unknown or ambiguous.",
          "anyOf": [{ "required": ["uuid"] }, { "required": ["name"] }],
          "properties": {
            "uuid": { "type": "string", "format": "uuid" },
            "name": { "type": "string", "minLength": 1, "maxLength": 16 }
          },
          "additionalProperties": false
        },
        "kind": {
          "enum": ["BAN", "TEMPBAN", "SERVER_BAN", "SERVER_TEMPBAN", "WARN", "KICK"],
          "description": "BAN/TEMPBAN with a SERVER or GROUP scope are treated as SERVER_BAN/SERVER_TEMPBAN. TEMPBAN kinds require a finite duration (from `duration` or the reason's default)."
        },
        "scope": {
          "type": "object",
          "description": "Where the ban applies. Omit for the whole network. Required for SERVER_BAN and SERVER_TEMPBAN.",
          "required": ["type"],
          "properties": {
            "type": { "enum": ["NETWORK", "SERVER", "GROUP"] },
            "target": { "type": "string", "minLength": 1, "maxLength": 64, "description": "Server id or group name; ignored for NETWORK." }
          },
          "if": { "properties": { "type": { "enum": ["SERVER", "GROUP"] } }, "required": ["type"] },
          "then": { "required": ["target"] },
          "additionalProperties": false
        },
        "reasonId": { "type": "string", "minLength": 1, "maxLength": 32, "description": "A reason id from reasons.yml (jb_reasons). Wins over customReason when both are given." },
        "customReason": { "type": "string", "minLength": 1, "maxLength": 512, "description": "Free text, used when no reasonId is given. Longer text is truncated." },
        "duration": {
          "type": "string",
          "pattern": "^([0-9]+(mo|y|w|d|h|m|s)\\s*)+$|^(perm|permanent)$",
          "description": "e.g. 30d, 1d12h, 90m, perm (lower case). Omit to use the reason's default duration. Maximum 10 years."
        },
        "silent": { "type": "boolean", "default": false, "description": "Do not broadcast to players (staff are still alerted)." },
        "note": { "type": "string", "maxLength": 512, "description": "Internal staff note, never shown to the player or put in events." },
        "force": { "type": "boolean", "default": false, "description": "Replace an existing active ban in the same scope instead of failing with ALREADY_BANNED. It does not bypass exemptions: players holding justbans.exempt still answer TARGET_EXEMPT." }
      },
      "anyOf": [{ "required": ["reasonId"] }, { "required": ["customReason"] }],
      "additionalProperties": false
    }
  }
}
```

### `revoke-punishment.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/revoke-punishment.schema.json",
  "title": "REVOKE_PUNISHMENT command",
  "description": "Pardons a ban or removes a warn. Address the punishment either by `punishmentId` (exact) or by `target` + `kind` (+ `scope` for bans).",
  "allOf": [{ "$ref": "envelope.schema.json" }],
  "type": "object",
  "properties": {
    "type": { "const": "REVOKE_PUNISHMENT" },
    "seq": false,
    "payload": {
      "type": "object",
      "required": ["requestId", "actor"],
      "properties": {
        "requestId": { "type": "string", "format": "uuid" },
        "actor": {
          "type": "object",
          "required": ["name"],
          "properties": {
            "name": { "type": "string", "minLength": 1, "maxLength": 64 },
            "ref": { "type": "string", "maxLength": 64 }
          },
          "additionalProperties": false
        },
        "reason": { "type": "string", "maxLength": 512, "description": "Why it is being lifted (shown in history)." },
        "punishmentId": { "type": "integer", "minimum": 1, "description": "jb_punishments.id from an event or a COMMAND_RESULT." },
        "target": {
          "type": "object",
          "anyOf": [{ "required": ["uuid"] }, { "required": ["name"] }],
          "properties": {
            "uuid": { "type": "string", "format": "uuid" },
            "name": { "type": "string", "minLength": 1, "maxLength": 16 }
          },
          "additionalProperties": false
        },
        "kind": { "enum": ["BAN", "TEMPBAN", "WARN"], "default": "BAN", "description": "BAN lifts the ban in `scope` (network by default); WARN removes the player's most recent active warn." },
        "scope": {
          "type": "object",
          "required": ["type"],
          "properties": {
            "type": { "enum": ["NETWORK", "SERVER", "GROUP"] },
            "target": { "type": "string", "minLength": 1, "maxLength": 64 }
          },
          "additionalProperties": false
        }
      },
      "oneOf": [{ "required": ["punishmentId"] }, { "required": ["target"] }],
      "additionalProperties": false
    }
  }
}
```

### `update-punishment.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/update-punishment.schema.json",
  "title": "UPDATE_PUNISHMENT command",
  "description": "Edits the expiry, the reason text or the internal note of an existing punishment. Fields that are absent are left alone. The reason and note can be corrected on any punishment; the expiry only while the punishment is active (and kicks have none). Asking for the values it already has succeeds without emitting an event.",
  "allOf": [{ "$ref": "envelope.schema.json" }],
  "type": "object",
  "properties": {
    "type": { "const": "UPDATE_PUNISHMENT" },
    "seq": false,
    "payload": {
      "type": "object",
      "required": ["requestId", "actor", "punishmentId"],
      "properties": {
        "requestId": { "type": "string", "format": "uuid" },
        "actor": {
          "type": "object",
          "required": ["name"],
          "properties": {
            "name": { "type": "string", "minLength": 1, "maxLength": 64 },
            "ref": { "type": "string", "maxLength": 64 }
          },
          "additionalProperties": false
        },
        "punishmentId": { "type": "integer", "minimum": 1, "description": "jb_punishments.id from an event or a COMMAND_RESULT." },
        "expiresAt": {
          "type": ["integer", "null"],
          "minimum": 0,
          "description": "New absolute expiry in epoch milliseconds, or JSON null to make it permanent. Must be in the future (pardon to end it now) and at most 10 years away. This is the one field where null is meaningful: absent means unchanged."
        },
        "reasonText": { "type": "string", "minLength": 1, "maxLength": 512, "description": "New reason shown to the player. The configured reason id of the punishment is kept." },
        "note": { "type": ["string", "null"], "maxLength": 512, "description": "New internal staff note. An empty string or null removes the note." }
      },
      "anyOf": [{ "required": ["expiresAt"] }, { "required": ["reasonText"] }, { "required": ["note"] }],
      "additionalProperties": false
    }
  }
}
```

### `command-result.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/command-result.schema.json",
  "title": "COMMAND_RESULT",
  "description": "The plugin's answer to every command, published on justbans:command-results. Results are broadcast: match yours by payload.requestId.",
  "allOf": [{ "$ref": "envelope.schema.json" }],
  "type": "object",
  "properties": {
    "type": { "const": "COMMAND_RESULT" },
    "payload": {
      "type": "object",
      "required": ["requestId", "action", "status"],
      "properties": {
        "requestId": { "type": "string", "format": "uuid" },
        "action": { "type": "string", "description": "The type of the command this answers." },
        "status": { "enum": ["OK", "ERROR"] },
        "punishmentId": { "type": "integer", "description": "ISSUE_PUNISHMENT / REVOKE_PUNISHMENT: the affected jb_punishments.id." },
        "publicId": { "type": "string" },
        "node": { "type": "string", "description": "PING: id of the node that answered." },
        "pong": { "type": "boolean" },
        "error": {
          "enum": ["UNKNOWN_PLAYER", "UNKNOWN_REASON", "REASON_TYPE_NOT_ALLOWED", "ALREADY_BANNED", "NOT_BANNED", "UNKNOWN_PUNISHMENT", "NOT_ACTIVE", "TARGET_EXEMPT", "INVALID_DURATION", "INVALID_REQUEST", "UNKNOWN_SERVER", "BAD_SIGNATURE", "EXPIRED_REQUEST", "INTERNAL"],
          "description": "Present when status is ERROR."
        },
        "message": { "type": "string", "description": "Human-readable English explanation for logs. Do not parse it; branch on `error`." }
      },
      "if": { "properties": { "status": { "const": "ERROR" } }, "required": ["status"] },
      "then": { "required": ["error", "message"] }
    }
  }
}
```

### `punishment-created.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/punishment-created.schema.json",
  "title": "PUNISHMENT_CREATED event",
  "description": "Published on justbans:events after a ban, warn or kick has been committed to the database. PUNISHMENT_REVOKED has the same shape with `payload.punishment.revoked` set, so the same schema family applies (change the `type` const).",
  "allOf": [{ "$ref": "envelope.schema.json" }],
  "type": "object",
  "required": ["seq", "payload"],
  "properties": {
    "type": { "const": "PUNISHMENT_CREATED" },
    "seq": { "type": "integer", "minimum": 1 },
    "payload": {
      "type": "object",
      "required": ["punishment"],
      "properties": {
        "punishment": { "$ref": "punishment.schema.json" }
      },
      "additionalProperties": false
    }
  }
}
```

### `punishment-updated.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/punishment-updated.schema.json",
  "title": "PUNISHMENT_UPDATED event",
  "description": "Published after an edit (UPDATE_PUNISHMENT or /justbans edit) changed something. `punishment` is the snapshot after the edit; `update` says who edited and which fields changed. The note's content is never published, only the fact that it changed.",
  "allOf": [{ "$ref": "envelope.schema.json" }],
  "type": "object",
  "required": ["seq", "payload"],
  "properties": {
    "type": { "const": "PUNISHMENT_UPDATED" },
    "seq": { "type": "integer", "minimum": 1 },
    "payload": {
      "type": "object",
      "required": ["punishment", "update"],
      "properties": {
        "punishment": { "$ref": "punishment.schema.json" },
        "update": {
          "type": "object",
          "required": ["by", "changed"],
          "properties": {
            "by": { "$ref": "punishment.schema.json#/$defs/actor" },
            "changed": {
              "type": "array",
              "minItems": 1,
              "uniqueItems": true,
              "items": { "enum": ["expiresAt", "reasonText", "note"] }
            }
          },
          "additionalProperties": false
        }
      },
      "additionalProperties": false
    }
  }
}
```

### `punishment-expired.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://kingdomcraft.pl/justbans/schemas/punishment-expired.schema.json",
  "title": "PUNISHMENT_EXPIRED event",
  "description": "Published once for each ban or warn whose expiry has passed, by whichever node the expiry sweeper elected. Expiry itself needs no action (a punishment is active while its end lies ahead); this event tells caches and the web panel. Never emitted for pardoned punishments, permanent ones or kicks.",
  "allOf": [{ "$ref": "envelope.schema.json" }],
  "type": "object",
  "required": ["seq", "payload"],
  "properties": {
    "type": { "const": "PUNISHMENT_EXPIRED" },
    "seq": { "type": "integer", "minimum": 1 },
    "payload": {
      "type": "object",
      "required": ["punishment"],
      "properties": {
        "punishment": { "$ref": "punishment.schema.json" }
      },
      "additionalProperties": false
    }
  }
}
```

---

## Appendix B: Signed example messages (test fixtures)

Every message below is a complete, correctly signed envelope using the **test secret `justbans-test-secret-0123456789`**. Use them as fixtures: (1) your verifier must accept every one, (2) changing any character of `payload`, `ts`, `id`, `type`, `node` or `seq` must make it fail, (3) each must validate against its schema in Appendix A, (4) your signer, given the same fields and the same secret, must reproduce the same `sig`. Ids and timestamps are fixed on purpose (so a signature is reproducible); real traffic uses random UUIDs and the current time. Note that `update-punishment` contains `"note": null`: it must stay `null` on the wire.

### `issue-punishment.json`

```json
{
  "v": 1,
  "id": "a1f3c5d7-0000-4000-8000-000000000001",
  "type": "ISSUE_PUNISHMENT",
  "ts": 1767225600000,
  "node": "web-1",
  "payload": {
    "requestId": "b9a3f4c0-6a1e-4f0e-9d58-7c2f5e8a1d33",
    "actor": {
      "name": "Web: alice",
      "ref": "42"
    },
    "target": {
      "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5"
    },
    "kind": "TEMPBAN",
    "reasonId": "cheats",
    "duration": "7d",
    "silent": false,
    "note": "seen flying, ticket 1187"
  },
  "sig": "ekTWdVz1ONSZ2mM42lchm1c5l8QGZsxcE/mMSSrn4Wg="
}
```

### `revoke-punishment.json`

```json
{
  "v": 1,
  "id": "a1f3c5d7-0000-4000-8000-000000000004",
  "type": "REVOKE_PUNISHMENT",
  "ts": 1767226000000,
  "node": "web-1",
  "payload": {
    "requestId": "d4e5f6a7-6a1e-4f0e-9d58-7c2f5e8a1d35",
    "actor": {
      "name": "Web: alice",
      "ref": "42"
    },
    "punishmentId": 4211,
    "reason": "Appeal accepted"
  },
  "sig": "NXH/dYfjmLePdUwKT6FFn5JEQ5d9Nu/he6lzchHdRfQ="
}
```

### `update-punishment.json`

```json
{
  "v": 1,
  "id": "a1f3c5d7-0000-4000-8000-000000000005",
  "type": "UPDATE_PUNISHMENT",
  "ts": 1767226100000,
  "node": "web-1",
  "payload": {
    "requestId": "e5f6a7b8-6a1e-4f0e-9d58-7c2f5e8a1d36",
    "actor": {
      "name": "Web: alice",
      "ref": "42"
    },
    "punishmentId": 4211,
    "expiresAt": 1768435200000,
    "reasonText": "Flying near spawn",
    "note": null
  },
  "sig": "+lY0L65AwZrRaFYz8oUvkb6JZjQKXewubweADp/VehM="
}
```

### `command-result-ok.json`

```json
{
  "v": 1,
  "id": "a1f3c5d7-0000-4000-8000-000000000002",
  "type": "COMMAND_RESULT",
  "ts": 1767225600040,
  "node": "velocity-1",
  "payload": {
    "requestId": "b9a3f4c0-6a1e-4f0e-9d58-7c2f5e8a1d33",
    "action": "ISSUE_PUNISHMENT",
    "status": "OK",
    "punishmentId": 4211,
    "publicId": "K7Q2M9XA"
  },
  "sig": "dRlj0NJesW4iUsbz5p6hVA5QSebUca9ILiLYg2Ke+ls="
}
```

### `command-result-error.json`

```json
{
  "v": 1,
  "id": "a1f3c5d7-0000-4000-8000-000000000003",
  "type": "COMMAND_RESULT",
  "ts": 1767225700040,
  "node": "velocity-1",
  "payload": {
    "requestId": "c1d2e3f4-6a1e-4f0e-9d58-7c2f5e8a1d34",
    "action": "ISSUE_PUNISHMENT",
    "status": "ERROR",
    "error": "ALREADY_BANNED",
    "message": "Notch already has an active ban in this scope (#K7Q2M9XA)"
  },
  "sig": "8uH/CK2Yxv2MqdAk46Of6+YAvBSy6Z9VLqs5e1S83Ho="
}
```

### `punishment-created.json`

```json
{
  "v": 1,
  "id": "bb260029-82b9-367b-93a0-94a6dca03ec8",
  "type": "PUNISHMENT_CREATED",
  "ts": 1767225600030,
  "node": "velocity-1",
  "payload": {
    "punishment": {
      "id": 4211,
      "publicId": "K7Q2M9XA",
      "type": "BAN",
      "scope": {
        "type": "NETWORK",
        "target": "*"
      },
      "target": {
        "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        "name": "Notch"
      },
      "actor": {
        "type": "WEB",
        "ref": "42",
        "name": "Web: alice"
      },
      "reason": {
        "id": "cheats",
        "text": "Cheating / hacked client"
      },
      "createdAt": 1767225600030,
      "expiresAt": 1767830400030,
      "silent": false,
      "originServer": "web"
    }
  },
  "seq": 18842,
  "sig": "4SYoCSdNdn7xmdHRv2OvFD00EBeE1jtZc4c1vpsgvLQ="
}
```

### `punishment-updated.json`

```json
{
  "v": 1,
  "id": "bb260029-82b9-367b-93a0-94a6dca03eca",
  "type": "PUNISHMENT_UPDATED",
  "ts": 1767226100060,
  "node": "velocity-1",
  "payload": {
    "punishment": {
      "id": 4211,
      "publicId": "K7Q2M9XA",
      "type": "BAN",
      "scope": {
        "type": "NETWORK",
        "target": "*"
      },
      "target": {
        "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        "name": "Notch"
      },
      "actor": {
        "type": "WEB",
        "ref": "42",
        "name": "Web: alice"
      },
      "reason": {
        "id": "cheats",
        "text": "Flying near spawn"
      },
      "createdAt": 1767225600030,
      "silent": false,
      "originServer": "web",
      "expiresAt": 1768435200000
    },
    "update": {
      "by": {
        "type": "WEB",
        "ref": "42",
        "name": "Web: alice"
      },
      "changed": [
        "expiresAt",
        "reasonText",
        "note"
      ]
    }
  },
  "seq": 18844,
  "sig": "oJZ1lNSgcX23Ueyme1k+fWVF8qui8CPE92yFQRc/zGk="
}
```

### `punishment-revoked.json`

```json
{
  "v": 1,
  "id": "bb260029-82b9-367b-93a0-94a6dca03ec9",
  "type": "PUNISHMENT_REVOKED",
  "ts": 1767226000100,
  "node": "velocity-1",
  "payload": {
    "punishment": {
      "id": 4211,
      "publicId": "K7Q2M9XA",
      "type": "BAN",
      "scope": {
        "type": "NETWORK",
        "target": "*"
      },
      "target": {
        "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        "name": "Notch"
      },
      "actor": {
        "type": "WEB",
        "ref": "42",
        "name": "Web: alice"
      },
      "reason": {
        "id": "cheats",
        "text": "Cheating / hacked client"
      },
      "createdAt": 1767225600030,
      "expiresAt": 1767830400030,
      "silent": false,
      "originServer": "web",
      "revoked": {
        "at": 1767226000100,
        "by": {
          "type": "WEB",
          "ref": "42",
          "name": "Web: alice"
        },
        "reason": "Appeal accepted"
      }
    }
  },
  "seq": 18843,
  "sig": "aGwzkMsRvfWDo9wypX86OTqHwOG1fkZ3fBmxpTmk2cc="
}
```

### `punishment-expired.json`

```json
{
  "v": 1,
  "id": "bb260029-82b9-367b-93a0-94a6dca03ecb",
  "type": "PUNISHMENT_EXPIRED",
  "ts": 1767830460000,
  "node": "velocity-1",
  "payload": {
    "punishment": {
      "id": 4211,
      "publicId": "K7Q2M9XA",
      "type": "BAN",
      "scope": {
        "type": "NETWORK",
        "target": "*"
      },
      "target": {
        "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        "name": "Notch"
      },
      "actor": {
        "type": "WEB",
        "ref": "42",
        "name": "Web: alice"
      },
      "reason": {
        "id": "cheats",
        "text": "Cheating / hacked client"
      },
      "createdAt": 1767225600030,
      "silent": false,
      "originServer": "web",
      "expiresAt": 1767830400030
    }
  },
  "seq": 18850,
  "sig": "wga1W7FweAeg8WWMRBsOTUohYdUffq8Mf3wSrOYXE40="
}
```

---

## Appendix C: Development seed data

Load this into a **scratch** database that already contains the Part 3 schema, with a privileged account (never the panel's read-only account). It gives you an active permanent ban, an active temporary server ban, an expired ban, a pardoned ban, an active warn and a kick, with matching `PUNISHMENT_CREATED` events, so every status and filter has data. Timestamps are relative to "now".

```sql
-- Development seed data for the JustBans schema. It only exists so the web panel can be built and tested without a Minecraft
-- network. Timestamps are relative to "now" so the same file always yields active, expired and revoked punishments.
-- Load it into a scratch database that already has the schema from Part 3, using an account that may write (NOT the panel's read-only account).

INSERT INTO jb_servers (id, group_name, display_name, last_heartbeat) VALUES
  ('lobby',      NULL,       'Lobby',      NOW(3)),
  ('survival',   NULL,       'Survival',   NOW(3)),
  ('skyblock-1', 'skyblock', 'Skyblock 1', NOW(3)),
  ('skyblock-2', 'skyblock', 'Skyblock 2', NOW(3));

INSERT INTO jb_players (uuid, last_name, last_name_lower, first_seen, last_seen, last_server, last_ip) VALUES
  ('069a79f4-44e9-4726-a5be-fca90e38aaf5', 'Notch',     'notch',     NOW(3) - INTERVAL 90 DAY, NOW(3) - INTERVAL 5 MINUTE, 'survival',   NULL),
  ('853c80ef-3c37-49fd-aa49-938b674adae6', 'jeb_',      'jeb_',      NOW(3) - INTERVAL 60 DAY, NOW(3) - INTERVAL 2 HOUR,   'skyblock-1', NULL),
  ('61699b2e-d327-421a-84f6-4d4e1ab4a4a5', 'Dinnerbone','dinnerbone',NOW(3) - INTERVAL 30 DAY, NOW(3) - INTERVAL 1 DAY,    'lobby',      NULL),
  ('f7c77d99-9f15-4a66-a659-70e8f3c6b2d3', 'Grumm',     'grumm',     NOW(3) - INTERVAL 10 DAY, NOW(3) - INTERVAL 3 DAY,    'survival',   NULL),
  ('b0d4b28b-c1f3-4ac2-a8f0-7e4d5f0f4d20', 'Staffer',   'staffer',   NOW(3) - INTERVAL 200 DAY, NOW(3),                    'lobby',      NULL);

INSERT INTO jb_player_names (uuid, name, name_lower, first_seen, last_seen) VALUES
  ('069a79f4-44e9-4726-a5be-fca90e38aaf5', 'Notch',      'notch',      NOW(3) - INTERVAL 90 DAY, NOW(3) - INTERVAL 5 MINUTE),
  ('069a79f4-44e9-4726-a5be-fca90e38aaf5', 'NotchOld',   'notchold',   NOW(3) - INTERVAL 200 DAY, NOW(3) - INTERVAL 91 DAY),
  ('853c80ef-3c37-49fd-aa49-938b674adae6', 'jeb_',       'jeb_',       NOW(3) - INTERVAL 60 DAY, NOW(3) - INTERVAL 2 HOUR),
  ('61699b2e-d327-421a-84f6-4d4e1ab4a4a5', 'Dinnerbone', 'dinnerbone', NOW(3) - INTERVAL 30 DAY, NOW(3) - INTERVAL 1 DAY),
  ('f7c77d99-9f15-4a66-a659-70e8f3c6b2d3', 'Grumm',      'grumm',      NOW(3) - INTERVAL 10 DAY, NOW(3) - INTERVAL 3 DAY),
  ('b0d4b28b-c1f3-4ac2-a8f0-7e4d5f0f4d20', 'Staffer',    'staffer',    NOW(3) - INTERVAL 200 DAY, NOW(3));

INSERT INTO jb_reasons (id, display_name, allowed_types, definition, sort_order, enabled, updated_at) VALUES
  ('cheats', 'Cheating / hacked client', 'BAN,SERVER_BAN,SERVER_TEMPBAN,TEMPBAN',
   '{"types":["BAN","SERVER_BAN","SERVER_TEMPBAN","TEMPBAN"],"defaultDurationSeconds":2592000,"icon":"DIAMOND_SWORD","ladder":[],"templates":{}}', 1, TRUE, NOW(3)),
  ('spam', 'Spamming the chat', 'KICK,WARN',
   '{"types":["KICK","WARN"],"defaultDurationSeconds":null,"icon":"PAPER","ladder":[],"templates":{}}', 2, TRUE, NOW(3)),
  ('toxic', 'Toxic behaviour', 'TEMPBAN,WARN',
   '{"types":["TEMPBAN","WARN"],"defaultDurationSeconds":null,"icon":"TNT","ladder":[{"type":"WARN","durationSeconds":null},{"type":"BAN","durationSeconds":86400},{"type":"BAN","durationSeconds":604800}],"templates":{}}', 3, TRUE, NOW(3)),
  ('retired', 'Retired reason', 'BAN', '{"types":["BAN"],"defaultDurationSeconds":null,"icon":null,"ladder":[],"templates":{}}', 4, FALSE, NOW(3));

INSERT INTO jb_punishments
  (public_id, type, scope_type, scope_target, target_uuid, target_name, actor_type, actor_uuid, actor_ref, actor_name, reason_id, reason_text, note,
   created_at, expires_at, origin_server, silent, ladder_step, revoked_at, revoked_by_type, revoked_by_uuid, revoked_by_name, revoke_reason, expiry_event_at)
VALUES
  -- 1: active permanent network ban issued by a staff member in game
  ('K7Q2M9XA', 'BAN', 'NETWORK', '*', '853c80ef-3c37-49fd-aa49-938b674adae6', 'jeb_', 'PLAYER', 'b0d4b28b-c1f3-4ac2-a8f0-7e4d5f0f4d20', NULL, 'Staffer', 'cheats', 'Cheating / hacked client', 'seen using fly, ticket 1187',
   NOW(3) - INTERVAL 2 DAY, NULL, 'survival', FALSE, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  -- 2: active temporary server ban issued from the web panel
  ('3ZXBWQCD', 'BAN', 'SERVER', 'skyblock-1', '61699b2e-d327-421a-84f6-4d4e1ab4a4a5', 'Dinnerbone', 'WEB', NULL, '42', 'Web: alice', NULL, 'Griefing spawn', NULL,
   NOW(3) - INTERVAL 1 HOUR, NOW(3) + INTERVAL 6 DAY, 'web', FALSE, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  -- 3: expired temporary ban, already announced by the sweeper
  ('P4NBTW8H', 'BAN', 'NETWORK', '*', 'f7c77d99-9f15-4a66-a659-70e8f3c6b2d3', 'Grumm', 'CONSOLE', NULL, NULL, 'Console', 'toxic', 'Toxic behaviour', NULL,
   NOW(3) - INTERVAL 5 DAY, NOW(3) - INTERVAL 4 DAY, 'proxy', FALSE, 1, NULL, NULL, NULL, NULL, NULL, NOW(3) - INTERVAL 4 DAY),
  -- 4: pardoned ban
  ('A2B3C4D5', 'BAN', 'NETWORK', '*', '069a79f4-44e9-4726-a5be-fca90e38aaf5', 'Notch', 'PLAYER', 'b0d4b28b-c1f3-4ac2-a8f0-7e4d5f0f4d20', NULL, 'Staffer', 'cheats', 'Cheating / hacked client', NULL,
   NOW(3) - INTERVAL 20 DAY, NULL, 'lobby', TRUE, NULL, NOW(3) - INTERVAL 19 DAY, 'WEB', NULL, 'Web: alice', 'Appeal accepted', NULL),
  -- 5: active warn that expires in 3 days
  ('W9X8Y7Z6', 'WARN', 'NETWORK', '*', '069a79f4-44e9-4726-a5be-fca90e38aaf5', 'Notch', 'PLAYER', 'b0d4b28b-c1f3-4ac2-a8f0-7e4d5f0f4d20', NULL, 'Staffer', 'spam', 'Spamming the chat', NULL,
   NOW(3) - INTERVAL 4 DAY, NOW(3) + INTERVAL 3 DAY, 'survival', FALSE, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  -- 6: kick (instant, never active)
  ('M5N6P7Q8', 'KICK', 'NETWORK', '*', '853c80ef-3c37-49fd-aa49-938b674adae6', 'jeb_', 'PLAYER', 'b0d4b28b-c1f3-4ac2-a8f0-7e4d5f0f4d20', NULL, 'Staffer', 'spam', 'Spamming the chat', NULL,
   NOW(3) - INTERVAL 10 DAY, NULL, 'lobby', FALSE, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO jb_events (event_type, punishment_id, payload, origin_node, created_at, published_at)
SELECT 'PUNISHMENT_CREATED', p.id,
       JSON_OBJECT('punishment', JSON_OBJECT('id', p.id, 'publicId', p.public_id, 'type', p.type, 'scope', JSON_OBJECT('type', p.scope_type, 'target', p.scope_target),
                   'target', JSON_OBJECT('uuid', p.target_uuid, 'name', p.target_name), 'actor', JSON_OBJECT('type', p.actor_type, 'name', p.actor_name),
                   'reason', JSON_OBJECT('text', p.reason_text), 'createdAt', UNIX_TIMESTAMP(p.created_at) * 1000, 'silent', p.silent = 1)),
       'velocity-1', p.created_at, p.created_at
FROM jb_punishments p ORDER BY p.id;
```

---

*This document was generated from the JustBans repository at commit `1fee117` (protocol version 1, database schema migrations V1 and V2). It is the whole contract: nothing outside it is needed or available.*
