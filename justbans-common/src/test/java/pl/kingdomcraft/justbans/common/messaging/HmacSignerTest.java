package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class HmacSignerTest {

	private static final String SECRET = "0123456789abcdef-test-secret";

	/** Produced independently with Python's hmac/hashlib so the Java and the web side cannot drift unnoticed. */
	private static final String HELLO_SIGNATURE = "wpBKOF5C8JOW7X8kdQEoTkzLAjnXCnu/SOlzOpNUgW0=";
	private static final String EVENT_SIGNATURE = "ct7VQqmTYRIlUl69Lm3Wb6hlrEnra1G/6eTtaSCAnmI=";
	private static final String PING_SIGNATURE = "G5gLjdOEhtgpHkdicT/CYm98g4oPPjVdGx3YS4Gz1A4=";

	private final HmacSigner signer = new HmacSigner(SECRET);

	private static Envelope event() {
		final JsonObject payload = JsonParser.parseString("{\"zeta\":1,\"alpha\":{\"b\":true,\"a\":[1,\"x\\\"y\",null]},\"name\":\"Zoë \\n\"}").getAsJsonObject();

		return new Envelope(1, "8d3c1c9e-0000-4000-8000-000000000001", "PUNISHMENT_CREATED", 1760430000123L, "velocity-1", 18842L, payload, null);
	}

	/** The vectors printed in docs/redis-protocol.md and asserted by tools/web-client.py selftest. */
	@Test
	void matchesPublishedProtocolVectors() {
		final HmacSigner published = new HmacSigner("justbans-test-secret-0123456789");

		final JsonObject payload = new JsonObject();
		payload.addProperty("requestId", "0b7d8c2e-52a1-4f7e-8d2a-3a0c9f1b6e44");
		payload.addProperty("note", "caf\u00e9 \"quoted\" \\ \u0001");

		final Envelope ping = new Envelope(1, "6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10", "PING", 1767225600000L, "web-1", null, payload, null);

		assertEquals("1|6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b10|PING|1767225600000|web-1||{\"note\":\"caf\u00e9 \\\"quoted\\\" \\\\ \\u0001\",\"requestId\":\"0b7d8c2e-52a1-4f7e-8d2a-3a0c9f1b6e44\"}", ping.signingString());
		assertEquals("NGxZ/e4tqNzxw1H1mtRp+wRST/LQiCBYQHUgzzBWEuQ=", published.sign(ping).sig());

		final JsonObject punishment = new JsonObject();
		punishment.addProperty("id", 7);
		punishment.addProperty("publicId", "A1B2C3D4");
		final JsonObject eventPayload = new JsonObject();
		eventPayload.add("punishment", punishment);

		final Envelope revoked = new Envelope(1, "6f0b1a52-3d1e-4c58-9a57-0a1d5d3f2b11", "PUNISHMENT_REVOKED", 1767225601234L, "velocity-1", 42L, eventPayload, null);

		assertEquals("zs8ngliPqgtlX+fq8Vq3jk7zzvRWFdJ6MiGRbNmMVls=", published.sign(revoked).sig());
	}

	@Test
	void matchesKnownVectors() {
		assertEquals(HELLO_SIGNATURE, signer.sign("hello"));
		assertEquals(EVENT_SIGNATURE, signer.sign(event()).sig());

		final Envelope ping = new Envelope(1, "8d3c1c9e-0000-4000-8000-000000000002", "PING", 1760430000123L, "web", null, new JsonObject(), null);
		assertEquals(PING_SIGNATURE, signer.sign(ping).sig(), "an absent seq signs as an empty field");
	}

	@Test
	void signingStringIsPipeSeparatedWithCanonicalPayload() {
		assertEquals("1|8d3c1c9e-0000-4000-8000-000000000001|PUNISHMENT_CREATED|1760430000123|velocity-1|18842|"
				+ "{\"alpha\":{\"a\":[1,\"x\\\"y\",null],\"b\":true},\"name\":\"Zoë \\n\",\"zeta\":1}", event().signingString());
	}

	@Test
	void signedEnvelopeVerifies() {
		assertTrue(signer.verify(signer.sign(event())));
	}

	@Test
	void unsignedEnvelopeNeverVerifies() {
		assertNull(event().sig());
		assertFalse(signer.verify(event()));
	}

	@Test
	void anyTamperedFieldBreaksTheSignature() {
		final Envelope good = signer.sign(event());
		final JsonObject otherPayload = new JsonObject();
		otherPayload.addProperty("x", 1);

		assertFalse(signer.verify(new Envelope(good.v() + 1, good.id(), good.type(), good.ts(), good.node(), good.seq(), good.payload(), good.sig())));
		assertFalse(signer.verify(new Envelope(good.v(), "other-id", good.type(), good.ts(), good.node(), good.seq(), good.payload(), good.sig())));
		assertFalse(signer.verify(new Envelope(good.v(), good.id(), "PUNISHMENT_REVOKED", good.ts(), good.node(), good.seq(), good.payload(), good.sig())));
		assertFalse(signer.verify(new Envelope(good.v(), good.id(), good.type(), good.ts() + 1, good.node(), good.seq(), good.payload(), good.sig())));
		assertFalse(signer.verify(new Envelope(good.v(), good.id(), good.type(), good.ts(), "evil", good.seq(), good.payload(), good.sig())));
		assertFalse(signer.verify(new Envelope(good.v(), good.id(), good.type(), good.ts(), good.node(), 18843L, good.payload(), good.sig())));
		assertFalse(signer.verify(new Envelope(good.v(), good.id(), good.type(), good.ts(), good.node(), null, good.payload(), good.sig())));
		assertFalse(signer.verify(new Envelope(good.v(), good.id(), good.type(), good.ts(), good.node(), good.seq(), otherPayload, good.sig())));
	}

	@Test
	void garbageSignatureFails() {
		assertFalse(signer.verify(event().withSignature("")));
		assertFalse(signer.verify(event().withSignature("not base64 !!")));
		assertFalse(signer.verify(event().withSignature(EVENT_SIGNATURE.substring(1))));
	}

	@Test
	void differentSecretDoesNotVerify() {
		final HmacSigner other = new HmacSigner("a-completely-different-secret");

		assertNotEquals(signer.sign("x"), other.sign("x"));
		assertFalse(other.verify(signer.sign(event())));
	}

	@Test
	void payloadKeyOrderDoesNotMatter() {
		final Envelope a = new Envelope(1, "i", "T", 1, "n", null, JsonParser.parseString("{\"a\":1,\"b\":2}").getAsJsonObject(), null);
		final Envelope b = new Envelope(1, "i", "T", 1, "n", null, JsonParser.parseString("{\"b\":2,\"a\":1}").getAsJsonObject(), null);

		assertEquals(signer.sign(a).sig(), signer.sign(b).sig());
	}

	@Test
	void rejectsWeakOrMissingSecrets() {
		assertThrows(IllegalArgumentException.class, () -> new HmacSigner(null));
		assertThrows(IllegalArgumentException.class, () -> new HmacSigner(""));
		assertThrows(IllegalArgumentException.class, () -> new HmacSigner("short"));
		new HmacSigner("x".repeat(HmacSigner.MIN_SECRET_LENGTH));
	}

	@Test
	void freshnessWindowIsSymmetric() {
		final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC);
		final Duration skew = Duration.ofSeconds(30);

		assertTrue(HmacSigner.isFresh(at(1_000_000), clock, skew));
		assertTrue(HmacSigner.isFresh(at(1_000_000 - 30_000), clock, skew));
		assertTrue(HmacSigner.isFresh(at(1_000_000 + 30_000), clock, skew));
		assertFalse(HmacSigner.isFresh(at(1_000_000 - 30_001), clock, skew));
		assertFalse(HmacSigner.isFresh(at(1_000_000 + 30_001), clock, skew));
	}

	private static Envelope at(long ts) {
		return new Envelope(1, "i", "T", ts, "n", null, new JsonObject(), null);
	}
}
