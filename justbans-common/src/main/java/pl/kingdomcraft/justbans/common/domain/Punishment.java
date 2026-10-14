package pl.kingdomcraft.justbans.common.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a row in {@code jb_punishments}.
 *
 * @param id           database id
 * @param publicId     short code shown to the player and used for appeals
 * @param reasonId     configured reason id, or null for a free-text reason
 * @param reasonText   rendered reason snapshot
 * @param note         internal staff note, never shown to the target
 * @param expiresAt    null means permanent
 * @param originServer where it was issued ("proxy", "web" or a server id)
 * @param revocation   null while the punishment has not been pardoned
 */
public record Punishment(
		long id,
		String publicId,
		PunishmentType type,
		Scope scope,
		UUID targetUuid,
		String targetName,
		Actor actor,
		String reasonId,
		String reasonText,
		String note,
		Instant createdAt,
		Instant expiresAt,
		String originServer,
		boolean silent,
		Integer ladderStep,
		Revocation revocation) {

	public Punishment {
		Objects.requireNonNull(publicId, "publicId");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(targetUuid, "targetUuid");
		Objects.requireNonNull(targetName, "targetName");
		Objects.requireNonNull(actor, "actor");
		Objects.requireNonNull(reasonText, "reasonText");
		Objects.requireNonNull(createdAt, "createdAt");
	}

	/**
	 * Revocation details (pardon / unwarn).
	 */
	public record Revocation(Instant at, Actor by, String reason) {

		public Revocation {
			Objects.requireNonNull(at, "at");
			Objects.requireNonNull(by, "by");
		}
	}

	public boolean isPermanent() {
		return expiresAt == null;
	}

	public boolean isRevoked() {
		return revocation != null;
	}

	/**
	 * Active means not revoked and not expired. Kicks are instantaneous and are never active.
	 * Mirrors the SQL condition {@code revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now)}.
	 */
	public boolean isActive(Instant now) {
		if (type == PunishmentType.KICK || isRevoked())
			return false;

		return expiresAt == null || expiresAt.isAfter(now);
	}
}
