package pl.kingdomcraft.justbans.paper.command;

import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.service.RevokeRequest;
import pl.kingdomcraft.justbans.paper.Core;

/**
 * {@code /unban <player> [note...] [server:<name>|group:<name>]}: pardons the ban in exactly that scope (the network by
 * default). Works for offline players.
 */
@AutoRegister
public final class UnbanCommand extends PunishmentCommand {

	public UnbanCommand() {
		super("unban|pardon", "justbans.unban", "<player> [note...] [server:<name>|group:<name>]", "Pardon a banned player.", 1);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		final String note = parsed.join(1).isEmpty() ? null : parsed.join(1);
		final Scope scope = parsed.isScoped() ? parsed.scope() : Scope.network();

		return context -> Reports.result(context.core(), context.sender(),
				context.core().service().revokeBlocking(RevokeRequest.ban(context.actor(), context.target().uuid(), scope, note)));
	}

	@Override
	protected boolean allowsSelf() {
		return true;
	}

	@Override
	protected boolean supportsScope() {
		return true;
	}

	@Override
	protected boolean supportsSilent() {
		return false;
	}
}
