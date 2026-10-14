package pl.kingdomcraft.justbans.common.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Who issued or revoked a punishment.
 *
 * @param uuid player UUID, only for {@link ActorType#PLAYER}
 * @param ref  external reference, e.g. the web panel user id for {@link ActorType#WEB}
 */
public record Actor(ActorType type, UUID uuid, String ref, String name) {

	public Actor {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(name, "name");

		if (type == ActorType.PLAYER && uuid == null)
			throw new IllegalArgumentException("PLAYER actor requires a uuid");
	}

	public static Actor player(UUID uuid, String name) {
		return new Actor(ActorType.PLAYER, uuid, null, name);
	}

	public static Actor console() {
		return new Actor(ActorType.CONSOLE, null, null, "Console");
	}

	public static Actor system(String name) {
		return new Actor(ActorType.SYSTEM, null, null, name);
	}

	public static Actor web(String userRef, String name) {
		return new Actor(ActorType.WEB, null, userRef, name);
	}
}
