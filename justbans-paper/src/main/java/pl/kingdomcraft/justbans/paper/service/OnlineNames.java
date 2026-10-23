package pl.kingdomcraft.justbans.paper.service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import pl.kingdomcraft.justbans.common.messaging.BusException;
import pl.kingdomcraft.justbans.common.messaging.RedisBus;

/**
 * Names of everyone online on the whole network, taken from the proxy's {@code <prefix>online} Redis hash. Refreshed in
 * the background so tab completion (which runs on the main thread) only ever reads memory.
 */
public final class OnlineNames {

	private final RedisBus bus;
	private final AtomicReference<Set<String>> names = new AtomicReference<>(Set.of());

	public OnlineNames(RedisBus bus) {
		this.bus = bus;
	}

	/** Reloads from Redis. Blocking; call from a background thread. Keeps the previous list if Redis is unreachable. */
	public void refresh() {
		if (!this.bus.isConnected())
			return; // Redis is down: do not queue up connection attempts every few seconds, keep the last list

		try {
			this.names.set(parse(this.bus.hashGetAll(this.bus.channels().online()).values()));

		} catch (final BusException ignored) {
			// keep the last known names; completion is a convenience
		}
	}

	/** Current snapshot; never null, never blocks. */
	public Set<String> names() {
		return this.names.get();
	}

	/**
	 * Whether the player is online anywhere on the network right now. Blocking Redis read; call from a background thread.
	 *
	 * @return false when Redis is unreachable, so callers that need a definite answer should treat that as "unknown"
	 */
	public boolean isOnline(UUID uuid) {
		try {
			return this.bus.hashGet(this.bus.channels().online(), uuid.toString()) != null;
		} catch (final BusException ex) {
			return false;
		}
	}

	/** Extracts the {@code name} of each hash value (JSON {@code {"name","server"}}); malformed values are skipped. */
	static Set<String> parse(Iterable<String> values) {
		final Set<String> result = new HashSet<>();

		for (final String value : values)
			try {
				final JsonElement element = JsonParser.parseString(value);

				if (element.isJsonObject()) {
					final JsonObject json = element.getAsJsonObject();

					if (json.has("name") && json.get("name").isJsonPrimitive())
						result.add(json.get("name").getAsString());
				}
			} catch (final JsonParseException ignored) {
				// skip garbage
			}

		return Set.copyOf(result);
	}
}
