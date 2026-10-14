package pl.kingdomcraft.justbans.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.EventRecord;
import pl.kingdomcraft.justbans.common.domain.EventType;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition.LadderStep;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.repository.EventRepository;
import pl.kingdomcraft.justbans.common.repository.PunishmentRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.repository.ServerRepository;
import pl.kingdomcraft.justbans.common.support.MutableClock;
import pl.kingdomcraft.justbans.common.support.TestDatabase;
import pl.kingdomcraft.justbans.common.time.DurationParser;

class PunishmentServiceIntegrationTest {

	private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");
	private static final Actor ADMIN = Actor.player(UUID.randomUUID(), "Admin");

	private static TestDatabase db;
	private static Database database;
	private static MutableClock clock;
	private static PunishmentService service;
	private static Set<UUID> exempt = Set.of();

	private final PunishmentRepository punishments = new PunishmentRepository();
	private final EventRepository events = new EventRepository();

	@BeforeAll
	static void setUp() throws Exception {
		db = TestDatabase.create();
		database = db.database();
		clock = new MutableClock(START);
		service = new PunishmentService(database, "test-node", clock, (target, type) -> exempt.contains(target));

		database.query(c -> {
			final ServerRepository servers = new ServerRepository();
			servers.upsert(c, "survival", "survival", "Survival");
			servers.upsert(c, "skyblock-1", "skyblock", "Skyblock 1");

			new ReasonRepository().sync(c, List.of(
					new ReasonDefinition("cheats", "Cheating", Set.of(PunishmentKind.BAN, PunishmentKind.TEMPBAN, PunishmentKind.SERVER_BAN, PunishmentKind.SERVER_TEMPBAN), Duration.ofDays(30), List.of(), "DIAMOND_SWORD", Map.of(), 1, true),
					new ReasonDefinition("spam", "Spamming", Set.of(PunishmentKind.WARN, PunishmentKind.KICK), null, List.of(), "PAPER", Map.of(), 2, true),
					new ReasonDefinition("toxic", "Toxic behaviour", Set.of(PunishmentKind.WARN, PunishmentKind.TEMPBAN), null,
							List.of(new LadderStep(PunishmentType.WARN, null), new LadderStep(PunishmentType.BAN, Duration.ofDays(1)), new LadderStep(PunishmentType.BAN, Duration.ofDays(7))),
							null, Map.of(), 3, true),
					new ReasonDefinition("retired", "Retired", Set.of(PunishmentKind.BAN), null, List.of(), null, Map.of(), 4, false)), START);
			return null;
		});
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (db != null)
			db.close();
	}

	// ---- offline players -----------------------------------------------------------------

	@Test
	void bansAPlayerWhoHasNeverJoined() throws Exception {
		final UUID target = UUID.randomUUID();

		final Result<Punishment> result = service.issue(ban(target, "cheats").build()).get();

		assertTrue(result.isOk(), result.message());
		final Punishment p = result.value();
		assertEquals(PunishmentType.BAN, p.type());
		assertEquals(Scope.network(), p.scope());
		assertNull(p.expiresAt());
		assertEquals("Cheating", p.reasonText());
		assertEquals(8, p.publicId().length());
		assertEquals(ADMIN, p.actor());
		assertEquals(1, activeBans(target).size());
	}

	@Test
	void tempBanUsesReasonDefaultThenExplicitDurationAndExpiresWithTheClock() throws Exception {
		final UUID byDefault = UUID.randomUUID();
		final UUID explicit = UUID.randomUUID();

		final Punishment a = service.issue(PunishmentRequest.builder(PunishmentKind.TEMPBAN, byDefault, "A", ADMIN).reasonId("cheats").build()).get().value();
		final Punishment b = service.issue(PunishmentRequest.builder(PunishmentKind.TEMPBAN, explicit, "B", ADMIN).reasonId("cheats").duration(Duration.ofHours(2)).build()).get().value();

		assertEquals(clock.instant().plus(Duration.ofDays(30)), a.expiresAt());
		assertEquals(clock.instant().plus(Duration.ofHours(2)), b.expiresAt());

		assertEquals(1, activeBans(explicit).size());
		clock.advance(Duration.ofHours(3));
		try {
			assertEquals(0, activeBans(explicit).size(), "an expired tempban is simply no longer active");
			assertEquals(1, activeBans(byDefault).size());
		} finally {
			clock.advance(Duration.ofHours(-3));
		}
	}

	@Test
	void customReasonIsAcceptedAndTrimmed() throws Exception {
		final Result<Punishment> result = service.issue(PunishmentRequest.builder(PunishmentKind.BAN, UUID.randomUUID(), "X", ADMIN).customReason("  griefing spawn  ").build()).get();

		assertTrue(result.isOk());
		assertEquals("griefing spawn", result.value().reasonText());
		assertNull(result.value().reasonId());
	}

	// ---- duplicate bans ------------------------------------------------------------------

	@Test
	void secondBanInSameScopeIsRejected() throws Exception {
		final UUID target = UUID.randomUUID();
		final Punishment first = service.issue(ban(target, "cheats").build()).get().value();

		final Result<Punishment> second = service.issue(ban(target, "cheats").build()).get();

		assertFalse(second.isOk());
		assertEquals(ErrorCode.ALREADY_BANNED, second.error());
		assertTrue(second.message().contains(first.publicId()));
		assertEquals(1, activeBans(target).size());
	}

	@Test
	void differentScopesCoexist() throws Exception {
		final UUID target = UUID.randomUUID();

		assertTrue(service.issue(ban(target, "cheats").build()).get().isOk());
		assertTrue(service.issue(serverBan(target, Scope.server("survival")).build()).get().isOk());
		assertTrue(service.issue(serverBan(target, Scope.group("skyblock")).build()).get().isOk());

		assertEquals(3, activeBans(target).size());
	}

	@Test
	void forceReplacesTheExistingBanAndRecordsTheRevocation() throws Exception {
		final UUID target = UUID.randomUUID();
		final Punishment old = service.issue(ban(target, "cheats").build()).get().value();

		final Result<Punishment> replaced = service.issue(ban(target, "cheats").force(true).build()).get();

		assertTrue(replaced.isOk());
		final List<Punishment> active = activeBans(target);
		assertEquals(1, active.size());
		assertEquals(replaced.value().id(), active.get(0).id());
		assertTrue(database.query(c -> punishments.findById(c, old.id())).orElseThrow().isRevoked());
	}

	@Test
	void concurrentBansOfTheSamePlayerYieldExactlyOneWinner() throws Exception {
		final int threads = 12;

		for (int round = 0; round < 3; round++) {
			final UUID target = UUID.randomUUID();
			final ExecutorService pool = Executors.newFixedThreadPool(threads);
			final CountDownLatch start = new CountDownLatch(1);
			final List<Future<Result<Punishment>>> futures = new ArrayList<>();

			try {
				for (int i = 0; i < threads; i++)
					futures.add(pool.submit(() -> {
						start.await();
						return service.issue(ban(target, "cheats").build()).get();
					}));

				start.countDown();

				int ok = 0;
				int already = 0;

				for (final Future<Result<Punishment>> future : futures) {
					final Result<Punishment> result = future.get();

					if (result.isOk())
						ok++;
					else if (result.error() == ErrorCode.ALREADY_BANNED)
						already++;
					else
						throw new AssertionError("unexpected result " + result);
				}

				assertEquals(1, ok, "exactly one concurrent ban may win");
				assertEquals(threads - 1, already);
				assertEquals(1, activeBans(target).size());
				assertEquals(1, eventsFor(activeBans(target).get(0).id(), EventType.PUNISHMENT_CREATED));

			} finally {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void concurrentForcedBansLeaveExactlyOneActive() throws Exception {
		final int threads = 8;
		final UUID target = UUID.randomUUID();
		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch start = new CountDownLatch(1);
		final List<Future<Result<Punishment>>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < threads; i++)
				futures.add(pool.submit(() -> {
					start.await();
					return service.issue(ban(target, "cheats").force(true).build()).get();
				}));

			start.countDown();

			for (final Future<Result<Punishment>> future : futures)
				assertTrue(future.get().isOk());

			assertEquals(1, activeBans(target).size());
			assertEquals(threads, database.query(c -> punishments.history(c, target, 0, 100)).size());

		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void sameRequestIdIsIdempotent() throws Exception {
		final UUID target = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();

		final Punishment first = service.issue(ban(target, "cheats").requestId(requestId).build()).get().value();
		final Result<Punishment> retry = service.issue(ban(target, "cheats").requestId(requestId).build()).get();

		assertTrue(retry.isOk());
		assertEquals(first.id(), retry.value().id());
		assertEquals(1, database.query(c -> punishments.history(c, target, 0, 10)).size());
	}

	// ---- validation ----------------------------------------------------------------------

	@Test
	void rejectsBadRequests() throws Exception {
		final UUID target = UUID.randomUUID();

		assertEquals(ErrorCode.UNKNOWN_REASON, service.issue(ban(target, "nope").build()).get().error());
		assertEquals(ErrorCode.UNKNOWN_REASON, service.issue(ban(target, "retired").build()).get().error(), "disabled reasons cannot be used");
		assertEquals(ErrorCode.REASON_TYPE_NOT_ALLOWED, service.issue(PunishmentRequest.builder(PunishmentKind.BAN, target, "T", ADMIN).reasonId("spam").build()).get().error());
		assertEquals(ErrorCode.INVALID_REQUEST, service.issue(PunishmentRequest.builder(PunishmentKind.BAN, target, "T", ADMIN).build()).get().error(), "a reason is required");
		assertEquals(ErrorCode.INVALID_REQUEST, service.issue(PunishmentRequest.builder(PunishmentKind.SERVER_BAN, target, "T", ADMIN).reasonId("cheats").build()).get().error(), "server bans need a scope");
		assertEquals(ErrorCode.INVALID_REQUEST, service.issue(serverBan(target, Scope.network()).build()).get().error());
		assertEquals(ErrorCode.UNKNOWN_SERVER, service.issue(serverBan(target, Scope.server("nowhere")).build()).get().error());
		assertEquals(ErrorCode.UNKNOWN_SERVER, service.issue(serverBan(target, Scope.group("nogroup")).build()).get().error());
		assertEquals(ErrorCode.INVALID_DURATION, service.issue(PunishmentRequest.builder(PunishmentKind.TEMPBAN, target, "T", ADMIN).customReason("x").build()).get().error(), "tempban without any duration");
		assertEquals(ErrorCode.INVALID_DURATION, service.issue(PunishmentRequest.builder(PunishmentKind.TEMPBAN, target, "T", ADMIN).customReason("x").duration(DurationParser.PERMANENT).build()).get().error());
		assertEquals(ErrorCode.INVALID_DURATION, service.issue(PunishmentRequest.builder(PunishmentKind.BAN, target, "T", ADMIN).customReason("x").duration(Duration.ofDays(1)).build()).get().error(), "permban with a finite duration");

		assertEquals(0, database.query(c -> punishments.history(c, target, 0, 10)).size(), "rejected requests write nothing");
	}

	@Test
	void exemptPlayersCannotBePunished() throws Exception {
		final UUID target = UUID.randomUUID();
		exempt = Set.of(target);

		try {
			assertEquals(ErrorCode.TARGET_EXEMPT, service.issue(ban(target, "cheats").build()).get().error());
		} finally {
			exempt = Set.of();
		}
	}

	// ---- server bans, warns, kicks -------------------------------------------------------

	@Test
	void serverTempBanStoresScopeAndExpiry() throws Exception {
		final UUID target = UUID.randomUUID();

		final Punishment p = service.issue(PunishmentRequest.builder(PunishmentKind.SERVER_TEMPBAN, target, "T", ADMIN).reasonId("cheats").scope(Scope.server("survival")).duration(Duration.ofDays(3)).build()).get().value();

		assertEquals(Scope.server("survival"), p.scope());
		assertEquals(clock.instant().plus(Duration.ofDays(3)), p.expiresAt());
	}

	@Test
	void warnsAndKicksAreRecordedButKicksNeverActive() throws Exception {
		final UUID target = UUID.randomUUID();

		final Punishment warn = service.issue(PunishmentRequest.builder(PunishmentKind.WARN, target, "T", ADMIN).reasonId("spam").build()).get().value();
		final Punishment kick = service.issue(PunishmentRequest.builder(PunishmentKind.KICK, target, "T", ADMIN).reasonId("spam").build()).get().value();

		assertEquals(PunishmentType.WARN, warn.type());
		assertTrue(warn.isActive(clock.instant()));
		assertEquals(PunishmentType.KICK, kick.type());
		assertFalse(kick.isActive(clock.instant()));
		assertNull(kick.expiresAt());
	}

	@Test
	void ladderEscalatesWithEachOffence() throws Exception {
		final UUID target = UUID.randomUUID();

		final Punishment first = issueToxic(target);
		assertEquals(PunishmentType.WARN, first.type());
		assertEquals(0, first.ladderStep());

		final Punishment second = issueToxic(target);
		assertEquals(PunishmentType.BAN, second.type());
		assertEquals(clock.instant().plus(Duration.ofDays(1)), second.expiresAt());
		assertEquals(1, second.ladderStep());

		// Expired bans still count as offences; pardoned ones (issued by mistake) do not.
		clock.advance(Duration.ofDays(2));

		final Punishment third = issueToxic(target);
		assertEquals(clock.instant().plus(Duration.ofDays(7)), third.expiresAt());
		assertEquals(2, third.ladderStep());

		clock.advance(Duration.ofDays(8));
		assertEquals(2, issueToxic(target).ladderStep(), "the last step repeats");
	}

	@Test
	void pardonedPunishmentsDoNotAdvanceTheLadder() throws Exception {
		final UUID target = UUID.randomUUID();

		final Punishment warn = issueToxic(target);
		service.revoke(RevokeRequest.byId(ADMIN, warn.id(), "issued by mistake")).get();

		assertEquals(0, issueToxic(target).ladderStep());
	}

	private Punishment issueToxic(UUID target) throws Exception {
		final Result<Punishment> result = service.issue(PunishmentRequest.builder(PunishmentKind.TEMPBAN, target, "T", ADMIN).reasonId("toxic").build()).get();
		assertTrue(result.isOk(), result.message());

		return result.value();
	}

	// ---- revoke --------------------------------------------------------------------------

	@Test
	void pardonRevokesOnlyTheRequestedScope() throws Exception {
		final UUID target = UUID.randomUUID();
		service.issue(ban(target, "cheats").build()).get();
		service.issue(serverBan(target, Scope.server("survival")).build()).get();

		final Result<Punishment> pardon = service.revoke(RevokeRequest.ban(ADMIN, target, Scope.server("survival"), "appeal accepted")).get();

		assertTrue(pardon.isOk(), pardon.message());
		assertTrue(pardon.value().isRevoked());
		assertEquals("appeal accepted", pardon.value().revocation().reason());
		assertEquals(ADMIN.uuid(), pardon.value().revocation().by().uuid());
		assertEquals(List.of(Scope.network()), activeBans(target).stream().map(Punishment::scope).toList());
	}

	@Test
	void pardoningTwiceOrWithoutABanFails() throws Exception {
		final UUID target = UUID.randomUUID();

		assertEquals(ErrorCode.NOT_BANNED, service.revoke(RevokeRequest.ban(ADMIN, target, Scope.network(), null)).get().error());

		service.issue(ban(target, "cheats").build()).get();
		assertTrue(service.revoke(RevokeRequest.ban(ADMIN, target, Scope.network(), null)).get().isOk());
		assertEquals(ErrorCode.NOT_BANNED, service.revoke(RevokeRequest.ban(ADMIN, target, Scope.network(), null)).get().error());
	}

	@Test
	void pardoningAnExpiredBanFailsAsNotBanned() throws Exception {
		final UUID target = UUID.randomUUID();
		service.issue(PunishmentRequest.builder(PunishmentKind.TEMPBAN, target, "T", ADMIN).reasonId("cheats").duration(Duration.ofMinutes(1)).build()).get();

		clock.advance(Duration.ofMinutes(2));
		try {
			assertEquals(ErrorCode.NOT_BANNED, service.revoke(RevokeRequest.ban(ADMIN, target, Scope.network(), null)).get().error());
		} finally {
			clock.advance(Duration.ofMinutes(-2));
		}
	}

	@Test
	void revokeByIdAndByLastWarn() throws Exception {
		final UUID target = UUID.randomUUID();
		final Punishment w1 = service.issue(PunishmentRequest.builder(PunishmentKind.WARN, target, "T", ADMIN).reasonId("spam").build()).get().value();
		final Punishment w2 = service.issue(PunishmentRequest.builder(PunishmentKind.WARN, target, "T", ADMIN).reasonId("spam").build()).get().value();

		assertEquals(w2.id(), service.revoke(RevokeRequest.lastWarn(ADMIN, target, null)).get().value().id());
		assertEquals(w1.id(), service.revoke(RevokeRequest.byId(ADMIN, w1.id(), "mistake")).get().value().id());
		assertEquals(ErrorCode.NOT_ACTIVE, service.revoke(RevokeRequest.lastWarn(ADMIN, target, null)).get().error());
		assertEquals(ErrorCode.NOT_ACTIVE, service.revoke(RevokeRequest.byId(ADMIN, w1.id(), null)).get().error());
		assertEquals(ErrorCode.UNKNOWN_PUNISHMENT, service.revoke(RevokeRequest.byId(ADMIN, 987_654_321L, null)).get().error());
	}

	@Test
	void kicksCannotBeRevoked() throws Exception {
		final UUID target = UUID.randomUUID();
		final Punishment kick = service.issue(PunishmentRequest.builder(PunishmentKind.KICK, target, "T", ADMIN).reasonId("spam").build()).get().value();

		assertEquals(ErrorCode.NOT_ACTIVE, service.revoke(RevokeRequest.byId(ADMIN, kick.id(), null)).get().error());
		assertEquals(ErrorCode.INVALID_REQUEST, service.revoke(new RevokeRequest(ADMIN, null, target, PunishmentType.KICK, null, null)).get().error());
	}

	@Test
	void concurrentPardonsSucceedOnce() throws Exception {
		final int threads = 8;
		final UUID target = UUID.randomUUID();
		service.issue(ban(target, "cheats").build()).get();

		final ExecutorService pool = Executors.newFixedThreadPool(threads);
		final CountDownLatch start = new CountDownLatch(1);
		final List<Future<Result<Punishment>>> futures = new ArrayList<>();

		try {
			for (int i = 0; i < threads; i++)
				futures.add(pool.submit(() -> {
					start.await();
					return service.revoke(RevokeRequest.ban(ADMIN, target, Scope.network(), null)).get();
				}));

			start.countDown();

			int ok = 0;

			for (final Future<Result<Punishment>> future : futures) {
				final Result<Punishment> result = future.get();

				if (result.isOk())
					ok++;
				else
					assertEquals(ErrorCode.NOT_BANNED, result.error());
			}

			assertEquals(1, ok);

		} finally {
			pool.shutdownNow();
		}
	}

	// ---- events --------------------------------------------------------------------------

	@Test
	void issueAndRevokeAppendOutboxEventsWithSnapshots() throws Exception {
		final UUID target = UUID.randomUUID();
		final Punishment p = service.issue(ban(target, "cheats").build()).get().value();
		service.revoke(RevokeRequest.byId(ADMIN, p.id(), "oops")).get();

		final List<EventRecord> mine = database.query(c -> events.since(c, 0, 10_000)).stream().filter(e -> Long.valueOf(p.id()).equals(e.punishmentId())).toList();

		assertEquals(List.of(EventType.PUNISHMENT_CREATED, EventType.PUNISHMENT_REVOKED), mine.stream().map(EventRecord::type).toList());
		assertEquals("test-node", mine.get(0).originNode());
		assertNull(mine.get(0).publishedAt());
		assertTrue(mine.get(0).payload().contains(p.publicId()));
		assertTrue(mine.get(0).payload().contains(target.toString()));
		assertFalse(mine.get(0).payload().contains("\"revoked\""));
		assertTrue(mine.get(1).payload().contains("\"revoked\""));
		assertTrue(mine.get(1).payload().contains("oops"));
		assertTrue(mine.get(1).id() > mine.get(0).id());
	}

	@Test
	void rejectedRequestsAppendNoEvents() throws Exception {
		final long before = database.query(c -> events.since(c, 0, 100_000)).size();

		service.issue(ban(UUID.randomUUID(), "nope").build()).get();
		service.revoke(RevokeRequest.ban(ADMIN, UUID.randomUUID(), Scope.network(), null)).get();

		assertEquals(before, database.query(c -> events.since(c, 0, 100_000)).size());
	}

	@Test
	void everyPunishmentGetsAUniquePublicId() throws Exception {
		final Set<String> ids = new java.util.HashSet<>();

		for (int i = 0; i < 25; i++) {
			final String id = service.issue(PunishmentRequest.builder(PunishmentKind.WARN, UUID.randomUUID(), "T", ADMIN).reasonId("spam").build()).get().value().publicId();

			assertNotNull(id);
			assertTrue(ids.add(id));
		}
	}

	// ---- helpers -------------------------------------------------------------------------

	private static PunishmentRequest.Builder ban(UUID target, String reason) {
		return PunishmentRequest.builder(PunishmentKind.BAN, target, "Target", ADMIN).reasonId(reason);
	}

	private static PunishmentRequest.Builder serverBan(UUID target, Scope scope) {
		return PunishmentRequest.builder(PunishmentKind.SERVER_BAN, target, "Target", ADMIN).reasonId("cheats").scope(scope);
	}

	private List<Punishment> activeBans(UUID target) throws Exception {
		return database.query(c -> punishments.findActiveBans(c, target, clock.instant()));
	}

	private int eventsFor(long punishmentId, EventType type) throws Exception {
		return (int) database.query(c -> events.since(c, 0, 100_000)).stream().filter(e -> e.type() == type && Long.valueOf(punishmentId).equals(e.punishmentId())).count();
	}
}
