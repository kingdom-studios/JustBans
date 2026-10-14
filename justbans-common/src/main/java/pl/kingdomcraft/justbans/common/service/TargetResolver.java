package pl.kingdomcraft.justbans.common.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import pl.kingdomcraft.justbans.common.domain.PlayerRecord;
import pl.kingdomcraft.justbans.common.repository.PlayerRepository;

/**
 * Resolves a command target from a UUID and/or name using only the local player tables (works for offline
 * players who have joined the network before). A Mojang lookup for never-seen names can be layered on later.
 */
public final class TargetResolver {

	public record Target(UUID uuid, String name) {
	}

	private final PlayerRepository players = new PlayerRepository();

	public Result<Target> resolve(Connection connection, String uuidText, String name) throws SQLException {
		if (uuidText != null && !uuidText.isBlank()) {
			final UUID uuid;

			try {
				uuid = UUID.fromString(uuidText.trim());
			} catch (final IllegalArgumentException ex) {
				return Result.error(ErrorCode.UNKNOWN_PLAYER, "'" + uuidText + "' is not a valid UUID");
			}

			final Optional<PlayerRecord> known = this.players.findByUuid(connection, uuid);

			if (known.isPresent())
				return Result.ok(new Target(uuid, known.get().lastName()));

			if (name != null && !name.isBlank())
				return Result.ok(new Target(uuid, name.trim()));

			// A bare UUID for someone we have never seen is still valid to punish; use a stable placeholder name.
			return Result.ok(new Target(uuid, uuid.toString().substring(0, 8)));
		}

		if (name == null || name.isBlank())
			return Result.error(ErrorCode.UNKNOWN_PLAYER, "A target uuid or name is required");

		final List<PlayerRecord> matches = this.players.findByName(connection, name.trim());

		if (matches.isEmpty())
			return Result.error(ErrorCode.UNKNOWN_PLAYER, "No player named '" + name + "' has joined the network; use their UUID");

		if (matches.size() > 1)
			return Result.error(ErrorCode.UNKNOWN_PLAYER, "'" + name + "' is ambiguous (" + matches.size() + " players have used it); use their UUID");

		return Result.ok(new Target(matches.get(0).uuid(), matches.get(0).lastName()));
	}
}
