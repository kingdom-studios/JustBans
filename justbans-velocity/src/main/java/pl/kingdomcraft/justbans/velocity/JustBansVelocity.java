package pl.kingdomcraft.justbans.velocity;

import java.nio.file.Path;

import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import pl.kingdomcraft.justbans.velocity.config.ConfigException;
import pl.kingdomcraft.justbans.velocity.metrics.ProxyMetrics;

@Plugin(id = "justbans", name = "JustBans", version = "1.0.0", description = "Network-wide punishments", authors = { "cammaj" })
public final class JustBansVelocity {

	/**
	 * The plugin's id on bStats.org. Register the plugin there, put the id here, and statistics start flowing; while it is
	 * not a positive number nothing is ever sent.
	 */
	static final int BSTATS_SERVICE_ID = 0;

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;
	private final Metrics.Factory metricsFactory;

	private volatile VelocityRuntime runtime;
	private volatile Metrics metrics;

	@Inject
	public JustBansVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
		this.metricsFactory = metricsFactory;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		try {
			this.runtime = VelocityRuntime.start(this, this.proxy, this.logger, this.dataDirectory);

			if (this.runtime.config().metricsEnabled() && BSTATS_SERVICE_ID > 0)
				this.metrics = ProxyMetrics.register(this.metricsFactory, this, BSTATS_SERVICE_ID, this.runtime);

		} catch (final ConfigException ex) {
			this.logger.error("JustBans is NOT running: fix the configuration and restart the proxy. Bans are not being enforced.\n{}", ex.getMessage());

		} catch (final Exception ex) {
			this.logger.error("JustBans is NOT running because it failed to start. Bans are not being enforced.", ex);
		}
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		final Metrics currentMetrics = this.metrics;

		if (currentMetrics != null)
			currentMetrics.shutdown();

		final VelocityRuntime current = this.runtime;

		if (current != null)
			current.close();
	}
}
