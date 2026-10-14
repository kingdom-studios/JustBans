package pl.kingdomcraft.justbans.velocity.config;

import java.util.List;

/** A configuration file is unusable. Carries every problem found so the admin can fix them in one go. */
public final class ConfigException extends Exception {
	private static final long serialVersionUID = 1L;

	private final List<String> problems;

	public ConfigException(String file, List<String> problems) {
		super(file + " has " + problems.size() + " problem(s):\n - " + String.join("\n - ", problems));

		this.problems = List.copyOf(problems);
	}

	public ConfigException(String message, Throwable cause) {
		super(message, cause);

		this.problems = List.of(message);
	}

	public List<String> problems() {
		return this.problems;
	}
}
