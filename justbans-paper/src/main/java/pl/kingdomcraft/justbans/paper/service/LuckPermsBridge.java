package pl.kingdomcraft.justbans.paper.service;

import java.util.OptionalInt;
import java.util.UUID;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;

/**
 * Thin wrapper over the LuckPerms API for players who may be offline. Kept in its own class so the plugin works without
 * LuckPerms: this class is only loaded when the LuckPerms plugin is present.
 *
 * <p>All methods block on LuckPerms loading the user, so call them from a background thread only.
 */
final class LuckPermsBridge {

	private final LuckPerms api = LuckPermsProvider.get();

	boolean hasPermission(UUID player, String permission) {
		final User user = this.api.getUserManager().loadUser(player).join();

		return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
	}

	/** Highest weight among the player's groups (inherited ones included), or 0 if none has a weight. */
	int weight(UUID player) {
		final User user = this.api.getUserManager().loadUser(player).join();

		return user.getInheritedGroups(QueryOptions.defaultContextualOptions()).stream()
				.map(Group::getWeight)
				.filter(OptionalInt::isPresent)
				.mapToInt(OptionalInt::getAsInt)
				.max()
				.orElse(0);
	}
}
