package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.service.ExemptionChecker;
import pl.kingdomcraft.justbans.common.service.ExpirySweeper;
import pl.kingdomcraft.justbans.common.service.PunishmentRequest;
import pl.kingdomcraft.justbans.common.service.PunishmentService;
import pl.kingdomcraft.justbans.common.support.MutableClock;
import pl.kingdomcraft.justbans.common.support.TestDatabase;
import pl.kingdomcraft.justbans.common.support.TestRedis;

/** Sweepers on several nodes elect one through Redis, and the events reach the channel. */
class ExpirySweeperIntegrationTest {

	private static final Logger LOGGER = Logger.getLogger("sweeper-test");
	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Instant START = Instant.parse("2026-10-14T12:00:00Z");

	static {
		LOGGER.setLevel(Level.OFF);
	}

	private static TestDatabase db;
	private static Database database;

	private final List<AutoCloseable> closeables = new ArrayList<>();

	@BeforeAll
	static void setUp() throws Exception {
		db = TestDatabase.create();
		database = db.database();

		database.query(c -> {
			new ReasonRepository().sync(c, List.of(new ReasonDefinition("cheats", "Cheating", Set.of(PunishmentKind.BAN, PunishmentKind.TEMPBAN), Duration.ofDays(30), List.of(), null, Map.of(), 1, true)), START);
			return null;
		});
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (db != null)
			db.close();
	}

	@AfterEach
	void close() throws Exception {
		for (final AutoCloseable closeable : closeables)
			closeable.close();
	}

	private record Node(RedisBus bus, PunishmentService service, ExpirySweeper sweeper, OutboxPublisher outbox) {
	}

	private Node node(RedisConfig redis, String name, MutableClock clock) throws Exception {
		final RedisBus bus = new RedisBus(redis, new HmacSigner(SECRET), name, clock, LOGGER);
		closeables.add(bus);
		bus.start();
		assertTrue(TestRedis.await(bus::isConnected, 5_000));

		final PunishmentService service = new PunishmentService(database, name, clock, ExemptionChecker.NONE);
		final OutboxPublisher outbox = new OutboxPublisher(database, bus, name, clock, LOGGER, 200);
		service.onEventsAppended(outbox::wake);
		outbox.start();
		closeables.add(outbox);

		final ExpirySweeper sweeper = new ExpirySweeper(service, bus, name, Duration.ofSeconds(30), LOGGER);
		closeables.add(sweeper);

		return new Node(bus, service, sweeper, outbox);
	}

	@Test
	void onlyTheNodeHoldingTheLockSweepsAndTheEventsArePublished() throws Exception {
		final RedisConfig redis = TestRedis.config();
		final MutableClock clock = new MutableClock(START);
		final Node a = node(redis, "node-a", clock);
		final Node b = node(redis, "node-b", clock);
		final RedisBus listener = new RedisBus(redis, new HmacSigner(SECRET), "listener", clock, LOGGER);
		closeables.add(listener);

		final List<Envelope> expired = new CopyOnWriteArrayList<>();
		listener.onVerified(listener.channels().events(), event -> {
			if (event.type().equals("PUNISHMENT_EXPIRED"))
				expired.add(event);
		});
		listener.start();
		assertTrue(TestRedis.await(listener::isConnected, 5_000));

		final List<Punishment> bans = new ArrayList<>();

		for (int i = 0; i < 3; i++)
			bans.add(a.service().issueBlocking(PunishmentRequest.builder(PunishmentKind.TEMPBAN, UUID.randomUUID(), "S" + i, Actor.console()).reasonId("cheats").duration(Duration.ofMinutes(1)).build()).value());

		clock.advance(Duration.ofMinutes(10));

		final int first = a.sweeper().tick();
		final int second = b.sweeper().tick();

		assertEquals(3, first, "the first node to ask holds the lock and sweeps");
		assertEquals(0, second, "the other node sees the lock and skips this interval");

		assertTrue(TestRedis.await(() -> expired.size() == 3, 5_000), "events published: " + expired.size());
		assertEquals(3, expired.stream().map(Envelope::seq).distinct().count());
		assertTrue(expired.stream().allMatch(e -> e.node().equals("node-a")));
		assertEquals(bans.stream().map(Punishment::publicId).sorted().toList(),
				expired.stream().map(e -> e.payload().getAsJsonObject("punishment").get("publicId").getAsString()).sorted().toList());

		assertFalse(a.bus().tryLock(ExpirySweeper.lockKey(a.bus()), "x", 1_000), "the lock is still held until its TTL passes");

		// Real traffic for the schema checker (tools/check-schemas.py), see WebClientInteropTest.
		final Path dump = Path.of("target", "interop");
		Files.createDirectories(dump);

		for (int i = 0; i < expired.size(); i++)
			Files.writeString(dump.resolve("sweeper-" + i + "-PUNISHMENT_EXPIRED.json"), expired.get(i).toJson(), StandardCharsets.UTF_8);
	}

	@Test
	void lockCanBeTakenAgainAfterItsTtl() throws Exception {
		final RedisConfig redis = TestRedis.config();
		final Node a = node(redis, "node-a", new MutableClock(START));

		assertTrue(a.bus().tryLock("test:lock", "a", 300));
		assertFalse(a.bus().tryLock("test:lock", "b", 300));
		assertTrue(TestRedis.await(() -> a.bus().tryLock("test:lock", "b", 300), 3_000));
	}
}
