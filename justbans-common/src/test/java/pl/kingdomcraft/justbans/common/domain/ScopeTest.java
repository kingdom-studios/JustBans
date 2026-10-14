package pl.kingdomcraft.justbans.common.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScopeTest {

	@Test
	void networkCoversEverything() {
		assertTrue(Scope.network().covers("survival", null));
		assertEquals("*", new Scope(ScopeType.NETWORK, "ignored").target());
	}

	@Test
	void serverMatchesIgnoringCase() {
		assertTrue(Scope.server("Survival").covers("survival", null));
		assertFalse(Scope.server("survival").covers("lobby", null));
	}

	@Test
	void groupMatchesServerGroup() {
		assertTrue(Scope.group("skyblock").covers("skyblock-2", "skyblock"));
		assertFalse(Scope.group("skyblock").covers("skyblock-2", null));
	}

	@Test
	void concreteScopesRejectWildcardTarget() {
		assertThrows(IllegalArgumentException.class, () -> Scope.server("*"));
		assertThrows(IllegalArgumentException.class, () -> Scope.group(" "));
	}
}
