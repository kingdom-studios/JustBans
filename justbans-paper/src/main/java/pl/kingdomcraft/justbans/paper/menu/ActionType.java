package pl.kingdomcraft.justbans.paper.menu;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;

/**
 * What staff pick in the punishment GUI. It is the same four verbs as the commands; whether a ban is network-wide or
 * limited to a server is a separate choice (scope), which together give the {@link PunishmentKind}.
 */
public enum ActionType {

	BAN("justbans.ban", false, true),
	TEMPBAN("justbans.tempban", true, true),
	WARN("justbans.warn", false, false),
	KICK("justbans.kick", false, false);

	private final String permission;
	private final boolean needsDuration;
	private final boolean scopeable;

	ActionType(String permission, boolean needsDuration, boolean scopeable) {
		this.permission = permission;
		this.needsDuration = needsDuration;
		this.scopeable = scopeable;
	}

	/** The permission staff need, the same one the matching command checks. */
	public String permission() {
		return this.permission;
	}

	/** Whether a duration applies (temporary bans). */
	public boolean needsDuration() {
		return this.needsDuration;
	}

	/** Whether the action can be limited to one server or group (bans). */
	public boolean isScopeable() {
		return this.scopeable;
	}

	public PunishmentKind kind(boolean serverScoped) {
		return switch (this) {
			case BAN -> serverScoped ? PunishmentKind.SERVER_BAN : PunishmentKind.BAN;
			case TEMPBAN -> serverScoped ? PunishmentKind.SERVER_TEMPBAN : PunishmentKind.TEMPBAN;
			case WARN -> PunishmentKind.WARN;
			case KICK -> PunishmentKind.KICK;
		};
	}

	/** Whether the reason can be used with this action at all (network-wide). */
	public boolean isAllowedBy(ReasonDefinition reason) {
		return reason.allows(this.kind(false));
	}

	/** Whether the reason also allows this action limited to a server or group. */
	public boolean allowsScopeFor(ReasonDefinition reason) {
		return this.scopeable && reason.allows(this.kind(true));
	}
}
