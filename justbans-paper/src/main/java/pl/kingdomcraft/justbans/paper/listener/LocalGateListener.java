package pl.kingdomcraft.justbans.paper.listener;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.mineacademy.fo.annotation.AutoRegister;

import net.kyori.adventure.text.Component;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.render.RemainingTimeFormatter;
import pl.kingdomcraft.justbans.common.render.TemplateRenderer;
import pl.kingdomcraft.justbans.common.service.BanMatcher;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.JustBans;
import pl.kingdomcraft.justbans.paper.diagnostics.Diagnostics;
import pl.kingdomcraft.justbans.paper.settings.Settings;

/**
 * Safety net behind the proxy's login gate: if the proxy failed to enforce a ban, or a player connected around it, this
 * server still refuses a player who has an active network ban or a ban covering this server (or its group).
 *
 * <p>{@link AsyncPlayerPreLoginEvent} already runs off the main thread, so querying the database here is fine. While the
 * plugin is still starting (core not ready) logins are allowed: the proxy remains the primary gate.
 */
@AutoRegister
public final class LocalGateListener implements Listener {

	@EventHandler(priority = EventPriority.HIGH)
	public void onPreLogin(AsyncPlayerPreLoginEvent event) {
		if (!Settings.Punishments.LOCAL_GATE || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED)
			return; // disabled, or another plugin already refused this login

		final Core core = JustBans.core();

		if (core == null)
			return;

		try {
			final List<Punishment> bans = core.database().query(connection -> core.punishments().findActiveBans(connection, event.getUniqueId(), core.clock().instant()));
			final Optional<Punishment> ban = BanMatcher.banFor(bans, core.serverName(), core.serverGroup(), core.clock().instant());

			Diagnostics.debug(Diagnostics.ENFORCE, "local gate: " + event.getName() + (ban.isPresent() ? " denied by ban #" + ban.get().publicId() : " allowed") + " (" + bans.size() + " active ban(s))");

			if (ban.isPresent())
				event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(core, ban.get()));

		} catch (final SQLException | RuntimeException ex) {
			core.logGateFailure(event.getName(), ex);

			if (Settings.Punishments.DENY_ON_DATABASE_ERROR)
				event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, TemplateRenderer.renderJoined(Settings.Punishments.GATE_DATABASE_ERROR_SCREEN, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.empty()));
		}
	}

	/** The reason's own ban-screen template if the catalog has one for this punishment, else the one from settings.yml. */
	static Component banScreen(Core core, Punishment ban) {
		final List<String> lines = core.reasons().find(ban.reasonId())
				.map(reason -> reason.templates().get("ban-screen"))
				.orElse(Settings.Punishments.GATE_BAN_SCREEN);

		final TemplateRenderer.Options options = new TemplateRenderer.Options(core.dateFormat(), core.serverName(), 0, RemainingTimeFormatter.defaults(), "never");

		return TemplateRenderer.renderJoined(lines, TemplateRenderer.resolvers(ban, core.clock().instant(), options));
	}
}
