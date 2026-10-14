package pl.kingdomcraft.justbans.common.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;

class TemplateRendererTest {

	private static final Instant CREATED = Instant.parse("2026-10-14T12:00:00Z");
	private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

	private static Punishment punishment(PunishmentType type, Scope scope, Instant expires, String reason, String actorName) {
		return new Punishment(7, "K7Q2M9XA", type, scope, UUID.randomUUID(), "Notch", Actor.console().name().equals(actorName) ? Actor.console() : Actor.system(actorName),
				"cheats", reason, "secret staff note", CREATED, expires, "proxy", false, null, null);
	}

	private static String plain(String template, Punishment p, Instant now) {
		return PLAIN.serialize(TemplateRenderer.renderJoined(List.of(template), TemplateRenderer.resolvers(p, now, TemplateRenderer.Options.defaults())));
	}

	@Test
	void fillsEveryDocumentedPlaceholderForATempBan() {
		final Punishment p = punishment(PunishmentType.BAN, Scope.network(), CREATED.plus(Duration.ofDays(30)), "Cheating", "Admin");
		final Instant now = CREATED.plus(Duration.ofDays(1));

		assertEquals("Notch", plain("<target>", p, now));
		assertEquals("Admin", plain("<actor>", p, now));
		assertEquals("Cheating", plain("<reason>", p, now));
		assertEquals("K7Q2M9XA", plain("<id>", p, now));
		assertEquals("ban", plain("<type>", p, now));
		assertEquals("temporarily banned", plain("<type_past>", p, now));
		assertEquals("network", plain("<scope>", p, now));
		assertEquals("14.10.2026 12:00", plain("<created>", p, now));
		assertEquals("13.11.2026 12:00", plain("<expires>", p, now));
		assertEquals("29d 0h", plain("<remaining>", p, now));
		assertEquals("30d", plain("<duration>", p, now));
		assertEquals("0", plain("<warn_count>", p, now));
	}

	@Test
	void permanentBansReadAsNever() {
		final Punishment p = punishment(PunishmentType.BAN, Scope.network(), null, "Cheating", "Admin");

		assertEquals("banned", plain("<type_past>", p, CREATED));
		assertEquals("never", plain("<expires>", p, CREATED));
		assertEquals("never", plain("<remaining>", p, CREATED));
		assertEquals("never", plain("<duration>", p, CREATED));
	}

	@Test
	void serverScopesExposeTheServer() {
		final Punishment server = punishment(PunishmentType.BAN, Scope.server("skyblock-1"), null, "x", "Admin");
		final Punishment group = punishment(PunishmentType.BAN, Scope.group("skyblock"), null, "x", "Admin");

		assertEquals("server skyblock-1", plain("<scope>", server, CREATED));
		assertEquals("skyblock-1", plain("<server>", server, CREATED));
		assertEquals("group skyblock", plain("<scope>", group, CREATED));
		assertEquals("skyblock", plain("<server>", group, CREATED));
	}

	@Test
	void networkBansUseTheCurrentServerWhenKnown() {
		final Punishment p = punishment(PunishmentType.BAN, Scope.network(), null, "x", "Admin");
		final TemplateRenderer.Options options = TemplateRenderer.Options.defaults().withServer("lobby").withWarnCount(2);

		assertEquals("lobby", PLAIN.serialize(TemplateRenderer.renderJoined(List.of("<server>"), TemplateRenderer.resolvers(p, CREATED, options))));
		assertEquals("network", plain("<server>", p, CREATED));
		assertEquals("2", PLAIN.serialize(TemplateRenderer.renderJoined(List.of("<warn_count>"), TemplateRenderer.resolvers(p, CREATED, options))));
	}

	@Test
	void warnAndKickWording() {
		assertEquals("warned", plain("<type_past>", punishment(PunishmentType.WARN, Scope.network(), null, "x", "Admin"), CREATED));
		assertEquals("kicked", plain("<type_past>", punishment(PunishmentType.KICK, Scope.network(), null, "x", "Admin"), CREATED));
	}

	@Test
	void userSuppliedTextCannotInjectMiniMessageTags() {
		final String evil = "<click:run_command:'/op me'><red>hello</red></click> <rainbow>";
		final Punishment p = punishment(PunishmentType.BAN, Scope.network(), null, evil, "<bold>Admin");

		final Component rendered = TemplateRenderer.renderJoined(List.of("Reason: <reason> by <actor>"), TemplateRenderer.resolvers(p, CREATED, TemplateRenderer.Options.defaults()));

		assertEquals("Reason: " + evil + " by <bold>Admin", PLAIN.serialize(rendered));
		assertTrue(hasNoClickEvents(rendered));
		assertFalse(rendered.children().stream().anyMatch(c -> c.color() != null));
	}

	@Test
	void templateOwnFormattingStillWorks() {
		final Punishment p = punishment(PunishmentType.BAN, Scope.network(), null, "Griefing", "Admin");

		final Component rendered = TemplateRenderer.renderJoined(List.of("<red>Banned</red> for <reason>"), TemplateRenderer.resolvers(p, CREATED, TemplateRenderer.Options.defaults()));

		assertEquals("Banned for Griefing", PLAIN.serialize(rendered));
		assertEquals(NamedTextColor.RED, rendered.children().get(0).color());
	}

	@Test
	void linesAreRenderedAndJoinedWithNewlines() {
		final Punishment p = punishment(PunishmentType.BAN, Scope.network(), null, "Griefing", "Admin");
		final var resolver = TemplateRenderer.resolvers(p, CREATED, TemplateRenderer.Options.defaults());
		final List<String> lines = List.of("<red>You are banned", "", "<gray>Reason: <reason>");

		assertEquals(3, TemplateRenderer.render(lines, resolver).size());
		assertEquals("You are banned\n\nReason: Griefing", PLAIN.serialize(TemplateRenderer.renderJoined(lines, resolver)));
	}

	@Test
	void staffNoteIsNeverExposed() {
		final Punishment p = punishment(PunishmentType.BAN, Scope.network(), null, "x", "Admin");

		assertEquals("<note>", plain("<note>", p, CREATED).replace("\\", ""), "no <note> placeholder exists, so the tag is left as text or dropped but never filled");
		assertFalse(plain("<note>", p, CREATED).contains("secret"));
	}

	private static boolean hasNoClickEvents(Component component) {
		final ClickEvent click = component.clickEvent();

		return click == null && component.children().stream().allMatch(TemplateRendererTest::hasNoClickEvents);
	}
}
