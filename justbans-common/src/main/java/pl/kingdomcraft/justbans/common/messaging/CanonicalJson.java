package pl.kingdomcraft.justbans.common.messaging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Deterministic JSON text used as the signed form of a payload, so that any implementation (Java, Node,
 * Python, PHP) can reproduce the same bytes:
 * <ul>
 * <li>no whitespace;</li>
 * <li>object keys sorted by UTF-16 code unit order, recursively;</li>
 * <li>strings escape only the double quote, the backslash, backspace, form feed, newline, carriage return and tab
 * (as their two-character short forms), and any other control character below 0x20 as a six-character
 * lower-case hex escape (backslash, 'u', four hex digits); every other character is written as-is (UTF-8);</li>
 * <li>numbers are written exactly as they appear in the received JSON text (Java: {@code Long.toString} for
 * integers it produces itself).</li>
 * </ul>
 */
public final class CanonicalJson {

	private CanonicalJson() {
	}

	public static String write(JsonElement element) {
		final StringBuilder out = new StringBuilder();
		write(element, out);

		return out.toString();
	}

	private static void write(JsonElement element, StringBuilder out) {
		if (element == null || element.isJsonNull()) {
			out.append("null");

		} else if (element.isJsonObject()) {
			final JsonObject object = element.getAsJsonObject();
			final List<String> keys = new ArrayList<>(object.keySet());
			Collections.sort(keys);

			out.append('{');
			boolean first = true;

			for (final String key : keys) {
				if (!first)
					out.append(',');

				first = false;
				string(key, out);
				out.append(':');
				write(object.get(key), out);
			}

			out.append('}');

		} else if (element.isJsonArray()) {
			out.append('[');
			boolean first = true;

			for (final JsonElement child : element.getAsJsonArray()) {
				if (!first)
					out.append(',');

				first = false;
				write(child, out);
			}

			out.append(']');

		} else {
			final JsonPrimitive primitive = element.getAsJsonPrimitive();

			if (primitive.isString())
				string(primitive.getAsString(), out);
			else
				out.append(primitive.getAsString());
		}
	}

	private static void string(String value, StringBuilder out) {
		out.append('"');

		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);

			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20)
						out.append(String.format("\\u%04x", (int) c));
					else
						out.append(c);
				}
			}
		}

		out.append('"');
	}

	/** Visible for tests: entries of an object in canonical order. */
	static List<Map.Entry<String, JsonElement>> sortedEntries(JsonObject object) {
		final List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(object.entrySet());
		entries.sort(Map.Entry.comparingByKey());

		return entries;
	}
}
