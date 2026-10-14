package pl.kingdomcraft.justbans.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;

class PunishmentJsonTest {

	private static final Instant T = Instant.parse("2026-10-14T12:00:00.123Z");

	private static Punishment full() {
		return new Punishment(4211, "K7Q2M9XA", PunishmentType.BAN, Scope.group("skyblock"), UUID.randomUUID(), "Notch", Actor.player(UUID.randomUUID(), "Admin"),
				"cheats", "Cheating", null, T, T.plusSeconds(3600), "survival", true, 2,
				new Punishment.Revocation(T.plusSeconds(10), Actor.web("u-1", "Panel"), "appeal"));
	}

	@Test
	void roundTripsThroughAnEventPayloadString() {
		final Punishment original = full();

		final JsonObject payload = JsonParser.parseString(PunishmentJson.eventPayload(original)).getAsJsonObject();

		assertEquals(original, PunishmentJson.fromEventPayload(payload));
	}

	@Test
	void roundTripsMinimalPunishmentWithNulls() {
		final Punishment minimal = new Punishment(1, "AAAAAAAA", PunishmentType.KICK, Scope.network(), UUID.randomUUID(), "X", Actor.console(), null, "why", null, T, null, null, false, null, null);

		final JsonObject payload = JsonParser.parseString(PunishmentJson.eventPayload(minimal)).getAsJsonObject();
		final Punishment parsed = PunishmentJson.fromEventPayload(payload);

		assertEquals(minimal, parsed);
		assertNull(parsed.expiresAt());
		assertNull(parsed.reasonId());
		assertNull(parsed.revocation());
	}

	@Test
	void theStaffNoteIsNeverPublished() {
		final Punishment withNote = new Punishment(1, "AAAAAAAA", PunishmentType.BAN, Scope.network(), UUID.randomUUID(), "X", Actor.console(), null, "why", "secret note", T, null, null, false, null, null);

		assertEquals(-1, PunishmentJson.eventPayload(withNote).indexOf("secret"));
		assertNull(PunishmentJson.fromEventPayload(JsonParser.parseString(PunishmentJson.eventPayload(withNote)).getAsJsonObject()).note());
	}

	@Test
	void malformedSnapshotsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> PunishmentJson.fromEventPayload(new JsonObject()));
		assertThrows(IllegalArgumentException.class, () -> PunishmentJson.fromEventPayload(JsonParser.parseString("{\"punishment\":{\"id\":1}}").getAsJsonObject()));
		assertThrows(IllegalArgumentException.class, () -> PunishmentJson.fromEventPayload(JsonParser.parseString("{\"punishment\":5}").getAsJsonObject()));
	}
}
