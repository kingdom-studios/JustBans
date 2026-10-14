package pl.kingdomcraft.justbans.paper.service;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.ActorType;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.service.ExemptionChecker;

/**
 * Decides who cannot be punished.
 * <ol>
 * <li>A player online on this server is checked with Bukkit's {@code hasPermission("justbans.exempt")}, on the main thread.</li>
 * <li>Anyone else (offline, or on another server) is checked through LuckPerms, which loads their data on demand, so a
 * permission or group inherited from a group still counts.</li>
 * <li>Optionally, a player whose highest group weight is greater than zero and not lower than the actor's is protected
 * from that actor ({@code Punishments.Use_Weights}).</li>
 * </ol>
 * Without LuckPerms only step 1 exists, so offline players can only be exempted while they are online here.
 */
public final class PaperExemptions implements ExemptionChecker {

	public static final String EXEMPT_PERMISSION = "justbans.exempt";

	private static final long MAIN_THREAD_TIMEOUT_MILLIS = 2_000;

	private final Plugin plugin;
	private final LuckPermsBridge luckPerms;
	private final boolean useWeights;
	private final Logger logger;

	/**
	 * @param luckPerms the bridge, or null when LuckPerms is not installed
	 */
	PaperExemptions(Plugin plugin, LuckPermsBridge luckPerms, boolean useWeights, Logger logger) {
		this.plugin = plugin;
		this.luckPerms = luckPerms;
		this.useWeights = useWeights;
		this.logger = logger;
	}

	/** Builds the checker, enabling LuckPerms support only if that plugin is enabled. */
	public static PaperExemptions create(Plugin plugin, boolean useWeights, Logger logger) {
		LuckPermsBridge bridge = null;

		if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))
			try {
				bridge = new LuckPermsBridge();
				logger.info("LuckPerms found: offline exemption checks are enabled" + (useWeights ? " (including group weights)" : ""));

			} catch (final Throwable ex) {
				logger.log(Level.WARNING, "LuckPerms is installed but its API is unavailable; offline exemption checks are disabled: " + ex.getMessage());
			}
		else if (useWeights)
			logger.warning("Punishments.Use_Weights is on but LuckPerms is not installed; weights are ignored");

		return new PaperExemptions(plugin, bridge, useWeights, logger);
	}

	@Override
	public boolean isExempt(UUID target, PunishmentType type) {
		return this.isExempt(Actor.console(), target, type);
	}

	@Override
	public boolean isExempt(Actor actor, UUID target, PunishmentType type) {
		if (Boolean.TRUE.equals(this.onlineHere(target)))
			return true;

		if (this.luckPerms == null)
			return false;

		try {
			if (this.luckPerms.hasPermission(target, EXEMPT_PERMISSION))
				return true;

			if (this.useWeights && actor.type() == ActorType.PLAYER && actor.uuid() != null) {
				final int targetWeight = this.luckPerms.weight(target);

				return targetWeight > 0 && targetWeight >= this.luckPerms.weight(actor.uuid());
			}

		} catch (final RuntimeException ex) {
			this.logger.log(Level.WARNING, "Could not check LuckPerms data for " + target + ", treating them as not exempt: " + ex.getMessage());
		}

		return false;
	}

	/**
	 * @return whether the player is exempt according to Bukkit, or null if they are not online on this server
	 */
	private Boolean onlineHere(UUID target) {
		final Callable<Boolean> check = () -> {
			final Player player = Bukkit.getPlayer(target);

			return player == null ? null : player.hasPermission(EXEMPT_PERMISSION);
		};

		try {
			// Bukkit permission state is only safe to read on the main thread; we are on a database thread.
			return Bukkit.isPrimaryThread() ? check.call() : Bukkit.getScheduler().callSyncMethod(this.plugin, check).get(MAIN_THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;

		} catch (final Exception ex) {
			this.logger.log(Level.WARNING, "Could not read the online permission state of " + target + ": " + ex.getMessage());
			return null;
		}
	}
}
