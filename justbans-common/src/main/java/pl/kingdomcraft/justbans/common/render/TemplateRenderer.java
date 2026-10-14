package pl.kingdomcraft.justbans.common.render;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ScopeType;
import pl.kingdomcraft.justbans.common.time.DurationParser;

/**
 * Turns reasons.yml templates (MiniMessage) into components for ban screens, kick screens and broadcasts.
 *
 * <p>All values are supplied as <em>unparsed</em> placeholders: whatever a staff member typed as a reason or a
 * player's name is shown literally and can never inject tags such as {@code <click>} or {@code <red>}.
 *
 * <p>Placeholders: {@code <target> <actor> <reason> <id> <type> <type_past> <scope> <server> <created> <expires>
 * <remaining> <duration> <warn_count>}.
 */
public final class TemplateRenderer {

	/**
	 * @param dateFormat    format for {@code <created>} and {@code <expires>}
	 * @param currentServer value of {@code <server>} for network-wide punishments (e.g. where the player was), may be null
	 * @param warnCount     value of {@code <warn_count>}
	 * @param remaining     formatter for {@code <remaining>}
	 * @param neverText     what {@code <expires>} and {@code <duration>} show for permanent punishments
	 */
	public record Options(DateTimeFormatter dateFormat, String currentServer, int warnCount, RemainingTimeFormatter remaining, String neverText) {

		public static Options defaults() {
			return new Options(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("UTC")), null, 0, RemainingTimeFormatter.defaults(), "never");
		}

		public Options withServer(String server) {
			return new Options(dateFormat, server, warnCount, remaining, neverText);
		}

		public Options withWarnCount(int count) {
			return new Options(dateFormat, currentServer, count, remaining, neverText);
		}
	}

	private static final MiniMessage MINI = MiniMessage.miniMessage();

	private TemplateRenderer() {
	}

	public static TagResolver resolvers(Punishment p, Instant now, Options options) {
		final String scopeServer = p.scope().type() == ScopeType.NETWORK ? options.currentServer() : p.scope().target();

		return TagResolver.resolver(
				Placeholder.unparsed("target", p.targetName()),
				Placeholder.unparsed("actor", p.actor().name()),
				Placeholder.unparsed("reason", p.reasonText()),
				Placeholder.unparsed("id", p.publicId()),
				Placeholder.unparsed("type", p.type().name().toLowerCase()),
				Placeholder.unparsed("type_past", pastTense(p)),
				Placeholder.unparsed("scope", scopeText(p.scope())),
				Placeholder.unparsed("server", scopeServer == null ? "network" : scopeServer),
				Placeholder.unparsed("created", options.dateFormat().format(p.createdAt())),
				Placeholder.unparsed("expires", p.expiresAt() == null ? options.neverText() : options.dateFormat().format(p.expiresAt())),
				Placeholder.unparsed("remaining", options.remaining().format(now, p.expiresAt())),
				Placeholder.unparsed("duration", p.expiresAt() == null ? options.neverText() : DurationParser.format(Duration.between(p.createdAt(), p.expiresAt()))),
				Placeholder.unparsed("warn_count", Integer.toString(options.warnCount())));
	}

	/** One component per template line. */
	public static List<Component> render(List<String> lines, TagResolver resolver) {
		final List<Component> components = new ArrayList<>(lines.size());

		for (final String line : lines)
			components.add(MINI.deserialize(line, resolver));

		return components;
	}

	/** All lines joined with newlines, as one component (what a disconnect screen takes). */
	public static Component renderJoined(List<String> lines, TagResolver resolver) {
		return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), render(lines, resolver));
	}

	static String scopeText(Scope scope) {
		return switch (scope.type()) {
			case NETWORK -> "network";
			case SERVER -> "server " + scope.target();
			case GROUP -> "group " + scope.target();
		};
	}

	private static String pastTense(Punishment p) {
		return switch (p.type()) {
			case BAN -> p.expiresAt() == null ? "banned" : "temporarily banned";
			case WARN -> "warned";
			case KICK -> "kicked";
		};
	}
}
