package pl.kingdomcraft.justbans.common.domain;

import java.time.Instant;

/** A row of {@code jb_events}; {@code payload} is the exact JSON published to Redis. */
public record EventRecord(long id, EventType type, Long punishmentId, String payload, String originNode, Instant createdAt, Instant publishedAt) {
}
