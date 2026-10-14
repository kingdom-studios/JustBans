package pl.kingdomcraft.justbans.paper.command;

import org.bukkit.command.CommandSender;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.Messenger;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.render.RemainingTimeFormatter;
import pl.kingdomcraft.justbans.common.service.Result;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/**
 * Sends results back to whoever ran a command. Commands finish on a background thread, so everything here hops back to
 * the main thread before touching the sender, and it never uses the command's own sender field (a command object is
 * shared by every invocation).
 */
final class Reports {

	private Reports() {
	}

	static void success(CommandSender sender, String message) {
		Common.runLater(() -> Messenger.success(sender, message));
	}

	static void error(CommandSender sender, String message) {
		Common.runLater(() -> Messenger.error(sender, message));
	}

	static void plain(CommandSender sender, String... messages) {
		Common.runLater(() -> Common.tell(sender, messages));
	}

	/** Reports a service result: the success line for the punishment, or the localized error. */
	static void result(Core core, CommandSender sender, Result<Punishment> result) {
		if (result.isOk())
			success(sender, describe(core, result.value()));
		else
			error(sender, errorText(result));
	}

	static String errorText(Result<?> result) {
		return Msg.fill(Localization.Errors.forCode(result.error()), "message", result.message());
	}

	/** The success line for a created or revoked punishment. */
	static String describe(Core core, Punishment p) {
		final String[] values = { "target", p.targetName(), "id", p.publicId(), "scope", scopeText(p), "expires", expiresText(core, p) };

		if (p.isRevoked())
			return Msg.fill(p.type() == pl.kingdomcraft.justbans.common.domain.PunishmentType.WARN ? Localization.Punish.UNWARNED : Localization.Punish.PARDONED, values);

		return switch (p.type()) {
			case BAN -> Msg.fill(Localization.Punish.BANNED, values);
			case WARN -> Msg.fill(Localization.Punish.WARNED, values);
			case KICK -> Msg.fill(Localization.Punish.KICKED, values);
		};
	}

	static String scopeText(Punishment p) {
		return switch (p.scope().type()) {
			case NETWORK -> Localization.Punish.SCOPE_NETWORK;
			case SERVER -> Msg.fill(Localization.Punish.SCOPE_SERVER, "target", p.scope().target());
			case GROUP -> Msg.fill(Localization.Punish.SCOPE_GROUP, "target", p.scope().target());
		};
	}

	static String expiresText(Core core, Punishment p) {
		if (p.expiresAt() == null)
			return Localization.Punish.EXPIRES_NEVER;

		return Msg.fill(Localization.Punish.EXPIRES_IN, "remaining", RemainingTimeFormatter.defaults().format(core.clock().instant(), p.expiresAt()));
	}
}
