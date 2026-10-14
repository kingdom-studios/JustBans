package pl.kingdomcraft.justbans.velocity.track;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;

import pl.kingdomcraft.justbans.common.messaging.BusException;
import pl.kingdomcraft.justbans.common.messaging.RedisBus;

/**
 * The Redis hash {@code <prefix>online}: {@code uuid -> {"name","server"}}. Used by Paper servers for network-wide
 * tab completion and by the web panel's "online now" view. It is purely informational: failures are logged and never
 * affect a player.
 */
public final class OnlineDirectory {

	/** @param server where the player is, or null while they are still connecting */
	public record Entry(String name, String server) {
	}

	private final RedisBus bus;
	private final Logger logger;

	public OnlineDirectory(RedisBus bus, Logger logger) {
		this.bus = bus;
		this.logger = logger;
	}

	/** The JSON stored as the hash value. */
	public static String value(String name, String server) {
		final JsonObject json = new JsonObject();

		json.addProperty("name", name);
		json.addProperty("server", server);

		return json.toString();
	}

	public void set(UUID uuid, String name, String server) {
		this.guard(() -> this.bus.hashPut(this.bus.channels().online(), uuid.toString(), value(name, server)));
	}

	public void remove(UUID uuid) {
		this.guard(() -> this.bus.hashRemove(this.bus.channels().online(), uuid.toString()));
	}

	/** Replaces the whole directory, e.g. at startup, so entries left behind by a crash disappear. */
	public void rebuild(Map<UUID, Entry> online) {
		final Map<String, String> values = new HashMap<>();

		online.forEach((uuid, entry) -> values.put(uuid.toString(), value(entry.name(), entry.server())));

		this.guard(() -> this.bus.hashReplace(this.bus.channels().online(), values));
	}

	private void guard(Runnable action) {
		try {
			action.run();
		} catch (final BusException ex) {
			this.logger.log(Level.FINE, "Online directory not updated: " + ex.getMessage());
		}
	}
}
