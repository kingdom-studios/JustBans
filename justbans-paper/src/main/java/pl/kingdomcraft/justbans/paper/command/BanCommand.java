package pl.kingdomcraft.justbans.paper.command;

import java.util.List;

import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.paper.Core;

/** {@code /ban <player> <reason|text...> [-s] [-f] [server:<name>|group:<name>]} */
@AutoRegister
public final class BanCommand extends PunishmentCommand {

	public BanCommand() {
		super("ban", "justbans.ban", "<player> <reason|text...> [-s] [-f] [server:<name>|group:<name>]", "Permanently ban a player from the network or one server.", 2);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		final ReasonInput reason = this.reasonFrom(core, parsed, 1);
		final PunishmentKind kind = parsed.isScoped() ? PunishmentKind.SERVER_BAN : PunishmentKind.BAN;

		return context -> issue(context, kind, reason, null);
	}

	@Override
	protected boolean supportsScope() {
		return true;
	}

	@Override
	protected boolean supportsForce() {
		return true;
	}

	@Override
	protected List<String> suggest(Core core, int position) {
		return position == 2 ? core.reasons().idsFor(PunishmentKind.BAN) : List.of();
	}
}
