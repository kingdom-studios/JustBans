package pl.kingdomcraft.justbans.velocity.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;

class BanMatcherTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
	private static long ids;

	static Punishment p(PunishmentType type, Scope scope, Instant expires, boolean revoked) {
		return new Punishment(++ids, "ID" + ids, type, scope, UUID.randomUUID(), "T", Actor.console(), null, "r", null, NOW.minusSeconds(100), expires, "proxy", false, null,
				revoked ? new Punishment.Revocation(NOW.minusSeconds(1), Actor.console(), null) : null);
	}

	@Test
	void networkBanIgnoresServerScopesExpiredAndRevoked() {
		final Punishment server = p(PunishmentType.BAN, Scope.server("survival"), null, false);
		final Punishment expired = p(PunishmentType.BAN, Scope.network(), NOW.minusSeconds(1), false);
		final Punishment revoked = p(PunishmentType.BAN, Scope.network(), null, true);
		final Punishment warn = p(PunishmentType.WARN, Scope.network(), null, false);

		assertTrue(BanMatcher.networkBan(List.of(server, expired, revoked, warn), NOW).isEmpty());
		assertTrue(BanMatcher.networkBan(List.of(), NOW).isEmpty());
	}

	@Test
	void cachedBansThatExpiredSinceAreIgnored() {
		final Punishment temp = p(PunishmentType.BAN, Scope.network(), NOW.plusSeconds(10), false);

		assertTrue(BanMatcher.networkBan(List.of(temp), NOW).isPresent());
		assertTrue(BanMatcher.networkBan(List.of(temp), NOW.plusSeconds(11)).isEmpty());
	}

	@Test
	void permanentOutranksTemporaryAndLongestTemporaryWins() {
		final Punishment shortBan = p(PunishmentType.BAN, Scope.network(), NOW.plusSeconds(10), false);
		final Punishment longBan = p(PunishmentType.BAN, Scope.network(), NOW.plusSeconds(1000), false);
		final Punishment perm = p(PunishmentType.BAN, Scope.network(), null, false);

		assertEquals(longBan, BanMatcher.networkBan(List.of(shortBan, longBan), NOW).orElseThrow());
		assertEquals(perm, BanMatcher.networkBan(List.of(shortBan, perm, longBan), NOW).orElseThrow());
	}

	@Test
	void serverAndGroupBansOnlyCoverTheirServers() {
		final Punishment survival = p(PunishmentType.BAN, Scope.server("survival"), null, false);
		final Punishment skyblock = p(PunishmentType.BAN, Scope.group("skyblock"), null, false);
		final List<Punishment> bans = List.of(survival, skyblock);

		assertEquals(survival, BanMatcher.banFor(bans, "survival", null, NOW).orElseThrow());
		assertEquals(skyblock, BanMatcher.banFor(bans, "skyblock-2", "skyblock", NOW).orElseThrow());
		assertTrue(BanMatcher.banFor(bans, "lobby", null, NOW).isEmpty());
		assertTrue(BanMatcher.banFor(bans, "skyblock-2", null, NOW).isEmpty(), "no group configured means no group match");
	}

	@Test
	void aNetworkBanAlsoBlocksEveryServer() {
		final Punishment network = p(PunishmentType.BAN, Scope.network(), null, false);

		assertTrue(BanMatcher.banFor(List.of(network), "lobby", null, NOW).isPresent());
		assertTrue(BanMatcher.isNetworkWide(network));
		assertTrue(!BanMatcher.isNetworkWide(p(PunishmentType.BAN, Scope.server("x"), null, false)));
	}
}
