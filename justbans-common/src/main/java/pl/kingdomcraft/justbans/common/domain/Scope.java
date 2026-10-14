package pl.kingdomcraft.justbans.common.domain;

import java.util.Objects;

/**
 * Where a punishment applies. {@link #target()} is {@code "*"} for the whole network,
 * otherwise a server id or a server group name.
 */
public record Scope(ScopeType type, String target) {

	public static final String ALL = "*";

	private static final Scope NETWORK = new Scope(ScopeType.NETWORK, ALL);

	public Scope {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(target, "target");

		if (type == ScopeType.NETWORK) {
			target = ALL;
		} else if (target.isBlank() || target.equals(ALL))
			throw new IllegalArgumentException(type + " scope requires a concrete target");
	}

	public static Scope network() {
		return NETWORK;
	}

	public static Scope server(String serverId) {
		return new Scope(ScopeType.SERVER, serverId);
	}

	public static Scope group(String groupName) {
		return new Scope(ScopeType.GROUP, groupName);
	}

	/**
	 * Does this scope cover a connection to the given server?
	 *
	 * @param serverId   id of the server (as named in velocity.toml)
	 * @param serverGroup group of that server, or null if it has none
	 */
	public boolean covers(String serverId, String serverGroup) {
		return switch (type) {
			case NETWORK -> true;
			case SERVER -> target.equalsIgnoreCase(serverId);
			case GROUP -> serverGroup != null && target.equalsIgnoreCase(serverGroup);
		};
	}
}
