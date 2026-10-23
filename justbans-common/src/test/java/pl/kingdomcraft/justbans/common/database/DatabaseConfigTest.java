package pl.kingdomcraft.justbans.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariConfig;

class DatabaseConfigTest {

	private static DatabaseConfig config(long maxLifetime, long keepalive, Map<String, String> properties) {
		return new DatabaseConfig("db", 3306, "jb", "u", "p", 6, 10_000, maxLifetime, keepalive, properties);
	}

	@Test
	void defaultsKeepIdleConnectionsAliveAndRetireOldOnes() {
		final HikariConfig hikari = Database.hikariConfig(DatabaseConfig.of("db", 3306, "jb", "u", "p"));

		assertEquals(DatabaseConfig.DEFAULT_MAX_LIFETIME_MS, hikari.getMaxLifetime());
		assertEquals(DatabaseConfig.DEFAULT_KEEPALIVE_MS, hikari.getKeepaliveTime());
		assertTrue(hikari.getKeepaliveTime() < hikari.getMaxLifetime());
		assertTrue(hikari.getMaxLifetime() < 30 * 60_000, "shorter than HikariCP's 30 minute default, which is what let connections go stale");
	}

	@Test
	void theExistingConstructorGetsTheSameDefaults() {
		final DatabaseConfig old = new DatabaseConfig("db", 3306, "jb", "u", "p", 6, 10_000, Map.of());

		assertEquals(DatabaseConfig.DEFAULT_MAX_LIFETIME_MS, old.maxLifetimeMs());
		assertEquals(DatabaseConfig.DEFAULT_KEEPALIVE_MS, old.keepaliveMs());
	}

	@Test
	void customValuesReachThePool() {
		final HikariConfig hikari = Database.hikariConfig(config(130_000, 30_000, Map.of()));

		assertEquals(130_000, hikari.getMaxLifetime());
		assertEquals(30_000, hikari.getKeepaliveTime());
		assertEquals(6, hikari.getMaximumPoolSize());
		assertEquals(2, hikari.getMinimumIdle());
	}

	@Test
	void keepaliveCanBeSwitchedOff() {
		assertEquals(0, Database.hikariConfig(config(130_000, 0, Map.of())).getKeepaliveTime());
	}

	@Test
	void rejectsValuesHikariWouldSilentlyIgnore() {
		assertThrows(IllegalArgumentException.class, () -> config(10_000, 0, Map.of()), "lifetime under 30 s");
		assertThrows(IllegalArgumentException.class, () -> config(130_000, 5_000, Map.of()), "keepalive under 10 s");
		assertThrows(IllegalArgumentException.class, () -> config(130_000, 130_000, Map.of()), "keepalive not below the lifetime");
	}

	@Test
	void tcpKeepAliveIsOnUnlessTheAdminSaysOtherwise() {
		assertTrue(config(130_000, 30_000, Map.of()).jdbcUrl().contains("tcpKeepAlive=true"));
		assertTrue(config(130_000, 30_000, Map.of("tcpKeepAlive", "false")).jdbcUrl().contains("tcpKeepAlive=false"));
		assertTrue(config(130_000, 30_000, Map.of()).jdbcUrl().contains("connectionTimeZone=UTC"), "the UTC settings are still there");
	}
}
