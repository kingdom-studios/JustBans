package pl.kingdomcraft.justbans.paper.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;

/** Which reasons and actions a staff member sees in the GUI, based on their permissions. Pure, so it can be tested. */
public final class ActionRules {

	private ActionRules() {
	}

	/**
	 * The actions the reason allows and the staff member has permission for, in {@link ActionType} order.
	 *
	 * @param hasPermission the staff member's permission check
	 */
	public static List<ActionType> usableActions(ReasonDefinition reason, Predicate<String> hasPermission) {
		final List<ActionType> usable = new ArrayList<>();

		for (final ActionType action : ActionType.values())
			if (action.isAllowedBy(reason) && hasPermission.test(action.permission()))
				usable.add(action);

		return usable;
	}

	/** A reason is shown only if staff can use it for at least one action. */
	public static boolean isVisible(ReasonDefinition reason, Predicate<String> hasPermission) {
		return !usableActions(reason, hasPermission).isEmpty();
	}

	public static List<ReasonDefinition> visibleReasons(List<ReasonDefinition> reasons, Predicate<String> hasPermission) {
		return reasons.stream().filter(reason -> isVisible(reason, hasPermission)).toList();
	}

	/** The permission needed to revoke a punishment of this type, or null if it cannot be revoked (kicks). */
	public static String revokePermission(pl.kingdomcraft.justbans.common.domain.PunishmentType type) {
		return switch (type) {
			case BAN -> "justbans.unban";
			case WARN -> "justbans.unwarn";
			case KICK -> null;
		};
	}
}
