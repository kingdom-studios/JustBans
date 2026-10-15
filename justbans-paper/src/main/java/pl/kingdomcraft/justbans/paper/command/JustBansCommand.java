package pl.kingdomcraft.justbans.paper.command;

import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.command.ReloadCommand;
import org.mineacademy.fo.command.SimpleCommandGroup;

/**
 * The {@code /justbans} (alias {@code /jb}) command group. Its labels come from {@code Command_Aliases} in settings.yml;
 * Foundation registers it as the main command automatically.
 */
@AutoRegister
public final class JustBansCommand extends SimpleCommandGroup {

	@Override
	protected void registerSubcommands() {
		this.registerSubcommand(new ReloadCommand("justbans.admin"));
		this.registerSubcommand(new EditCommand());
	}

	@Override
	protected String getCredits() {
		return "&7JustBans - network-wide punishments.";
	}
}
