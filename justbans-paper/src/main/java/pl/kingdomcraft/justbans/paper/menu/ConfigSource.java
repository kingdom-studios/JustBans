package pl.kingdomcraft.justbans.paper.menu;

/** Read access to raw config values by dotted path, so {@link MenuSettings} can be parsed from Foundation's YamlConfig or from a plain map in tests. */
@FunctionalInterface
public interface ConfigSource {

	/** @return the raw value (String, Number, Boolean or List), or null if the path is not set */
	Object get(String path);
}
