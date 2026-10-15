package pl.kingdomcraft.justbans.paper.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import pl.kingdomcraft.justbans.common.domain.Punishment;

/**
 * In-memory view of each online player's punishments, for things that must answer instantly on the main thread (PlaceholderAPI
 * placeholders, scoreboards, tab lists).
 *
 * <p>Reading never touches the database: {@link #peek} returns what is cached, or null while the first load is still running.
 * Loads happen on the executor you give it (the database executor). A cached entry is refreshed in the background once it is
 * older than {@code refreshAfter}, and immediately when the plugin hears of a change to that player's punishments
 * ({@link #refreshIfTracked}); readers keep seeing the previous snapshot until the new one lands. Whether a punishment is still
 * active is decided by the reader against the current time, so a countdown stays correct between loads.
 */
public final class PlayerSnapshots {

	/**
	 * @param bans  the player's bans that were active at load time
	 * @param warns the player's warns that were active at load time
	 * @param last  the most recent punishment of any kind or state, or null if the player has none
	 */
	public record Snapshot(List<Punishment> bans, List<Punishment> warns, Punishment last, Instant loadedAt) {

		public Snapshot {
			bans = List.copyOf(bans);
			warns = List.copyOf(warns);
		}
	}

	/** Reads one player's data from the database. Runs on the executor, never on the main thread. */
	@FunctionalInterface
	public interface Loader {
		Snapshot load(UUID player, Instant now) throws SQLException;
	}

	private final Loader loader;
	private final Executor executor;
	private final Clock clock;
	private final Duration refreshAfter;
	private final Logger logger;

	private final Cache<UUID, Snapshot> cache = Caffeine.newBuilder().maximumSize(5_000).expireAfterAccess(Duration.ofMinutes(10)).build();

	/** Players with a load running; the value is true if another refresh was requested meanwhile (so the load repeats). */
	private final ConcurrentHashMap<UUID, Boolean> loading = new ConcurrentHashMap<>();

	public PlayerSnapshots(Loader loader, Executor executor, Clock clock, Duration refreshAfter, Logger logger) {
		this.loader = loader;
		this.executor = executor;
		this.clock = clock;
		this.refreshAfter = refreshAfter;
		this.logger = logger;
	}

	/**
	 * The cached snapshot, or null if none has been loaded yet. Starts a background (re)load when there is none or it is
	 * older than the refresh interval and none is running. Never blocks.
	 */
	public Snapshot peek(UUID player) {
		final Snapshot snapshot = this.cache.getIfPresent(player);

		if ((snapshot == null || snapshot.loadedAt().plus(this.refreshAfter).isBefore(this.clock.instant())) && this.loading.putIfAbsent(player, Boolean.FALSE) == null)
			this.start(player); // a load already running will deliver fresh data, so asking again would only repeat it

		return snapshot;
	}

	/** Starts loading now, or, if a load is already running, makes it run again once it is done (its data may predate a change). */
	public void refresh(UUID player) {
		if (this.loading.putIfAbsent(player, Boolean.FALSE) != null) {
			this.loading.replace(player, Boolean.TRUE);
			return;
		}

		this.start(player);
	}

	/** Refreshes only players this server is showing data for, so network-wide events do not load strangers. */
	public void refreshIfTracked(UUID player) {
		if (this.cache.getIfPresent(player) != null || this.loading.containsKey(player))
			this.refresh(player);
	}

	public void evict(UUID player) {
		this.cache.invalidate(player);
	}

	public void clear() {
		this.cache.invalidateAll();
	}

	private void start(UUID player) {
		try {
			this.executor.execute(() -> this.run(player));

		} catch (final RuntimeException rejected) {
			this.loading.remove(player); // shutting down
		}
	}

	private void run(UUID player) {
		try {
			this.cache.put(player, this.loader.load(player, this.clock.instant()));

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.FINE, "Could not load punishments of " + player + ": " + ex.getMessage());

		} finally {
			final boolean[] again = { false };

			this.loading.compute(player, (key, dirty) -> {
				if (Boolean.TRUE.equals(dirty)) {
					again[0] = true;
					return Boolean.FALSE;
				}

				return null;
			});

			if (again[0])
				this.start(player);
		}
	}
}
