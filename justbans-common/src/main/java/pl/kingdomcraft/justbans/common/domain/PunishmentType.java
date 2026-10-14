package pl.kingdomcraft.justbans.common.domain;

/**
 * Persisted punishment type. Pardons are not a type: they revoke an existing row.
 */
public enum PunishmentType {
	BAN,
	WARN,
	KICK
}
