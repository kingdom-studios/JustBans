package pl.kingdomcraft.justbans.velocity.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition.LadderStep;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.render.TemplateRenderer;
import pl.kingdomcraft.justbans.common.time.DurationParser;

/**
 * Reads and validates reasons.yml. Every problem is reported with its path (including the offending MiniMessage
 * line) so a typo is caught at startup or reload instead of showing up on a player's ban screen.
 */
public final class ReasonsLoader {

	private static final String FILE = "reasons.yml";
	private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
	private static final Set<String> KNOWN_TOP_LEVEL = Set.of("defaults", "reasons");

	/**
	 * The same lenient parser the renderer uses. Strict mode is not used because it demands every tag be closed,
	 * which would reject ordinary lines such as {@code <red>You were kicked}.
	 */
	private static final MiniMessage MINI = MiniMessage.miniMessage();

	private ReasonsLoader() {
	}

	public static ReasonsFile load(Path file, Logger logger) throws ConfigException {
		final ConfigurationNode root;

		try {
			root = YamlConfigurationLoader.builder().path(file).build().load();
		} catch (final ConfigurateException ex) {
			throw new ConfigException(FILE + " is not valid YAML: " + ex.getMessage(), ex);
		}

		return parse(root, logger);
	}

	public static ReasonsFile parse(ConfigurationNode root, Logger logger) throws ConfigException {
		final Problems problems = new Problems();

		for (final Object key : root.childrenMap().keySet())
			if (!KNOWN_TOP_LEVEL.contains(String.valueOf(key)))
				logger.warning(FILE + ": ignoring unknown top-level key '" + key + "' (only 'defaults' and 'reasons' are supported in this version)");

		final Map<String, List<String>> defaults = templates(root.node("defaults", "templates"), problems);

		final List<ReasonDefinition> reasons = new ArrayList<>();
		final ConfigurationNode reasonsNode = root.node("reasons");

		if (reasonsNode.virtual() || !reasonsNode.isMap() || reasonsNode.childrenMap().isEmpty())
			problems.add("reasons must contain at least one reason");
		else {
			int order = 0;

			for (final Map.Entry<Object, ? extends ConfigurationNode> entry : reasonsNode.childrenMap().entrySet()) {
				final ReasonDefinition reason = reason(String.valueOf(entry.getKey()), entry.getValue(), order++, problems);

				if (reason != null)
					reasons.add(reason);
			}
		}

		problems.throwIfAny(FILE);

		return new ReasonsFile(defaults, reasons);
	}

	private static ReasonDefinition reason(String id, ConfigurationNode node, int order, Problems problems) {
		final int before = problems.list().size();

		if (!ID.matcher(id).matches())
			problems.add("reasons." + id + ": the id must be 1-32 characters of a-z, 0-9, '_' or '-'");

		if (!node.isMap()) {
			problems.add("reasons." + id + " must be a section");
			return null;
		}

		final String display = problems.requiredString(node.node("display"));

		if (display != null && display.length() > 128)
			problems.add(Problems.path(node.node("display")) + " is longer than 128 characters");

		final Set<PunishmentKind> kinds = EnumSet.noneOf(PunishmentKind.class);
		final List<String> typeNames = problems.stringList(node.node("types"));

		if (typeNames.isEmpty())
			problems.add(Problems.path(node.node("types")) + " must list at least one of " + List.of(PunishmentKind.values()));

		for (final String name : typeNames)
			try {
				kinds.add(PunishmentKind.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
			} catch (final IllegalArgumentException ex) {
				problems.add(Problems.path(node.node("types")) + " has unknown type '" + name + "', expected one of " + List.of(PunishmentKind.values()));
			}

		Duration defaultDuration = null;
		final ConfigurationNode durationNode = node.node("duration");

		if (!durationNode.virtual() && durationNode.raw() != null) {
			try {
				defaultDuration = DurationParser.parse(String.valueOf(durationNode.raw()));

				if (DurationParser.isPermanent(defaultDuration)) {
					problems.add(Problems.path(durationNode) + " is the default for temporary punishments and cannot be 'perm'; use a BAN type for permanent ones");
					defaultDuration = null;
				}
			} catch (final IllegalArgumentException ex) {
				problems.add(Problems.path(durationNode) + ": " + ex.getMessage());
			}
		}

		final List<LadderStep> ladder = new ArrayList<>();
		final ConfigurationNode ladderNode = node.node("ladder");
		int step = 0;

		for (final String raw : problems.stringList(ladderNode)) {
			final LadderStep parsed = ladderStep(raw, Problems.path(ladderNode) + "[" + step++ + "]", problems);

			if (parsed != null)
				ladder.add(parsed);
		}

		final String icon = problems.string(node.node("icon"), null);
		final boolean enabled = problems.bool(node.node("enabled"), true);
		final Map<String, List<String>> templates = templates(node.node("templates"), problems);

		if (problems.list().size() > before)
			return null;

		return new ReasonDefinition(id, display.strip(), kinds, defaultDuration, ladder, icon, templates, order, enabled);
	}

	private static LadderStep ladderStep(String raw, String where, Problems problems) {
		final String text = raw.trim();

		switch (text.toUpperCase(Locale.ROOT)) {
			case "WARN":
				return new LadderStep(PunishmentType.WARN, null);
			case "KICK":
				return new LadderStep(PunishmentType.KICK, null);
			case "BAN":
			case "PERM":
			case "PERMANENT":
				return new LadderStep(PunishmentType.BAN, null);
			default:
				try {
					return new LadderStep(PunishmentType.BAN, DurationParser.parse(text));
				} catch (final IllegalArgumentException ex) {
					problems.add(where + " '" + raw + "' is not WARN, KICK, perm or a duration like 7d: " + ex.getMessage());
					return null;
				}
		}
	}

	private static Map<String, List<String>> templates(ConfigurationNode node, Problems problems) {
		final Map<String, List<String>> templates = new LinkedHashMap<>();

		if (node.virtual() || node.raw() == null)
			return templates;

		if (!node.isMap()) {
			problems.add(Problems.path(node) + " must be a section of template names");
			return templates;
		}

		for (final Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
			final String name = String.valueOf(entry.getKey());
			final ConfigurationNode linesNode = entry.getValue();

			if (!TemplateNames.ALL.contains(name)) {
				problems.add(Problems.path(linesNode) + " is not a template; expected one of " + new java.util.TreeSet<>(TemplateNames.ALL));
				continue;
			}

			final List<String> lines = problems.stringList(linesNode);

			if (lines.isEmpty()) {
				problems.add(Problems.path(linesNode) + " must have at least one line");
				continue;
			}

			for (int i = 0; i < lines.size(); i++)
				validateLine(lines.get(i), Problems.path(linesNode) + "[" + i + "]", problems);

			templates.put(name, List.copyOf(lines));
		}

		return templates;
	}

	/**
	 * Renders the line against a sample punishment. The lenient parser prints anything it does not understand (an
	 * unknown placeholder, a misspelt tag, a tag with bad arguments, a missing {@code >}) as literal text, so a
	 * {@code <} left in the output means the line is broken. Consequence: templates cannot show a literal {@code <}.
	 */
	static void validateLine(String line, String where, Problems problems) {
		final Instant now = Instant.parse("2026-01-01T00:00:00Z");
		final Punishment sample = new Punishment(1, "ABCDEFGH", PunishmentType.BAN, Scope.network(), UUID.randomUUID(), "Player", Actor.console(), "id", "Reason", null, now,
				now.plusSeconds(3600), "proxy", false, null, null);

		try {
			final String text = plainText(MINI.deserialize(line, TemplateRenderer.resolvers(sample, now, TemplateRenderer.Options.defaults())));

			if (text.indexOf('<') >= 0)
				problems.add(where + " has an unknown or malformed tag or placeholder near '" + text.substring(text.indexOf('<'), Math.min(text.length(), text.indexOf('<') + 20)) + "': " + line);

		} catch (final RuntimeException ex) {
			problems.add(where + " is not valid MiniMessage (" + firstLine(ex.getMessage()) + "): " + line);
		}
	}

	private static String plainText(Component component) {
		final StringBuilder out = new StringBuilder();
		append(component, out);

		return out.toString();
	}

	private static void append(Component component, StringBuilder out) {
		if (component instanceof TextComponent text)
			out.append(text.content());

		for (final Component child : component.children())
			append(child, out);
	}

	private static String firstLine(String message) {
		if (message == null)
			return "parse error";

		final int newline = message.indexOf('\n');

		return newline < 0 ? message : message.substring(0, newline);
	}
}
