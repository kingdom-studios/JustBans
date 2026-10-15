package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.json.PunishmentJson;

/**
 * The example messages in docs/schemas/examples are signed with the documented test secret. Reading them with the
 * real {@link Envelope} parser and verifying them with the real {@link HmacSigner} keeps the documentation honest.
 */
class ProtocolExamplesTest {

	private static final Path EXAMPLES = Path.of("..", "docs", "schemas", "examples");
	private final HmacSigner signer = new HmacSigner("justbans-test-secret-0123456789");

	private List<Path> files() throws IOException {
		try (Stream<Path> stream = Files.list(EXAMPLES)) {
			return stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}
	}

	@Test
	void everyExampleParsesAndVerifies() throws Exception {
		final List<Path> files = files();

		assertTrue(files.size() >= 6, "examples are missing");

		for (final Path file : files) {
			final Envelope envelope = Envelope.fromJson(Files.readString(file, StandardCharsets.UTF_8));

			assertTrue(signer.verify(envelope), file + " has a signature that does not verify");
		}
	}

	@Test
	void eventExamplesCarryReadableSnapshots() throws Exception {
		for (final String name : new String[] { "punishment-created", "punishment-revoked" }) {
			final Envelope envelope = Envelope.fromJson(Files.readString(EXAMPLES.resolve(name + ".json"), StandardCharsets.UTF_8));

			assertEquals(name.toUpperCase().replace('-', '_'), envelope.type());
			assertTrue(envelope.seq() != null);
			assertEquals("K7Q2M9XA", PunishmentJson.fromEventPayload(envelope.payload()).publicId());
		}

		assertFalse(PunishmentJson.fromEventPayload(Envelope.fromJson(Files.readString(EXAMPLES.resolve("punishment-created.json"), StandardCharsets.UTF_8)).payload()).isRevoked());
	}
}
