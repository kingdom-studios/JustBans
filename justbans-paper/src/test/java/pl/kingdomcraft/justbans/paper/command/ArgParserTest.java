package pl.kingdomcraft.justbans.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Scope;

class ArgParserTest {

	private static ArgParser.Parsed parse(String line) {
		return ArgParser.parse(line.isEmpty() ? new String[0] : line.split(" "));
	}

	@Test
	void plainWordsPassThrough() {
		final ArgParser.Parsed p = parse("Notch cheats");

		assertEquals(List.of("Notch", "cheats"), p.words());
		assertFalse(p.silent());
		assertFalse(p.force());
		assertNull(p.scope());
		assertFalse(p.isScoped());
		assertFalse(p.hasScopeError());
	}

	@Test
	void flagsAreRemovedFromAnywhereInTheLine() {
		final ArgParser.Parsed p = parse("-s Notch -f cheats");

		assertEquals(List.of("Notch", "cheats"), p.words());
		assertTrue(p.silent());
		assertTrue(p.force());
	}

	@Test
	void flagsAreCaseInsensitive() {
		final ArgParser.Parsed p = parse("Notch cheats -S -F");

		assertTrue(p.silent());
		assertTrue(p.force());
		assertEquals(2, p.words().size());
	}

	@Test
	void serverAndGroupScopes() {
		assertEquals(Scope.server("survival"), parse("Notch cheats server:survival").scope());
		assertEquals(Scope.group("skyblock"), parse("Notch cheats group:skyblock").scope());
		assertEquals(Scope.server("Skyblock-1"), parse("Notch SERVER:Skyblock-1 cheats").scope(), "the keyword is case-insensitive, the server name keeps its case");
		assertTrue(parse("Notch cheats server:survival").isScoped());
		assertEquals(List.of("Notch", "cheats"), parse("Notch cheats server:survival").words());
	}

	@Test
	void contradictoryOrEmptyScopesAreErrors() {
		assertTrue(parse("Notch cheats server:a group:b").hasScopeError());
		assertTrue(parse("Notch cheats server:a server:b").hasScopeError());
		assertTrue(parse("Notch cheats server:").hasScopeError());
		assertTrue(parse("Notch cheats group:").hasScopeError());
	}

	@Test
	void wordHelpers() {
		final ArgParser.Parsed p = parse("Notch griefed the spawn badly");

		assertEquals("Notch", p.word(0));
		assertEquals("griefed", p.word(1));
		assertNull(p.word(9));
		assertEquals("griefed the spawn badly", p.join(1));
		assertEquals("spawn badly", p.join(3));
		assertEquals("", p.join(5));
		assertEquals("", p.join(99));
	}

	@Test
	void emptyLine() {
		assertTrue(parse("").words().isEmpty());
	}

	@Test
	void aColonInAnOrdinaryWordIsNotAScope() {
		final ArgParser.Parsed p = parse("Notch note:important");

		assertEquals(List.of("Notch", "note:important"), p.words());
		assertNull(p.scope());
	}
}
