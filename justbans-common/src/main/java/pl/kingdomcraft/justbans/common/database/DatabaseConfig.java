package pl.kingdomcraft.justbans.common.database;

import java.util.Map;
import java.util.Objects;

/**
 * Connection settings, built by each platform from its own config format.
 *
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
		Map<String, String> properties) {

	public DatabaseConfig {
		Objects.requireNonNull(host, "host");
		Objects.requireNonNull(database, "database");
		Objects.requireNonNull(username, "username");
		Objects.requireNonNull(password, "password");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid database port: " + port);
		if (poolSize < 1)
			throw new IllegalArgumentException("poolSize must be at least 1");

		properties = Map.copyOf(properties);
	}

	public static DatabaseConfig of(String host, int port, String database, String username, String password) {
		return new DatabaseConfig(host, port, database, username, password, 8, 10_000, Map.of());
	}

	/** JDBC URL without credentials. */
	public String jdbcUrl() {
		final StringBuilder url = new StringBuilder("jdbc:mariadb://").append(host).append(':').append(port).append('/').append(database);
		char separator = '?';

		for (final Map.Entry<String, String> entry : properties.entrySet()) {
			url.append(separator).append(entry.getKey()).append('=').append(entry.getValue());
			separator = '&';
		}

		return url.toString();
	}
}
