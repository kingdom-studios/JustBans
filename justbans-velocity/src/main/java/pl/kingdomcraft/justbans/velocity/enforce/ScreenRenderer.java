package pl.kingdomcraft.justbans.velocity.enforce;

import java.time.Clock;
import java.util.function.Supplier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.render.RemainingTimeFormatter;
import pl.kingdomcraft.justbans.common.render.TemplateRenderer;
import pl.kingdomcraft.justbans.velocity.config.PluginConfig;
import pl.kingdomcraft.justbans.velocity.config.ReasonsFile;
import pl.kingdomcraft.justbans.velocity.config.TemplateNames;

/**
 * Builds every player- and staff-facing component from the current reasons.yml. The reasons are read through a
 * supplier on each call, so a reload takes effect immediately without recreating anything.
 */
public final class ScreenRenderer {

	private static final MiniMessage MINI = MiniMessage.miniMessage();

	private final Supplier<ReasonsFile> reasons;
	private final PluginConfig config;
	private final Clock clock;

	public ScreenRenderer(Supplier<ReasonsFile> reasons, PluginConfig config, Clock clock) {
		this.reasons = reasons;
		this.config = config;
		this.clock = clock;
	}

	/** Network ban screen shown on the disconnect/login-denied screen. */
	public Component banScreen(Punishment p, String currentServer) {
		return this.render(TemplateNames.BAN_SCREEN, p, currentServer, 0);
	}

	/** Chat message for a server or group ban. */
	public Component serverBanMessage(Punishment p) {
		return this.render(TemplateNames.SERVER_BAN_MESSAGE, p, null, 0);
	}

	public Component kickScreen(Punishment p, String currentServer) {
		return this.render(TemplateNames.KICK_SCREEN, p, currentServer, 0);
	}

	public Component warnMessage(Punishment p, int activeWarns) {
		return this.render(TemplateNames.WARN_MESSAGE, p, null, activeWarns);
	}

	public Component broadcast(Punishment p) {
		return this.render(TemplateNames.BROADCAST, p, null, 0);
	}

	public Component staffAlert(Punishment p) {
		return this.render(TemplateNames.STAFF_ALERT, p, null, 0);
	}

	/** Renders a free-standing MiniMessage string from config.yml with {@code <server>} filled in. */
	public Component message(String template, String server) {
		return MINI.deserialize(template, Placeholder.unparsed("server", server == null ? "network" : server));
	}

	public Component databaseError() {
		return this.message(this.config.messages().databaseError(), null);
	}

	public Component noFallback(String server) {
		return this.message(this.config.messages().noFallback(), server);
	}

	private Component render(String template, Punishment p, String server, int warns) {
		final TemplateRenderer.Options options = new TemplateRenderer.Options(this.config.dateFormat(), server, warns, RemainingTimeFormatter.defaults(), "never");
		final net.kyori.adventure.text.minimessage.tag.resolver.TagResolver resolver = TemplateRenderer.resolvers(p, this.clock.instant(), options);

		return TemplateRenderer.renderJoined(this.reasons.get().template(p.reasonId(), template), resolver);
	}
}
