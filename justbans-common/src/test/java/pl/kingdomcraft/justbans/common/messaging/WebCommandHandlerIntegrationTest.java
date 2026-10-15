package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.ActorType;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ScopeType;
import pl.kingdomcraft.justbans.common.repository.CommandLogRepository;
import pl.kingdomcraft.justbans.common.repository.PlayerRepository;
import pl.kingdomcraft.justbans.common.repository.PunishmentRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.repository.ServerRepository;
import pl.kingdomcraft.justbans.common.service.ErrorCode;
import pl.kingdomcraft.justbans.common.service.ExemptionChecker;
import pl.kingdomcraft.justbans.common.service.PunishmentService;
import pl.kingdomcraft.justbans.common.service.Result;
import pl.kingdomcraft.justbans.common.support.TestDatabase;
import pl.kingdomcraft.justbans.common.support.TestRedis;

/**
 * End to end: a fake web backend signs commands and publishes them to Redis; two plugin nodes sharing one
 * database consume them.
 */
class WebCommandHandlerIntegrationTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Logger LOGGER = Logger.getLogger("web-test");

	static {
		LOGGER.setLevel(Level.OFF);
	}

	private static TestDatabase db;
	private static Database database;

	private final PunishmentRepository punishments = new PunishmentRepository();
	private final List<AutoCloseable> closeables = new ArrayList<>();

	private RedisBus web;
	private final List<Envelope> results = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void setUpDatabase() throws Exception {
		db = TestDatabase.create();
		database = db.database();

		database.query(c -> {
			new ServerRepository().upsert(c, "survival", "survival", "Survival");
			new ReasonRepository().sync(c, List.of(
					new ReasonDefinition("cheats", "Cheating", Set.of(PunishmentKind.BAN, PunishmentKind.TEMPBAN, PunishmentKind.SERVER_BAN, PunishmentKind.SERVER_TEMPBAN), Duration.ofDays(30), List.of(), null, Map.of(), 1, true),
					new ReasonDefinition("spam", "Spamming", Set.of(PunishmentKind.WARN), null, List.of(), null, Map.of(), 2, true)), Instant.now());
			return null;
		});
	}

	@AfterAll
	static void tearDownDatabase() throws Exception {
		if (db != null)
			db.close();
	}

	private RedisConfig redis;
	private WebCommandHandler handlerA;
	private WebCommandHandler handlerB;

	@BeforeEach
	void setUp() throws Exception {
		redis = TestRedis.config();

		handlerA = node("node-a");
		handlerB = node("node-b");

		web = bus("web", SECRET, Clock.systemUTC());
		web.onVerified(web.channels().commandResults(), results::add);
	}

	@AfterEach
	void tearDown() throws Exception {
		for (final AutoCloseable closeable : closeables)
			closeable.close();
	}

	private RedisBus bus(String name, String secret, Clock clock) throws Exception {
		final RedisBus bus = new RedisBus(redis, new HmacSigner(secret), name, clock, LOGGER);
		closeables.add(bus);
		bus.start();
		assertTrue(TestRedis.await(bus::isConnected, 5_000));

		return bus;
	}

	private WebCommandHandler node(String name) throws Exception {
		final RedisBus bus = bus(name, SECRET, Clock.systemUTC());
		final PunishmentService service = new PunishmentService(database, name, Clock.systemUTC(), ExemptionChecker.NONE);
		final WebCommandHandler handler = new WebCommandHandler(database, service, bus, name, Clock.systemUTC(), LOGGER, Duration.ofSeconds(30));
		handler.start();

		return handler;
	}

	// ---- helpers -------------------------------------------------------------------------

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static JsonObject command(UUID requestId, String body) {
		final JsonObject json = json(body);
		json.addProperty("requestId", requestId.toString());

		return json;
	}

	private void send(String type, JsonObject payload) {
		web.publish(web.channels().commands(), web.newEnvelope(type, null, payload));
	}

	private JsonObject awaitResult(UUID requestId) throws Exception {
		assertTrue(TestRedis.await(() -> find(requestId) != null, 6_000), "no result for " + requestId);

		return find(requestId).payload();
	}

	private Envelope find(UUID requestId) {
		return results.stream().filter(e -> requestId.toString().equals(e.payload().get("requestId").getAsString())).findFirst().orElse(null);
	}

	private long countResults(UUID requestId) {
		return results.stream().filter(e -> requestId.toString().equals(e.payload().get("requestId").getAsString())).count();
	}

	private void assertNoResult(UUID requestId) throws Exception {
		Thread.sleep(1_200);
		assertNull(find(requestId), "unexpected result: " + (find(requestId) == null ? "" : find(requestId).payload()));
	}

	private UUID knownPlayer(String name) throws Exception {
		final UUID uuid = UUID.randomUUID();
		database.query(c -> {
			new PlayerRepository().touch(c, uuid, name, Instant.now(), "lobby");
			return null;
		});

		return uuid;
	}

	private static String uniqueName() {
		return "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private Punishment bySomeId(JsonObject result) throws Exception {
		return database.query(c -> punishments.findById(c, result.get("punishmentId").getAsLong())).orElseThrow();
	}

	// ---- tests ---------------------------------------------------------------------------

	@Test
	void issuesABanForAnOfflinePlayerByNameExactlyOnceAcrossNodes() throws Exception {
		final String name = uniqueName();
		final UUID uuid = knownPlayer(name);
		final UUID requestId = UUID.randomUUID();

		send("ISSUE_PUNISHMENT", command(requestId, "{\"actor\":{\"ref\":\"user-42\",\"name\":\"PanelAdmin\"},\"target\":{\"name\":\"" + name + "\"},"
				+ "\"kind\":\"BAN\",\"reasonId\":\"cheats\",\"silent\":true,\"note\":\"from web\"}"));

		final JsonObject result = awaitResult(requestId);

		assertEquals("OK", result.get("status").getAsString());
		assertEquals("ISSUE_PUNISHMENT", result.get("action").getAsString());
		assertEquals(8, result.get("publicId").getAsString().length());

		final Punishment p = bySomeId(result);
		assertEquals(uuid, p.targetUuid());
		assertEquals(PunishmentType.BAN, p.type());
		assertEquals(Scope.network(), p.scope());
		assertEquals(ActorType.WEB, p.actor().type());
		assertEquals("user-42", p.actor().ref());
		assertEquals("PanelAdmin", p.actor().name());
		assertEquals("web", p.originServer());
		assertEquals("from web", p.note());
		assertTrue(p.silent());

		Thread.sleep(1_000);
		assertEquals(1, countResults(requestId), "two nodes received the command but only one may answer");
		assertEquals(1, database.query(c -> punishments.history(c, uuid, 0, 10)).size());

		database.query(c -> {
			final CommandLogRepository.Entry entry = new CommandLogRepository().find(c, requestId).orElseThrow();
			assertEquals(CommandLogRepository.Status.OK, entry.status());
			assertEquals("ISSUE_PUNISHMENT", entry.action());
			assertTrue(entry.handledBy().startsWith("node-"));
			assertEquals(result.get("publicId").getAsString(), json(entry.result()).get("publicId").getAsString());
			return null;
		});
	}

	@Test
	void retryingWithTheSameRequestIdReturnsTheStoredResultWithoutRunningAgain() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();
		final String body = "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\"}";

		send("ISSUE_PUNISHMENT", command(requestId, body));
		final String publicId = awaitResult(requestId).get("publicId").getAsString();

		send("ISSUE_PUNISHMENT", command(requestId, body));
		assertTrue(TestRedis.await(() -> countResults(requestId) == 2, 6_000), "the owning node must answer the retry");

		assertEquals(publicId, results.get(results.size() - 1).payload().get("publicId").getAsString());
		Thread.sleep(500);
		assertEquals(2, countResults(requestId));
		assertEquals(1, database.query(c -> punishments.history(c, uuid, 0, 10)).size());
	}

	@Test
	void ignoresMessagesWithABadSignature() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();
		final RedisBus attacker = bus("attacker", "an-entirely-different-secret", Clock.systemUTC());

		attacker.publish(attacker.channels().commands(), attacker.newEnvelope("ISSUE_PUNISHMENT", null,
				command(requestId, "{\"actor\":{\"name\":\"Evil\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"customReason\":\"pwned\"}")));

		assertNoResult(requestId);
		assertEquals(0, database.query(c -> punishments.history(c, uuid, 0, 10)).size());
		assertTrue(database.query(c -> new CommandLogRepository().find(c, requestId)).isEmpty(), "unauthenticated requests must not even claim an id");
	}

	@Test
	void rejectsStaleOrFutureTimestamps() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final RedisBus lagging = bus("lagging-web", SECRET, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-5)));
		final RedisBus ahead = bus("ahead-web", SECRET, Clock.offset(Clock.systemUTC(), Duration.ofMinutes(5)));

		for (final RedisBus sender : List.of(lagging, ahead)) {
			final UUID requestId = UUID.randomUUID();

			sender.publish(sender.channels().commands(), sender.newEnvelope("ISSUE_PUNISHMENT", null,
					command(requestId, "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"customReason\":\"late\"}")));

			final JsonObject result = awaitResult(requestId);

			assertEquals("ERROR", result.get("status").getAsString());
			assertEquals("EXPIRED_REQUEST", result.get("error").getAsString());
		}

		assertEquals(0, database.query(c -> punishments.history(c, uuid, 0, 10)).size());
	}

	@Test
	void reportsDomainErrorsWithStableCodes() throws Exception {
		final String name = uniqueName();
		final UUID uuid = knownPlayer(name);
		final String actor = "\"actor\":{\"name\":\"PanelAdmin\"}";

		final Map<String, String> cases = Map.of(
				"UNKNOWN_PLAYER", "{" + actor + ",\"target\":{\"name\":\"nobody" + UUID.randomUUID().toString().substring(0, 6) + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\"}",
				"UNKNOWN_REASON", "{" + actor + ",\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"nope\"}",
				"REASON_TYPE_NOT_ALLOWED", "{" + actor + ",\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"spam\"}",
				"INVALID_DURATION", "{" + actor + ",\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"TEMPBAN\",\"reasonId\":\"cheats\",\"duration\":\"banana\"}",
				"UNKNOWN_SERVER", "{" + actor + ",\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\",\"scope\":{\"type\":\"SERVER\",\"target\":\"nowhere\"}}",
				"INVALID_REQUEST", "{\"actor\":{},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\"}");

		for (final Map.Entry<String, String> entry : cases.entrySet()) {
			final UUID requestId = UUID.randomUUID();

			send("ISSUE_PUNISHMENT", command(requestId, entry.getValue()));

			final JsonObject result = awaitResult(requestId);

			assertEquals("ERROR", result.get("status").getAsString(), entry.getKey());
			assertEquals(entry.getKey(), result.get("error").getAsString());
			assertFalse(result.get("message").getAsString().isBlank());
		}

		assertEquals(0, database.query(c -> punishments.history(c, uuid, 0, 10)).size(), "failed commands write no punishments");
	}

	@Test
	void malformedPayloadsAreInvalidRequestsNotCrashes() throws Exception {
		for (final String body : List.of(
				"{\"actor\":\"just a string\",\"target\":{},\"kind\":\"BAN\"}",
				"{\"actor\":{\"name\":\"A\"},\"target\":{\"uuid\":\"not-a-uuid\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\"}",
				"{\"actor\":{\"name\":\"A\"},\"target\":{\"uuid\":\"" + UUID.randomUUID() + "\"},\"kind\":\"EXPLODE\",\"reasonId\":\"cheats\"}",
				"{\"actor\":{\"name\":\"A\"},\"target\":{\"uuid\":\"" + UUID.randomUUID() + "\"},\"kind\":\"BAN\",\"reasonId\":5}",
				"{\"actor\":{\"name\":\"A\"},\"target\":{\"uuid\":\"" + UUID.randomUUID() + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\",\"silent\":\"yes\"}",
				"{\"actor\":{\"name\":\"A\"},\"target\":{\"uuid\":\"" + UUID.randomUUID() + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\",\"scope\":{\"type\":\"PLANET\"}}")) {
			final UUID requestId = UUID.randomUUID();

			send("ISSUE_PUNISHMENT", command(requestId, body));

			final JsonObject result = awaitResult(requestId);
			final String error = result.get("error").getAsString();

			assertTrue(error.equals("INVALID_REQUEST") || error.equals("UNKNOWN_PLAYER"), body + " -> " + error);
		}
	}

	@Test
	void commandsWithoutAValidRequestIdAreDropped() throws Exception {
		send("PING", json("{}"));
		send("PING", json("{\"requestId\":\"nope\"}"));
		send("PING", json("{\"requestId\":7}"));

		Thread.sleep(1_000);
		assertTrue(results.isEmpty());
	}

	@Test
	void unknownCommandTypesAreRejected() throws Exception {
		final UUID requestId = UUID.randomUUID();

		send("FORMAT_DISK", command(requestId, "{}"));

		final JsonObject result = awaitResult(requestId);
		assertEquals("INVALID_REQUEST", result.get("error").getAsString());
		assertTrue(result.get("message").getAsString().contains("FORMAT_DISK"));
	}

	@Test
	void serverTempBanFromTheWebHonoursScopeAndDuration() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final UUID requestId = UUID.randomUUID();

		send("ISSUE_PUNISHMENT", command(requestId, "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\",\"name\":\"Offline\"},"
				+ "\"kind\":\"TEMPBAN\",\"scope\":{\"type\":\"SERVER\",\"target\":\"survival\"},\"reasonId\":\"cheats\",\"duration\":\"7d\"}"));

		final Punishment p = bySomeId(awaitResult(requestId));

		assertEquals(new Scope(ScopeType.SERVER, "survival"), p.scope());
		assertEquals("Offline", p.targetName());
		assertEquals(Duration.ofDays(7), Duration.between(p.createdAt(), p.expiresAt()));
	}

	@Test
	void duplicateBansNeedForceAndForceReplaces() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final String body = "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\"";

		final UUID first = UUID.randomUUID();
		send("ISSUE_PUNISHMENT", command(first, body + "}"));
		assertEquals("OK", awaitResult(first).get("status").getAsString());

		final UUID second = UUID.randomUUID();
		send("ISSUE_PUNISHMENT", command(second, body + "}"));
		assertEquals("ALREADY_BANNED", awaitResult(second).get("error").getAsString());

		final UUID third = UUID.randomUUID();
		send("ISSUE_PUNISHMENT", command(third, body + ",\"force\":true}"));
		assertEquals("OK", awaitResult(third).get("status").getAsString());

		assertEquals(1, database.query(c -> punishments.findActiveBans(c, uuid, Instant.now())).size());
	}

	@Test
	void revokesByIdAndByTarget() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final UUID banRequest = UUID.randomUUID();

		send("ISSUE_PUNISHMENT", command(banRequest, "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\"}"));
		final JsonObject ban = awaitResult(banRequest);
		final long id = ban.get("punishmentId").getAsLong();

		final UUID byId = UUID.randomUUID();
		send("REVOKE_PUNISHMENT", command(byId, "{\"actor\":{\"ref\":\"u1\",\"name\":\"PanelAdmin\"},\"punishmentId\":" + id + ",\"reason\":\"appeal accepted\"}"));
		final JsonObject revoked = awaitResult(byId);

		assertEquals("OK", revoked.get("status").getAsString());
		assertEquals(ban.get("publicId").getAsString(), revoked.get("publicId").getAsString());

		final Punishment p = bySomeId(ban);
		assertTrue(p.isRevoked());
		assertEquals("appeal accepted", p.revocation().reason());
		assertEquals(ActorType.WEB, p.revocation().by().type());

		final UUID again = UUID.randomUUID();
		send("REVOKE_PUNISHMENT", command(again, "{\"actor\":{\"name\":\"PanelAdmin\"},\"punishmentId\":" + id + "}"));
		assertEquals("NOT_ACTIVE", awaitResult(again).get("error").getAsString());

		final UUID missing = UUID.randomUUID();
		send("REVOKE_PUNISHMENT", command(missing, "{\"actor\":{\"name\":\"PanelAdmin\"},\"punishmentId\":987654321}"));
		assertEquals("UNKNOWN_PUNISHMENT", awaitResult(missing).get("error").getAsString());

		final UUID rebanRequest = UUID.randomUUID();
		send("ISSUE_PUNISHMENT", command(rebanRequest, "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\",\"reasonId\":\"cheats\"}"));
		assertEquals("OK", awaitResult(rebanRequest).get("status").getAsString());

		final UUID byTarget = UUID.randomUUID();
		send("REVOKE_PUNISHMENT", command(byTarget, "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\"}"));
		assertEquals("OK", awaitResult(byTarget).get("status").getAsString());

		final UUID notBanned = UUID.randomUUID();
		send("REVOKE_PUNISHMENT", command(notBanned, "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"BAN\"}"));
		assertEquals("NOT_BANNED", awaitResult(notBanned).get("error").getAsString());
	}

	@Test
	void updatesExpiryReasonAndNoteAndReportsErrors() throws Exception {
		final UUID uuid = UUID.randomUUID();
		final UUID banRequest = UUID.randomUUID();

		send("ISSUE_PUNISHMENT", command(banRequest, "{\"actor\":{\"name\":\"PanelAdmin\"},\"target\":{\"uuid\":\"" + uuid + "\"},\"kind\":\"TEMPBAN\",\"reasonId\":\"cheats\",\"duration\":\"1d\"}"));
		final long id = awaitResult(banRequest).get("punishmentId").getAsLong();

		final long newExpiry = System.currentTimeMillis() + Duration.ofDays(9).toMillis();
		final UUID edit = UUID.randomUUID();

		send("UPDATE_PUNISHMENT", command(edit, "{\"actor\":{\"ref\":\"u1\",\"name\":\"PanelAdmin\"},\"punishmentId\":" + id + ",\"expiresAt\":" + newExpiry
				+ ",\"reasonText\":\"Flying near spawn\",\"note\":\"ticket 1187\"}"));
		final JsonObject edited = awaitResult(edit);

		assertEquals("OK", edited.get("status").getAsString(), edited.toString());
		assertEquals("UPDATE_PUNISHMENT", edited.get("action").getAsString());

		final Punishment p = database.query(c -> punishments.findById(c, id)).orElseThrow();
		assertEquals(newExpiry, p.expiresAt().toEpochMilli());
		assertEquals("Flying near spawn", p.reasonText());
		assertEquals("ticket 1187", p.note());

		final UUID permanent = UUID.randomUUID();
		send("UPDATE_PUNISHMENT", command(permanent, "{\"actor\":{\"name\":\"PanelAdmin\"},\"punishmentId\":" + id + ",\"expiresAt\":null,\"note\":null}"));
		assertEquals("OK", awaitResult(permanent).get("status").getAsString());

		final Punishment forever = database.query(c -> punishments.findById(c, id)).orElseThrow();
		assertNull(forever.expiresAt());
		assertNull(forever.note());

		final UUID past = UUID.randomUUID();
		send("UPDATE_PUNISHMENT", command(past, "{\"actor\":{\"name\":\"PanelAdmin\"},\"punishmentId\":" + id + ",\"expiresAt\":1000}"));
		assertEquals(ErrorCode.INVALID_DURATION.name(), awaitResult(past).get("error").getAsString());

		final UUID nothing = UUID.randomUUID();
		send("UPDATE_PUNISHMENT", command(nothing, "{\"actor\":{\"name\":\"PanelAdmin\"},\"punishmentId\":" + id + "}"));
		assertEquals(ErrorCode.INVALID_REQUEST.name(), awaitResult(nothing).get("error").getAsString());

		final UUID missing = UUID.randomUUID();
		send("UPDATE_PUNISHMENT", command(missing, "{\"actor\":{\"name\":\"PanelAdmin\"},\"punishmentId\":987654321,\"note\":\"x\"}"));
		assertEquals(ErrorCode.UNKNOWN_PUNISHMENT.name(), awaitResult(missing).get("error").getAsString());

		final UUID malformed = UUID.randomUUID();
		send("UPDATE_PUNISHMENT", command(malformed, "{\"actor\":{\"name\":\"PanelAdmin\"},\"punishmentId\":\"seven\"}"));
		assertEquals(ErrorCode.INVALID_REQUEST.name(), awaitResult(malformed).get("error").getAsString());
	}

	@Test
	void pingReportsWhichNodeAnswered() throws Exception {
		final UUID requestId = UUID.randomUUID();

		send("PING", command(requestId, "{}"));

		final JsonObject result = awaitResult(requestId);
		assertEquals("OK", result.get("status").getAsString());
		assertTrue(result.get("pong").getAsBoolean());
		assertTrue(Set.of("node-a", "node-b").contains(result.get("node").getAsString()));
		assertEquals(find(requestId).node(), result.get("node").getAsString());
	}

	@Test
	void customActionsCanBeRegistered() throws Exception {
		for (final WebCommandHandler handler : List.of(handlerA, handlerB))
			handler.register("RELOAD_REASONS", command -> {
				final JsonObject out = new JsonObject();
				out.addProperty("reloaded", 3);

				return Result.ok(out);
			});

		final UUID requestId = UUID.randomUUID();

		send("RELOAD_REASONS", command(requestId, "{}"));

		final JsonObject result = awaitResult(requestId);
		assertEquals("OK", result.get("status").getAsString());
		assertEquals(3, result.get("reloaded").getAsInt());
	}

	@Test
	void resultsAreSignedAndCarryTheEnvelopeFields() throws Exception {
		final UUID requestId = UUID.randomUUID();

		send("PING", command(requestId, "{}"));
		awaitResult(requestId);

		final Envelope envelope = find(requestId);
		assertEquals("COMMAND_RESULT", envelope.type());
		assertNull(envelope.seq());
		assertNotNull(envelope.sig());
		assertTrue(new HmacSigner(SECRET).verify(envelope));
	}
}
