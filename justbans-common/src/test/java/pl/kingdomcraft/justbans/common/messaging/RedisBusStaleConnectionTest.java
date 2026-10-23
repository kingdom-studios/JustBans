package pl.kingdomcraft.justbans.common.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.support.FlakyProxy;
import pl.kingdomcraft.justbans.common.support.TestRedis;

/**
 * Connections in the shared pool can die silently (a firewall drops them after a quiet spell, Redis restarts). The bus must
 * notice on the first failing command, throw away all idle connections together and retry on a fresh one, instead of paying one
 * read timeout per dead connection (which stalled the plugin's threads for many seconds and logged a stack trace each time).
 */
class RedisBusStaleConnectionTest {

	private static final Logger LOGGER = Logger.getLogger("stale-test");
	private static final long READ_TIMEOUT_MS = 1_000;

	static {
		LOGGER.setLevel(Level.OFF);
	}

	private final List<AutoCloseable> closeables = new ArrayList<>();

	@AfterEach
	void tearDown() throws Exception {
		for (final AutoCloseable closeable : closeables)
			closeable.close();
	}

	private record Setup(RedisBus bus, FlakyProxy proxy, String key) {
	}

	/** A bus whose pool already holds several connections that all go through the proxy. */
	private Setup busWithWarmPool() throws Exception {
		final RedisConfig real = TestRedis.config();
		final FlakyProxy proxy = new FlakyProxy(real.host(), real.port(), 60);
		closeables.add(proxy);

		final RedisConfig viaProxy = new RedisConfig("127.0.0.1", proxy.port(), real.user(), real.password(), false, 0, (int) READ_TIMEOUT_MS, real.prefix());
		final RedisBus bus = new RedisBus(viaProxy, new HmacSigner("0123456789abcdef-test-secret"), "stale-test", Clock.systemUTC(), LOGGER);
		closeables.add(bus);

		final String key = real.prefix() + "online";
		final ExecutorService callers = Executors.newFixedThreadPool(6);

		try {
			final List<Future<?>> calls = new ArrayList<>();

			for (int i = 0; i < 6; i++)
				calls.add(callers.submit(() -> {
					for (int n = 0; n < 4; n++)
						bus.hashPut(key, "warm", "{}");
				}));

			for (final Future<?> call : calls)
				call.get();
		} finally {
			callers.shutdownNow();
		}

		assertTrue(proxy.connectionsAccepted() >= 3, "the test needs several pooled connections, got " + proxy.connectionsAccepted());

		return new Setup(bus, proxy, key);
	}

	@Test
	void recoversFromSilentlyDeadPooledConnectionsWithinOneTimeoutNotOnePerConnection() throws Exception {
		final Setup setup = busWithWarmPool();
		setup.proxy().stallExisting();

		final long start = System.nanoTime();
		final Map<String, String> result = setup.bus().hashGetAll(setup.key());
		final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertEquals("{}", result.get("warm"));
		assertTrue(elapsedMs < 2_500, "one read timeout (" + READ_TIMEOUT_MS + " ms) plus a retry was expected, but it took " + elapsedMs + " ms, so every dead connection was waited out");
	}

	@Test
	void everyOperationRecoversTheSameWay() throws Exception {
		final Setup setup = busWithWarmPool();
		final RedisBus bus = setup.bus();
		final String lock = setup.key() + ":lock";

		setup.proxy().stallExisting();
		assertTrue(bus.tryLock(lock, "me", 5_000), "the lock is taken on the retry");

		setup.proxy().stallExisting();
		bus.hashPut(setup.key(), "a", "1");

		setup.proxy().stallExisting();
		assertEquals("1", bus.hashGet(setup.key(), "a"));

		setup.proxy().stallExisting();
		bus.hashReplace(setup.key(), Map.of("b", "2"));
		assertEquals(Map.of("b", "2"), bus.hashGetAll(setup.key()));

		setup.proxy().stallExisting();
		bus.hashRemove(setup.key(), "b");
		assertTrue(bus.hashGetAll(setup.key()).isEmpty());

		final Envelope envelope = bus.newEnvelope("TEST", null, new com.google.gson.JsonObject());
		setup.proxy().stallExisting();
		bus.publish(bus.channels().events(), envelope);
	}

	@Test
	void aRealOutageIsReportedAsABusExceptionWithoutAnExtraTimeout() throws Exception {
		final Setup setup = busWithWarmPool();
		setup.proxy().close(); // Redis is gone: connections reset, new ones are refused

		final long start = System.nanoTime();

		assertThrows(BusException.class, () -> setup.bus().hashGetAll(setup.key()));
		assertThrows(BusException.class, () -> setup.bus().tryLock(setup.key() + ":lock", "me", 1_000));

		final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertTrue(elapsedMs < 4_000, "an outage must fail fast, took " + elapsedMs + " ms");
		assertFalse(setup.proxy().connectionsAccepted() < 0);
	}
}
