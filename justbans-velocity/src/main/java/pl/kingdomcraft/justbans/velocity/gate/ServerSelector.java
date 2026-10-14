package pl.kingdomcraft.justbans.velocity.gate;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.service.BanMatcher;

/** Picks a server a banned player may be sent to. Works on names only so it needs no Velocity types. */
public final class ServerSelector {

	private ServerSelector() {
	}

	/**
	 * First candidate the player is not banned from. Preferred servers are tried first, in order, then every other
	 * known server.
	 *
	 * @param preferred   configured fallback servers
	 * @param allServers  every registered server name
	 * @param excluded    servers that must not be chosen (e.g. the one being left)
	 * @param groupOf     group of a server, or null
	 */
	public static Optional<String> firstAllowed(List<String> preferred, Collection<String> allServers, Set<String> excluded, Collection<Punishment> bans,
			Function<String, String> groupOf, Instant now) {
		final Set<String> order = new LinkedHashSet<>(preferred);
		order.addAll(allServers);

		for (final String candidate : order) {
			if (excluded.contains(candidate) || !allServers.contains(candidate))
				continue;

			if (BanMatcher.banFor(bans, candidate, groupOf.apply(candidate), now).isEmpty())
				return Optional.of(candidate);
		}

		return Optional.empty();
	}
}
