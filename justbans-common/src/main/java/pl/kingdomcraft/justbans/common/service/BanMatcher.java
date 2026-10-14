package pl.kingdomcraft.justbans.common.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ScopeType;

/** Decides which bans apply to a connection attempt. Pure functions, shared by the Velocity gates and the Paper fallback gate. */
public final class BanMatcher {

	/** Permanent bans outrank temporary ones; among temporary ones the longest wins, so the screen shows the real end. */
	private static final Comparator<Punishment> LONGEST_LASTING = Comparator.comparing(
			(Punishment p) -> p.expiresAt() == null ? Instant.MAX : p.expiresAt());

	private BanMatcher() {
	}

	/** An active network-wide ban: the reason a player may not join the proxy at all. */
	public static Optional<Punishment> networkBan(Collection<Punishment> bans, Instant now) {
		return bans.stream()
				.filter(p -> p.type() == PunishmentType.BAN && p.scope().type() == ScopeType.NETWORK && p.isActive(now))
				.max(LONGEST_LASTING);
	}

	/**
	 * An active ban that stops the player entering {@code serverId}. Includes network bans as a safety net for a
	 * player who is already online when the ban event was missed.
	 *
	 * @param serverGroup group of that server, or null
	 */
	public static Optional<Punishment> banFor(Collection<Punishment> bans, String serverId, String serverGroup, Instant now) {
		return bans.stream()
				.filter(p -> p.type() == PunishmentType.BAN && p.isActive(now) && p.scope().covers(serverId, serverGroup))
				.max(LONGEST_LASTING);
	}

	public static boolean isNetworkWide(Punishment ban) {
		return ban.scope().type() == ScopeType.NETWORK;
	}
}
