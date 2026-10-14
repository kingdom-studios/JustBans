package pl.kingdomcraft.justbans.paper.command;

import org.bukkit.entity.Player;
import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.menu.HistoryMenu;

/** {@code /history <player>}: opens the player's punishment history. Works for offline players; staff may look at their own. */
@AutoRegister
public final class HistoryCommand extends PunishmentCommand {

	public HistoryCommand() {
		super("history|hist", "justbans.history", "<player>", "Browse a player's punishment history.", 1);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		this.checkConsole();

		final Player staff = this.getPlayer();

		return context -> HistoryMenu.open(context.core(), staff, context.target());
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
