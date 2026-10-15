package pl.kingdomcraft.justbans.paper.placeholder;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.render.RemainingTimeFormatter;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.service.PlayerSnapshots.Snapshot;

/**
 * The text of each {@code %justbans_<identifier>%} placeholder, computed from a cached {@link Snapshot}. Pure functions of
 * their arguments: no database, no Bukkit, safe to call from any thread.
 */
public final class PlaceholderValues {

	/**
	 * @param none       shown by the text placeholders when there is nothing to show
	 * @param loading    shown while a player's data has not been loaded yet
	 * @param permanent  shown for an active ban that never ends
	 * @param lastFormat template for the last punishment: {@code {type} {reason} {id} {actor} {ago}}
	 */
	public record Texts(String none, String loading, String permanent, String lastFormat) {
	}

	private PlaceholderValues() {
	}

	/** Whether the identifier is one of ours. */
	public static boolean isKnown(String identifier) {
		return switch (identifier.toLowerCase(Locale.ROOT)) {
			case "warns", "banned_servers", "last_punishment", "active_ban_expiry" -> true;
			default -> false;
		};
	}

	/**
	 * @param identifier everything after {@code justbans_}, case-insensitive
	 * @param snapshot   the player's cached data, or null if it is still loading
	 * @return the replacement text, or null if the identifier is not one of ours (so other expansions may handle it)
	 */
	public static String resolve(String identifier, Snapshot snapshot, Instant now, Texts texts) {
		final String key = identifier.toLowerCase(Locale.ROOT);

		return switch (key) {
			case "warns" -> snapshot == null ? texts.loading() : Long.toString(snapshot.warns().stream().filter(p -> p.isActive(now)).count());
			case "banned_servers" -> snapshot == null ? texts.loading() : bannedServers(snapshot, now, texts);
			case "last_punishment" -> snapshot == null ? texts.loading() : lastPunishment(snapshot, now, texts);
			case "active_ban_expiry" -> snapshot == null ? texts.loading() : activeBanExpiry(snapshot, now, texts);
			default -> null;
		};
	}

	private static String bannedServers(Snapshot snapshot, Instant now, Texts texts) {
		final Set<String> where = new LinkedHashSet<>();

		for (final Punishment ban : snapshot.bans())
			if (ban.isActive(now))
				where.add(switch (ban.scope().type()) {
					case NETWORK -> "network";
					case SERVER, GROUP -> ban.scope().target();
				});

		return where.isEmpty() ? texts.none() : String.join(", ", where);
	}

	private static String lastPunishment(Snapshot snapshot, Instant now, Texts texts) {
		final Punishment last = snapshot.last();

		if (last == null)
			return texts.none();

		final Instant created = last.createdAt().isAfter(now) ? now : last.createdAt();
		final String ago = Duration.between(created, now).isZero() ? "0s" : RemainingTimeFormatter.defaults().format(created, now);

		return Msg.fill(texts.lastFormat(), "type", last.type().name().toLowerCase(Locale.ROOT), "reason", last.reasonText(), "id", last.publicId(), "actor", last.actor().name(), "ago", ago);
	}

	/** Time left on the active ban that ends first; the permanent text if every active ban is permanent. */
	private static String activeBanExpiry(Snapshot snapshot, Instant now, Texts texts) {
		boolean permanent = false;
		Optional<Instant> soonest = Optional.empty();

		for (final Punishment ban : snapshot.bans()) {
			if (!ban.isActive(now))
				continue;

			if (ban.expiresAt() == null)
				permanent = true;
			else if (soonest.isEmpty() || ban.expiresAt().isBefore(soonest.get()))
				soonest = Optional.of(ban.expiresAt());
		}

		if (soonest.isPresent())
			return RemainingTimeFormatter.defaults().format(now, soonest.get());

		return permanent ? texts.permanent() : texts.none();
	}
}
