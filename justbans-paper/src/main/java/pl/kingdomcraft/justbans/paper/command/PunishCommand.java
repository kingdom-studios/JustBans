package pl.kingdomcraft.justbans.paper.command;

import java.util.List;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.menu.ActionRules;
import pl.kingdomcraft.justbans.paper.menu.MenuConfig;
import pl.kingdomcraft.justbans.paper.menu.PunishMenu;

/**
 * {@code /punish <player>}: opens the punishment menu. The reasons shown are filtered by the staff member's permissions on
 * the main thread; the target is resolved and their ban and warn counts are read on a background thread, and the menu is
 * opened back on the main thread once everything is ready.
 */
@AutoRegister
public final class PunishCommand extends PunishmentCommand {

	public PunishCommand() {
		super("punish|gui", "justbans.gui", "<player>", "Open the punishment menu for a player.", 1);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		this.checkConsole();

		final Player staff = this.getPlayer();
		final Predicate<String> permissions = staff::hasPermission;
		final List<ReasonDefinition> reasons = ActionRules.visibleReasons(core.reasons().all(), permissions);

		if (reasons.isEmpty())
			this.returnTell(MenuConfig.current().punish().empty());

		return context -> {
			final Core c = context.core();
			final java.time.Instant now = c.clock().instant();

			final int bans = c.database().query(connection -> c.punishments().findActiveBans(connection, context.target().uuid(), now)).size();
			final int warns = c.database().query(connection -> c.punishments().findActiveWarns(connection, context.target().uuid(), now)).size();

			Common.runLater(() -> {
				if (staff.isOnline())
					new PunishMenu(c, permissions, context.target(), reasons, bans, warns).displayTo(staff);
			});
		};
	}

	@Override
	protected boolean supportsSilent() {
		return false;
	}
}
