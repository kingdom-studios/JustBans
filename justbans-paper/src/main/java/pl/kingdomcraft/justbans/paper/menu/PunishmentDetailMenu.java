package pl.kingdomcraft.justbans.paper.menu;

import java.time.Instant;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.menu.Menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.service.RevokeRequest;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.command.Reports;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/**
 * One punishment in detail, with two buttons: <b>Revoke</b> (shift-click, only for active bans and warns, and only for staff
 * with the matching {@code justbans.unban} / {@code justbans.unwarn} permission) and <b>Copy ID</b>, which sends a chat
 * message the staff member can click to copy the punishment id.
 */
public final class PunishmentDetailMenu extends Menu {

	private final Core core;
	private final Predicate<String> permissions;
	private final Target target;
	private final Punishment punishment;

	public PunishmentDetailMenu(Core core, Menu parent, Predicate<String> permissions, Target target, Punishment punishment) {
		super(parent);

		this.core = core;
		this.permissions = permissions;
		this.target = target;
		this.punishment = punishment;

		final MenuSettings.Detail config = MenuConfig.current().detail();

		this.setTitle(Msg.fill(config.title(), "id", punishment.publicId()));
		this.setSize(config.size());

		this.registerButton(MenuItems.display(config.infoSlot(), () -> {
			final Instant now = core.clock().instant();
			final String note = punishment.note() == null ? null : Msg.fill(config.noteLine(), "note", punishment.note());

			return HistoryMenu.describe(core, punishment, now, note, "");
		}));

		this.registerButton(MenuItems.conditional(config.revokeSlot(), this::canRevoke, () -> MenuItems.item(config.revoke(), false, HistoryMenu.placeholders(core, punishment, core.clock().instant())),
				(player, menu, click) -> {
					if (click.isShiftClick())
						this.revoke(player);
				}));

		this.registerButton(MenuItems.button(config.copySlot(), () -> MenuItems.item(config.copy(), false, HistoryMenu.placeholders(core, punishment, core.clock().instant())),
				(player, menu, click) -> this.copyId(player)));
	}

	/** Active bans and warns only, and only for staff who hold the matching permission. Kicks cannot be revoked. */
	private boolean canRevoke() {
		final String permission = ActionRules.revokePermission(this.punishment.type());

		return permission != null && this.permissions.test(permission) && PunishmentState.of(this.punishment, this.core.clock().instant()) == PunishmentState.ACTIVE;
	}

	private void revoke(Player staff) {
		final RevokeRequest request = RevokeRequest.byId(Actor.player(staff.getUniqueId(), staff.getName()), this.punishment.id(), null);

		staff.closeInventory();

		Common.runAsync(() -> {
			try {
				Reports.result(this.core, staff, this.core.service().revokeBlocking(request));
				HistoryMenu.open(this.core, staff, this.target); // show the updated history

			} catch (final Throwable t) {
				Common.error(t, "Failed to revoke punishment " + this.punishment.publicId());
				Reports.error(staff, Msg.fill(Localization.Errors.forCode(pl.kingdomcraft.justbans.common.service.ErrorCode.INTERNAL), "message", ""));
			}
		});
	}

	/** Chat cannot be written to the clipboard directly; a clickable message can. */
	private void copyId(Player staff) {
		final String id = this.punishment.publicId();
		final String line = Msg.fill(MenuConfig.current().detail().copyMessage(), "id", id);

		final Component message = LegacyComponentSerializer.legacyAmpersand().deserialize(line)
				.clickEvent(ClickEvent.copyToClipboard(id))
				.hoverEvent(HoverEvent.showText(Component.text("Click to copy " + id, NamedTextColor.GRAY)));

		staff.closeInventory();
		staff.sendMessage(message);
	}

	@Override
	public Menu newInstance() {
		return new PunishmentDetailMenu(this.core, this.getParent(), this.permissions, this.target, this.punishment);
	}
}
