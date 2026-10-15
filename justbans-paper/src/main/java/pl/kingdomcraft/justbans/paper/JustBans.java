package pl.kingdomcraft.justbans.paper;

import org.bstats.bukkit.Metrics;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.justbans.paper.diagnostics.PluginMetrics;

/**
 * Paper side of JustBans: staff commands, tab completion and a fallback login gate, built on Foundation. Settings,
 * localization, commands and listeners are discovered and registered by Foundation (see {@code Settings},
 * {@code Localization} and the {@code @AutoRegister} classes); this class only manages the database/Redis lifecycle.
 *
 * <p>Foundation registers commands before {@link #onPluginStart()} runs, so commands never hold services; they ask for
 * {@link #core()} when executed.
 */
public final class JustBans extends SimplePlugin {

	/** Serializes starting and stopping, so a reload or shutdown during a slow start cannot leak connections. */
	private static final Object LIFECYCLE = new Object();

	private static volatile Core core;

	private Metrics metrics;

	@Override
	protected void onPluginStart() {
		this.metrics = PluginMetrics.register(this);
		this.startCoreAsync();
	}

	/** Settings have just been re-read when this runs, so the new core picks them up. */
	@Override
	protected void onPluginReload() {
		this.startCoreAsync();
	}

	@Override
	protected void onPluginPreReload() {
		stopCore();
	}

	@Override
	protected void onPluginStop() {
		stopCore();

		if (this.metrics != null)
			this.metrics.shutdown();
	}

	/**
	 * Connecting to MariaDB and Redis can take seconds, so it never happens on the main thread. Until it finishes
	 * {@link #core()} is null and commands tell staff to wait.
	 */
	private void startCoreAsync() {
		Common.runAsync(() -> {
			synchronized (LIFECYCLE) {
				if (!this.isEnabled() || core != null)
					return;

				try {
					core = Core.start(this);
					Common.log("Ready: node '" + core.nodeId() + "', " + core.reasons().size() + " reasons loaded");

				} catch (final IllegalArgumentException ex) {
					Common.log("&cJustBans is NOT running: " + ex.getMessage());

				} catch (final Throwable t) {
					Common.error(t, "JustBans could not start. Punishment commands are disabled until this is fixed and the plugin is reloaded.");
				}
			}
		});
	}

	private static void stopCore() {
		synchronized (LIFECYCLE) {
			final Core current = core;
			core = null;

			if (current != null)
				current.close();
		}
	}

	/**
	 * The running core, or null while starting, after a failed start, or while reloading.
	 */
	public static Core core() {
		return core;
	}

	public static JustBans getInstance() {
		return (JustBans) SimplePlugin.getInstance();
	}
}
