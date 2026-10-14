package pl.kingdomcraft.justbans.paper.menu;

import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.settings.YamlConfig;

/**
 * menus.yml. Foundation loads it when the plugin starts and reloads it on {@code /justbans reload} (the class is a
 * singleton marked {@link AutoRegister}); every load re-parses the file into an immutable {@link MenuSettings} snapshot
 * that the menus read when they are opened.
 */
@AutoRegister
public final class MenuConfig extends YamlConfig {

	private static final MenuConfig INSTANCE = new MenuConfig();

	private volatile MenuSettings settings = MenuSettings.defaults();

	private MenuConfig() {
		this.loadConfiguration("menus.yml");
	}

	@Override
	protected void onLoadFinish() {
		// FileConfig#get throws for a key that is in neither the player's file nor the bundled one, hence the isSet checks.
		final MenuSettings.Parsed parsed = MenuSettings.parse(path -> this.isSet(path) || this.isSetDefault(path) ? this.getObject(path) : null);

		for (final String problem : parsed.problems())
			Common.warning("menus.yml: " + problem);

		if (!parsed.problems().isEmpty())
			Common.warning("menus.yml has " + parsed.problems().size() + " problem(s); the built-in menu layout is used until they are fixed.");

		this.settings = parsed.settings();
	}

	/** The current settings; never null. */
	public MenuSettings settings() {
		return this.settings;
	}

	public static MenuConfig getInstance() {
		return INSTANCE;
	}

	/** Shortcut for {@code getInstance().settings()}. */
	public static MenuSettings current() {
		return INSTANCE.settings();
	}
}
