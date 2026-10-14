package pl.kingdomcraft.justbans.common.service;

import java.util.Objects;
import java.util.UUID;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;

/**
 * Pardon or unwarn. Either {@code punishmentId} is set, or {@code targetUuid} + {@code type} (+ {@code scope} for bans).
 */
public record RevokeRequest(Actor actor, Long punishmentId, UUID targetUuid, PunishmentType type, Scope scope, String reason) {

	public RevokeRequest {
		Objects.requireNonNull(actor, "actor");

		if (punishmentId == null && (targetUuid == null || type == null))
			throw new IllegalArgumentException("Either punishmentId or targetUuid and type are required");
	}

	public static RevokeRequest byId(Actor actor, long punishmentId, String reason) {
		return new RevokeRequest(actor, punishmentId, null, null, null, reason);
	}

	/** Pardons the active ban in exactly this scope. */
	public static RevokeRequest ban(Actor actor, UUID target, Scope scope, String reason) {
		return new RevokeRequest(actor, null, target, PunishmentType.BAN, scope, reason);
	}

	/** Revokes the player's most recent active warn. */
	public static RevokeRequest lastWarn(Actor actor, UUID target, String reason) {
		return new RevokeRequest(actor, null, target, PunishmentType.WARN, null, reason);
	}
}
