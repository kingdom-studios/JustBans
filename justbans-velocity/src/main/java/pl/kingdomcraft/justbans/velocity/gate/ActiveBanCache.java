package pl.kingdomcraft.justbans.velocity.gate;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import pl.kingdomcraft.justbans.common.domain.Punishment;

/**
 * Short-lived per-player cache of active bans, so server switches need no database call. It is filled at login,
 * emptied for a player when an event says their punishments changed and when they disconnect, and also expires on
 * its own as a safety net in case an event was missed while Redis was down.
 */
public final class ActiveBanCache {

	/** Where bans come from on a cache miss (the database). */
	@FunctionalInterface
	public interface Source {
		List<Punishment> load(UUID player) throws SQLException;
	}

	private final Cache<UUID, List<Punishment>> cache;
	private final Source source;

	public ActiveBanCache(Source source, Duration ttl) {
		this.source = source;
		this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(50_000).build();
	}

	/**
	 * The player's bans, from the cache or else loaded and cached.
	 *
	 * @throws SQLException if the source fails; nothing is cached in that case
	 */
	public List<Punishment> get(UUID player) throws SQLException {
		final List<Punishment> cached = this.cache.getIfPresent(player);

		if (cached != null)
			return cached;

		final List<Punishment> loaded = List.copyOf(this.source.load(player));
		this.cache.put(player, loaded);

		return loaded;
	}

	/** Forces the next {@link #get} to reload from the source. */
	public void invalidate(UUID player) {
		this.cache.invalidate(player);
	}

	public void invalidateAll() {
		this.cache.invalidateAll();
	}

	public List<Punishment> getIfPresent(UUID player) {
		return this.cache.getIfPresent(player);
	}
}
