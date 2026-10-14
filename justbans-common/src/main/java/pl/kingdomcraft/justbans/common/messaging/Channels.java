package pl.kingdomcraft.justbans.common.messaging;

/** Redis channel and key names, all under a configurable prefix (default {@code justbans:}). */
public record Channels(String prefix) {

	public static final String DEFAULT_PREFIX = "justbans:";

	public Channels {
		if (prefix == null || prefix.isBlank())
			throw new IllegalArgumentException("Redis prefix cannot be empty");
	}

	public static Channels defaults() {
		return new Channels(DEFAULT_PREFIX);
	}

	/** Punishment lifecycle events, plugin to everyone. */
	public String events() {
		return prefix + "events";
	}

	/** Signed action requests, web to plugin. */
	public String commands() {
		return prefix + "commands";
	}

	/** One result per command, plugin to web. */
	public String commandResults() {
		return prefix + "command-results";
	}

	public String[] all() {
		return new String[] { events(), commands(), commandResults() };
	}
}
