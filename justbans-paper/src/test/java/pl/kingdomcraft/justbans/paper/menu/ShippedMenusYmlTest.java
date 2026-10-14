package pl.kingdomcraft.justbans.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The menus.yml shipped in the jar must describe exactly the layout the code falls back to. */
class ShippedMenusYmlTest {

	private static Map<String, Object> load() throws Exception {
		try (InputStream stream = ShippedMenusYmlTest.class.getClassLoader().getResourceAsStream("menus.yml")) {
			assertNotNull(stream, "menus.yml is missing from the resources");

			final Map<String, Object> flat = new HashMap<>();
			flatten("", new Yaml().load(new InputStreamReader(stream, StandardCharsets.UTF_8)), flat);

			return flat;
		}
	}

	/** Nested sections become dotted paths; lists and scalars are leaves. */
	@SuppressWarnings("unchecked")
	private static void flatten(String prefix, Object node, Map<String, Object> out) {
		if (node instanceof Map<?, ?> map)
			((Map<String, Object>) map).forEach((key, value) -> flatten(prefix.isEmpty() ? key : prefix + "." + key, value, out));
		else
			out.put(prefix, node);
	}

	@Test
	void parsesWithoutProblems() throws Exception {
		final MenuSettings.Parsed parsed = MenuSettings.parse(load()::get);

		assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
	}

	@Test
	void matchesTheBuiltInDefaultsExactly() throws Exception {
		final MenuSettings fromFile = MenuSettings.parse(load()::get).settings();
		final MenuSettings builtIn = MenuSettings.defaults();

		assertEquals(builtIn.punish(), fromFile.punish());
		assertEquals(builtIn.action(), fromFile.action());
		assertEquals(builtIn.servers(), fromFile.servers());
		assertEquals(builtIn.confirm(), fromFile.confirm());
		assertEquals(builtIn.history(), fromFile.history());
		assertEquals(builtIn.detail(), fromFile.detail());
	}

	@Test
	void everyPathInTheFileIsReadByTheCode() throws Exception {
		// Guards against a typo'd key in menus.yml that would silently be ignored: change one value per key and expect a difference.
		final Map<String, Object> file = load();

		for (final Map.Entry<String, Object> entry : file.entrySet()) {
			if (entry.getKey().equals("Version"))
				continue;

			final Map<String, Object> changed = new HashMap<>(file);
			changed.put(entry.getKey(), entry.getValue() instanceof Number ? (Object) 5 : entry.getValue() instanceof java.util.List ? (Object) java.util.List.of("changed") : "changed");

			final MenuSettings.Parsed parsed = MenuSettings.parse(changed::get);
			final boolean differs = !parsed.problems().isEmpty() || !parsed.settings().equals(MenuSettings.parse(file::get).settings())
					|| !same(parsed.settings(), MenuSettings.parse(file::get).settings());

			assertTrue(differs, "menus.yml key '" + entry.getKey() + "' is not read by MenuSettings");
		}
	}

	private static boolean same(MenuSettings a, MenuSettings b) {
		return a.punish().equals(b.punish()) && a.action().equals(b.action()) && a.servers().equals(b.servers()) && a.confirm().equals(b.confirm()) && a.history().equals(b.history())
				&& a.detail().equals(b.detail());
	}
}
