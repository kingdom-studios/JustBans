package pl.kingdomcraft.justbans.velocity;

import java.io.BufferedReader;
import java.io.StringReader;

import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Parses YAML text into a Configurate tree for tests. */
public final class TestYaml {

	private TestYaml() {
	}

	public static ConfigurationNode parse(String text) throws Exception {
		return YamlConfigurationLoader.builder().source(() -> new BufferedReader(new StringReader(text))).build().load();
	}
}
