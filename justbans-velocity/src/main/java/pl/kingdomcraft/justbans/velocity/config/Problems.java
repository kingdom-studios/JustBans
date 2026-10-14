package pl.kingdomcraft.justbans.velocity.config;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.configurate.ConfigurationNode;

/** Collects validation problems while reading a config tree, with typed accessors that report instead of throwing. */
final class Problems {

	private final List<String> list = new ArrayList<>();

	void add(String message) {
		this.list.add(message);
	}

	List<String> list() {
		return this.list;
	}

	boolean isEmpty() {
		return this.list.isEmpty();
	}

	void throwIfAny(String file) throws ConfigException {
		if (!this.list.isEmpty())
			throw new ConfigException(file, this.list);
	}

	static String path(ConfigurationNode node) {
		final Object[] path = node.path().array();
		final StringBuilder out = new StringBuilder();

		for (final Object part : path)
			out.append(out.length() == 0 ? "" : ".").append(part);

		return out.length() == 0 ? "(root)" : out.toString();
	}

	String string(ConfigurationNode node, String defaultValue) {
		if (node.virtual() || node.raw() == null)
			return defaultValue;

		if (node.isMap() || node.isList()) {
			this.add(path(node) + " must be a single value");
			return defaultValue;
		}

		return String.valueOf(node.raw());
	}

	String requiredString(ConfigurationNode node) {
		final String value = this.string(node, null);

		if (value == null || value.isBlank())
			this.add(path(node) + " is required");

		return value;
	}

	int integer(ConfigurationNode node, int defaultValue, int min, int max) {
		if (node.virtual() || node.raw() == null)
			return defaultValue;

		final int value;

		if (node.raw() instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue()))
			value = number.intValue();
		else {
			try {
				value = Integer.parseInt(String.valueOf(node.raw()).trim());
			} catch (final NumberFormatException ex) {
				this.add(path(node) + " must be a whole number, got '" + node.raw() + "'");
				return defaultValue;
			}
		}

		if (value < min || value > max) {
			this.add(path(node) + " must be between " + min + " and " + max + ", got " + value);
			return defaultValue;
		}

		return value;
	}

	boolean bool(ConfigurationNode node, boolean defaultValue) {
		if (node.virtual() || node.raw() == null)
			return defaultValue;

		if (node.raw() instanceof Boolean value)
			return value;

		final String text = String.valueOf(node.raw()).trim().toLowerCase();

		if (text.equals("true") || text.equals("false"))
			return Boolean.parseBoolean(text);

		this.add(path(node) + " must be true or false, got '" + node.raw() + "'");
		return defaultValue;
	}

	List<String> stringList(ConfigurationNode node) {
		final List<String> values = new ArrayList<>();

		if (node.virtual() || node.raw() == null)
			return values;

		if (node.isList()) {
			for (final ConfigurationNode child : node.childrenList()) {
				if (child.isMap() || child.isList() || child.raw() == null)
					this.add(path(child) + " must be a single value");
				else
					values.add(String.valueOf(child.raw()));
			}

		} else if (node.isMap())
			this.add(path(node) + " must be a list");
		else
			values.add(String.valueOf(node.raw())); // a lone value is accepted as a one-item list

		return values;
	}
}
