package pl.kingdomcraft.justbans.paper.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pl.kingdomcraft.justbans.common.domain.Scope;

/**
 * Splits a command line into plain words and the options every punishment command shares:
 * <ul>
 * <li>{@code -s} silent (no public broadcast), {@code -f} force (replace an existing ban);</li>
 * <li>{@code server:<name>} or {@code group:<name>} limits a ban to one server or server group.</li>
 * </ul>
 * Options may appear anywhere after the first word. Pure and independent of Bukkit so it can be tested.
 */
public final class ArgParser {

	/**
	 * @param words    everything that is not an option, in order
	 * @param scope    the requested scope, or null for the whole network
	 * @param scopeError set when the scope options were contradictory or empty
	 */
	public record Parsed(List<String> words, boolean silent, boolean force, Scope scope, String scopeError) {

		public boolean hasScopeError() {
			return this.scopeError != null;
		}

		public boolean isScoped() {
			return this.scope != null;
		}

		public String word(int index) {
			return index < this.words.size() ? this.words.get(index) : null;
		}

		/** The words from {@code from} on, joined with spaces; empty if there are none. */
		public String join(int from) {
			return from >= this.words.size() ? "" : String.join(" ", this.words.subList(from, this.words.size()));
		}
	}

	private ArgParser() {
	}

	public static Parsed parse(String[] args) {
		final List<String> words = new ArrayList<>();
		boolean silent = false;
		boolean force = false;
		Scope scope = null;
		String error = null;

		for (final String arg : args) {
			final String lower = arg.toLowerCase(Locale.ROOT);

			if (lower.equals("-s"))
				silent = true;
			else if (lower.equals("-f"))
				force = true;
			else if (lower.startsWith("server:") || lower.startsWith("group:")) {
				final boolean server = lower.startsWith("server:");
				final String name = arg.substring(arg.indexOf(':') + 1);

				if (name.isBlank() || scope != null)
					error = "scope";
				else
					scope = server ? Scope.server(name) : Scope.group(name);

			} else
				words.add(arg);
		}

		return new Parsed(List.copyOf(words), silent, force, scope, error);
	}
}
