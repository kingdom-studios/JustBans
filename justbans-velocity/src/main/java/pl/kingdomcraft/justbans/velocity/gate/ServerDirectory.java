package pl.kingdomcraft.justbans.velocity.gate;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.velocity.config.PluginConfig;

/** Bridges server names (used by the pure logic) and Velocity's {@link RegisteredServer}s. */
public final class ServerDirectory {

	private final ProxyServer proxy;
	private final PluginConfig config;

	public ServerDirectory(ProxyServer proxy, PluginConfig config) {
		this.proxy = proxy;
		this.config = config;
	}

	public static String nameOf(RegisteredServer server) {
		return server.getServerInfo().getName();
	}

	/** The server the player is on right now, or null while connecting. */
	public static String currentServerOf(Player player) {
		return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
	}

	public String groupOf(String serverName) {
		return this.config.groupOf(serverName);
	}

	/**
	 * Somewhere the player may go instead: configured fallbacks first, then any registered server they are not banned from.
	 *
	 * @param excluded server names that must not be chosen
	 */
	public Optional<RegisteredServer> alternative(Collection<Punishment> bans, Set<String> excluded, Instant now) {
		final List<String> allNames = this.proxy.getAllServers().stream().map(ServerDirectory::nameOf).toList();

		return ServerSelector.firstAllowed(this.config.fallbackServers(), allNames, excluded, bans, this::groupOf, now).flatMap(this.proxy::getServer);
	}
}
