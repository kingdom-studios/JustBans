package pl.kingdomcraft.justbans.velocity.gate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.velocity.config.PluginConfig;
import pl.kingdomcraft.justbans.velocity.enforce.ScreenRenderer;

/**
 * The three places a ban is enforced as a player moves through the proxy:
 * <ol>
 * <li>{@link LoginEvent}: network bans keep the player out entirely;</li>
 * <li>{@link PlayerChooseInitialServerEvent}: a banned default server is swapped for an allowed one;</li>
 * <li>{@link ServerPreConnectEvent}: server and group bans stop (or redirect) every later connection.</li>
 * </ol>
 * All handlers run asynchronously because they may query the database on a cache miss.
 */
public final class ConnectionGate {

	private final PluginConfig config;
	private final ActiveBanCache cache;
	private final ScreenRenderer screens;
	private final ServerDirectory servers;
	private final Clock clock;
	private final Logger logger;
	private final Consumer<Punishment> onJoinDenied;

	/**
	 * @param onJoinDenied called (at most for the first denial per player in a while, the callee decides) when a banned player is turned away
	 */
	public ConnectionGate(PluginConfig config, ActiveBanCache cache, ScreenRenderer screens, ServerDirectory servers, Clock clock, Logger logger, Consumer<Punishment> onJoinDenied) {
		this.config = config;
		this.cache = cache;
		this.screens = screens;
		this.servers = servers;
		this.clock = clock;
		this.logger = logger;
		this.onJoinDenied = onJoinDenied;
	}

	@Subscribe(order = PostOrder.EARLY)
	public EventTask onLogin(LoginEvent event) {
		return EventTask.async(() -> {
			if (!event.getResult().isAllowed())
				return; // another plugin already refused this login

			final Player player = event.getPlayer();

			try {
				this.cache.invalidate(player.getUniqueId()); // always judge a new session on fresh data
				final Optional<Punishment> ban = BanMatcher.networkBan(this.cache.get(player.getUniqueId()), this.clock.instant());

				if (ban.isPresent()) {
					event.setResult(ResultedEvent.ComponentResult.denied(this.screens.banScreen(ban.get(), null)));
					this.onJoinDenied.accept(ban.get());
				}

			} catch (final SQLException | RuntimeException ex) {
				this.logger.log(Level.SEVERE, "Could not check bans for " + player.getUsername() + " (" + player.getUniqueId() + "): " + ex.getMessage(), ex);

				if (this.config.onDatabaseError() == PluginConfig.FailurePolicy.DENY)
					event.setResult(ResultedEvent.ComponentResult.denied(this.screens.databaseError()));
			}
		});
	}

	@Subscribe(order = PostOrder.EARLY)
	public EventTask onChooseInitialServer(PlayerChooseInitialServerEvent event) {
		return EventTask.async(() -> {
			final Optional<RegisteredServer> initial = event.getInitialServer();

			if (initial.isEmpty())
				return;

			final Player player = event.getPlayer();
			final String name = ServerDirectory.nameOf(initial.get());
			final List<Punishment> bans = this.bansOrEmpty(player.getUniqueId());
			final Instant now = this.clock.instant();
			final Optional<Punishment> ban = BanMatcher.banFor(bans, name, this.servers.groupOf(name), now);

			if (ban.isEmpty())
				return;

			if (BanMatcher.isNetworkWide(ban.get())) {
				player.disconnect(this.screens.banScreen(ban.get(), name));
				return;
			}

			final Optional<RegisteredServer> alternative = this.servers.alternative(bans, Set.of(name), now);

			if (alternative.isPresent())
				event.setInitialServer(alternative.get());
			else
				player.disconnect(this.screens.serverBanMessage(ban.get()).append(net.kyori.adventure.text.Component.newline()).append(this.screens.noFallback(name)));
		});
	}

	@Subscribe(order = PostOrder.EARLY)
	public EventTask onServerPreConnect(ServerPreConnectEvent event) {
		return EventTask.async(() -> {
			final Optional<RegisteredServer> target = event.getResult().getServer();

			if (target.isEmpty())
				return;

			final Player player = event.getPlayer();
			final String name = ServerDirectory.nameOf(target.get());
			final List<Punishment> bans = this.bansOrEmpty(player.getUniqueId());
			final Instant now = this.clock.instant();
			final Optional<Punishment> ban = BanMatcher.banFor(bans, name, this.servers.groupOf(name), now);

			if (ban.isEmpty())
				return;

			event.setResult(ServerPreConnectEvent.ServerResult.denied());

			// A network ban reaching this point means the ban event was missed; enforce it now.
			if (BanMatcher.isNetworkWide(ban.get())) {
				player.disconnect(this.screens.banScreen(ban.get(), name));
				return;
			}

			if (player.getCurrentServer().isPresent()) {
				player.sendMessage(this.screens.serverBanMessage(ban.get()));
				return;
			}

			// Still connecting for the first time: send them somewhere they are allowed instead of leaving them stranded.
			final Optional<RegisteredServer> alternative = this.servers.alternative(bans, Set.of(name), now);

			if (alternative.isPresent())
				event.setResult(ServerPreConnectEvent.ServerResult.allowed(alternative.get()));
			else
				player.disconnect(this.screens.serverBanMessage(ban.get()).append(net.kyori.adventure.text.Component.newline()).append(this.screens.noFallback(name)));
		});
	}

	/** On a database failure server gates fail open: the login gate is the strict one and has its own policy. */
	private List<Punishment> bansOrEmpty(UUID player) {
		try {
			return this.cache.get(player);

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not load bans for " + player + ", allowing the connection: " + ex.getMessage());
			return List.of();
		}
	}
}
