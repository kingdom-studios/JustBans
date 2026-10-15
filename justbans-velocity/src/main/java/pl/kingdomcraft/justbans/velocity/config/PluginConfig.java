package pl.kingdomcraft.justbans.velocity.config;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import pl.kingdomcraft.justbans.common.database.DatabaseConfig;
import pl.kingdomcraft.justbans.common.messaging.RedisConfig;

/**
 * Parsed config.yml.
 *
 * @param nodeId              unique id of this node in Redis messages and jb_events (must differ from every Paper node)
 * @param serverGroups        server id to group name, for group bans; servers not listed have no group
 * @param fallbackServers     where players are sent when they are banned from their current or default server
 * @param banCacheSeconds     how long a player's bans stay cached without an event refreshing them
 * @param expirySweeperEnabled whether this node takes part in announcing expired punishments (one node at a time does the work)
 * @param expirySweepInterval  how often the sweeper elects a node and sweeps
 * @param metricsEnabled      whether anonymous bStats statistics are sent (bStats' own global opt-out still applies)
 */
public record PluginConfig(
		String nodeId,
		String webSecret,
		DatabaseConfig database,
		RedisConfig redis,
		boolean webCommandsEnabled,
		Duration webMaxSkew,
		FailurePolicy onDatabaseError,
		boolean joinDeniedEvents,
		int banCacheSeconds,
		long outboxIntervalMs,
		boolean expirySweeperEnabled,
		Duration expirySweepInterval,
		boolean metricsEnabled,
		Map<String, String> serverGroups,
		List<String> fallbackServers,
		DateTimeFormatter dateFormat,
		Messages messages) {

	/** What the login gate does when the database cannot be reached. */
	public enum FailurePolicy {
		ALLOW,
		DENY
	}

	/** MiniMessage strings for situations that are not tied to a punishment. */
	public record Messages(String databaseError, String serverBanned, String noFallback) {
	}

	public String groupOf(String serverId) {
		return this.serverGroups.get(serverId);
	}
}
