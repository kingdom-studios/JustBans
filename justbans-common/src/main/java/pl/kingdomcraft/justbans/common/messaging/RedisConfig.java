package pl.kingdomcraft.justbans.common.messaging;

import java.util.Objects;

/**
 * @param user     ACL user name, or null for the default user
 * @param password password, or null/empty for none
 * @param prefix   channel prefix, see {@link Channels}
 */
public record RedisConfig(String host, int port, String user, String password, boolean ssl, int database, int connectTimeoutMs, String prefix) {

	public RedisConfig {
		Objects.requireNonNull(host, "host");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid Redis port: " + port);

		if (prefix == null || prefix.isBlank())
			prefix = Channels.DEFAULT_PREFIX;
	}

	public static RedisConfig of(String host, int port, String password) {
		return new RedisConfig(host, port, null, password, false, 0, 5_000, Channels.DEFAULT_PREFIX);
	}

	public RedisConfig withPrefix(String newPrefix) {
		return new RedisConfig(host, port, user, password, ssl, database, connectTimeoutMs, newPrefix);
	}
}
