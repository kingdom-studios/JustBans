package pl.kingdomcraft.justbans.common.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a row in {@code jb_players}. Enables punishing players who are offline.
 */
public record PlayerRecord(UUID uuid, String lastName, Instant firstSeen, Instant lastSeen, String lastServer) {

	public PlayerRecord {
		Objects.requireNonNull(uuid, "uuid");
		Objects.requireNonNull(lastName, "lastName");
		Objects.requireNonNull(firstSeen, "firstSeen");
		Objects.requireNonNull(lastSeen, "lastSeen");
	}
}
