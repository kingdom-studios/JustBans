package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.EventRecord;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.repository.EventRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.service.ExemptionChecker;
import pl.kingdomcraft.justbans.common.service.PunishmentRequest;
import pl.kingdomcraft.justbans.common.service.PunishmentService;
import pl.kingdomcraft.justbans.common.service.RevokeRequest;
import pl.kingdomcraft.justbans.common.support.TestDatabase;
import pl.kingdomcraft.justbans.common.support.TestRedis;

class OutboxPublisherIntegrationTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Logger LOGGER = Logger.getLogger("outbox-test");
	private static final Actor ADMIN = Actor.console();

	static {
		LOGGER.setLevel(Level.OFF);
	}

	private static TestDatabase db;
	private static Database database;

	private final EventRepository events = new EventRepository();
	private final List<AutoCloseable> closeables = new ArrayList<>();

	private RedisConfig redis;
	private String node;
	private PunishmentService service;
	private RedisBus listenerBus;
	private BlockingQueue<RedisBus.Delivery> received;

	@BeforeAll
	static void setUpDatabase() throws Exception {
		db = TestDatabase.create();
		database = db.database();

		database.query(c -> {
			new ReasonRepository().sync(c, List.of(new ReasonDefinition("cheats", "Cheating", Set.of(PunishmentKind.BAN), null, List.of(), null, Map.of(), 1, true)), Instant.now());
			return null;
		});
	}

	@AfterAll
	static void tearDownDatabase() throws Exception {
		if (db != null)
			db.close();
	}

	@BeforeEach
	void setUp() throws Exception {
		redis = TestRedis.config();
		node = "node-" + UUID.randomUUID().toString().substring(0, 8);
		service = new PunishmentService(database, node, Clock.systemUTC(), ExemptionChecker.NONE);

		listenerBus = newBus("consumer", redis);
		received = new LinkedBlockingQueue<>();
		listenerBus.listen(listenerBus.channels().events(), received::add);
	}

	@AfterEach
	void tearDown() throws Exception {
		for (final AutoCloseable closeable : closeables)
			closeable.close();
	}

	private RedisBus newBus(String name, RedisConfig config) throws Exception {
		final RedisBus bus = new RedisBus(config, new HmacSigner(SECRET), name, Clock.systemUTC(), LOGGER);
		closeables.add(bus);
		bus.start();

		if (config.port() == redis.port())
			assertTrue(TestRedis.await(bus::isConnected, 5_000));

		return bus;
	}

	private OutboxPublisher outbox(RedisBus bus, long intervalMs) {
		final OutboxPublisher outbox = new OutboxPublisher(database, bus, node, Clock.systemUTC(), LOGGER, intervalMs);
		closeables.add(outbox);

		return outbox;
	}

	private Punishment ban() throws Exception {
		return service.issue(PunishmentRequest.builder(PunishmentKind.BAN, UUID.randomUUID(), "Target", ADMIN).reasonId("cheats").build()).get().value();
	}

	private List<EventRecord> mine() throws Exception {
		return database.query(c -> events.since(c, 0, 10_000)).stream().filter(e -> e.originNode().equals(node)).toList();
	}

	@Test
	void publishesPendingEventsInOrderWithSeqStableIdAndSignature() throws Exception {
		final RedisBus publisherBus = newBus(node, redis);
		final Punishment first = ban();
		final Punishment second = ban();
		service.revoke(RevokeRequest.byId(ADMIN, first.id(), "oops")).get();

		assertEquals(3, mine().size());
		assertTrue(mine().stream().allMatch(e -> e.publishedAt() == null));

		assertEquals(3, outbox(publisherBus, 60_000).drainOnce());

		final List<Envelope> envelopes = new ArrayList<>();

		for (int i = 0; i < 3; i++) {
			final RedisBus.Delivery delivery = received.poll(5, TimeUnit.SECONDS);

			assertNotNull(delivery);
			assertTrue(delivery.signatureValid());
			envelopes.add(delivery.envelope());
		}

		final List<EventRecord> stored = mine();

		assertEquals(stored.stream().map(EventRecord::id).toList(), envelopes.stream().map(Envelope::seq).toList(), "seq is the jb_events id, in order");
		assertEquals(List.of("PUNISHMENT_CREATED", "PUNISHMENT_CREATED", "PUNISHMENT_REVOKED"), envelopes.stream().map(Envelope::type).toList());

		for (int i = 0; i < 3; i++) {
			assertEquals(OutboxPublisher.envelopeIdFor(stored.get(i).id()), envelopes.get(i).id());
			assertEquals(node, envelopes.get(i).node());
			assertEquals(stored.get(i).createdAt().toEpochMilli(), envelopes.get(i).ts());
		}

		assertEquals(first.publicId(), envelopes.get(0).payload().getAsJsonObject("punishment").get("publicId").getAsString());
		assertEquals(second.publicId(), envelopes.get(1).payload().getAsJsonObject("punishment").get("publicId").getAsString());
		assertTrue(envelopes.get(2).payload().getAsJsonObject("punishment").has("revoked"));
		assertTrue(mine().stream().allMatch(e -> e.publishedAt() != null), "everything is marked published");
		assertEquals(0, outbox(publisherBus, 60_000).drainOnce(), "nothing left to send");
	}

	@Test
	void eventsPileUpWhileRedisIsDownAndFlushInOrderWhenItReturns() throws Exception {
		final RedisBus deadBus = new RedisBus(new RedisConfig("127.0.0.1", 1, null, null, false, 0, 200, "dead:"), new HmacSigner(SECRET), node, Clock.systemUTC(), LOGGER);
		closeables.add(deadBus);

		ban();
		ban();
		ban();

		assertEquals(0, outbox(deadBus, 60_000).drainOnce());
		assertEquals(3, mine().size());
		assertTrue(mine().stream().allMatch(e -> e.publishedAt() == null), "nothing may be marked published while Redis is down");
		assertNull(received.poll(200, TimeUnit.MILLISECONDS));

		ban(); // keeps working with Redis down

		final RedisBus workingBus = newBus(node, redis);
		assertEquals(4, outbox(workingBus, 60_000).drainOnce());

		final List<Long> seqs = new ArrayList<>();

		for (int i = 0; i < 4; i++)
			seqs.add(received.poll(5, TimeUnit.SECONDS).envelope().seq());

		assertEquals(mine().stream().map(EventRecord::id).toList(), seqs, "backlog arrives complete and ordered");
	}

	@Test
	void aRepublishedEventIsADuplicateTheBusDrops() throws Exception {
		final RedisBus publisherBus = newBus(node, redis);
		final OutboxPublisher outbox = outbox(publisherBus, 60_000);
		ban();

		assertEquals(1, outbox.drainOnce());
		assertNotNull(received.poll(5, TimeUnit.SECONDS));

		// Simulate a crash between publishing and marking: the row looks unpublished again.
		database.query(c -> {
			try (PreparedStatement statement = c.prepareStatement("UPDATE jb_events SET published_at = NULL WHERE origin_node = ?")) {
				statement.setString(1, node);
				statement.executeUpdate();
			}
			return null;
		});

		assertEquals(1, outbox.drainOnce());
		assertNull(received.poll(500, TimeUnit.MILLISECONDS), "the consumer must see the same id only once");
		assertTrue(mine().get(0).publishedAt() != null);
	}

	@Test
	void onlyPublishesItsOwnNodesEvents() throws Exception {
		final RedisBus publisherBus = newBus(node, redis);
		final PunishmentService other = new PunishmentService(database, "other-" + node, Clock.systemUTC(), ExemptionChecker.NONE);

		other.issue(PunishmentRequest.builder(PunishmentKind.BAN, UUID.randomUUID(), "T", ADMIN).reasonId("cheats").build()).get();
		ban();

		assertEquals(1, outbox(publisherBus, 60_000).drainOnce());
		assertEquals(node, received.poll(5, TimeUnit.SECONDS).envelope().node());
		assertNull(received.poll(300, TimeUnit.MILLISECONDS));

		database.query(c -> {
			assertTrue(events.unpublished(c, "other-" + node, 10).size() == 1, "the other node's event stays for that node to publish");
			return null;
		});
	}

	@Test
	void wakePublishesPromptlyWithoutWaitingForTheInterval() throws Exception {
		final RedisBus publisherBus = newBus(node, redis);
		final OutboxPublisher outbox = outbox(publisherBus, 600_000);
		service.onEventsAppended(outbox::wake);
		outbox.start();

		Thread.sleep(300); // let the initial (empty) drain pass
		ban();

		assertNotNull(received.poll(3, TimeUnit.SECONDS), "a committed event must be relayed immediately, not after the 10 minute interval");
	}

	@Test
	void periodicDrainRecoversEventsWithoutAnyWake() throws Exception {
		final RedisBus publisherBus = newBus(node, redis);
		ban();

		outbox(publisherBus, 200).start();

		assertNotNull(received.poll(3, TimeUnit.SECONDS));
		assertFalse(mine().isEmpty());
	}

	@Test
	void envelopeIdsAreStablePerEventAndDistinct() {
		assertEquals(OutboxPublisher.envelopeIdFor(42), OutboxPublisher.envelopeIdFor(42));
		assertFalse(OutboxPublisher.envelopeIdFor(42).equals(OutboxPublisher.envelopeIdFor(43)));
	}
}
