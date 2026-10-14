package pl.kingdomcraft.justbans.paper.command;

import java.util.List;

import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.paper.Core;

/** {@code /warn <player> <reason|text...> [-s]} */
@AutoRegister
public final class WarnCommand extends PunishmentCommand {

	public WarnCommand() {
		super("warn", "justbans.warn", "<player> <reason|text...> [-s]", "Warn a player.", 2);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		final ReasonInput reason = this.reasonFrom(core, parsed, 1);

		return context -> issue(context, PunishmentKind.WARN, reason, null);
	}

	@Override
	protected List<String> suggest(Core core, int position) {
		return position == 2 ? core.reasons().idsFor(PunishmentKind.WARN) : List.of();
	}
}
