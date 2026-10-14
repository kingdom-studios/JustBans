package pl.kingdomcraft.justbans.common.service;

import java.util.UUID;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;

/**
 * Platform hook deciding whether a player cannot be punished (permission, LuckPerms weight, ...).
 * Called on a database executor thread, never on the Paper main thread.
 */
@FunctionalInterface
public interface ExemptionChecker {

	ExemptionChecker NONE = (target, type) -> false;

	boolean isExempt(UUID target, PunishmentType type);

	/**
	 * Actor-aware variant used by {@link PunishmentService}; override to compare the actor with the target (for
	 * example by permission weight). The default ignores the actor.
	 */
	default boolean isExempt(Actor actor, UUID target, PunishmentType type) {
		return this.isExempt(target, type);
	}
}
