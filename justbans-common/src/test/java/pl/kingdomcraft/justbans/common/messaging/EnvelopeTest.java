package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class EnvelopeTest {

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void roundTripsThroughJson() throws Exception {
		final Envelope original = new Envelope(1, UUID.randomUUID().toString(), "PUNISHMENT_CREATED", 1760430000123L, "velocity-1", 42L,
				json("{\"punishment\":{\"id\":7,\"reason\":{\"text\":\"Zoë \\\"quoted\\\"\"}}}"), "c2ln");

		assertEquals(original, Envelope.fromJson(original.toJson()));
	}

	@Test
	void seqAndSigAreOptional() throws Exception {
		final Envelope parsed = Envelope.fromJson("{\"v\":1,\"id\":\"i\",\"type\":\"PING\",\"ts\":5,\"node\":\"web\",\"payload\":{}}");

		assertNull(parsed.seq());
		assertNull(parsed.sig());
		assertTrue(parsed.toJson().indexOf("\"seq\"") < 0);
		assertTrue(parsed.toJson().indexOf("\"sig\"") < 0);
	}

	@Test
	void nullSeqAndSigAreTreatedAsAbsent() throws Exception {
		final Envelope parsed = Envelope.fromJson("{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":5,\"node\":\"n\",\"seq\":null,\"sig\":null,\"payload\":{}}");

		assertNull(parsed.seq());
		assertNull(parsed.sig());
	}

	@Test
	void jsonContainsEveryDocumentedField() {
		final JsonObject written = json(new Envelope(1, "id-1", "T", 99L, "node-1", 3L, json("{\"a\":1}"), "sig-1").toJson());

		for (final String key : new String[] { "v", "id", "type", "ts", "node", "seq", "payload", "sig" })
			assertTrue(written.has(key), "missing " + key);
	}

	@Test
	void createStampsClockTimeAndRandomIds() {
		final Clock clock = Clock.fixed(Instant.ofEpochMilli(123_456), ZoneOffset.UTC);

		final Envelope a = Envelope.create("PING", "node", null, new JsonObject(), clock);
		final Envelope b = Envelope.create("PING", "node", null, new JsonObject(), clock);

		assertEquals(123_456, a.ts());
		assertEquals(Envelope.VERSION, a.v());
		assertNotEquals(a.id(), b.id());
		assertNull(a.sig());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"",
			"not json",
			"[]",
			"\"string\"",
			"null",
			"{}",
			"{\"v\":2,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"v\":\"1\",\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":5,\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"i\",\"ts\":1,\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":\"soon\",\"node\":\"n\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"payload\":{}}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\"}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":[]}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":null}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"seq\":\"x\",\"payload\":{}}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"sig\":[],\"payload\":{}}",
			"{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{}" })
	void rejectsInvalidEnvelopes(String text) {
		assertThrows(Envelope.InvalidEnvelopeException.class, () -> Envelope.fromJson(text));
	}

	// ---- canonical JSON ------------------------------------------------------------------

	@Test
	void canonicalJsonSortsKeysRecursivelyWithoutWhitespace() {
		assertEquals("{\"a\":{\"x\":1,\"y\":[3,{\"b\":null,\"c\":false}]},\"b\":\"s\"}",
				CanonicalJson.write(json("{ \"b\" : \"s\", \"a\" : { \"y\" : [ 3, { \"c\": false, \"b\": null } ], \"x\": 1 } }")));
	}

	@Test
	void canonicalJsonKeepsArrayOrder() {
		assertEquals("{\"l\":[3,1,2]}", CanonicalJson.write(json("{\"l\":[3,1,2]}")));
	}

	@Test
	void canonicalJsonEscapesOnlyWhatIsNecessary() {
		final JsonObject payload = new JsonObject();
		payload.addProperty("s", "q\" b\\ n\n r\r t\t f\f b\b c\u0001 u\u00e9 \u2028 </script>");

		assertEquals("{\"s\":\"q\\\" b\\\\ n\\n r\\r t\\t f\\f b\\b c\\u0001 u\u00e9 \u2028 </script>\"}", CanonicalJson.write(payload));
	}

	@Test
	void canonicalJsonPreservesNumberText() throws Exception {
		final Envelope parsed = Envelope.fromJson("{\"v\":1,\"id\":\"i\",\"type\":\"T\",\"ts\":1,\"node\":\"n\",\"payload\":{\"big\":1760430000123,\"small\":0.5,\"neg\":-3}}");

		assertEquals("{\"big\":1760430000123,\"neg\":-3,\"small\":0.5}", CanonicalJson.write(parsed.payload()));
	}

	@Test
	void signatureSurvivesTransportThroughJson() throws Exception {
		final HmacSigner signer = new HmacSigner("0123456789abcdef-test-secret");
		final Envelope signed = signer.sign(new Envelope(1, "i", "T", 1, "n", 9L, json("{\"z\":[1,2],\"a\":\"é\",\"n\":1.50}"), null));

		final Envelope received = Envelope.fromJson(signed.toJson());

		assertTrue(signer.verify(received));
		assertEquals(signed.sig(), received.sig());
	}
}
