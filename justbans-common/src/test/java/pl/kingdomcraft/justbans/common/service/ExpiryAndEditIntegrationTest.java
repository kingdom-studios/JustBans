package pl.kingdomcraft.justbans.common.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.EventRecord;
import pl.kingdomcraft.justbans.common.domain.EventType;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.repository.EventRepository;
import pl.kingdomcraft.justbans.common.repository.PunishmentRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.support.MutableClock;
import pl.kingdomcraft.justbans.common.support.TestDatabase;

/** The expiry announcements and punishment editing of PunishmentService. */
class ExpiryAndEditIntegrationTest {

	private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");
	private static final Actor ADMIN = Actor.player(UUID.randomUUID(), "Admin");
	private static final Actor WEB = Actor.web("42", "Web: alice");

	private static TestDatabase db;
	private static Database database;
	private static MutableClock clock;
	private static PunishmentService service;

	private final EventRepository events = new EventRepository();
	private final PunishmentRepository punishments = new PunishmentRepository();

	@BeforeAll
	static void setUp() throws Exception {
		db = TestDatabase.create();
		database = db.database();
		clock = new MutableClock(START);
		service = new PunishmentService(database, "test-node", clock, ExemptionChecker.NONE);

		database.query(c -> {
			new ReasonRepository().sync(c, List.of(
					new ReasonDefinition("cheats", "Cheating", Set.of(PunishmentKind.BAN, PunishmentKind.TEMPBAN), Duration.ofDays(30), List.of(), null, Map.of(), 1, true),
					new ReasonDefinition("spam", "Spamming", Set.of(PunishmentKind.WARN, PunishmentKind.KICK), null, List.of(), null, Map.of(), 2, true)), START);
			return null;
		});
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (db != null)
			db.close();
	}

	private Punishment tempBan(Duration duration) throws Exception {
		return service.issueBlocking(PunishmentRequest.builder(PunishmentKind.TEMPBAN, UUID.randomUUID(), "T" + UUID.randomUUID().toString().substring(0, 6), ADMIN).reasonId("cheats").duration(duration).build()).value();
	}

	private List<EventRecord> eventsOf(Punishment p, EventType type) throws Exception {
		return database.query(c -> events.since(c, 0, 10_000)).stream().filter(e -> e.type() == type && Long.valueOf(p.id()).equals(e.punishmentId())).toList();
	}

	private void advance(Duration by) {
		clock.advance(by);
	}

	// ---- expiry sweeper -----------------------------------------------------------------

	@Test
	void announcesAnExpiredBanOnceAndOnlyAfterItExpired() throws Exception {
		final Punishment ban = tempBan(Duration.ofHours(1));

		assertEquals(0, sweepOnlyThisOne(ban), "not expired yet");
		assertTrue(eventsOf(ban, EventType.PUNISHMENT_EXPIRED).isEmpty());

		advance(Duration.ofHours(2));

		assertTrue(service.sweepExpiredBlocking(500) >= 1);
		assertEquals(1, eventsOf(ban, EventType.PUNISHMENT_EXPIRED).size());

		final JsonObject payload = JsonParser.parseString(eventsOf(ban, EventType.PUNISHMENT_EXPIRED).get(0).payload()).getAsJsonObject();
		assertEquals(ban.publicId(), payload.getAsJsonObject("punishment").get("publicId").getAsString());

		assertEquals(0, sweepOnlyThisOne(ban), "already announced");
		assertEquals(1, eventsOf(ban, EventType.PUNISHMENT_EXPIRED).size());
	}

	/** Sweeps and reports how many events this punishment got in that sweep. */
	private int sweepOnlyThisOne(Punishment p) throws Exception {
		final int before = eventsOf(p, EventType.PUNISHMENT_EXPIRED).size();
		service.sweepExpiredBlocking(500);

		return eventsOf(p, EventType.PUNISHMENT_EXPIRED).size() - before;
	}

	@Test
	void doesNotAnnouncePardonedPermanentOrKickedPunishments() throws Exception {
		final Punishment pardoned = tempBan(Duration.ofHours(1));
		assertTrue(service.revokeBlocking(RevokeRequest.byId(ADMIN, pardoned.id(), "mistake")).isOk());

		final Punishment permanent = service.issueBlocking(PunishmentRequest.builder(PunishmentKind.BAN, UUID.randomUUID(), "Perm", ADMIN).reasonId("cheats").build()).value();
		final Punishment kick = service.issueBlocking(PunishmentRequest.builder(PunishmentKind.KICK, UUID.randomUUID(), "Kick", ADMIN).reasonId("spam").build()).value();

		advance(Duration.ofDays(400));
		service.sweepExpiredBlocking(500);

		assertTrue(eventsOf(pardoned, EventType.PUNISHMENT_EXPIRED).isEmpty());
		assertTrue(eventsOf(permanent, EventType.PUNISHMENT_EXPIRED).isEmpty());
		assertTrue(eventsOf(kick, EventType.PUNISHMENT_EXPIRED).isEmpty());
	}

	@Test
	void announcesExpiredWarnsToo() throws Exception {
		final Punishment warn = service.issueBlocking(PunishmentRequest.builder(PunishmentKind.WARN, UUID.randomUUID(), "Warned", ADMIN).reasonId("spam").duration(Duration.ofDays(7)).build()).value();

		advance(Duration.ofDays(8));
		service.sweepExpiredBlocking(500);

		assertEquals(1, eventsOf(warn, EventType.PUNISHMENT_EXPIRED).size());
	}

	@Test
	void respectsTheBatchLimitAndFinishesTheBacklogOnTheNextCall() throws Exception {
		final List<Punishment> bans = new ArrayList<>();

		for (int i = 0; i < 5; i++)
			bans.add(tempBan(Duration.ofMinutes(1)));

		advance(Duration.ofMinutes(5));

		assertEquals(3, service.sweepExpiredBlocking(3));
		assertTrue(service.sweepExpiredBlocking(500) >= 2);

		for (final Punishment ban : bans)
			assertEquals(1, eventsOf(ban, EventType.PUNISHMENT_EXPIRED).size());
	}

	@Test
	void concurrentSweepersEmitExactlyOneEventPerExpiry() throws Exception {
		final List<Punishment> bans = new ArrayList<>();

		for (int i = 0; i < 20; i++)
			bans.add(tempBan(Duration.ofMinutes(1)));

		advance(Duration.ofMinutes(5));

		final ExecutorService pool = Executors.newFixedThreadPool(4);

		try {
			final List<Future<Integer>> runs = new ArrayList<>();

			for (int i = 0; i < 4; i++) {
				final Callable<Integer> sweep = () -> service.sweepExpiredBlocking(500);
				runs.add(pool.submit(sweep));
			}

			for (final Future<Integer> run : runs)
				run.get();
		} finally {
			pool.shutdownNow();
		}

		for (final Punishment ban : bans)
			assertEquals(1, eventsOf(ban, EventType.PUNISHMENT_EXPIRED).size(), "ban " + ban.publicId());
	}

	// ---- editing -------------------------------------------------------------------------

	@Test
	void extendsAndShortensTheExpiryAndEmitsAnUpdateEvent() throws Exception {
		final Punishment ban = tempBan(Duration.ofDays(1));
		final Instant later = clock.instant().plus(Duration.ofDays(10));

		final Result<Punishment> result = service.updateBlocking(UpdateRequest.builder(WEB, ban.id()).expiresAt(later).build());

		assertTrue(result.isOk(), result.message());
		assertEquals(later, result.value().expiresAt());

		final List<EventRecord> updates = eventsOf(ban, EventType.PUNISHMENT_UPDATED);
		assertEquals(1, updates.size());

		final JsonObject payload = JsonParser.parseString(updates.get(0).payload()).getAsJsonObject();
		assertEquals("expiresAt", payload.getAsJsonObject("update").getAsJsonArray("changed").get(0).getAsString());
		assertEquals("WEB", payload.getAsJsonObject("update").getAsJsonObject("by").get("type").getAsString());
		assertEquals(later.toEpochMilli(), payload.getAsJsonObject("punishment").get("expiresAt").getAsLong());
	}

	@Test
	void makesATempbanPermanent() throws Exception {
		final Punishment ban = tempBan(Duration.ofDays(1));

		final Result<Punishment> result = service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).expiresAt(null).build());

		assertTrue(result.isOk(), result.message());
		assertNull(result.value().expiresAt());
	}

	@Test
	void editsReasonAndNoteAndNeverPublishesTheNote() throws Exception {
		final Punishment ban = tempBan(Duration.ofDays(1));

		final Result<Punishment> result = service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).reasonText("  Flying near spawn ").note("secret staff context").build());

		assertTrue(result.isOk(), result.message());
		assertEquals("Flying near spawn", result.value().reasonText());
		assertEquals("secret staff context", result.value().note());

		final EventRecord event = eventsOf(ban, EventType.PUNISHMENT_UPDATED).get(0);
		assertFalse(event.payload().contains("secret staff context"));
		assertTrue(event.payload().contains("\"reasonText\"") && event.payload().contains("\"note\""));

		final Result<Punishment> cleared = service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).note("").build());
		assertTrue(cleared.isOk());
		assertNull(cleared.value().note());
	}

	@Test
	void editingToTheSameValuesSucceedsWithoutAnEvent() throws Exception {
		final Punishment ban = tempBan(Duration.ofDays(1));

		final Result<Punishment> result = service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).expiresAt(ban.expiresAt()).reasonText(ban.reasonText()).build());

		assertTrue(result.isOk());
		assertTrue(eventsOf(ban, EventType.PUNISHMENT_UPDATED).isEmpty());
	}

	@Test
	void rejectsInvalidEdits() throws Exception {
		final Punishment ban = tempBan(Duration.ofDays(1));
		final Punishment kick = service.issueBlocking(PunishmentRequest.builder(PunishmentKind.KICK, UUID.randomUUID(), "Kicked", ADMIN).reasonId("spam").build()).value();

		assertEquals(ErrorCode.INVALID_REQUEST, service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).build()).error(), "nothing to change");
		assertEquals(ErrorCode.INVALID_REQUEST, service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).reasonText("   ").build()).error());
		assertEquals(ErrorCode.INVALID_REQUEST, service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).reasonText("x".repeat(600)).build()).error());
		assertEquals(ErrorCode.INVALID_DURATION, service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).expiresAt(clock.instant().minusSeconds(1)).build()).error());
		assertEquals(ErrorCode.INVALID_DURATION, service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).expiresAt(clock.instant().plus(Duration.ofDays(4000))).build()).error());
		assertEquals(ErrorCode.INVALID_REQUEST, service.updateBlocking(UpdateRequest.builder(ADMIN, kick.id()).expiresAt(clock.instant().plusSeconds(60)).build()).error(), "kicks have no expiry");
		assertEquals(ErrorCode.UNKNOWN_PUNISHMENT, service.updateBlocking(UpdateRequest.builder(ADMIN, 99_999_999L).reasonText("x").build()).error());
		assertTrue(eventsOf(ban, EventType.PUNISHMENT_UPDATED).isEmpty());
	}

	@Test
	void cannotChangeTheExpiryOfEndedPunishmentsButCanFixTheirText() throws Exception {
		final Punishment pardoned = tempBan(Duration.ofDays(1));
		service.revokeBlocking(RevokeRequest.byId(ADMIN, pardoned.id(), "oops"));

		final Punishment expired = tempBan(Duration.ofMinutes(1));
		advance(Duration.ofMinutes(5));

		for (final Punishment ended : List.of(pardoned, expired))
			assertEquals(ErrorCode.NOT_ACTIVE, service.updateBlocking(UpdateRequest.builder(ADMIN, ended.id()).expiresAt(clock.instant().plus(Duration.ofDays(1))).build()).error());

		assertTrue(service.updateBlocking(UpdateRequest.builder(ADMIN, pardoned.id()).reasonText("Corrected wording").build()).isOk());
	}

	@Test
	void anExtensionAfterExpiryCannotResurrectABanTheSweeperAlreadyAnnounced() throws Exception {
		final Punishment ban = tempBan(Duration.ofMinutes(1));
		advance(Duration.ofMinutes(5));
		service.sweepExpiredBlocking(500);

		assertEquals(ErrorCode.NOT_ACTIVE, service.updateBlocking(UpdateRequest.builder(ADMIN, ban.id()).expiresAt(clock.instant().plus(Duration.ofDays(1))).build()).error());
	}

	// ---- lookup --------------------------------------------------------------------------

	@Test
	void findsByPublicCodeWithOrWithoutHashAndByNumericId() throws Exception {
		final Punishment ban = tempBan(Duration.ofDays(1));

		assertEquals(ban.id(), service.findBlocking(ban.publicId()).orElseThrow().id());
		assertEquals(ban.id(), service.findBlocking("#" + ban.publicId().toLowerCase()).orElseThrow().id());
		assertEquals(ban.id(), service.findBlocking(Long.toString(ban.id())).orElseThrow().id());
		assertTrue(service.findBlocking("#999999").isEmpty());
		assertTrue(service.findBlocking("not-an-id").isEmpty());
		assertTrue(service.findBlocking("   ").isEmpty());
		assertTrue(service.findBlocking(null).isEmpty());
	}
}
