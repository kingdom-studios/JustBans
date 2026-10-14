package pl.kingdomcraft.justbans.velocity.gate;

import java.time.Instant;
import java.util.UUID;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;

/** Builds punishments for gate tests. */
final class TestBans {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
	private static long ids;

	private TestBans() {
	}

	static Punishment p(PunishmentType type, Scope scope, Instant expires, boolean revoked) {
		return new Punishment(++ids, "ID" + ids, type, scope, UUID.randomUUID(), "T", Actor.console(), null, "r", null, NOW.minusSeconds(100), expires, "proxy", false, null,
				revoked ? new Punishment.Revocation(NOW.minusSeconds(1), Actor.console(), null) : null);
	}
}
