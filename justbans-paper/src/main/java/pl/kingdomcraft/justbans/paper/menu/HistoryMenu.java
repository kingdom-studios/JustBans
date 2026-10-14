package pl.kingdomcraft.justbans.paper.menu;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.Messenger;
import org.mineacademy.fo.menu.Menu;
import org.mineacademy.fo.menu.MenuPagged;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.command.Reports;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/**
 * {@code /history <player>}: every punishment on record, newest first, coloured by state (active, expired, revoked, kick).
 * Clicking one opens {@link PunishmentDetailMenu}. Use {@link #open} to show it: it loads the history on a background
 * thread and opens the menu on the main thread.
 */
public final class HistoryMenu extends MenuPagged<Punishment> {

	/** How many punishments are loaded; older ones are not shown. */
	static final int MAX_ENTRIES = 300;

	private final Core core;
	private final Predicate<String> permissions;
	private final Target target;
	private final List<Punishment> history;

	public HistoryMenu(Core core, Predicate<String> permissions, Target target, List<Punishment> history) {
		super((Menu) null, MenuConfig.current().history().slots(), history);

		this.core = core;
		this.permissions = permissions;
		this.target = target;
		this.history = history;

		this.setTitle(Msg.fill(MenuConfig.current().history().title(), "target", target.name()));
		this.setSize(MenuConfig.current().history().size());
	}

	/**
	 * Loads the player's history off the main thread and opens the menu for {@code staff}. Safe to call from any thread.
	 */
	public static void open(Core core, Player staff, Target target) {
		final Predicate<String> permissions = staff::hasPermission;

		Common.runAsync(() -> {
			try {
				final List<Punishment> history = core.database().query(connection -> core.punishments().history(connection, target.uuid(), 0, MAX_ENTRIES));

				Common.runLater(() -> {
					if (!staff.isOnline())
						return;

					if (history.isEmpty())
						Messenger.info(staff, Msg.fill(MenuConfig.current().history().empty(), "target", target.name()));
					else
						new HistoryMenu(core, permissions, target, history).displayTo(staff);
				});

			} catch (final Exception ex) {
				Common.error(ex, "Could not load the punishment history of " + target.name());
				Reports.error(staff, Msg.fill(Localization.Errors.forCode(pl.kingdomcraft.justbans.common.service.ErrorCode.INTERNAL), "message", ""));
			}
		});
	}

	/** The item for one punishment; shared with the detail menu. */
	static ItemStack describe(Core core, Punishment p, Instant now, String extraLore, String hint) {
		final MenuSettings.History config = MenuConfig.current().history();

		final MenuSettings.Item spec = switch (PunishmentState.of(p, now)) {
			case ACTIVE -> config.active();
			case EXPIRED -> config.expired();
			case REVOKED -> config.revoked();
			case KICK -> config.kick();
		};

		final MenuSettings.Item withExtra = extraLore == null ? spec : new MenuSettings.Item(spec.material(), spec.name(), java.util.stream.Stream.concat(spec.lore().stream(), java.util.stream.Stream.of(extraLore)).toList());

		final String[] base = placeholders(core, p, now);
		final String[] all = java.util.Arrays.copyOf(base, base.length + 2);
		all[base.length] = "hint";
		all[base.length + 1] = hint;

		return MenuItems.item(withExtra, PunishmentState.of(p, now) == PunishmentState.ACTIVE, all);
	}

	static String[] placeholders(Core core, Punishment p, Instant now) {
		final String revokedBy = p.revocation() == null ? "-" : p.revocation().by().name();
		final String revokedAt = p.revocation() == null ? "-" : core.dateFormat().format(p.revocation().at());

		return new String[] { "id", p.publicId(), "type", DraftText.typeLabel(p), "scope", Reports.scopeText(p), "reason", p.reasonText(), "actor", p.actor().name(),
				"created", core.dateFormat().format(p.createdAt()), "expires", DraftText.expires(p, now, core.dateFormat(), Localization.Punish.EXPIRES_NEVER),
				"revoked_by", revokedBy, "revoked_at", revokedAt, "target", p.targetName(), "note", p.note() == null ? "-" : p.note() };
	}

	@Override
	protected ItemStack convertToItemStack(Punishment p) {
		return describe(this.core, p, this.core.clock().instant(), null, MenuConfig.current().history().hint());
	}

	@Override
	protected void onPageClick(Player player, Punishment p, ClickType click) {
		new PunishmentDetailMenu(this.core, this, this.permissions, this.target, p).displayTo(player);
	}

	@Override
	public Menu newInstance() {
		return new HistoryMenu(this.core, this.permissions, this.target, this.history);
	}
}
