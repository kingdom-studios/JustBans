package pl.kingdomcraft.justbans.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;

class ConfigLoaderTest {

	static ConfigurationNode yaml(String text) throws Exception {
		return pl.kingdomcraft.justbans.velocity.TestYaml.parse(text);
	}

	private static final String MINIMAL = "web-secret: 0123456789abcdef-secret\ndatabase:\n  name: jb\n  user: jb\n";

	@Test
	void minimalConfigGetsSafeDefaults() throws Exception {
		final PluginConfig config = ConfigLoader.parse(yaml(MINIMAL));

		assertEquals("velocity-1", config.nodeId());
		assertEquals("127.0.0.1", config.database().host());
		assertEquals(3306, config.database().port());
		assertEquals("jb", config.database().database());
		assertEquals(6379, config.redis().port());
		assertNull(config.redis().password());
		assertEquals("justbans:", config.redis().prefix());
		assertTrue(config.webCommandsEnabled());
		assertTrue(config.expirySweeperEnabled());
		assertEquals(Duration.ofSeconds(60), config.expirySweepInterval());
		assertTrue(config.metricsEnabled());
		assertEquals(Duration.ofSeconds(30), config.webMaxSkew());
		assertEquals(PluginConfig.FailurePolicy.ALLOW, config.onDatabaseError());
		assertTrue(config.joinDeniedEvents());
		assertEquals(60, config.banCacheSeconds());
		assertTrue(config.serverGroups().isEmpty());
		assertTrue(config.fallbackServers().isEmpty());
		assertEquals("14.10.2026 12:00", config.dateFormat().format(Instant.parse("2026-10-14T12:00:00Z")));
	}

	@Test
	void readsEveryOption() throws Exception {
		final PluginConfig config = ConfigLoader.parse(yaml("""
				node-id: proxy-eu
				web-secret: "0123456789abcdef-secret"
				database: { host: db.local, port: 3307, name: jb, user: u, password: p, pool-size: 4, connection-timeout-ms: 5000, properties: { useSsl: "true" } }
				redis: { host: r.local, port: 6380, user: acl, password: rp, ssl: true, database: 2, prefix: "net1:", connect-timeout-ms: 2000 }
				web-commands: { enabled: false, max-skew-seconds: 10 }
				on-database-error: DENY
				join-denied-events: false
				ban-cache-seconds: 5
				outbox-interval-ms: 500
				expiry-sweeper: { enabled: false, interval-seconds: 15 }
				metrics: false
				server-groups: { skyblock-1: skyblock, skyblock-2: skyblock }
				fallback-servers: [lobby, hub]
				date-format: "yyyy-MM-dd HH:mm"
				time-zone: Europe/Warsaw
				messages: { database-error: "<red>db", server-banned: "<red>no <server>", no-fallback: "<red>none" }
				"""));

		assertEquals("proxy-eu", config.nodeId());
		assertEquals("db.local", config.database().host());
		assertEquals(3307, config.database().port());
		assertEquals(4, config.database().poolSize());
		assertTrue(config.database().jdbcUrl().contains("useSsl=true"));
		assertEquals("acl", config.redis().user());
		assertEquals("rp", config.redis().password());
		assertTrue(config.redis().ssl());
		assertEquals(2, config.redis().database());
		assertEquals("net1:", config.redis().prefix());
		assertFalse(config.webCommandsEnabled());
		assertEquals(Duration.ofSeconds(10), config.webMaxSkew());
		assertEquals(PluginConfig.FailurePolicy.DENY, config.onDatabaseError());
		assertFalse(config.joinDeniedEvents());
		assertEquals(500, config.outboxIntervalMs());
		assertFalse(config.expirySweeperEnabled());
		assertEquals(Duration.ofSeconds(15), config.expirySweepInterval());
		assertFalse(config.metricsEnabled());
		assertEquals("skyblock", config.groupOf("skyblock-2"));
		assertNull(config.groupOf("lobby"));
		assertEquals(java.util.List.of("lobby", "hub"), config.fallbackServers());
		assertEquals("2026-10-14 14:00", config.dateFormat().format(Instant.parse("2026-10-14T12:00:00Z")));
		assertEquals("<red>no <server>", config.messages().serverBanned());
	}

	@Test
	void reportsEveryProblemAtOnce() throws Exception {
		final ConfigException ex = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml("""
				web-secret: short
				database: { port: 99999 }
				redis: { port: abc }
				on-database-error: maybe
				ban-cache-seconds: 0
				date-format: "not a pattern ((("
				""")));

		final String all = String.join("\n", ex.problems());

		assertTrue(all.contains("web-secret"), all);
		assertTrue(all.contains("database.port"), all);
		assertTrue(all.contains("database.name"), all);
		assertTrue(all.contains("database.user"), all);
		assertTrue(all.contains("redis.port"), all);
		assertTrue(all.contains("on-database-error"), all);
		assertTrue(all.contains("ban-cache-seconds"), all);
		assertTrue(all.contains("date-format"), all);
		assertTrue(ex.problems().size() >= 7);
	}

	@Test
	void shippedDefaultConfigOnlyFailsOnTheMissingSecret() throws Exception {
		final ConfigException ex = assertThrows(ConfigException.class, () -> ConfigLoader.parse(yaml(new String(getClass().getClassLoader().getResourceAsStream("config.yml").readAllBytes()))));

		assertEquals(1, ex.problems().size(), ex.getMessage());
		assertTrue(ex.problems().get(0).contains("web-secret"));
	}

	@Test
	void shippedDefaultConfigWorksOnceTheSecretIsSet() throws Exception {
		final String text = new String(getClass().getClassLoader().getResourceAsStream("config.yml").readAllBytes()).replace("web-secret: \"\"", "web-secret: \"0123456789abcdef-secret\"");

		final PluginConfig config = ConfigLoader.parse(yaml(text));

		assertEquals(java.util.List.of("lobby"), config.fallbackServers());
		assertEquals("justbans", config.database().database());
	}

	@Test
	void ensureDefaultCopiesOnceAndNeverOverwrites(@TempDir Path dir) throws Exception {
		ConfigLoader.ensureDefault(dir, "config.yml");
		assertTrue(Files.readString(dir.resolve("config.yml")).contains("web-secret"));

		Files.writeString(dir.resolve("config.yml"), "edited");
		ConfigLoader.ensureDefault(dir, "config.yml");
		assertEquals("edited", Files.readString(dir.resolve("config.yml")));
	}

	@Test
	void invalidYamlIsAConfigException(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("config.yml"), "a: [unclosed");

		assertThrows(ConfigException.class, () -> ConfigLoader.load(dir.resolve("config.yml")));
	}
}
