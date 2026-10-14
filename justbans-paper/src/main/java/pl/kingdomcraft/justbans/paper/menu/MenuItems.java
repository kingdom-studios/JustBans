package pl.kingdomcraft.justbans.paper.menu;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.mineacademy.fo.menu.Menu;
import org.mineacademy.fo.menu.button.Button;
import org.mineacademy.fo.menu.model.ItemCreator;
import org.mineacademy.fo.remain.CompMaterial;

import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/** Builds menu items and buttons from {@link MenuSettings.Item}s. */
final class MenuItems {

	private MenuItems() {
	}

	/** The material by name, or the fallback if the name is unknown (a typo in menus.yml must not break a menu). */
	static CompMaterial material(String name, CompMaterial fallback) {
		if (name == null || name.isBlank())
			return fallback;

		try {
			final CompMaterial material = CompMaterial.fromString(name.trim());

			return material == null ? fallback : material;

		} catch (final Throwable ex) {
			return fallback;
		}
	}

	/**
	 * Builds the item, filling {@code {placeholders}} in the name and every lore line. A value may contain newlines;
	 * each becomes its own lore line.
	 *
	 * @param keyValues alternating placeholder names and values
	 */
	static ItemStack item(MenuSettings.Item spec, boolean glow, String... keyValues) {
		return creator(spec, glow, keyValues).makeMenuTool();
	}

	/** Same as {@link #item} but with an override for the skull owner (player heads). */
	static ItemStack head(MenuSettings.Item spec, String owner, String... keyValues) {
		return creator(spec, false, keyValues).skullOwner(owner).makeMenuTool();
	}

	private static ItemCreator creator(MenuSettings.Item spec, boolean glow, String... keyValues) {
		final List<String> lore = new ArrayList<>();

		for (final String line : spec.lore())
			for (final String part : Msg.fill(line, keyValues).split("\n", -1))
				lore.add(part);

		return ItemCreator.of(material(spec.material(), CompMaterial.PAPER)).name(Msg.fill(spec.name(), keyValues)).lore(lore).glow(glow);
	}

	/** A button that only shows an item. */
	static Button display(int slot, java.util.function.Supplier<ItemStack> item) {
		return new Button(slot) {
			@Override
			public void onClickedInMenu(Player player, Menu menu, ClickType click) {
				// decoration
			}

			@Override
			public ItemStack getItem() {
				return item.get();
			}
		};
	}

	/**
	 * A button whose item can be empty and which ignores clicks while it is. Menus register all their buttons once, but some
	 * only apply to certain choices (the duration controls exist only for temporary bans).
	 */
	static Button conditional(int slot, java.util.function.BooleanSupplier visible, java.util.function.Supplier<ItemStack> item, Click onClick) {
		return new Button(slot) {
			@Override
			public void onClickedInMenu(Player player, Menu menu, ClickType click) {
				if (visible.getAsBoolean())
					onClick.run(player, menu, click);
			}

			@Override
			public ItemStack getItem() {
				return visible.getAsBoolean() ? item.get() : ItemCreator.of(CompMaterial.AIR).make();
			}
		};
	}

	static Button button(int slot, java.util.function.Supplier<ItemStack> item, Click onClick) {
		return conditional(slot, () -> true, item, onClick);
	}

	/** A click handler. */
	@FunctionalInterface
	interface Click {
		void run(Player player, Menu menu, ClickType click);
	}

	/** Human wording of a scope, e.g. {@code network} or {@code server survival}. */
	static String scopeText(Scope scope) {
		return switch (scope.type()) {
			case NETWORK -> Localization.Punish.SCOPE_NETWORK;
			case SERVER -> Msg.fill(Localization.Punish.SCOPE_SERVER, "target", scope.target());
			case GROUP -> Msg.fill(Localization.Punish.SCOPE_GROUP, "target", scope.target());
		};
	}

	/** {@code TEMPBAN} as {@code Tempban}. */
	static String label(Enum<?> value) {
		final String name = value.name().toLowerCase();

		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}
}
