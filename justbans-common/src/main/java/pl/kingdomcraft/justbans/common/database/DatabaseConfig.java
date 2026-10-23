package pl.kingdomcraft.justbans.common.database;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Connection settings, built by each platform from its own config format.
 *
 * @param maxLifetimeMs   a pooled connection is retired and replaced after this long, so none outlives the limits of the database
 *                        server or of any firewall in between
 * @param keepaliveMs     idle pooled connections are pinged this often, which stops a server or firewall from dropping them for
 *                        being idle; 0 disables it
 * @param properties extra JDBC URL parameters, e.g. {@code useSsl=true}
 */
public record DatabaseConfig(
		String host,
		int port,
		String database,
		String username,
		String password,
		int poolSize,
		long connectionTimeoutMs,
		long maxLifetimeMs,
		long keepaliveMs,
		Map<String, String> properties) {

	/** Pool defaults, chosen to stay well below the idle limits databases and firewalls commonly enforce. */
	public static final long DEFAULT_MAX_LIFETIME_MS = 10 * 60_000;
	public static final long DEFAULT_KEEPALIVE_MS = 60_000;

	/** HikariCP's own lower bounds for these two settings. */
	public static final long MIN_MAX_LIFETIME_MS = 30_000;
	public static final long MIN_KEEPALIVE_MS = 30_000;

	/** With the default pool lifetimes. */
	public DatabaseConfig(String host, int port, String database, String username, String password, int poolSize, long connectionTimeoutMs, Map<String, String> properties) {
		this(host, port, database, username, password, poolSize, connectionTimeoutMs, DEFAULT_MAX_LIFETIME_MS, DEFAULT_KEEPALIVE_MS, properties);
	}

	public DatabaseConfig {
		Objects.requireNonNull(host, "host");
		Objects.requireNonNull(database, "database");
		Objects.requireNonNull(username, "username");
		Objects.requireNonNull(password, "password");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid database port: " + port);
		if (poolSize < 1)
			throw new IllegalArgumentException("poolSize must be at least 1");

		if (maxLifetimeMs < MIN_MAX_LIFETIME_MS)
			throw new IllegalArgumentException("maxLifetimeMs must be at least " + MIN_MAX_LIFETIME_MS);
		if (keepaliveMs != 0 && (keepaliveMs < MIN_KEEPALIVE_MS || keepaliveMs >= maxLifetimeMs))
			throw new IllegalArgumentException("keepaliveMs must be 0 (off), or at least " + MIN_KEEPALIVE_MS + " and less than maxLifetimeMs");

		properties = Map.copyOf(properties);
	}

	public static DatabaseConfig of(String host, int port, String database, String username, String password) {
		return new DatabaseConfig(host, port, database, username, password, 8, 10_000, Map.of());
	}

	/** JDBC URL without credentials. */
	public String jdbcUrl() {
		final StringBuilder url = new StringBuilder("jdbc:mariadb://").append(host).append(':').append(port).append('/').append(database);
		char separator = '?';

		// Timestamps are always stored and read as UTC regardless of the JVM or server time zone.
		final Map<String, String> effective = new LinkedHashMap<>();
		effective.put("connectionTimeZone", "UTC");
		effective.put("forceConnectionTimeZoneToSession", "true");

		// Lets the operating system notice a connection the network silently dropped, instead of leaving it looking open.
		effective.put("tcpKeepAlive", "true");
		effective.putAll(properties);

		for (final Map.Entry<String, String> entry : effective.entrySet()) {
			url.append(separator).append(entry.getKey()).append('=').append(entry.getValue());
			separator = '&';
		}

		return url.toString();
	}
}
