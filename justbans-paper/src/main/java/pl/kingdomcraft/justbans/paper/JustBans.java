package pl.kingdomcraft.justbans.paper;

import org.mineacademy.fo.plugin.SimplePlugin;

/**
 * Paper-side bridge: staff commands, menus and local enforcement, built on Foundation.
 * Persistence and messaging come from justbans-common.
 */
public final class JustBans extends SimplePlugin {

	@Override
	protected void onPluginStart() {
	}

	@Override
	protected void onReloadablesStart() {
	}

	@Override
	protected void onPluginPreReload() {
	}

	public static JustBans getInstance() {
		return (JustBans) SimplePlugin.getInstance();
	}
}
