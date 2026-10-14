package pl.kingdomcraft.justbans.velocity;

import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import pl.kingdomcraft.justbans.velocity.config.ConfigException;

@Plugin(id = "justbans", name = "JustBans", version = "1.0.0", description = "Network-wide punishments", authors = { "cammaj" })
public final class JustBansVelocity {

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;

	private volatile VelocityRuntime runtime;

	@Inject
	public JustBansVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		try {
			this.runtime = VelocityRuntime.start(this, this.proxy, this.logger, this.dataDirectory);

		} catch (final ConfigException ex) {
			this.logger.error("JustBans is NOT running: fix the configuration and restart the proxy. Bans are not being enforced.\n{}", ex.getMessage());

		} catch (final Exception ex) {
			this.logger.error("JustBans is NOT running because it failed to start. Bans are not being enforced.", ex);
		}
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		final VelocityRuntime current = this.runtime;

		if (current != null)
			current.close();
	}
}
