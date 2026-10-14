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

@Plugin(id = "justbans", name = "JustBans", version = "1.0.0", authors = { "cammaj" })
public final class JustBansVelocity {

	private final ProxyServer proxy;
	private final Logger logger;
	private final Path dataDirectory;

	@Inject
	public JustBansVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
		this.proxy = proxy;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		this.logger.info("JustBans starting (data folder: {})", this.dataDirectory);
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
	}

	public ProxyServer proxy() {
		return this.proxy;
	}
}
