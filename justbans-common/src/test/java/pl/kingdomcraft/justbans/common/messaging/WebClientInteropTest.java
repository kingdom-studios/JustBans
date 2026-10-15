package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.repository.PlayerRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.repository.ServerRepository;
import pl.kingdomcraft.justbans.common.service.ExemptionChecker;
import pl.kingdomcraft.justbans.common.service.PunishmentService;
import pl.kingdomcraft.justbans.common.support.TestDatabase;
import pl.kingdomcraft.justbans.common.support.TestRedis;

/**
 * Runs the real {@code tools/web-client.py} against a real plugin-side handler, to prove the Python reference client
 * and the Java implementation agree on canonical JSON and signatures end to end.
 *
 * <p>Opt-in: set {@code JUSTBANS_TEST_PYTHON} to a Python 3 interpreter that has redis-py installed
 * (e.g. {@code JUSTBANS_TEST_PYTHON=/path/to/venv/bin/python}). Also needs Redis and MariaDB like the other
 * integration tests. Run with a UTF-8 locale (e.g. {@code LC_ALL=C.UTF-8}) so the non-ASCII arguments reach Python intact. Every message that crossed Redis is written to {@code target/interop/} so the JSON schemas in
 * docs/schemas can be checked against real traffic.
 */
class WebClientInteropTest {

	private static final String SECRET = "justbans-interop-secret-123";
	private static final Logger LOGGER = Logger.getLogger("interop");
	private static final Path CLIENT = Path.of("..", "tools", "web-client.py");
	private static final Path DUMP = Path.of("target", "interop");

	static {
		LOGGER.setLevel(Level.OFF);
	}

	private static TestDatabase db;
	private static Database database;

	@BeforeAll
	static void setUp() throws Exception {
		Assumptions.assumeTrue(System.getenv("JUSTBANS_TEST_PYTHON") != null, "JUSTBANS_TEST_PYTHON is not set");
		Assumptions.assumeTrue(Files.exists(CLIENT), "tools/web-client.py not found");

		db = TestDatabase.create();
		database = db.database();

		database.query(c -> {
			new ServerRepository().upsert(c, "survival", "survival", "Survival");
			new ReasonRepository().sync(c, List.of(new ReasonDefinition("cheats", "Cheating", Set.of(PunishmentKind.BAN, PunishmentKind.TEMPBAN, PunishmentKind.SERVER_BAN, PunishmentKind.SERVER_TEMPBAN),
					Duration.ofDays(30), List.of(), null, Map.of(), 1, true)), Instant.now());
			return null;
		});
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (db != null)
			db.close();
	}

	private record Run(int exit, String out, String err) {
	}

	private RedisConfig redis;

	private Run python(String... args) throws Exception {
		final String url = "redis://" + (redis.password() == null ? "" : ":" + redis.password() + "@") + redis.host() + ":" + redis.port() + "/0";
		final List<String> command = new ArrayList<>(List.of(System.getenv("JUSTBANS_TEST_PYTHON"), CLIENT.toString(), "--redis-url", url, "--prefix", redis.prefix(), "--timeout", "8"));
		command.addAll(List.of(args));

		final ProcessBuilder builder = new ProcessBuilder(command);
		builder.environment().put("JUSTBANS_SECRET", SECRET);
		final Process process = builder.start();
		final boolean finished = process.waitFor(30, TimeUnit.SECONDS);

		if (!finished)
			process.destroyForcibly();

		return new Run(finished ? process.exitValue() : -1, new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	@Test
	void pythonClientTalksToTheJavaHandler() throws Exception {
		redis = TestRedis.config();
		Files.createDirectories(DUMP);

		final RedisBus nodeBus = new RedisBus(redis, new HmacSigner(SECRET), "velocity-1", Clock.systemUTC(), LOGGER);
		final RedisBus capture = new RedisBus(redis, new HmacSigner(SECRET), "capture", Clock.systemUTC(), LOGGER);
		final List<Envelope> seen = new CopyOnWriteArrayList<>();

		try {
			nodeBus.start();
			capture.start();
			assertTrue(TestRedis.await(() -> nodeBus.isConnected() && capture.isConnected(), 5_000));

			for (final String channel : capture.channels().all())
				capture.onVerified(channel, seen::add);

			final PunishmentService service = new PunishmentService(database, "velocity-1", Clock.systemUTC(), ExemptionChecker.NONE);
			new WebCommandHandler(database, service, nodeBus, "velocity-1", Clock.systemUTC(), LOGGER, Duration.ofSeconds(30)).start();

			final OutboxPublisher outbox = new OutboxPublisher(database, nodeBus, "velocity-1", Clock.systemUTC(), LOGGER, 300);
			outbox.start();
			service.onEventsAppended(outbox::wake);

			final String name = "i" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
			database.query(c -> {
				new PlayerRepository().touch(c, UUID.randomUUID(), name, Instant.now(), "lobby");
				return null;
			});

			Run run = python("selftest");
			assertEquals(0, run.exit(), run.out() + run.err());

			run = python("ping");
			assertEquals(0, run.exit(), run.out() + run.err());
			assertTrue(run.out().contains("\"pong\": true"), run.out());

			run = python("ban", "--player", name, "--reason", "cheats", "--actor-name", "Web: alice é☃", "--actor-ref", "42", "--note", "interop \"quoted\" \\ test");
			assertEquals(0, run.exit(), run.out() + run.err());
			assertTrue(run.out().contains("\"status\": \"OK\""), run.out());

			run = python("ban", "--player", name, "--reason", "cheats", "--actor-name", "Web: alice");
			assertEquals(1, run.exit(), run.out() + run.err());
			assertTrue(run.out().contains("ALREADY_BANNED"), run.out());

			run = python("pardon", "--player", name, "--reason", "Appeal accepted", "--actor-name", "Web: alice");
			assertEquals(0, run.exit(), run.out() + run.err());

			run = python("ban", "--player", name, "--kind", "TEMPBAN", "--duration", "7d", "--custom-reason", "Griefing 😀", "--actor-name", "Web: alice", "--server", "survival");
			assertEquals(0, run.exit(), run.out() + run.err());

			run = python("ban", "--player", "n" + name.substring(1), "--reason", "cheats", "--actor-name", "Web: alice");
			assertEquals(1, run.exit(), run.out() + run.err());
			assertTrue(run.out().contains("UNKNOWN_PLAYER"), run.out());

			assertTrue(TestRedis.await(() -> seen.stream().filter(e -> e.type().equals("PUNISHMENT_REVOKED")).count() >= 1 && seen.stream().filter(e -> e.type().equals("PUNISHMENT_CREATED")).count() >= 2, 6_000),
					"events were not published: " + seen.stream().map(Envelope::type).toList());

			int index = 0;

			for (final Envelope envelope : seen)
				Files.writeString(DUMP.resolve(String.format("%02d-%s.json", index++, envelope.type())), envelope.toJson(), StandardCharsets.UTF_8);

			final JsonObject first = JsonParser.parseString(seen.stream().filter(e -> e.type().equals("ISSUE_PUNISHMENT")).findFirst().orElseThrow().toJson()).getAsJsonObject();
			assertTrue(first.has("sig"));
			outbox.close();

		} finally {
			capture.close();
			nodeBus.close();
		}
	}
}
