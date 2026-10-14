package pl.kingdomcraft.justbans.velocity.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class OnlineDirectoryTest {

	@Test
	void valueIsJsonWithNameAndServer() {
		final var json = JsonParser.parseString(OnlineDirectory.value("Notch", "lobby")).getAsJsonObject();

		assertEquals("Notch", json.get("name").getAsString());
		assertEquals("lobby", json.get("server").getAsString());
	}

	@Test
	void serverIsOmittedWhileStillConnecting() {
		final var json = JsonParser.parseString(OnlineDirectory.value("Notch", null)).getAsJsonObject();

		assertEquals("Notch", json.get("name").getAsString());
		assertTrue(!json.has("server") || json.get("server").isJsonNull());
	}
}
