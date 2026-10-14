package pl.kingdomcraft.justbans.common.service;

import java.util.UUID;

import pl.kingdomcraft.justbans.common.domain.PunishmentType;

/**
 * Platform hook deciding whether a player cannot be punished (permission, LuckPerms weight, ...).
 * Called on a database executor thread, never on the Paper main thread.
 */
@FunctionalInterface
public interface ExemptionChecker {

	ExemptionChecker NONE = (target, type) -> false;

	boolean isExempt(UUID target, PunishmentType type);
}
