package pl.kingdomcraft.justbans.paper.placeholder;

import java.time.Instant;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.model.SimpleExpansion;

import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.JustBans;
import pl.kingdomcraft.justbans.paper.diagnostics.Diagnostics;
import pl.kingdomcraft.justbans.paper.settings.Settings;

/**
 * PlaceholderAPI placeholders. Foundation hooks every {@link SimpleExpansion} into PlaceholderAPI under the plugin's name, so
 * these are available as:
 * <ul>
 * <li>{@code %justbans_warns%}: number of active warns;</li>
 * <li>{@code %justbans_banned_servers%}: servers or groups the player is banned from;</li>
 * <li>{@code %justbans_last_punishment%}: the most recent punishment, formatted by {@code Placeholders.Last_Punishment_Format};</li>
 * <li>{@code %justbans_active_ban_expiry%}: time left on the soonest-ending active ban.</li>
 * </ul>
 *
 * <p>PlaceholderAPI may call this on the main thread, many times per tick (scoreboards, tab lists), and Foundation interrupts a
 * placeholder that takes too long. So this only reads {@code PlayerSnapshots}, an in-memory cache that is filled in the
 * background; it never waits for the database. The expansion is a singleton shared by all callers and keeps no state.
 */
@AutoRegister
public final class JustBansExpansion extends SimpleExpansion {

	@Override
	protected String onReplace(CommandSender sender, String identifier) {
		if (!PlaceholderValues.isKnown(identifier))
			return NO_REPLACE;

		if (!(sender instanceof Player player))
			return "";

		final Core core = JustBans.core();
		final PlaceholderValues.Texts texts = new PlaceholderValues.Texts(Settings.Placeholders.NONE_TEXT, Settings.Placeholders.LOADING_TEXT, Settings.Placeholders.PERMANENT_TEXT,
				Settings.Placeholders.LAST_PUNISHMENT_FORMAT);

		if (core == null)
			return texts.loading();

		return Diagnostics.timed("JustBans placeholder " + identifier, () -> PlaceholderValues.resolve(identifier, core.snapshots().peek(player.getUniqueId()), Instant.now(core.clock()), texts));
	}
}
