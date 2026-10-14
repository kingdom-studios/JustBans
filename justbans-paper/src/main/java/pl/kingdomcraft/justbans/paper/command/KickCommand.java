package pl.kingdomcraft.justbans.paper.command;

import java.util.List;

import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.paper.Core;

/** {@code /kick <player> <reason|text...> [-s]}: removes the player from the whole network; they must be online. */
@AutoRegister
public final class KickCommand extends PunishmentCommand {

	public KickCommand() {
		super("kick", "justbans.kick", "<player> <reason|text...> [-s]", "Kick a player off the network.", 2);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		final ReasonInput reason = this.reasonFrom(core, parsed, 1);

		return context -> issue(context, PunishmentKind.KICK, reason, null);
	}

	@Override
	protected boolean requiresOnlineTarget() {
		return true;
	}

	@Override
	protected List<String> suggest(Core core, int position) {
		return position == 2 ? core.reasons().idsFor(PunishmentKind.KICK) : List.of();
	}
}
