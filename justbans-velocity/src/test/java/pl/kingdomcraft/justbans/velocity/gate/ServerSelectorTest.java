package pl.kingdomcraft.justbans.velocity.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;

class ServerSelectorTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
	private static final Function<String, String> GROUPS = name -> name.startsWith("skyblock") ? "skyblock" : null;
	private static final List<String> ALL = List.of("lobby", "survival", "skyblock-1", "skyblock-2");

	private static Punishment ban(Scope scope) {
		return BanMatcherTest.p(PunishmentType.BAN, scope, null, false);
	}

	@Test
	void prefersConfiguredFallbacksInOrder() {
		assertEquals(Optional.of("lobby"), ServerSelector.firstAllowed(List.of("lobby", "survival"), ALL, Set.of(), List.of(), GROUPS, NOW));
		assertEquals(Optional.of("survival"), ServerSelector.firstAllowed(List.of("lobby", "survival"), ALL, Set.of("lobby"), List.of(), GROUPS, NOW));
	}

	@Test
	void skipsServersTheBanCovers() {
		assertEquals(Optional.of("survival"), ServerSelector.firstAllowed(List.of("lobby", "survival"), ALL, Set.of(), List.of(ban(Scope.server("lobby"))), GROUPS, NOW));
		assertEquals(Optional.of("lobby"), ServerSelector.firstAllowed(List.of("skyblock-1", "lobby"), ALL, Set.of(), List.of(ban(Scope.group("skyblock"))), GROUPS, NOW));
	}

	@Test
	void fallsBackToAnyOtherRegisteredServer() {
		assertEquals(Optional.of("survival"), ServerSelector.firstAllowed(List.of("lobby"), ALL, Set.of(), List.of(ban(Scope.server("lobby"))), GROUPS, NOW));
	}

	@Test
	void ignoresConfiguredFallbacksThatAreNotRegistered() {
		assertEquals(Optional.of("lobby"), ServerSelector.firstAllowed(List.of("ghost", "lobby"), ALL, Set.of(), List.of(), GROUPS, NOW));
	}

	@Test
	void emptyWhenNothingIsAllowed() {
		assertTrue(ServerSelector.firstAllowed(List.of("lobby"), ALL, Set.of(), List.of(ban(Scope.network())), GROUPS, NOW).isEmpty());
		assertTrue(ServerSelector.firstAllowed(List.of(), List.of(), Set.of(), List.of(), GROUPS, NOW).isEmpty());
		assertTrue(ServerSelector.firstAllowed(List.of("lobby"), List.of("lobby"), Set.of("lobby"), List.of(), GROUPS, NOW).isEmpty());
	}
}
