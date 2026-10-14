package pl.kingdomcraft.justbans.paper.command;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/** {@code /check <player>}: lists the player's active bans and warns. Works for offline players. */
@AutoRegister
public final class CheckCommand extends PunishmentCommand {

	public CheckCommand() {
		super("check|punishments", "justbans.check", "<player>", "Show a player's active bans and warns.", 1);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		return context -> {
			final Core c = context.core();
			final Instant now = c.clock().instant();

			final List<Punishment> bans = c.database().query(connection -> c.punishments().findActiveBans(connection, context.target().uuid(), now));
			final List<Punishment> warns = c.database().query(connection -> c.punishments().findActiveWarns(connection, context.target().uuid(), now));

			final List<String> lines = new ArrayList<>();
			lines.add(Msg.fill(Localization.Check.HEADER, "target", context.target().name()));

			if (bans.isEmpty() && warns.isEmpty())
				lines.add(Localization.Check.NONE);

			for (final Punishment ban : bans)
				lines.add(line(Localization.Check.BAN_LINE, c, ban));

			for (final Punishment warn : warns)
				lines.add(line(Localization.Check.WARN_LINE, c, warn));

			if (!warns.isEmpty())
				lines.add(Msg.fill(Localization.Check.WARN_TOTAL, "count", Integer.toString(warns.size())));

			Reports.plain(context.sender(), lines.toArray(new String[0]));
		};
	}

	private static String line(String template, Core core, Punishment p) {
		return Msg.fill(template, "id", p.publicId(), "scope", Reports.scopeText(p), "reason", p.reasonText(), "actor", p.actor().name(), "expires", Reports.expiresText(core, p));
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
