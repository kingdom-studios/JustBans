package pl.kingdomcraft.justbans.paper.diagnostics;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

import pl.kingdomcraft.justbans.paper.settings.Settings;

/**
 * Anonymous usage statistics. Only coarse feature flags are reported (no names, addresses, hosts or secrets), and the chart
 * callbacks read settings only: no database or Redis access.
 */
public final class PluginMetrics {

	/**
	 * The plugin's id on bStats.org. Register the plugin there, put the id here, and statistics start flowing; while it is not a
	 * positive number nothing is ever sent.
	 */
	static final int BSTATS_SERVICE_ID = 0;

	private PluginMetrics() {
	}

	/** @return the metrics handle to {@code shutdown()} on disable, or null if statistics are off */
	public static Metrics register(JavaPlugin plugin) {
		if (!Settings.Statistics.ENABLED || BSTATS_SERVICE_ID <= 0)
			return null;

		final Metrics metrics = new Metrics(plugin, BSTATS_SERVICE_ID);

		metrics.addCustomChart(new SimplePie("web_commands", () -> Settings.WebCommands.ENABLED ? "enabled" : "disabled"));
		metrics.addCustomChart(new SimplePie("expiry_sweeper", () -> Settings.ExpirySweeper.ENABLED ? "enabled" : "disabled"));
		metrics.addCustomChart(new SimplePie("local_gate", () -> Settings.Punishments.LOCAL_GATE ? "enabled" : "disabled"));
		metrics.addCustomChart(new SimplePie("server_group", () -> Settings.Server.groupOrNull() == null ? "none" : "set"));

		return metrics;
	}
}
