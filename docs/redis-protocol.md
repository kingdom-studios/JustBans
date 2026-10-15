# JustBans Redis protocol, version 1

This is the contract between the JustBans plugins (Velocity proxy and Paper servers) and a web
panel backend. Everything here is implemented in `justbans-common` (`pl.kingdomcraft.justbans.common.messaging`)
and is checked by tests; where a feature is *not* implemented yet it is listed in
[section 12](#12-known-limitations).

| Artifact | Location |
|---|---|
| JSON Schemas | [`docs/schemas/`](schemas/) |
| Signed example messages (secret from [section 5.4](#54-test-vectors)) | [`docs/schemas/examples/`](schemas/examples/) |
| Reference client and smoke test | [`tools/web-client.py`](../tools/web-client.py) |
| Schema checker for captured traffic | [`tools/check-schemas.py`](../tools/check-schemas.py) |

Contents: [1 Principles](#1-principles) · [2 Database access](#2-database-access-read-only) · [3 Channels and keys](#3-channels-and-keys) · [4 Envelope](#4-envelope) · [5 Signing](#5-signing-hmac-sha256) · [6 Commands](#6-commands-web--plugin) · [7 Error codes](#7-error-codes) · [8 Events](#8-events-plugin--everyone) · [9 `seq` and catching up](#9-the-seq-mechanism-and-catching-up) · [10 Redis security](#10-redis-security) · [11 Versioning](#11-versioning-and-compatibility) · [12 Known limitations](#12-known-limitations) · [13 Reference client](#13-reference-client)

---

## 1. Principles

1. **The database is the single source of truth.** Redis carries *nudges* (events) and *requests*
   (commands). A lost Redis message never loses data, because every event is a row in `jb_events`
   written in the same transaction as the change it describes.
2. **All writes go through Redis commands.** The plugin executes them exactly like an admin typing
   the command in game: validation, exemption checks, duplicate-ban checks, audit trail, broadcast,
   kick/redirect of the online player, cache invalidation on every server.
3. **Everything is signed.** Every message in both directions carries an HMAC-SHA256 signature made
   with the shared `web-secret`. Unsigned or wrongly signed messages are ignored.
4. **At-least-once, in practice.** Pub/Sub itself is at-most-once per delivery. Events are therefore
   also stored in the database and may be re-published after a Redis outage, so consumers must
   de-duplicate ([section 9](#9-the-seq-mechanism-and-catching-up)).

## 2. Database access: READ-ONLY

> **The web backend MUST connect to the JustBans database with a read-only account, and MUST NOT
> write to any `jb_*` table. Every change goes through a signed Redis command ([section 6](#6-commands-web--plugin)).**

Why this is a hard rule, not a preference:

- A row inserted or updated directly **skips the whole pipeline**: no `PUNISHMENT_CREATED` event, so
  nobody is kicked, no broadcast or staff alert, and the proxy's ban cache (up to
  `ban-cache-seconds`) keeps letting the player in.
- It skips validation and locking: duplicate active bans, unknown reasons, exempt targets, bad
  durations, race conditions between two admins.
- It breaks the audit trail and the exactly-once guarantee (`jb_command_log`, `request_id`).
- It can corrupt the outbox ordering (`jb_events.id` / `seq`) that consumers use to catch up.

What the web backend uses the database for: listing and searching punishments, player history,
dashboards, and **catching up on events it missed** ([section 9](#9-the-seq-mechanism-and-catching-up)).

Recommended account (MariaDB). Column-level grants keep the player IP address out of reach of the web tier:

```sql
CREATE USER 'justbans_web'@'10.0.0.%' IDENTIFIED BY '<long random password>' REQUIRE SSL;

GRANT SELECT ON justbans.jb_punishments  TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_events       TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_reasons      TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_servers      TO 'justbans_web'@'10.0.0.%';
GRANT SELECT ON justbans.jb_player_names TO 'justbans_web'@'10.0.0.%';
-- jb_players has last_ip: grant every column except it
GRANT SELECT (uuid, last_name, last_name_lower, first_seen, last_seen, last_server)
      ON justbans.jb_players TO 'justbans_web'@'10.0.0.%';
-- Deliberately NOT granted: INSERT/UPDATE/DELETE/DDL anywhere, jb_command_log, jb_schema_version.
```

Read from a replica if you have one. Do not run schema migrations from the web side: the plugin owns
the schema (`jb_schema_version`).

## 3. Channels and keys

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

## 4. Envelope

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

## 5. Signing (HMAC-SHA256)

### 5.1 Algorithm

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

### 5.2 Canonical JSON

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

### 5.3 Reference implementations

Python (the full client is `tools/web-client.py`) and Node.js give identical results. Node:

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

### 5.4 Test vectors

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

These vectors are asserted by `HmacSignerTest` (Java), by `python3 tools/web-client.py selftest`, and were
reproduced independently with Node.js and OpenSSL. The files in `docs/schemas/examples/` are complete signed
messages made with the same secret and are verified by `ProtocolExamplesTest`.

### 5.5 Freshness and replay

- A command whose `ts` differs from the receiving node's clock by more than **30 seconds** (either direction;
  `web-commands.max-skew-seconds`, default 30) is answered with `EXPIRED_REQUEST`. **Run NTP on the web host.**
- Replays are stopped by the `requestId` claim in `jb_command_log` ([section 6.1](#61-exactly-once-and-retries)), which
  outlives the skew window, and by the 10-minute `id` memory.
- Short secrets can be brute-forced offline from one captured message: the plugin refuses to start with fewer than
  16 characters. Use 32+ random characters.

## 6. Commands (web → plugin)

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
Schemas: [`issue-punishment`](schemas/issue-punishment.schema.json), [`revoke-punishment`](schemas/revoke-punishment.schema.json),
[`update-punishment`](schemas/update-punishment.schema.json), [`command-result`](schemas/command-result.schema.json).

### 6.1 Exactly-once and retries

On receipt every node verifies the signature, then tries to *claim* `requestId` by inserting it into
`jb_command_log` (primary key). The node whose insert succeeds runs the command and publishes the result; every other node
ignores the message. So even with a proxy and several Paper servers listening, a command runs **once**.

If you do not get a result (timeout, restart), **retry with the same `requestId`** (a new envelope `id` and `ts` are fine):
the owning node re-publishes the stored result instead of running the command again. Never generate a new `requestId` for a
retry of the same user action, that would create a second punishment. Timeout suggestion: 10 s.

Recommended deployment: enable web commands on the proxy only (`web-commands.enabled: true`) and leave `Web_Commands.Enabled: false`
on Paper servers (the default).

### 6.2 `ISSUE_PUNISHMENT`

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

Example (complete signed message: [`examples/issue-punishment.json`](schemas/examples/issue-punishment.json)):

```json
{"v":1,"id":"a1f3c5d7-0000-4000-8000-000000000001","type":"ISSUE_PUNISHMENT","ts":1767225600000,"node":"web-1",
 "payload":{"requestId":"b9a3f4c0-6a1e-4f0e-9d58-7c2f5e8a1d33","actor":{"name":"Web: alice","ref":"42"},
  "target":{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5"},"kind":"TEMPBAN","reasonId":"cheats","duration":"7d",
  "silent":false,"note":"seen flying, ticket 1187"},
 "sig":"ekTWdVz1ONSZ2mM42lchm1c5l8QGZsxcE/mMSSrn4Wg="}
```

### 6.3 `REVOKE_PUNISHMENT`

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

### 6.4 `UPDATE_PUNISHMENT`

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

Example: [`examples/update-punishment.json`](schemas/examples/update-punishment.json). Schema: [`update-punishment`](schemas/update-punishment.schema.json).

### 6.5 `PING`

Payload: just `requestId`. Reply payload adds `"node":"<id of the answering node>","pong":true`. Use it for connectivity checks,
for testing the secret, and as a liveness probe of the command path. Because exactly one node answers each request, it proves
*a* node is alive, not every node.

### 6.6 `RELOAD_REASONS`

Payload: `requestId`. The proxy re-reads `reasons.yml`, syncs `jb_reasons`, and answers with `"reloaded": <number of reasons>`.
Every node then reloads its catalogue on the resulting `REASONS_UPDATED` event. A file with errors gives `INVALID_REQUEST`
and leaves the previous reasons active.

### 6.7 `COMMAND_RESULT`

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

## 7. Error codes

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

## 8. Events (plugin → everyone)

Published on `justbans:events` by the node that caused the change, signed, with `seq` set. The payload is a
**full snapshot** ([`punishment.schema.json`](schemas/punishment.schema.json)), so consumers rarely need to query anything.
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

Complete signed examples: [`punishment-created.json`](schemas/examples/punishment-created.json),
[`punishment-revoked.json`](schemas/examples/punishment-revoked.json).

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

## 9. The `seq` mechanism and catching up

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
   process those rows (read-only, see [section 2](#2-database-access-read-only)), then process the buffered live messages, skipping any
   `seq` already handled.
2. *Live:* verify the signature, drop a repeated `id` or already-processed `seq`, process, advance `lastSeq` to `max(lastSeq, seq)`.
3. *Suspected gap:* if `seq > lastSeq + 1`, wait a moment (about 2 s) for the missing numbers to arrive from another node. Anything still
   missing: run the query above (`id > lastSeq`). Rows that exist are real events you missed; ids with no row were rolled back and can be ignored.
   Do the same after any Redis reconnect.
4. *Rows that are not yet published* (`published_at IS NULL`) are valid events too; the database is the truth, publication is only the notification.

Caveats: the `payload` column is the same JSON as on Redis but is **not signed** (you read it over your own authenticated database
connection instead). Retention of old `jb_events` rows is a planned housekeeping job; if your cursor is older than the retained range,
rebuild from `jb_punishments` instead of replaying.

## 10. Redis security

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

## 11. Versioning and compatibility

- `v` is the protocol major version. Additive changes (new optional payload fields, new event or error codes, new command types) keep `v = 1`:
  **clients must ignore unknown fields and unknown event types**, and must not rely on field order.
- A breaking change bumps `v`; nodes drop messages with a version they do not know.
- The JSON Schemas in `docs/schemas/` describe version 1. They are intentionally strict on commands (`additionalProperties: false`) to catch typos
  before sending; the plugin itself tolerates unknown fields.

## 12. Known limitations

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

## 13. Reference client

`tools/web-client.py` (Python 3.9+, `pip install redis`) is an executable version of this document.

```sh
python3 tools/web-client.py selftest                    # no Redis: checks canonical JSON + HMAC against section 5.4
export JUSTBANS_REDIS_URL=redis://:password@127.0.0.1:6379/0
export JUSTBANS_SECRET='the same web-secret as in config.yml'
python3 tools/web-client.py ping                        # signs a PING, waits for the PONG, exit code 0 on success
python3 tools/web-client.py listen                      # prints verified events, reports seq jumps
python3 tools/web-client.py ban --player Notch --reason cheats --actor-name "Web: alice" --actor-ref 42
python3 tools/web-client.py pardon --player Notch --reason "Appeal accepted" --actor-name "Web: alice"
python3 tools/web-client.py edit --punishment-id 4211 --actor-name "Web: alice" --in 14d --reason-text "Flying near spawn"
```

Exit codes: `0` OK, `1` the plugin answered `ERROR`, `2` no answer in time, `3` bad usage or Redis unreachable.

How this is tested: `selftest` and `HmacSignerTest` pin the same vectors; `WebClientInteropTest` (opt-in: set `JUSTBANS_TEST_PYTHON` to a Python with
redis-py, plus Redis and MariaDB as for the other integration tests, and run under a UTF-8 locale) runs the real client against the real
`WebCommandHandler` and `OutboxPublisher`: ping, ban, duplicate ban, pardon, scoped tempban with an emoji reason, edits (expiry, reason, note, permanent), a rejected edit, unknown player. It dumps every message to
`justbans-common/target/interop/`, which `python3 tools/check-schemas.py justbans-common/target/interop/*.json` validates against the schemas
(`pip install jsonschema`). `python3 tools/check-schemas.py docs/schemas/examples/*.json` validates the shipped examples.
