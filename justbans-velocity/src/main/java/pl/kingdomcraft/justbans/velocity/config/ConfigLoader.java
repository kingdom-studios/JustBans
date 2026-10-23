package pl.kingdomcraft.justbans.velocity.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import pl.kingdomcraft.justbans.common.database.DatabaseConfig;
import pl.kingdomcraft.justbans.common.messaging.HmacSigner;
import pl.kingdomcraft.justbans.common.messaging.RedisConfig;
import pl.kingdomcraft.justbans.velocity.config.PluginConfig.FailurePolicy;

/** Reads and validates config.yml. */
public final class ConfigLoader {

	private static final String FILE = "config.yml";

	private ConfigLoader() {
	}

	public static PluginConfig load(Path file) throws ConfigException {
		final ConfigurationNode root;

		try {
			root = YamlConfigurationLoader.builder().path(file).build().load();
		} catch (final ConfigurateException ex) {
			throw new ConfigException(FILE + " is not valid YAML: " + ex.getMessage(), ex);
		}

		return parse(root);
	}

	public static PluginConfig parse(ConfigurationNode root) throws ConfigException {
		final Problems problems = new Problems();

		final String nodeId = problems.string(root.node("node-id"), "velocity-1");

		if (nodeId.isBlank() || nodeId.length() > 64)
			problems.add("node-id must be 1-64 characters");

		final String secret = problems.string(root.node("web-secret"), "");

		if (secret.length() < HmacSigner.MIN_SECRET_LENGTH)
			problems.add("web-secret must be at least " + HmacSigner.MIN_SECRET_LENGTH + " characters; set the same long random value on every JustBans node and in the web panel");

		// Database
		final ConfigurationNode db = root.node("database");
		final Map<String, String> jdbcProperties = new LinkedHashMap<>();

		for (final Map.Entry<Object, ? extends ConfigurationNode> entry : db.node("properties").childrenMap().entrySet())
			jdbcProperties.put(String.valueOf(entry.getKey()), problems.string(entry.getValue(), ""));

		final DatabaseConfig database = safeDatabase(problems, db, jdbcProperties);

		// Redis
		final ConfigurationNode redis = root.node("redis");
		final RedisConfig redisConfig = new RedisConfig(problems.string(redis.node("host"), "127.0.0.1"), problems.integer(redis.node("port"), 6379, 1, 65535),
				emptyToNull(problems.string(redis.node("user"), "")), emptyToNull(problems.string(redis.node("password"), "")), problems.bool(redis.node("ssl"), false),
				problems.integer(redis.node("database"), 0, 0, 255), problems.integer(redis.node("connect-timeout-ms"), 5000, 100, 120_000), problems.string(redis.node("prefix"), "justbans:"));

		// Behaviour
		final boolean webEnabled = problems.bool(root.node("web-commands", "enabled"), true);
		final int skew = problems.integer(root.node("web-commands", "max-skew-seconds"), 30, 1, 3600);

		final String policyText = problems.string(root.node("on-database-error"), "allow").trim().toUpperCase();
		FailurePolicy policy = FailurePolicy.ALLOW;

		try {
			policy = FailurePolicy.valueOf(policyText);
		} catch (final IllegalArgumentException ex) {
			problems.add("on-database-error must be 'allow' or 'deny', got '" + policyText.toLowerCase() + "'");
		}

		final Map<String, String> groups = new LinkedHashMap<>();

		for (final Map.Entry<Object, ? extends ConfigurationNode> entry : root.node("server-groups").childrenMap().entrySet()) {
			final String group = problems.string(entry.getValue(), "");

			if (!group.isBlank())
				groups.put(String.valueOf(entry.getKey()), group);
		}

		final List<String> fallback = problems.stringList(root.node("fallback-servers"));

		DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("UTC"));

		try {
			dateFormat = DateTimeFormatter.ofPattern(problems.string(root.node("date-format"), "dd.MM.yyyy HH:mm"))
					.withZone(ZoneId.of(problems.string(root.node("time-zone"), "UTC")));
		} catch (final IllegalArgumentException | DateTimeException ex) {
			problems.add("date-format / time-zone is invalid: " + ex.getMessage());
		}

		final PluginConfig.Messages messages = new PluginConfig.Messages(
				problems.string(root.node("messages", "database-error"), "<red>We could not verify your account right now. Please try again in a moment."),
				problems.string(root.node("messages", "server-banned"), "<red>You are banned from <white><server></white>."),
				problems.string(root.node("messages", "no-fallback"), "<red>You are banned from this server and there is no other server to send you to."));

		final boolean joinDenied = problems.bool(root.node("join-denied-events"), true);
		final int cacheSeconds = problems.integer(root.node("ban-cache-seconds"), 60, 1, 86_400);
		final int outboxInterval = problems.integer(root.node("outbox-interval-ms"), 2000, 100, 600_000);

		final boolean sweeperEnabled = problems.bool(root.node("expiry-sweeper", "enabled"), true);
		final int sweepSeconds = problems.integer(root.node("expiry-sweeper", "interval-seconds"), 60, 5, 86_400);
		final boolean metrics = problems.bool(root.node("metrics"), true);

		problems.throwIfAny(FILE);

		return new PluginConfig(nodeId, secret, database, redisConfig, webEnabled, Duration.ofSeconds(skew), policy, joinDenied, cacheSeconds, outboxInterval,
				sweeperEnabled, Duration.ofSeconds(sweepSeconds), metrics, Map.copyOf(groups), List.copyOf(fallback), dateFormat, messages);
	}

	private static DatabaseConfig safeDatabase(Problems problems, ConfigurationNode db, Map<String, String> properties) {
		final int before = problems.list().size();

		final String host = problems.string(db.node("host"), "127.0.0.1");
		final int port = problems.integer(db.node("port"), 3306, 1, 65535);
		final String name = problems.requiredString(db.node("name"));
		final String user = problems.requiredString(db.node("user"));
		final String password = problems.string(db.node("password"), "");
		final int pool = problems.integer(db.node("pool-size"), 8, 1, 100);
		final int timeout = problems.integer(db.node("connection-timeout-ms"), 10_000, 250, 600_000);
		final int maxLifetime = problems.integer(db.node("max-lifetime-ms"), (int) DatabaseConfig.DEFAULT_MAX_LIFETIME_MS, (int) DatabaseConfig.MIN_MAX_LIFETIME_MS, 86_400_000);
		final int keepalive = problems.integer(db.node("keepalive-ms"), (int) DatabaseConfig.DEFAULT_KEEPALIVE_MS, 0, 86_400_000);

		if (keepalive != 0 && (keepalive < DatabaseConfig.MIN_KEEPALIVE_MS || keepalive >= maxLifetime))
			problems.add("database.keepalive-ms must be 0 (off), or at least " + DatabaseConfig.MIN_KEEPALIVE_MS + " and less than database.max-lifetime-ms");

		if (problems.list().size() > before)
			return null; // errors are already recorded; the caller throws before using this

		return new DatabaseConfig(host, port, name, user, password, pool, timeout, maxLifetime, keepalive, properties);
	}

	private static String emptyToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	/**
	 * Copies a bundled resource into the data folder if the file does not exist yet, so admins get the commented default.
	 */
	public static void ensureDefault(Path dataDirectory, String name) throws IOException {
		Files.createDirectories(dataDirectory);

		final Path target = dataDirectory.resolve(name);

		if (Files.exists(target))
			return;

		try (var stream = ConfigLoader.class.getClassLoader().getResourceAsStream(name)) {
			if (stream == null)
				throw new IOException("Bundled default " + name + " is missing from the plugin jar");

			Files.copy(stream, target);
		}
	}
}
