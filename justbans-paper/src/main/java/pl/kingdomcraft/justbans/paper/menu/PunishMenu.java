package pl.kingdomcraft.justbans.paper.menu;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.mineacademy.fo.menu.Menu;
import org.mineacademy.fo.menu.MenuPagged;

import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.service.Msg;

/**
 * {@code /punish <player>}: the target's head with their active ban and warn counts, and one button per reason the staff
 * member may use (a reason is hidden unless they hold the permission for at least one action it allows). Clicking a reason
 * opens {@link ReasonActionMenu}.
 *
 * <p>Everything this menu shows is loaded before it is constructed (see {@code PunishCommand}), so opening it never touches the database.
 */
public final class PunishMenu extends MenuPagged<ReasonDefinition> {

	private final Core core;
	private final Predicate<String> permissions;
	private final Target target;
	private final List<ReasonDefinition> reasons;
	private final int bans;
	private final int warns;

	/**
	 * @param permissions the staff member's permission check; only called on the main thread
	 * @param reasons     the reasons already filtered to those the staff member may use
	 */
	public PunishMenu(Core core, Predicate<String> permissions, Target target, List<ReasonDefinition> reasons, int bans, int warns) {
		super((Menu) null, MenuConfig.current().punish().reasonSlots(), reasons);

		this.core = core;
		this.permissions = permissions;
		this.target = target;
		this.reasons = reasons;
		this.bans = bans;
		this.warns = warns;

		final MenuSettings.Punish config = MenuConfig.current().punish();

		this.setTitle(Msg.fill(config.title(), "target", target.name()));
		this.setSize(config.size());

		this.registerButton(MenuItems.display(config.headSlot(), () -> MenuItems.head(config.head(), target.name(), "target", target.name(), "bans", String.valueOf(this.bans), "warns", String.valueOf(this.warns))));
	}

	@Override
	protected ItemStack convertToItemStack(ReasonDefinition reason) {
		final MenuSettings.Punish config = MenuConfig.current().punish();

		final String actions = ActionRules.usableActions(reason, this.permissions).stream()
				.map(action -> Msg.fill(config.actionLine(), "action", MenuItems.label(action)))
				.collect(Collectors.joining("\n"));

		// The reason's own icon wins; otherwise the configured material is used.
		final MenuSettings.Item spec = new MenuSettings.Item(reason.icon() != null ? reason.icon() : config.reason().material(), config.reason().name(), config.reason().lore());

		return MenuItems.item(spec, false, "reason", reason.displayName(), "id", reason.id(), "actions", actions, "target", this.target.name());
	}

	@Override
	protected void onPageClick(Player player, ReasonDefinition reason, ClickType click) {
		final PunishDraft draft = new PunishDraft(this.target, reason, this.permissions);

		new ReasonActionMenu(this.core, this, this.permissions, draft).displayTo(player);
	}

	@Override
	public Menu newInstance() {
		return new PunishMenu(this.core, this.permissions, this.target, this.reasons, this.bans, this.warns);
	}
}
