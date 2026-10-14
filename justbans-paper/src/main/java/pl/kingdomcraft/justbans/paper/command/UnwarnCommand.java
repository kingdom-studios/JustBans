package pl.kingdomcraft.justbans.paper.command;

import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.service.RevokeRequest;
import pl.kingdomcraft.justbans.paper.Core;

/** {@code /unwarn <player> [note...]}: removes the player's most recent active warn. */
@AutoRegister
public final class UnwarnCommand extends PunishmentCommand {

	public UnwarnCommand() {
		super("unwarn", "justbans.unwarn", "<player> [note...]", "Remove a player's last active warn.", 1);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		final String note = parsed.join(1).isEmpty() ? null : parsed.join(1);

		return context -> Reports.result(context.core(), context.sender(),
				context.core().service().revokeBlocking(RevokeRequest.lastWarn(context.actor(), context.target().uuid(), note)));
	}

	@Override
	protected boolean allowsSelf() {
		return true;
	}

	@Override
	protected boolean supportsSilent() {
		return false;
	}
}
