package pl.kingdomcraft.justbans.paper.menu;

import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.menu.Menu;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.service.PunishmentRequest;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.command.Reports;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/**
 * Final step: shows what is about to happen, offers an optional staff note (a chat prompt) and applies the punishment
 * through {@code PunishmentService} on a background thread.
 *
 * <p>The note button starts the {@link NotePrompt} directly, exactly what Foundation's {@code ButtonConversation} does,
 * because that class has no slot parameter and the slot here comes from menus.yml.
 */
public final class ConfirmMenu extends Menu {

	private final Core core;
	private final Predicate<String> permissions;
	private final PunishDraft draft;

	public ConfirmMenu(Core core, Menu parent, Predicate<String> permissions, PunishDraft draft) {
		super(parent);

		this.core = core;
		this.permissions = permissions;
		this.draft = draft;

		final MenuSettings.Confirm config = MenuConfig.current().confirm();

		this.setTitle(Msg.fill(config.title(), "action", MenuItems.label(draft.action())));
		this.setSize(config.size());

		this.registerButton(MenuItems.display(config.summarySlot(), () -> MenuItems.item(config.summary(), false, this.placeholders())));

		this.registerButton(MenuItems.button(config.noteSlot(), () -> MenuItems.item(config.note(), draft.note() != null, this.placeholders()),
				(player, menu, click) -> new NotePrompt(draft).show(player)));

		this.registerButton(MenuItems.button(config.confirmSlot(), () -> MenuItems.item(config.confirm(), false, this.placeholders()), (player, menu, click) -> this.apply(player)));

		this.registerButton(MenuItems.button(config.cancelSlot(), () -> MenuItems.item(config.cancel(), false, this.placeholders()), (player, menu, click) -> player.closeInventory()));
	}

	private String[] placeholders() {
		return new String[] { "target", this.draft.target().name(), "reason", this.draft.reason().displayName(), "action", MenuItems.label(this.draft.action()),
				"scope", MenuItems.scopeText(this.draft.scope()), "duration", DraftText.duration(this.draft), "note", this.draft.note() == null ? "-" : this.draft.note() };
	}

	/** Closes the menu and runs the punishment off the main thread; the outcome is reported to the staff member in chat. */
	private void apply(Player player) {
		final PunishmentRequest request = this.draft.toRequest(Actor.player(player.getUniqueId(), player.getName()), this.core.serverName());

		player.closeInventory();

		Common.runAsync(() -> {
			try {
				Reports.result(this.core, player, this.core.service().issueBlocking(request));

			} catch (final Throwable t) {
				Common.error(t, "Failed to apply a punishment from the punish menu");
				Reports.error(player, Msg.fill(Localization.Errors.forCode(pl.kingdomcraft.justbans.common.service.ErrorCode.INTERNAL), "message", ""));
			}
		});
	}

	@Override
	public Menu newInstance() {
		return new ConfirmMenu(this.core, this.getParent(), this.permissions, this.draft);
	}
}
