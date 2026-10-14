package pl.kingdomcraft.justbans.paper.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.mineacademy.fo.Messenger;
import org.mineacademy.fo.menu.Menu;
import org.mineacademy.fo.menu.MenuPagged;

import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ServerRecord;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.service.Msg;

/** A paged list of the network's servers and server groups, read from {@code jb_servers}. Picking one limits the ban to it and returns to the previous menu. */
public final class ServerPickMenu extends MenuPagged<ServerPickMenu.Choice> {

	/** One entry: a single server, or all servers of a group. */
	public record Choice(boolean group, String name) {

		public Scope scope() {
			return this.group ? Scope.group(this.name) : Scope.server(this.name);
		}
	}

	private final Core core;
	private final PunishDraft draft;
	private final List<Choice> choices;

	public ServerPickMenu(Core core, Menu parent, PunishDraft draft, List<Choice> choices) {
		super(parent, MenuConfig.current().servers().slots(), choices);

		this.core = core;
		this.draft = draft;
		this.choices = choices;

		this.setTitle(MenuConfig.current().servers().title());
		this.setSize(MenuConfig.current().servers().size());
	}

	/** Servers first (alphabetical), then each distinct group. */
	public static List<Choice> choicesFrom(List<ServerRecord> servers) {
		final List<Choice> choices = new ArrayList<>();

		servers.stream().map(ServerRecord::id).sorted(Comparator.naturalOrder()).forEach(id -> choices.add(new Choice(false, id)));

		final TreeSet<String> groups = new TreeSet<>();

		for (final ServerRecord server : servers)
			if (server.groupName() != null && !server.groupName().isBlank())
				groups.add(server.groupName());

		groups.forEach(group -> choices.add(new Choice(true, group)));

		return choices;
	}

	@Override
	protected ItemStack convertToItemStack(Choice choice) {
		final MenuSettings.Servers config = MenuConfig.current().servers();

		return MenuItems.item(choice.group() ? config.group() : config.server(), choice.scope().equals(this.draft.scope()), "name", choice.name());
	}

	@Override
	protected void onPageClick(Player player, Choice choice, ClickType click) {
		if (!this.draft.selectScope(choice.scope())) {
			Messenger.error(player, Msg.fill("This reason cannot be used for a {scope} ban.", "scope", choice.group() ? "group" : "server"));
			return;
		}

		this.getParent().displayTo(player);
	}

	@Override
	public Menu newInstance() {
		return new ServerPickMenu(this.core, this.getParent(), this.draft, this.choices);
	}
}
