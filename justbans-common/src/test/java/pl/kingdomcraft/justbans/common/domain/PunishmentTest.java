package pl.kingdomcraft.justbans.common.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PunishmentTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

	private static Punishment punishment(PunishmentType type, Instant expiresAt, Punishment.Revocation revocation) {
		return new Punishment(1, "K7Q2M9XA", type, Scope.network(), UUID.randomUUID(), "Notch", Actor.console(),
				"cheats", "Cheating", null, NOW.minusSeconds(60), expiresAt, "proxy", false, null, revocation);
	}

	@Test
	void permanentBanIsActive() {
		assertTrue(punishment(PunishmentType.BAN, null, null).isActive(NOW));
	}

	@Test
	void tempBanExpires() {
		assertTrue(punishment(PunishmentType.BAN, NOW.plusSeconds(1), null).isActive(NOW));
		assertFalse(punishment(PunishmentType.BAN, NOW, null).isActive(NOW));
	}

	@Test
	void revokedIsNeverActive() {
		assertFalse(punishment(PunishmentType.BAN, null, new Punishment.Revocation(NOW, Actor.console(), null)).isActive(NOW));
	}

	@Test
	void kickIsNeverActive() {
		assertFalse(punishment(PunishmentType.KICK, null, null).isActive(NOW));
	}
}
