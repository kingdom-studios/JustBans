package pl.kingdomcraft.justbans.velocity.track;

import java.time.Clock;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.repository.PlayerRepository;
import pl.kingdomcraft.justbans.velocity.gate.ActiveBanCache;
import pl.kingdomcraft.justbans.velocity.gate.ServerDirectory;

/**
 * Keeps {@code jb_players} / {@code jb_player_names} and the Redis "online" hash current. This is what makes offline
 * punishments possible: every player who has ever connected can later be found by name or UUID.
 *
 * <p>Database and Redis work runs off the event thread and never affects the player; failures are only logged.
 */
public final class PlayerTracker {

	private final Database database;
	private final OnlineDirectory directory;
	private final ActiveBanCache bans;
	private final Predicate<UUID> isOnline;
	private final Clock clock;
	private final Logger logger;
	private final PlayerRepository players = new PlayerRepository();

	public PlayerTracker(Database database, OnlineDirectory directory, ActiveBanCache bans, Predicate<UUID> isOnline, Clock clock, Logger logger) {
		this.database = database;
		this.directory = directory;
		this.bans = bans;
		this.isOnline = isOnline;
		this.clock = clock;
		this.logger = logger;
	}

	@Subscribe(order = PostOrder.LATE)
	public void onPostLogin(PostLoginEvent event) {
		this.record(event.getPlayer(), null, false);
	}

	@Subscribe(order = PostOrder.LATE)
	public void onServerPostConnect(ServerPostConnectEvent event) {
		final Player player = event.getPlayer();

		this.record(player, ServerDirectory.currentServerOf(player), false);
	}

	@Subscribe(order = PostOrder.LATE)
	public void onDisconnect(DisconnectEvent event) {
		final Player player = event.getPlayer();

		this.bans.invalidate(player.getUniqueId());
		this.record(player, ServerDirectory.currentServerOf(player), true);
	}

	private void record(Player player, String server, boolean disconnected) {
		final UUID uuid = player.getUniqueId();
		final String name = player.getUsername();

		this.database.supplyAsync(() -> {
			this.database.query(connection -> {
				this.players.touch(connection, uuid, name, this.clock.instant(), server);
				return null;
			});

			if (!disconnected)
				this.directory.set(uuid, name, server);
			else if (!this.isOnline.test(uuid)) // a quick re-login may already have registered the player again
				this.directory.remove(uuid);

			return null;

		}).exceptionally(ex -> {
			this.logger.log(Level.WARNING, "Could not record " + name + ": " + ex.getMessage());
			return null;
		});
	}
}
