package pl.kingdomcraft.justbans.velocity.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.velocity.config.ConfigLoader;
import pl.kingdomcraft.justbans.velocity.config.ReasonsFile;
import pl.kingdomcraft.justbans.velocity.config.ReasonsLoader;

class ScreenRendererTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

	private final AtomicReference<ReasonsFile> reasons = new AtomicReference<>();
	private ScreenRenderer renderer;

	private ScreenRenderer renderer() throws Exception {
		reasons.set(ReasonsLoader.parse(pl.kingdomcraft.justbans.velocity.TestYaml.parse(new String(getClass().getClassLoader().getResourceAsStream("reasons.yml").readAllBytes())), Logger.getAnonymousLogger()));

		return new ScreenRenderer(reasons::get, ConfigLoader.parse(pl.kingdomcraft.justbans.velocity.TestYaml.parse("web-secret: 0123456789abcdef-secret\ndatabase: { name: a, user: b }\n")), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static Punishment ban(String reasonId, Instant expires) {
		return new Punishment(7, "K7Q2M9XA", PunishmentType.BAN, Scope.network(), UUID.randomUUID(), "Notch", Actor.console(), reasonId, "Cheating / hacked client", null, NOW, expires, "proxy", false, null, null);
	}

	@Test
	void banScreenUsesTheReasonsOwnOverride() throws Exception {
		renderer = renderer();

		final String text = PLAIN.serialize(renderer.banScreen(ban("cheats", NOW.plusSeconds(3 * 86_400 + 3600)), "lobby"));

		assertTrue(text.startsWith("Cheating detected\n"), text);
		assertTrue(text.contains("Expires: 17.10.2026 13:00 (3d 1h)"), text);
		assertTrue(text.contains("Appeal ID: #K7Q2M9XA"), text);
	}

	@Test
	void reasonsWithoutAnOverrideUseTheFileDefaultAndFreeTextUsesItToo() throws Exception {
		renderer = renderer();

		for (final String reasonId : new String[] { "spam", null, "since-removed" }) {
			final String text = PLAIN.serialize(renderer.banScreen(ban(reasonId, null), null));

			assertTrue(text.startsWith("You are banned from this network\n"), text);
			assertTrue(text.contains("Expires: never (never)"), text);
		}
	}

	@Test
	void aReloadIsPickedUpImmediately() throws Exception {
		renderer = renderer();
		final Punishment p = ban("spam", null);

		assertTrue(PLAIN.serialize(renderer.kickScreen(p, null)).startsWith("You were kicked"));

		reasons.set(ReasonsLoader.parse(pl.kingdomcraft.justbans.velocity.TestYaml.parse("defaults:\n  templates:\n    kick-screen: ['<red>Bye <reason>']\nreasons:\n  spam: { display: S, types: [KICK] }\n"), Logger.getAnonymousLogger()));

		assertEquals("Bye Cheating / hacked client", PLAIN.serialize(renderer.kickScreen(p, null)));
	}

	@Test
	void everyTemplateRendersForEveryType() throws Exception {
		renderer = renderer();
		final Punishment p = ban("spam", NOW.plusSeconds(60));

		assertTrue(PLAIN.serialize(renderer.serverBanMessage(p)).contains("(1m 0s)"));
		assertTrue(PLAIN.serialize(renderer.warnMessage(p, 3)).contains("(3 active)"));
		assertTrue(PLAIN.serialize(renderer.broadcast(p)).contains("Notch was temporarily banned by Console for Cheating / hacked client."));
		assertTrue(PLAIN.serialize(renderer.staffAlert(p)).contains("Console -> Notch: ban network 1m"));
	}

	@Test
	void configMessagesFillTheServerPlaceholder() throws Exception {
		renderer = renderer();

		assertEquals("You are banned from survival.", PLAIN.serialize(renderer.message("<red>You are banned from <white><server></white>.", "survival")));
		assertEquals("network", PLAIN.serialize(renderer.message("<server>", null)));
		assertTrue(PLAIN.serialize(renderer.databaseError()).contains("could not verify"));
		assertTrue(PLAIN.serialize(renderer.noFallback("x")).contains("no other server"));
	}

	@Test
	void playerControlledTextCannotInjectFormatting() throws Exception {
		renderer = renderer();
		final Punishment evil = new Punishment(1, "AAAAAAAA", PunishmentType.BAN, Scope.network(), UUID.randomUUID(), "<red>Evil", Actor.system("<bold>x"), null,
				"<click:run_command:'/op me'>hi", null, NOW, null, "proxy", false, null, null);

		final Component rendered = renderer.broadcast(evil);

		assertTrue(PLAIN.serialize(rendered).contains("<click:run_command:'/op me'>hi"));
	}
}
