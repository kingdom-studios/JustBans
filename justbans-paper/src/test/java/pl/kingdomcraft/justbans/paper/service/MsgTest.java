package pl.kingdomcraft.justbans.paper.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class MsgTest {

	@Test
	void fillsPlaceholders() {
		assertEquals("Banned Notch (#ABC)", Msg.fill("Banned {target} (#{id})", "target", "Notch", "id", "ABC"));
	}

	@Test
	void repeatedAndMissingPlaceholders() {
		assertEquals("a a {b}", Msg.fill("{x} {x} {b}", "x", "a"));
		assertEquals("plain", Msg.fill("plain"));
	}

	@Test
	void nullValuesBecomeEmpty() {
		assertEquals("[]", Msg.fill("[{x}]", "x", null));
	}

	@Test
	void valuesAreInsertedLiterallyAndNeverExpanded() {
		assertEquals("{id} ABC", Msg.fill("{a} {id}", "a", "{id}", "id", "ABC"));
		assertEquals("$1 \\ x", Msg.fill("{a} {b}", "a", "$1", "b", "\\ x"), "regex replacement characters in values are harmless");
	}

	@Test
	void oddArgumentCountIsAProgrammerError() {
		assertThrows(IllegalArgumentException.class, () -> Msg.fill("{x}", "x"));
	}

	@Test
	void onlineNamesParsesHashValuesAndSkipsGarbage() {
		final Set<String> names = OnlineNames.parse(java.util.List.of("{\"name\":\"Notch\",\"server\":\"lobby\"}", "{\"name\":\"Alex\"}", "not json", "{\"server\":\"x\"}", "[1]", "{\"name\":{\"a\":1}}", ""));

		assertEquals(Set.of("Notch", "Alex"), names);
	}

	@Test
	void resolverRecognisesUuidsAndNames() {
		assertTrue(PlayerResolver.looksLikeUuid("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
		assertEquals(false, PlayerResolver.looksLikeUuid("Notch"));
		assertEquals(false, PlayerResolver.looksLikeUuid("069a79f444e94726a5befca90e38aaf5"));

		assertTrue(PlayerResolver.isValidName("Notch"));
		assertTrue(PlayerResolver.isValidName("a_b_1"));
		assertTrue(PlayerResolver.isValidName(".BedrockPlayer"));
		assertEquals(false, PlayerResolver.isValidName("bad name"));
		assertEquals(false, PlayerResolver.isValidName("x".repeat(18)));
		assertEquals(false, PlayerResolver.isValidName(""));
		assertEquals(false, PlayerResolver.isValidName("a;DROP TABLE"));
	}
}
