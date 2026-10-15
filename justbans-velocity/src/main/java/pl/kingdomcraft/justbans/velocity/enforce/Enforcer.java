package pl.kingdomcraft.justbans.velocity.enforce;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.json.PunishmentJson;
import pl.kingdomcraft.justbans.common.messaging.Envelope;
import pl.kingdomcraft.justbans.common.repository.PunishmentRepository;
import pl.kingdomcraft.justbans.velocity.enforce.EnforcementPlanner.Plan;
import pl.kingdomcraft.justbans.velocity.gate.ActiveBanCache;
import pl.kingdomcraft.justbans.velocity.gate.ServerDirectory;

/**
 * Reacts to punishment events from Redis: removes or moves the target if they are online, tells chat, and alerts
 * staff. Enforcement is idempotent, so receiving an event twice or from several proxies is harmless.
 *
 * <p>Events are a prompt, not the authority: if one is lost (Redis down) the gates still catch the player the next time
 * they log in or switch servers.
 */
public final class Enforcer {

	/** Permission checked on Velocity for staff alerts. */
	public static final String NOTIFY_PERMISSION = "justbans.notify";

	private final ProxyServer proxy;
	private final ActiveBanCache cache;
	private final ScreenRenderer screens;
	private final ServerDirectory servers;
	private final Database database;
	private final Clock clock;
	private final Logger logger;
	private final PunishmentRepository punishments = new PunishmentRepository();

	public Enforcer(ProxyServer proxy, ActiveBanCache cache, ScreenRenderer screens, ServerDirectory servers, Database database, Clock clock, Logger logger) {
		this.proxy = proxy;
		this.cache = cache;
		this.screens = screens;
		this.servers = servers;
		this.database = database;
		this.clock = clock;
		this.logger = logger;
	}

	/** Entry point for verified messages on the events channel. */
	public void handle(Envelope event) {
		try {
			switch (event.type()) {
				case "PUNISHMENT_CREATED" -> this.created(PunishmentJson.fromEventPayload(event.payload()));
				// Revoked, edited and expired punishments change what the gates must decide, so drop the cached bans.
				case "PUNISHMENT_REVOKED", "PUNISHMENT_UPDATED", "PUNISHMENT_EXPIRED" -> this.cache.invalidate(PunishmentJson.fromEventPayload(event.payload()).targetUuid());
				default -> {
					// REASONS_UPDATED, JOIN_DENIED, ... are not our concern
				}
			}

		} catch (final IllegalArgumentException ex) {
			this.logger.warning("Ignoring unreadable " + event.type() + " event #" + event.seq() + ": " + ex.getMessage());
		}
	}

	private void created(Punishment p) {
		this.cache.invalidate(p.targetUuid()); // the next gate decision must see this punishment

		final Optional<Player> target = this.proxy.getPlayer(p.targetUuid());
		final String server = target.map(ServerDirectory::currentServerOf).orElse(null);
		final Plan plan = EnforcementPlanner.plan(p, target.isPresent(), server, this.servers::groupOf);

		if (target.isPresent())
			this.applyToTarget(plan.target(), target.get(), p, server);

		if (plan.broadcast()) {
			final Component message = this.screens.broadcast(p);

			for (final Player player : this.proxy.getAllPlayers())
				player.sendMessage(message);
		}

		if (plan.staffAlert()) {
			final Component alert = this.screens.staffAlert(p);

			for (final Player player : this.proxy.getAllPlayers())
				if (player.hasPermission(NOTIFY_PERMISSION))
					player.sendMessage(alert);

			this.proxy.getConsoleCommandSource().sendMessage(alert);
		}
	}

	private void applyToTarget(EnforcementPlanner.TargetAction action, Player player, Punishment p, String server) {
		switch (action) {
			case DISCONNECT_BAN -> player.disconnect(this.screens.banScreen(p, server));
			case DISCONNECT_KICK -> player.disconnect(this.screens.kickScreen(p, server));
			case WARN_MESSAGE -> player.sendMessage(this.screens.warnMessage(p, this.activeWarns(p)));
			case REDIRECT -> this.redirect(player, p, server);
			case NONE -> {
				// nothing to do for the target
			}
		}
	}

	private void redirect(Player player, Punishment p, String bannedServer) {
		final List<Punishment> bans = this.bansIncluding(p);
		final Optional<RegisteredServer> alternative = this.servers.alternative(bans, Set.of(bannedServer), this.clock.instant());

		if (alternative.isEmpty()) {
			player.disconnect(this.screens.serverBanMessage(p).append(Component.newline()).append(this.screens.noFallback(bannedServer)));
			return;
		}

		player.createConnectionRequest(alternative.get()).connect().thenAccept(result -> {
			if (result.isSuccessful())
				player.sendMessage(this.screens.serverBanMessage(p));
			else
				player.disconnect(this.screens.serverBanMessage(p));
		});
	}

	/** All of the player's active bans, guaranteed to include the new one even if the database read fails. */
	private List<Punishment> bansIncluding(Punishment p) {
		try {
			final List<Punishment> bans = this.cache.get(p.targetUuid());

			return bans.stream().anyMatch(existing -> existing.id() == p.id()) ? bans : java.util.stream.Stream.concat(bans.stream(), java.util.stream.Stream.of(p)).toList();

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not load bans to choose a redirect target: " + ex.getMessage());
			return List.of(p);
		}
	}

	private int activeWarns(Punishment p) {
		try {
			return this.database.query(connection -> this.punishments.findActiveWarns(connection, p.targetUuid(), this.clock.instant())).size();

		} catch (final SQLException | RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not count warns for " + p.targetName() + ": " + ex.getMessage());
			return 0;
		}
	}
}
