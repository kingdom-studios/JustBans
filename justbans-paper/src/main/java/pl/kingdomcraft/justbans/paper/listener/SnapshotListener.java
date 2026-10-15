package pl.kingdomcraft.justbans.paper.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.JustBans;

/** Loads a player's punishments into the placeholder cache when they join and drops them when they leave. */
@AutoRegister
public final class SnapshotListener implements Listener {

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		final Core core = JustBans.core();

		if (core != null)
			core.snapshots().refresh(event.getPlayer().getUniqueId()); // asynchronous, returns immediately
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		final Core core = JustBans.core();

		if (core != null)
			core.snapshots().evict(event.getPlayer().getUniqueId());
	}
}
