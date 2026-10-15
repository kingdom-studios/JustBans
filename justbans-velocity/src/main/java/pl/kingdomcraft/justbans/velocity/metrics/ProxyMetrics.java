package pl.kingdomcraft.justbans.velocity.metrics;

import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bstats.velocity.Metrics;

import pl.kingdomcraft.justbans.velocity.VelocityRuntime;
import pl.kingdomcraft.justbans.velocity.config.PluginConfig;

/**
 * Anonymous usage statistics. Only coarse feature flags and counts are reported (no names, addresses, hosts or secrets), and
 * none of the chart callbacks touch the database or Redis.
 */
public final class ProxyMetrics {

	private ProxyMetrics() {
	}

	public static Metrics register(Metrics.Factory factory, Object plugin, int serviceId, VelocityRuntime runtime) {
		final Metrics metrics = factory.make(plugin, serviceId);
		final PluginConfig config = runtime.config();

		metrics.addCustomChart(new SimplePie("web_commands", () -> config.webCommandsEnabled() ? "enabled" : "disabled"));
		metrics.addCustomChart(new SimplePie("expiry_sweeper", () -> config.expirySweeperEnabled() ? "enabled" : "disabled"));
		metrics.addCustomChart(new SimplePie("on_database_error", () -> config.onDatabaseError().name().toLowerCase()));
		metrics.addCustomChart(new SingleLineChart("configured_reasons", runtime::reasonCount));

		return metrics;
	}
}
