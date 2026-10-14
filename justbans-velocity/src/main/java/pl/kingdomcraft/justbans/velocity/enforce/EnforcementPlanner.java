package pl.kingdomcraft.justbans.velocity.enforce;

import java.util.function.Function;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;

/**
 * Decides what a freshly created punishment means for its target and for everyone else, without touching Velocity.
 */
public final class EnforcementPlanner {

	/** What happens to the target if they are online. */
	public enum TargetAction {
		/** Not online, or the punishment does not affect where they are. */
		NONE,
		/** Disconnect with the ban screen (network ban). */
		DISCONNECT_BAN,
		/** Move to another server (server or group ban covering the server they are on). */
		REDIRECT,
		DISCONNECT_KICK,
		/** Tell them in chat (warn). */
		WARN_MESSAGE
	}

	/**
	 * @param broadcast  tell every player (not for silent punishments)
	 * @param staffAlert tell everyone with the notify permission, silent or not
	 */
	public record Plan(TargetAction target, boolean broadcast, boolean staffAlert) {
	}

	private EnforcementPlanner() {
	}

	/**
	 * @param currentServer the server the target is on, or null if they are not online
	 * @param online        whether the target is connected to the proxy at all
	 * @param groupOf       group of a server, or null
	 */
	public static Plan plan(Punishment p, boolean online, String currentServer, Function<String, String> groupOf) {
		return new Plan(targetAction(p, online, currentServer, groupOf), !p.silent(), true);
	}

	private static TargetAction targetAction(Punishment p, boolean online, String currentServer, Function<String, String> groupOf) {
		if (!online)
			return TargetAction.NONE;

		if (p.type() == PunishmentType.KICK)
			return TargetAction.DISCONNECT_KICK;

		if (p.type() == PunishmentType.WARN)
			return TargetAction.WARN_MESSAGE;

		// BAN
		switch (p.scope().type()) {
			case NETWORK:
				return TargetAction.DISCONNECT_BAN;

			default:
				return currentServer != null && p.scope().covers(currentServer, groupOf.apply(currentServer)) ? TargetAction.REDIRECT : TargetAction.NONE;
		}
	}
}
