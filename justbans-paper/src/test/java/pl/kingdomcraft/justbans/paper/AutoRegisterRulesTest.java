package pl.kingdomcraft.justbans.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.model.SimpleExpansion;
import org.mineacademy.fo.settings.YamlConfig;

/**
 * Foundation refuses to start the plugin if an {@code @AutoRegister} class does not match its instantiation rules, and only
 * discovers that at server start. This test applies the same rules (see Foundation's {@code AutoRegisterScanner}) to every
 * annotated class, so a mistake fails the build instead:
 * <ul>
 * <li>exactly one constructor, with no arguments;</li>
 * <li>singleton kinds ({@code SimpleExpansion}, {@code YamlConfig}, {@code SimpleListener}): that constructor must be
 * <b>private</b>, plus a {@code private static final} (or {@code volatile}) field holding the instance;</li>
 * <li>everything else (commands, command groups, plain Bukkit listeners): a public no-args constructor, or the same singleton
 * form.</li>
 * </ul>
 */
class AutoRegisterRulesTest {

	/** Kinds for which Foundation's {@code enforceModeFor} demands the singleton form. */
	private static final List<String> SINGLETON_KINDS = List.of("org.mineacademy.fo.model.SimpleExpansion", "org.mineacademy.fo.settings.YamlConfig",
			"org.mineacademy.fo.model.SimpleListener");

	private static List<Class<?>> annotatedClasses() throws IOException, URISyntaxException, ClassNotFoundException {
		final ClassLoader loader = AutoRegisterRulesTest.class.getClassLoader();
		final List<Class<?>> found = new ArrayList<>();

		// The package exists in both target/classes and target/test-classes: walk every root that has it.
		for (final URL url : Collections.list(loader.getResources("pl/kingdomcraft/justbans/paper"))) {
			if (!"file".equals(url.getProtocol()))
				continue;

			final Path root = new File(url.toURI()).toPath();
			final Path classesRoot = root.getParent().getParent().getParent().getParent();

			try (Stream<Path> files = Files.walk(root)) {
				for (final Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
					final String relative = classesRoot.relativize(file).toString();
					final String name = relative.substring(0, relative.length() - ".class".length()).replace(File.separatorChar, '.');

					// Class.forName(..., false, ...) loads without running static initializers: no Foundation start-up needed.
					final Class<?> clazz = Class.forName(name, false, loader);

					if (clazz.isAnnotationPresent(AutoRegister.class))
						found.add(clazz);
				}
			}
		}

		return found;
	}

	private static boolean isSingletonKind(Class<?> clazz) {
		for (Class<?> type = clazz; type != null; type = type.getSuperclass())
			if (SINGLETON_KINDS.contains(type.getName()))
				return true;

		return false;
	}

	@Test
	void everyAutoRegisteredClassSatisfiesFoundationsInstantiationRules() throws Exception {
		final List<Class<?>> classes = annotatedClasses();

		assertTrue(classes.size() >= 12, "expected to find the plugin's @AutoRegister classes, found " + classes);

		for (final Class<?> clazz : classes) {
			final Constructor<?>[] constructors = clazz.getDeclaredConstructors();

			assertEquals(1, constructors.length, clazz.getName() + " must have exactly one constructor");
			assertEquals(0, constructors[0].getParameterCount(), clazz.getName() + " needs a no-args constructor");

			final boolean privateConstructor = Modifier.isPrivate(constructors[0].getModifiers());
			final boolean singleton = privateConstructor && hasInstanceField(clazz);

			if (isSingletonKind(clazz))
				assertTrue(singleton, clazz.getName() + " is a singleton kind: it needs ONE PRIVATE no-args constructor and a 'private static final " + clazz.getSimpleName()
						+ " instance' field");
			else
				assertTrue(Modifier.isPublic(constructors[0].getModifiers()) || singleton, clazz.getName() + " needs a public no-args constructor (or the singleton form)");
		}
	}

	@Test
	void theExpansionIsASingletonInTheFormFoundationExpects() throws Exception {
		final Class<?> clazz = Class.forName("pl.kingdomcraft.justbans.paper.placeholder.JustBansExpansion", false, getClass().getClassLoader());

		assertTrue(SimpleExpansion.class.isAssignableFrom(clazz));
		assertTrue(annotatedClasses().contains(clazz));
		assertFalse(Modifier.isPublic(clazz.getDeclaredConstructors()[0].getModifiers()));
		assertTrue(hasInstanceField(clazz));
	}

	@Test
	void theMenuConfigIsASingletonInTheFormFoundationExpects() throws Exception {
		final Class<?> clazz = Class.forName("pl.kingdomcraft.justbans.paper.menu.MenuConfig", false, getClass().getClassLoader());

		assertTrue(YamlConfig.class.isAssignableFrom(clazz));
		assertNotNull(clazz.getDeclaredConstructors()[0]);
		assertTrue(Modifier.isPrivate(clazz.getDeclaredConstructors()[0].getModifiers()));
		assertTrue(hasInstanceField(clazz));
	}

	/** Same test as Foundation's: a private static field whose type the class is assignable to, final or volatile. */
	private static boolean hasInstanceField(Class<?> clazz) {
		for (final Field field : clazz.getDeclaredFields()) {
			final int mods = field.getModifiers();

			if (field.getType().isAssignableFrom(clazz) && field.getType() != Object.class && Modifier.isPrivate(mods) && Modifier.isStatic(mods) && (Modifier.isFinal(mods) || Modifier.isVolatile(mods)))
				return true;
		}

		return false;
	}
}
