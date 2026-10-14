package pl.kingdomcraft.justbans.common.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.EventRecord;
import pl.kingdomcraft.justbans.common.domain.EventType;
import pl.kingdomcraft.justbans.common.domain.PlayerRecord;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ServerRecord;
import pl.kingdomcraft.justbans.common.support.TestDatabase;

class RepositoryIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-10-14T12:00:00.123Z");

	private static TestDatabase db;
	private static Database database;

	private final PlayerRepository players = new PlayerRepository();
	private final PunishmentRepository punishments = new PunishmentRepository();
	private final EventRepository events = new EventRepository();
	private final ReasonRepository reasons = new ReasonRepository();
	private final ServerRepository servers = new ServerRepository();

	@BeforeAll
	static void setUp() throws Exception {
		db = TestDatabase.create();
		database = db.database();
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (db != null)
			db.close();
	}

	// ---- players -------------------------------------------------------------------------

	@Test
	void touchCreatesAndUpdatesPlayerAndHistory() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final String first = name();
		final String second = name();

		database.query(c -> {
			players.touch(c, uuid, first, T0, "lobby");
			players.touch(c, uuid, second, T0.plusSeconds(60), null);

			final PlayerRecord record = players.findByUuid(c, uuid).orElseThrow();
			assertEquals(second, record.lastName());
			assertEquals(T0, record.firstSeen());
			assertEquals(T0.plusSeconds(60), record.lastSeen());
			assertEquals("lobby", record.lastServer(), "a null server must not erase the last known one");

			assertEquals(List.of(second, first), players.nameHistory(c, uuid));
			return null;
		});
	}

	@Test
	void findsPlayersByCurrentAndOldNameIgnoringCase() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final String oldName = name();
		final String newName = name();

		database.query(c -> {
			players.touch(c, uuid, oldName, T0, null);
			players.touch(c, uuid, newName, T0.plusSeconds(1), null);

			assertEquals(uuid, players.findByName(c, newName.toUpperCase()).get(0).uuid());
			assertEquals(uuid, players.findByName(c, oldName.toLowerCase()).get(0).uuid());
			assertTrue(players.findByName(c, "nobody" + uuid.toString().substring(0, 4)).isEmpty());
			return null;
		});
	}

	@Test
	void sharedNameIsReportedAsAmbiguousMostRecentFirst() throws Exception {
		final String shared = name();
		final UUID older = UUID.randomUUID();
		final UUID newer = UUID.randomUUID();

		database.query(c -> {
			players.touch(c, older, shared, T0, null);
			players.touch(c, newer, shared, T0.plusSeconds(500), null);

			final List<PlayerRecord> found = players.findByName(c, shared);
			assertEquals(2, found.size());
			assertEquals(newer, found.get(0).uuid());
			return null;
		});
	}

	@Test
	void ensureExistsNeverOverwritesARealPlayer() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final String real = name();

		database.query(c -> {
			assertFalse(players.lock(c, uuid));

			players.ensureExists(c, uuid, "Placeholder", T0);
			assertEquals("Placeholder", players.findByUuid(c, uuid).orElseThrow().lastName());

			players.touch(c, uuid, real, T0.plusSeconds(1), null);
			players.ensureExists(c, uuid, "Placeholder", T0.plusSeconds(2));
			assertEquals(real, players.findByUuid(c, uuid).orElseThrow().lastName());
			return null;
		});
	}

	// ---- punishments ---------------------------------------------------------------------

	@Test
	void punishmentRoundTripPreservesEveryField() throws Exception {
		final UUID target = UUID.randomUUID();
		final UUID staff = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();
		final Instant expires = T0.plus(Duration.ofDays(7));

		final Punishment draft = new Punishment(0, publicId(), PunishmentType.BAN, Scope.server("survival"), target, "Notch",
				Actor.player(staff, "Admin"), "cheats", "Cheating / hacked client", "internal note", T0, expires, "lobby", true, 2, null);

		database.query(c -> {
			players.ensureExists(c, target, "Notch", T0);
			final Punishment saved = punishments.insert(c, draft, requestId);
			assertTrue(saved.id() > 0);

			assertEquals(saved, punishments.findById(c, saved.id()).orElseThrow());
			assertEquals(saved, punishments.findByPublicId(c, saved.publicId()).orElseThrow());
			assertEquals(saved, punishments.findByRequestId(c, requestId).orElseThrow());
			assertEquals(expires, saved.expiresAt());
			return null;
		});
	}

	@Test
	void permanentConsolePunishmentHasNullableColumnsNull() throws Exception {
		final UUID target = UUID.randomUUID();

		database.query(c -> {
			players.ensureExists(c, target, "Steve", T0);
			final Punishment saved = punishments.insert(c, ban(target, Scope.network(), null), null);
			final Punishment loaded = punishments.findById(c, saved.id()).orElseThrow();

			assertNull(loaded.expiresAt());
			assertNull(loaded.actor().uuid());
			assertNull(loaded.reasonId());
			assertNull(loaded.revocation());
			assertTrue(loaded.isPermanent());
			return null;
		});
	}

	@Test
	void activeBanQueriesHonourExpiryRevocationAndScope() throws Exception {
		final UUID target = UUID.randomUUID();
		final Instant now = T0.plusSeconds(1000);

		database.query(c -> {
			players.ensureExists(c, target, "Alex", T0);

			final Punishment network = punishments.insert(c, ban(target, Scope.network(), null), null);
			final Punishment server = punishments.insert(c, ban(target, Scope.server("skyblock"), now.plusSeconds(60)), null);
			punishments.insert(c, ban(target, Scope.server("expired"), now.minusSeconds(1)), null);
			final Punishment revoked = punishments.insert(c, ban(target, Scope.server("revoked"), null), null);
			assertTrue(punishments.revoke(c, revoked.id(), now, Actor.console(), "mistake"));

			final List<Punishment> active = punishments.findActiveBans(c, target, now);
			assertEquals(List.of(network.id(), server.id()), active.stream().map(Punishment::id).toList());

			assertEquals(network.id(), punishments.findActiveBan(c, target, Scope.network(), now).orElseThrow().id());
			assertEquals(server.id(), punishments.findActiveBan(c, target, Scope.server("skyblock"), now).orElseThrow().id());
			assertTrue(punishments.findActiveBan(c, target, Scope.server("expired"), now).isEmpty());
			assertTrue(punishments.findActiveBan(c, target, Scope.server("revoked"), now).isEmpty());
			assertTrue(punishments.findActiveBan(c, target, Scope.group("skyblock"), now).isEmpty(), "server and group scopes are distinct");

			// The temp ban lapses with no write at all.
			assertEquals(1, punishments.findActiveBans(c, target, now.plusSeconds(61)).size());
			return null;
		});
	}

	@Test
	void revokeIsOneShotAndRecordsWho() throws Exception {
		final UUID target = UUID.randomUUID();
		final UUID staff = UUID.randomUUID();

		database.query(c -> {
			players.ensureExists(c, target, "Herobrine", T0);
			final Punishment saved = punishments.insert(c, ban(target, Scope.network(), null), null);

			assertTrue(punishments.revoke(c, saved.id(), T0.plusSeconds(5), Actor.player(staff, "Mod"), "appealed"));
			assertFalse(punishments.revoke(c, saved.id(), T0.plusSeconds(6), Actor.console(), "again"));

			final Punishment.Revocation revocation = punishments.findById(c, saved.id()).orElseThrow().revocation();
			assertEquals(T0.plusSeconds(5), revocation.at());
			assertEquals(staff, revocation.by().uuid());
			assertEquals("Mod", revocation.by().name());
			assertEquals("appealed", revocation.reason());
			return null;
		});
	}

	@Test
	void warnsPriorOffencesAndHistory() throws Exception {
		final UUID target = UUID.randomUUID();

		database.query(c -> {
			players.ensureExists(c, target, "Warned", T0);

			final Punishment first = punishments.insert(c, of(target, PunishmentType.WARN, "spam", null), null);
			final Punishment second = punishments.insert(c, of(target, PunishmentType.WARN, "spam", null), null);
			punishments.insert(c, of(target, PunishmentType.KICK, "other", null), null);
			punishments.revoke(c, first.id(), T0, Actor.console(), null);

			assertEquals(List.of(second.id()), punishments.findActiveWarns(c, target, T0).stream().map(Punishment::id).toList());
			assertEquals(1, punishments.countPriorOffences(c, target, "spam"), "revoked punishments do not count");
			assertEquals(0, punishments.countPriorOffences(c, target, "unknown"));

			final List<Punishment> page = punishments.history(c, target, 0, 2);
			assertEquals(2, page.size());
			assertTrue(page.get(0).id() > page.get(1).id(), "newest first");
			assertEquals(3, punishments.history(c, target, 0, 50).size());
			assertEquals(1, punishments.history(c, target, 2, 50).size());
			return null;
		});
	}

	// ---- events --------------------------------------------------------------------------

	@Test
	void eventsAreAppendedPublishedAndReplayable() throws Exception {
		final String node = "node-" + UUID.randomUUID();

		database.query(c -> {
			final long a = events.append(c, EventType.PUNISHMENT_CREATED, null, "{\"n\":1}", node, T0);
			final long b = events.append(c, EventType.PUNISHMENT_REVOKED, null, "{\"n\":2}", node, T0.plusSeconds(1));
			events.append(c, EventType.JOIN_DENIED, null, "{}", "other-node", T0);
			assertTrue(b > a);

			List<EventRecord> pending = events.unpublished(c, node, 10);
			assertEquals(List.of(a, b), pending.stream().map(EventRecord::id).toList());
			assertEquals(EventType.PUNISHMENT_CREATED, pending.get(0).type());
			assertNull(pending.get(0).publishedAt());

			events.markPublished(c, a, T0.plusSeconds(2));
			pending = events.unpublished(c, node, 10);
			assertEquals(List.of(b), pending.stream().map(EventRecord::id).toList());

			assertEquals(b, events.since(c, a, 1).get(0).id());
			assertEquals(1, events.unpublished(c, node, 1).size(), "limit is honoured");
			return null;
		});
	}

	@Test
	void eventPayloadIsStoredAsJson() throws Exception {
		database.query(c -> {
			final long id = events.append(c, EventType.REASONS_UPDATED, null, "{\"reasons\":[\"cheats\"]}", "n", T0);
			final EventRecord loaded = events.since(c, id - 1, 1).get(0);

			assertEquals(id, loaded.id());
			assertTrue(loaded.payload().contains("cheats"));
			return null;
		});
	}

	// ---- reasons -------------------------------------------------------------------------

	@Test
	void reasonsRoundTripAndSyncDisablesRemovedOnes() throws Exception {
		final String keep = "keep" + System.nanoTime();
		final String drop = "drop" + System.nanoTime();

		final ReasonDefinition cheats = new ReasonDefinition(keep, "Cheating", Set.of(PunishmentKind.TEMPBAN, PunishmentKind.BAN), Duration.ofDays(30),
				List.of(new ReasonDefinition.LadderStep(PunishmentType.WARN, null), new ReasonDefinition.LadderStep(PunishmentType.BAN, Duration.ofDays(7))),
				"DIAMOND_SWORD", Map.of("ban-screen", List.of("<red>Banned", "<gray><reason>")), 5, true);
		final ReasonDefinition spam = new ReasonDefinition(drop, "Spam", Set.of(PunishmentKind.WARN), null, List.of(), null, Map.of(), 9, true);

		database.query(c -> {
			reasons.sync(c, List.of(cheats, spam), T0);
			assertEquals(cheats, reasons.find(c, keep).orElseThrow());
			assertEquals(spam, reasons.find(c, drop).orElseThrow());

			reasons.sync(c, List.of(cheats), T0.plusSeconds(1));
			assertTrue(reasons.find(c, keep).orElseThrow().enabled());
			assertFalse(reasons.find(c, drop).orElseThrow().enabled(), "removed reasons are disabled, not deleted");

			assertTrue(reasons.findAll(c, false).stream().noneMatch(r -> r.id().equals(drop)));
			assertTrue(reasons.findAll(c, true).stream().anyMatch(r -> r.id().equals(drop)));
			assertTrue(reasons.find(c, "does-not-exist").isEmpty());
			return null;
		});
	}

	// ---- servers -------------------------------------------------------------------------

	@Test
	void serversUpsertHeartbeatAndGroups() throws Exception {
		final String id = "srv" + System.nanoTime();
		final String group = "grp" + System.nanoTime();

		database.query(c -> {
			assertFalse(servers.exists(c, id));
			assertFalse(servers.groupExists(c, group));

			servers.upsert(c, id, group, "Survival");
			servers.upsert(c, id, group, "Survival 2");
			servers.heartbeat(c, id, T0);

			final ServerRecord record = servers.find(c, id).orElseThrow();
			assertEquals("Survival 2", record.displayName());
			assertEquals(group, record.groupName());
			assertEquals(T0, record.lastHeartbeat());
			assertTrue(servers.exists(c, id));
			assertTrue(servers.groupExists(c, group));
			assertTrue(servers.findAll(c).stream().anyMatch(s -> s.id().equals(id)));

			servers.upsert(c, "bare" + id, null, null);
			assertNull(servers.find(c, "bare" + id).orElseThrow().groupName());
			return null;
		});
	}

	// ---- helpers -------------------------------------------------------------------------

	private static Punishment ban(UUID target, Scope scope, Instant expires) {
		return new Punishment(0, publicId(), PunishmentType.BAN, scope, target, "Target", Actor.console(), null, "reason", null, T0, expires, "proxy", false, null, null);
	}

	private static Punishment of(UUID target, PunishmentType type, String reasonId, Instant expires) {
		return new Punishment(0, publicId(), type, Scope.network(), target, "Target", Actor.console(), reasonId, "reason", null, T0, expires, "proxy", false, null, null);
	}

	private static String publicId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
	}

	private static String name() {
		return "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
