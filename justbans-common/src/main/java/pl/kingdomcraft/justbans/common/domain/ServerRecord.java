package pl.kingdomcraft.justbans.common.domain;

import java.time.Instant;
import java.util.Objects;

/** A row of {@code jb_servers}. {@code groupName} and {@code displayName} may be null. */
public record ServerRecord(String id, String groupName, String displayName, Instant lastHeartbeat) {

	public ServerRecord {
		Objects.requireNonNull(id, "id");
	}
}
