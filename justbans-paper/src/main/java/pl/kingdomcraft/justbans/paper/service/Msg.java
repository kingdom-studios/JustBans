package pl.kingdomcraft.justbans.paper.service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny placeholder filler for localized messages: {@code fill("Hi {name}", "name", "Bob")}. */
public final class Msg {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

	private Msg() {
	}

	/**
	 * Replaces each {@code {key}} with its value in a single pass, so a value that itself contains {@code {other}} (a
	 * player name, a typed reason) is inserted literally and never expanded. Unknown placeholders are left as they are.
	 *
	 * @param keyValues alternating keys and values; a null value becomes empty
	 */
	public static String fill(String template, String... keyValues) {
		if (keyValues.length % 2 != 0)
			throw new IllegalArgumentException("fill() needs key/value pairs");

		final Map<String, String> values = new HashMap<>();

		for (int i = 0; i < keyValues.length; i += 2)
			values.put(keyValues[i], keyValues[i + 1] == null ? "" : keyValues[i + 1]);

		final Matcher matcher = PLACEHOLDER.matcher(template);
		final StringBuilder out = new StringBuilder();

		while (matcher.find()) {
			final String value = values.get(matcher.group(1));
			matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
		}

		matcher.appendTail(out);

		return out.toString();
	}
}
