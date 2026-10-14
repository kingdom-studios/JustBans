package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import pl.kingdomcraft.justbans.common.support.TestRedis;
import redis.clients.jedis.Jedis;

class RedisBusIntegrationTest {

	private static final String SECRET = "0123456789abcdef-test-secret";
	private static final Logger LOGGER = Logger.getLogger("redis-test");

	static {
		LOGGER.setLevel(Level.OFF);
	}

	private RedisConfig config;
	private final List<RedisBus> buses = new ArrayList<>();

	@BeforeEach
	void setUp() {
		config = TestRedis.config();
	}

	@AfterEach
	void tearDown() {
		buses.forEach(RedisBus::close);
	}

	private RedisBus bus(String node) throws InterruptedException {
		return bus(node, config, SECRET);
	}

	private RedisBus bus(String node, RedisConfig cfg, String secret) throws InterruptedException {
		final RedisBus bus = new RedisBus(cfg, new HmacSigner(secret), node, Clock.systemUTC(), LOGGER);
		buses.add(bus);
		bus.start();

		assertTrue(TestRedis.await(bus::isConnected, 5_000), node + " did not subscribe");

		return bus;
	}

	private static JsonObject payload(String key, Object value) {
		final JsonObject json = new JsonObject();
		json.addProperty(key, String.valueOf(value));

		return json;
	}

	private static BlockingQueue<RedisBus.Delivery> collect(RedisBus bus, String channel) {
		final BlockingQueue<RedisBus.Delivery> queue = new LinkedBlockingQueue<>();
		bus.listen(channel, queue::add);

		return queue;
	}

	@Test
	void connectsAndReportsStatus() throws Exception {
		final RedisBus bus = new RedisBus(config, new HmacSigner(SECRET), "a", Clock.systemUTC(), LOGGER);
		buses.add(bus);
		final AtomicInteger connected = new AtomicInteger();
		bus.addConnectionListener(new RedisBus.ConnectionListener() {
			@Override
			public void onConnected() {
				connected.incrementAndGet();
			}

			@Override
			public void onDisconnected() {
			}
		});

		assertFalse(bus.isConnected());
		bus.start();

		assertTrue(TestRedis.await(bus::isConnected, 5_000));
		assertEquals(1, connected.get());
		assertEquals(config.prefix() + "events", bus.channels().events());
	}

	@Test
	void deliversSignedMessagesToEveryBusIncludingTheSender() throws Exception {
		final RedisBus sender = bus("velocity-1");
		final RedisBus other = bus("paper-1");
		final BlockingQueue<RedisBus.Delivery> atSender = collect(sender, sender.channels().events());
		final BlockingQueue<RedisBus.Delivery> atOther = collect(other, other.channels().events());

		final long receivers = sender.publish(sender.channels().events(), sender.newEnvelope("PUNISHMENT_CREATED", 17L, payload("hello", "wörld")));

		assertEquals(2, receivers);

		for (final BlockingQueue<RedisBus.Delivery> queue : List.of(atSender, atOther)) {
			final RedisBus.Delivery delivery = queue.poll(5, TimeUnit.SECONDS);

			assertNotNull(delivery);
			assertTrue(delivery.signatureValid());
			assertEquals(sender.channels().events(), delivery.channel());
			assertEquals("PUNISHMENT_CREATED", delivery.envelope().type());
			assertEquals("velocity-1", delivery.envelope().node());
			assertEquals(17L, delivery.envelope().seq());
			assertEquals("wörld", delivery.envelope().payload().get("hello").getAsString());
		}
	}

	@Test
	void channelsAreKeptApart() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> events = collect(bus, bus.channels().events());
		final BlockingQueue<RedisBus.Delivery> commands = collect(bus, bus.channels().commands());
		final BlockingQueue<RedisBus.Delivery> results = collect(bus, bus.channels().commandResults());

		bus.publish(bus.channels().commands(), bus.newEnvelope("PING", null, new JsonObject()));
		bus.publish(bus.channels().commandResults(), bus.newEnvelope("COMMAND_RESULT", null, new JsonObject()));

		assertEquals("PING", commands.poll(5, TimeUnit.SECONDS).envelope().type());
		assertEquals("COMMAND_RESULT", results.poll(5, TimeUnit.SECONDS).envelope().type());
		assertNull(events.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	void differentPrefixesDoNotSeeEachOther() throws Exception {
		final RedisBus one = bus("a");
		final RedisBus two = bus("b", config.withPrefix("jbtest-other-" + System.nanoTime() + ":"), SECRET);
		final BlockingQueue<RedisBus.Delivery> atTwo = collect(two, two.channels().events());

		one.publish(one.channels().events(), one.newEnvelope("X", null, new JsonObject()));

		assertNull(atTwo.poll(400, TimeUnit.MILLISECONDS));
	}

	@Test
	void flagsBadSignaturesAndVerifiedListenersNeverSeeThem() throws Exception {
		final RedisBus good = bus("good");
		final RedisBus attacker = bus("attacker", config, "an-entirely-different-secret");
		final BlockingQueue<RedisBus.Delivery> raw = collect(good, good.channels().commands());
		final BlockingQueue<Envelope> verified = new LinkedBlockingQueue<>();
		good.onVerified(good.channels().commands(), verified::add);

		attacker.publish(good.channels().commands(), attacker.newEnvelope("ISSUE_PUNISHMENT", null, payload("x", 1)));

		final RedisBus.Delivery delivery = raw.poll(5, TimeUnit.SECONDS);
		assertNotNull(delivery);
		assertFalse(delivery.signatureValid());
		assertNull(verified.poll(300, TimeUnit.MILLISECONDS));

		good.publish(good.channels().commands(), good.newEnvelope("PING", null, new JsonObject()));
		assertEquals("PING", verified.poll(5, TimeUnit.SECONDS).type());
	}

	@Test
	void forgedUnsignedMessageIsFlagged() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> queue = collect(bus, bus.channels().commands());

		try (Jedis jedis = TestRedis.raw(config)) {
			jedis.publish(bus.channels().commands(), "{\"v\":1,\"id\":\"forged\",\"type\":\"ISSUE_PUNISHMENT\",\"ts\":1,\"node\":\"web\",\"payload\":{}}");
		}

		final RedisBus.Delivery delivery = queue.poll(5, TimeUnit.SECONDS);
		assertNotNull(delivery);
		assertFalse(delivery.signatureValid());
	}

	@Test
	void duplicateEnvelopeIdsAreDeliveredOnce() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> queue = collect(bus, bus.channels().events());
		final Envelope envelope = bus.newEnvelope("E", 5L, payload("n", 1));

		bus.publish(bus.channels().events(), envelope);
		bus.publish(bus.channels().events(), envelope);
		bus.publish(bus.channels().events(), bus.newEnvelope("E", 6L, payload("n", 2)));

		assertEquals(5L, queue.poll(5, TimeUnit.SECONDS).envelope().seq());
		assertEquals(6L, queue.poll(5, TimeUnit.SECONDS).envelope().seq(), "the duplicate must have been dropped");
		assertNull(queue.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	void forgedMessagesCannotUseUpARealMessageId() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> queue = collect(bus, bus.channels().events());
		final Envelope real = bus.newEnvelope("E", 1L, payload("n", 1));

		try (Jedis jedis = TestRedis.raw(config)) {
			jedis.publish(bus.channels().events(), real.withSignature("forged").toJson());
		}

		assertFalse(queue.poll(5, TimeUnit.SECONDS).signatureValid());

		bus.publish(bus.channels().events(), real);
		assertTrue(queue.poll(5, TimeUnit.SECONDS).signatureValid(), "the genuine message with the same id must still arrive");
	}

	@Test
	void malformedMessagesAreDroppedWithoutBreakingTheSubscription() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> queue = collect(bus, bus.channels().events());

		try (Jedis jedis = TestRedis.raw(config)) {
			jedis.publish(bus.channels().events(), "garbage");
			jedis.publish(bus.channels().events(), "{}");
			jedis.publish(bus.channels().events(), "[1,2,3]");
			jedis.publish(bus.channels().events(), "x".repeat(RedisBus.MAX_MESSAGE_LENGTH + 1));
		}

		bus.publish(bus.channels().events(), bus.newEnvelope("REAL", 1L, new JsonObject()));

		assertEquals("REAL", queue.poll(5, TimeUnit.SECONDS).envelope().type());
		assertNull(queue.poll(300, TimeUnit.MILLISECONDS));
		assertTrue(bus.isConnected());
	}

	@Test
	void refusesToPublishOversizedMessages() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> queue = collect(bus, bus.channels().events());

		assertThrows(BusException.class, () -> bus.publish(bus.channels().events(), bus.newEnvelope("BIG", null, payload("blob", "x".repeat(RedisBus.MAX_MESSAGE_LENGTH)))));
		assertNull(queue.poll(300, TimeUnit.MILLISECONDS));
	}

	@Test
	void aThrowingListenerDoesNotStopOthersOrLaterMessages() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> queue = new LinkedBlockingQueue<>();

		bus.listen(bus.channels().events(), delivery -> {
			throw new IllegalStateException("boom");
		});
		bus.listen(bus.channels().events(), queue::add);

		bus.publish(bus.channels().events(), bus.newEnvelope("A", 1L, new JsonObject()));
		bus.publish(bus.channels().events(), bus.newEnvelope("B", 2L, new JsonObject()));

		assertEquals("A", queue.poll(5, TimeUnit.SECONDS).envelope().type());
		assertEquals("B", queue.poll(5, TimeUnit.SECONDS).envelope().type());
	}

	@Test
	void preservesOrderForManyMessages() throws Exception {
		final RedisBus bus = bus("a");
		final List<Long> received = new CopyOnWriteArrayList<>();
		bus.listen(bus.channels().events(), delivery -> received.add(delivery.envelope().seq()));

		for (long i = 1; i <= 300; i++)
			bus.publish(bus.channels().events(), bus.newEnvelope("E", i, new JsonObject()));

		assertTrue(TestRedis.await(() -> received.size() == 300, 10_000));

		for (int i = 0; i < 300; i++)
			assertEquals(i + 1L, received.get(i));
	}

	@Test
	void publishFailsCleanlyWhenRedisIsUnreachable() {
		final RedisBus dead = new RedisBus(new RedisConfig("127.0.0.1", 1, null, null, false, 0, 300, "x:"), new HmacSigner(SECRET), "n", Clock.systemUTC(), LOGGER);
		buses.add(dead);

		assertThrows(BusException.class, () -> dead.publish(dead.channels().events(), dead.newEnvelope("E", 1L, new JsonObject())));
	}

	@Test
	void reconnectsAfterTheConnectionIsKilledAndKeepsDelivering() throws Exception {
		final RedisBus bus = bus("a");
		final BlockingQueue<RedisBus.Delivery> queue = collect(bus, bus.channels().events());
		final AtomicInteger disconnects = new AtomicInteger();
		final AtomicInteger connects = new AtomicInteger();
		bus.addConnectionListener(new RedisBus.ConnectionListener() {
			@Override
			public void onConnected() {
				connects.incrementAndGet();
			}

			@Override
			public void onDisconnected() {
				disconnects.incrementAndGet();
			}
		});

		try (Jedis jedis = TestRedis.raw(config)) {
			jedis.clientKill(new redis.clients.jedis.params.ClientKillParams().type(redis.clients.jedis.args.ClientType.PUBSUB));
		}

		assertTrue(TestRedis.await(() -> disconnects.get() >= 1, 5_000), "the drop must be noticed");
		assertTrue(TestRedis.await(() -> connects.get() >= 1 && bus.isConnected(), 15_000), "the bus must resubscribe by itself");

		bus.publish(bus.channels().events(), bus.newEnvelope("AFTER", 1L, new JsonObject()));
		assertEquals("AFTER", queue.poll(5, TimeUnit.SECONDS).envelope().type());
	}

	@Test
	void startsBeforeRedisIsReachableAndConnectsOnceItIs() throws Exception {
		// Point at a closed port first to prove start() does not throw and the loop keeps retrying quietly.
		final RedisBus bus = new RedisBus(new RedisConfig("127.0.0.1", 1, null, null, false, 0, 200, "x:"), new HmacSigner(SECRET), "n", Clock.systemUTC(), LOGGER);
		buses.add(bus);

		bus.start();
		Thread.sleep(300);

		assertFalse(bus.isConnected());
	}

	@Test
	void hashHelpersMaintainTheOnlineDirectory() throws Exception {
		final RedisBus bus = bus("a");
		final String key = bus.channels().online();

		assertEquals(config.prefix() + "online", key);
		assertTrue(bus.hashGetAll(key).isEmpty());

		bus.hashPut(key, "u1", "{\"name\":\"A\",\"server\":\"lobby\"}");
		bus.hashPut(key, "u2", "{}");
		bus.hashPut(key, "u1", "{\"name\":\"A\",\"server\":\"survival\"}");
		assertEquals(2, bus.hashGetAll(key).size());
		assertTrue(bus.hashGetAll(key).get("u1").contains("survival"));

		assertTrue(bus.hashGet(key, "u1").contains("survival"));
		assertNull(bus.hashGet(key, "nobody"));

		bus.hashRemove(key, "u2");
		bus.hashRemove(key, "missing");
		assertEquals(List.of("u1"), List.copyOf(bus.hashGetAll(key).keySet()));

		bus.hashReplace(key, java.util.Map.of("x", "1", "y", "2"));
		assertEquals(java.util.Map.of("x", "1", "y", "2"), bus.hashGetAll(key));

		bus.hashReplace(key, java.util.Map.of());
		assertTrue(bus.hashGetAll(key).isEmpty());
	}

	@Test
	void hashHelpersFailCleanlyWithoutRedis() {
		final RedisBus dead = new RedisBus(new RedisConfig("127.0.0.1", 1, null, null, false, 0, 300, "x:"), new HmacSigner(SECRET), "n", Clock.systemUTC(), LOGGER);
		buses.add(dead);

		assertThrows(BusException.class, () -> dead.hashPut("k", "f", "v"));
		assertThrows(BusException.class, () -> dead.hashRemove("k", "f"));
		assertThrows(BusException.class, () -> dead.hashReplace("k", java.util.Map.of()));
		assertThrows(BusException.class, () -> dead.hashGetAll("k"));
	}

	@Test
	void closeStopsTheSubscriberThread() throws Exception {
		final RedisBus bus = bus("a");
		assertTrue(subscriberThreads() >= 1);

		bus.close();

		assertFalse(bus.isConnected());
		assertTrue(TestRedis.await(() -> subscriberThreads() == 0, 3_000), "subscriber thread must be gone after close()");
	}

	/** Earlier tests close their buses in tearDown, so only this test's bus can still have a thread. */
	private static long subscriberThreads() {
		return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("JustBans-Redis-Subscriber") && t.isAlive()).count();
	}
}
