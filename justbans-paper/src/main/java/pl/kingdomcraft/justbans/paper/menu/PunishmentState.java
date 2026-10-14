package pl.kingdomcraft.justbans.paper.menu;

import java.time.Instant;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;

/** How a punishment is shown in the history: its colour and wording depend on this. */
public enum PunishmentState {

	ACTIVE,
	EXPIRED,
	REVOKED,
	KICK;

	public static PunishmentState of(Punishment p, Instant now) {
		if (p.type() == PunishmentType.KICK)
			return KICK;

		if (p.isRevoked())
			return REVOKED;

		return p.isActive(now) ? ACTIVE : EXPIRED;
	}
}
