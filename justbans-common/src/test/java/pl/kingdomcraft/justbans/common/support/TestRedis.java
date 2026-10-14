package pl.kingdomcraft.justbans.common.support;

import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Assumptions;

import pl.kingdomcraft.justbans.common.messaging.RedisConfig;
import redis.clients.jedis.Jedis;

/**
 * Locates a Redis for integration tests: {@code JUSTBANS_TEST_REDIS=host:port:password} (password optional),
 * defaulting to {@code localhost:6379} without a password. Tests are skipped when it cannot be reached.
 * Every call returns a config with a unique channel prefix so tests never see each other's traffic.
 */
public final class TestRedis {

	private TestRedis() {
	}

	public static RedisConfig config() {
		String host = "localhost";
		int port = 6379;
		String password = null;

		final String env = System.getenv("JUSTBANS_TEST_REDIS");

		if (env != null && !env.isBlank()) {
			final String[] parts = env.split(":", 3);

			host = parts[0];

			if (parts.length > 1 && !parts[1].isBlank())
				port = Integer.parseInt(parts[1]);

			if (parts.length > 2 && !parts[2].isEmpty())
				password = parts[2];
		}

		final RedisConfig config = RedisConfig.of(host, port, password).withPrefix("jbtest-" + UUID.randomUUID().toString().substring(0, 8) + ":");

		try (Jedis jedis = raw(config)) {
			jedis.ping();
		} catch (final RuntimeException ex) {
			Assumptions.abort("No Redis reachable at " + host + ":" + port + " (set JUSTBANS_TEST_REDIS=host:port:password): " + ex.getMessage());
		}

		return config;
	}

	public static Jedis raw(RedisConfig config) {
		final Jedis jedis = new Jedis(config.host(), config.port());

		if (config.password() != null)
			jedis.auth(config.password());

		return jedis;
	}

	/** Polls until the condition holds or the timeout passes. */
	public static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
		final long deadline = System.nanoTime() + timeoutMs * 1_000_000;

		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean())
				return true;

			Thread.sleep(25);
		}

		return condition.getAsBoolean();
	}
}
