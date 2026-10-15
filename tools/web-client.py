#!/usr/bin/env python3
"""
JustBans reference web client: a smoke test for the Redis protocol (docs/redis-protocol.md).

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
  python3 tools/web-client.py selftest

  # 2. Connectivity + signature check against a running network. Prints the PONG, exit code 0 on success.
  export JUSTBANS_SECRET='the-same-secret-as-in-config.yml'
  python3 tools/web-client.py ping

  # 3. Watch events as they happen (Ctrl+C to stop). Verifies signatures and reports `seq` jumps.
  python3 tools/web-client.py listen

  # 4. Issue / revoke punishments. These really change data: use a test player.
  python3 tools/web-client.py ban --player Notch --reason hacking --actor-name "Web: alice" --actor-ref 42
  python3 tools/web-client.py ban --player Notch --kind TEMPBAN --duration 7d --custom-reason "Griefing"
  python3 tools/web-client.py pardon --player Notch --reason "Appeal accepted" --actor-name "Web: alice"
  python3 tools/web-client.py pardon --punishment-id 1234 --actor-name "Web: alice"

Exit codes: 0 success, 1 the plugin answered with status ERROR, 2 no answer in time, 3 bad usage / local failure.

This file only ever WRITES through Redis. The web backend must not write to the JustBans database;
give it a read-only database user (see docs/redis-protocol.md, section "Database access").
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
# Test vectors (identical to docs/redis-protocol.md and to HmacSignerTest.java)
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
