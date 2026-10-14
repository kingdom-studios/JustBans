package pl.kingdomcraft.justbans.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;

class PunishmentStateTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

	private static Punishment p(PunishmentType type, Instant expires, boolean revoked) {
		return new Punishment(1, "ABCDEFGH", type, Scope.network(), UUID.randomUUID(), "T", Actor.console(), null, "r", null, NOW.minusSeconds(100), expires, "proxy", false, null,
				revoked ? new Punishment.Revocation(NOW.minusSeconds(5), Actor.console(), null) : null);
	}

	@Test
	void classifiesEveryCase() {
		assertEquals(PunishmentState.ACTIVE, PunishmentState.of(p(PunishmentType.BAN, null, false), NOW));
		assertEquals(PunishmentState.ACTIVE, PunishmentState.of(p(PunishmentType.BAN, NOW.plusSeconds(1), false), NOW));
		assertEquals(PunishmentState.ACTIVE, PunishmentState.of(p(PunishmentType.WARN, null, false), NOW));
		assertEquals(PunishmentState.EXPIRED, PunishmentState.of(p(PunishmentType.BAN, NOW, false), NOW));
		assertEquals(PunishmentState.EXPIRED, PunishmentState.of(p(PunishmentType.WARN, NOW.minusSeconds(1), false), NOW));
		assertEquals(PunishmentState.REVOKED, PunishmentState.of(p(PunishmentType.BAN, null, true), NOW));
		assertEquals(PunishmentState.KICK, PunishmentState.of(p(PunishmentType.KICK, null, false), NOW));
	}

	@Test
	void revokedWinsOverExpired() {
		assertEquals(PunishmentState.REVOKED, PunishmentState.of(p(PunishmentType.BAN, NOW.minusSeconds(50), true), NOW));
	}
}
