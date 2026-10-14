package pl.kingdomcraft.justbans.common.domain;

/**
 * User-facing action an admin picks (command or GUI), as used in reasons.yml
 * {@code types:}. Each kind maps to a persisted {@link PunishmentType} and a scope.
 */
public enum PunishmentKind {
	BAN(PunishmentType.BAN, ScopeType.NETWORK, false),
	TEMPBAN(PunishmentType.BAN, ScopeType.NETWORK, true),
	SERVER_BAN(PunishmentType.BAN, ScopeType.SERVER, false),
	SERVER_TEMPBAN(PunishmentType.BAN, ScopeType.SERVER, true),
	WARN(PunishmentType.WARN, ScopeType.NETWORK, false),
	KICK(PunishmentType.KICK, ScopeType.NETWORK, false);

	private final PunishmentType type;
	private final ScopeType defaultScope;
	private final boolean temporary;

	PunishmentKind(PunishmentType type, ScopeType defaultScope, boolean temporary) {
		this.type = type;
		this.defaultScope = defaultScope;
		this.temporary = temporary;
	}

	public PunishmentType type() {
		return type;
	}

	/** Scope type this kind uses; {@link ScopeType#SERVER} kinds also accept a group target. */
	public ScopeType defaultScope() {
		return defaultScope;
	}

	/** Whether this kind requires a duration. */
	public boolean isTemporary() {
		return temporary;
	}

	public boolean requiresScopeTarget() {
		return defaultScope != ScopeType.NETWORK;
	}
}
