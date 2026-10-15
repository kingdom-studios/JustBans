package pl.kingdomcraft.justbans.paper.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.paper.placeholder.PlaceholderValues.Texts;
import pl.kingdomcraft.justbans.paper.service.PlayerSnapshots.Snapshot;

class PlaceholderValuesTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
	private static final Texts TEXTS = new Texts("none", "...", "permanent", "{type}: {reason} ({ago} ago)");

	private static Punishment punishment(PunishmentType type, Scope scope, String reason, Instant createdAt, Instant expiresAt) {
		return new Punishment(1, "K7Q2M9XA", type, scope, UUID.randomUUID(), "Notch", Actor.player(UUID.randomUUID(), "Admin"), null, reason, null, createdAt, expiresAt, "survival", false, null, null);
	}

	private static Snapshot snapshot(List<Punishment> bans, List<Punishment> warns, Punishment last) {
		return new Snapshot(bans, warns, last, NOW);
	}

	private static String value(String identifier, Snapshot snapshot) {
		return PlaceholderValues.resolve(identifier, snapshot, NOW, TEXTS);
	}

	@Test
	void showsTheLoadingTextUntilTheSnapshotArrives() {
		for (final String id : List.of("warns", "banned_servers", "last_punishment", "active_ban_expiry"))
			assertEquals("...", value(id, null), id);
	}

	@Test
	void unknownIdentifiersAreLeftToOtherExpansions() {
		assertNull(value("nonsense", snapshot(List.of(), List.of(), null)));
		assertFalse(PlaceholderValues.isKnown("nonsense"));
		assertTrue(PlaceholderValues.isKnown("WARNS"));
	}

	@Test
	void identifiersAreCaseInsensitive() {
		assertEquals("0", value("Warns", snapshot(List.of(), List.of(), null)));
	}

	@Test
	void countsOnlyWarnsThatAreStillActiveRightNow() {
		final Punishment ongoing = punishment(PunishmentType.WARN, Scope.network(), "a", NOW.minusSeconds(100), NOW.plus(Duration.ofDays(1)));
		final Punishment forever = punishment(PunishmentType.WARN, Scope.network(), "b", NOW.minusSeconds(100), null);
		final Punishment justExpired = punishment(PunishmentType.WARN, Scope.network(), "c", NOW.minusSeconds(100), NOW.minusSeconds(1));

		assertEquals("2", value("warns", snapshot(List.of(), List.of(ongoing, forever, justExpired), null)));
	}

	@Test
	void listsEachBannedServerOnceInOrder() {
		final Punishment skyblock = punishment(PunishmentType.BAN, Scope.server("skyblock-1"), "x", NOW.minusSeconds(5), null);
		final Punishment group = punishment(PunishmentType.BAN, Scope.group("minigames"), "x", NOW.minusSeconds(5), NOW.plusSeconds(60));
		final Punishment duplicate = punishment(PunishmentType.BAN, Scope.server("skyblock-1"), "x", NOW.minusSeconds(5), null);
		final Punishment expired = punishment(PunishmentType.BAN, Scope.server("old"), "x", NOW.minusSeconds(500), NOW.minusSeconds(1));

		assertEquals("skyblock-1, minigames", value("banned_servers", snapshot(List.of(skyblock, group, duplicate, expired), List.of(), null)));
		assertEquals("none", value("banned_servers", snapshot(List.of(expired), List.of(), null)));
		assertEquals("network", value("banned_servers", snapshot(List.of(punishment(PunishmentType.BAN, Scope.network(), "x", NOW, null)), List.of(), null)));
	}

	@Test
	void formatsTheLastPunishmentWithTheConfiguredTemplate() {
		final Punishment last = punishment(PunishmentType.BAN, Scope.network(), "Cheating {fly}", NOW.minus(Duration.ofHours(3)).minusSeconds(120), null);

		assertEquals("ban: Cheating {fly} (3h 2m ago)", value("last_punishment", snapshot(List.of(), List.of(), last)), "braces in a reason are not expanded");
		assertEquals("none", value("last_punishment", snapshot(List.of(), List.of(), null)));
		assertEquals("warn: x (0s ago)", value("last_punishment", snapshot(List.of(), List.of(), punishment(PunishmentType.WARN, Scope.network(), "x", NOW, null))));
	}

	@Test
	void showsTimeLeftOnTheSoonestEndingBanOrPermanent() {
		final Punishment week = punishment(PunishmentType.BAN, Scope.server("a"), "x", NOW, NOW.plus(Duration.ofDays(7)));
		final Punishment day = punishment(PunishmentType.BAN, Scope.server("b"), "x", NOW, NOW.plus(Duration.ofDays(1)).plusSeconds(3600));
		final Punishment forever = punishment(PunishmentType.BAN, Scope.server("c"), "x", NOW, null);

		assertEquals("1d 1h", value("active_ban_expiry", snapshot(List.of(week, day, forever), List.of(), null)));
		assertEquals("permanent", value("active_ban_expiry", snapshot(List.of(forever), List.of(), null)));
		assertEquals("none", value("active_ban_expiry", snapshot(List.of(), List.of(), null)));
	}

	@Test
	void aCountdownKeepsMovingBetweenLoads() {
		final Punishment ban = punishment(PunishmentType.BAN, Scope.server("a"), "x", NOW, NOW.plus(Duration.ofMinutes(10)));
		final Snapshot snapshot = snapshot(List.of(ban), List.of(), null);

		assertEquals("10m 0s", PlaceholderValues.resolve("active_ban_expiry", snapshot, NOW, TEXTS));
		assertEquals("4m 30s", PlaceholderValues.resolve("active_ban_expiry", snapshot, NOW.plusSeconds(330), TEXTS));
		assertEquals("none", PlaceholderValues.resolve("active_ban_expiry", snapshot, NOW.plus(Duration.ofMinutes(11)), TEXTS), "an expired ban stops being reported without waiting for a reload");
	}
}
