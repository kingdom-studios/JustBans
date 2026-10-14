package pl.kingdomcraft.justbans.velocity.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;

class ReasonsLoaderTest {

	private static final Logger LOGGER = Logger.getLogger("reasons-test");

	private static ReasonsFile parse(String yaml) throws Exception {
		return ReasonsLoader.parse(pl.kingdomcraft.justbans.velocity.TestYaml.parse(yaml), LOGGER);
	}

	private static String problems(String yaml) throws Exception {
		return String.join("\n", assertThrows(ConfigException.class, () -> parse(yaml)).problems());
	}

	@Test
	void shippedDefaultReasonsAreValid() throws Exception {
		final ReasonsFile file = parse(new String(getClass().getClassLoader().getResourceAsStream("reasons.yml").readAllBytes()));

		assertEquals(List.of("cheats", "spam", "toxicity", "griefing"), file.reasons().stream().map(ReasonDefinition::id).toList());
		assertEquals(TemplateNames.ALL, file.defaultTemplates().keySet());

		final ReasonDefinition cheats = file.find("cheats").orElseThrow();
		assertEquals(Duration.ofDays(30), cheats.defaultDuration());
		assertEquals("Cheating / hacked client", cheats.displayName());
		assertEquals("DIAMOND_SWORD", cheats.icon());
		assertTrue(cheats.allows(PunishmentKind.SERVER_TEMPBAN));
		assertFalse(cheats.allows(PunishmentKind.WARN));
		assertEquals(3, cheats.ladder().size());
		assertEquals(PunishmentType.BAN, cheats.ladder().get(2).type());
		assertEquals(null, cheats.ladder().get(2).duration());
		assertEquals(Duration.ofDays(7), cheats.ladder().get(0).duration());
		assertTrue(cheats.templates().containsKey(TemplateNames.BAN_SCREEN));
	}

	@Test
	void orderIsFileOrder() throws Exception {
		final ReasonsFile file = parse("""
				reasons:
				  zebra: { display: Z, types: [WARN] }
				  apple: { display: A, types: [WARN] }
				""");

		assertEquals(0, file.find("zebra").orElseThrow().sortOrder());
		assertEquals(1, file.find("apple").orElseThrow().sortOrder());
	}

	@Test
	void parsesLaddersWithMixedSteps() throws Exception {
		final ReasonDefinition r = parse("""
				reasons:
				  toxic: { display: T, types: [WARN, TEMPBAN], ladder: [WARN, kick, 1d, 7d, perm] }
				""").find("toxic").orElseThrow();

		assertEquals(List.of(PunishmentType.WARN, PunishmentType.KICK, PunishmentType.BAN, PunishmentType.BAN, PunishmentType.BAN), r.ladder().stream().map(ReasonDefinition.LadderStep::type).toList());
		assertEquals(Duration.ofDays(1), r.ladder().get(2).duration());
		assertEquals(null, r.ladder().get(4).duration());
	}

	@Test
	void typeNamesAreCaseAndDashInsensitive() throws Exception {
		final ReasonDefinition r = parse("reasons:\n  a: { display: A, types: [tempban, server-ban] }\n").find("a").orElseThrow();

		assertTrue(r.allows(PunishmentKind.TEMPBAN));
		assertTrue(r.allows(PunishmentKind.SERVER_BAN));
	}

	@Test
	void aSingleLineTemplateMayBeAPlainString() throws Exception {
		final ReasonsFile file = parse("defaults:\n  templates:\n    kick-screen: \"<red>Bye <reason>\"\nreasons:\n  a: { display: A, types: [KICK] }\n");

		assertEquals(List.of("<red>Bye <reason>"), file.defaultTemplates().get(TemplateNames.KICK_SCREEN));
	}

	@Test
	void templateLookupFallsBackReasonThenFileThenBuiltIn() throws Exception {
		final ReasonsFile file = parse("""
				defaults:
				  templates:
				    kick-screen: ["file default"]
				reasons:
				  own: { display: O, types: [KICK], templates: { kick-screen: ["own override"] } }
				  plain: { display: P, types: [KICK] }
				""");

		assertEquals(List.of("own override"), file.template("own", TemplateNames.KICK_SCREEN));
		assertEquals(List.of("file default"), file.template("plain", TemplateNames.KICK_SCREEN));
		assertEquals(List.of("file default"), file.template(null, TemplateNames.KICK_SCREEN), "free-text reasons use the defaults");
		assertEquals(List.of("file default"), file.template("removed-reason", TemplateNames.KICK_SCREEN));
		assertEquals(TemplateNames.BUILT_IN.get(TemplateNames.BAN_SCREEN), file.template("plain", TemplateNames.BAN_SCREEN));
	}

	@Test
	void everyBuiltInTemplateIsValidMiniMessageWithKnownPlaceholders() {
		final Problems problems = new Problems();

		TemplateNames.BUILT_IN.forEach((name, lines) -> lines.forEach(line -> ReasonsLoader.validateLine(line, name, problems)));

		assertTrue(problems.isEmpty(), problems.list().toString());
		assertEquals(TemplateNames.ALL, TemplateNames.BUILT_IN.keySet());
	}

	@Test
	void reportsStructuralProblemsWithPaths() throws Exception {
		final String all = problems("""
				reasons:
				  Bad Id: { display: X, types: [WARN] }
				  nodisplay: { types: [WARN] }
				  notypes: { display: X }
				  badtype: { display: X, types: [EXPLODE] }
				  baddur: { display: X, types: [TEMPBAN], duration: banana }
				  permdur: { display: X, types: [TEMPBAN], duration: perm }
				  badladder: { display: X, types: [TEMPBAN], ladder: [7d, nonsense] }
				  notasection: 5
				""");

		assertTrue(all.contains("Bad Id"), all);
		assertTrue(all.contains("reasons.nodisplay.display"), all);
		assertTrue(all.contains("reasons.notypes.types"), all);
		assertTrue(all.contains("EXPLODE"), all);
		assertTrue(all.contains("reasons.baddur.duration"), all);
		assertTrue(all.contains("reasons.permdur.duration"), all);
		assertTrue(all.contains("reasons.badladder.ladder[1]"), all);
		assertTrue(all.contains("reasons.notasection"), all);
	}

	@Test
	void requiresAtLeastOneReason() throws Exception {
		assertTrue(problems("defaults: {}\n").contains("at least one reason"));
		assertTrue(problems("reasons: {}\n").contains("at least one reason"));
	}

	@Test
	void rejectsUnknownTemplateNamesAndEmptyTemplates() throws Exception {
		final String all = problems("""
				reasons:
				  a:
				    display: A
				    types: [KICK]
				    templates:
				      kik-screen: ["typo"]
				      kick-screen: []
				""");

		assertTrue(all.contains("kik-screen") || all.contains("kick-screen"), all);
	}

	@Test
	void catchesBrokenMiniMessageAndUnknownPlaceholders() throws Exception {
		final String all = problems("""
				defaults:
				  templates:
				    ban-screen:
				      - "<red>fine <reason>"
				      - "<unknown_placeholder>"
				      - "<red>unclosed <bold"
				      - "<click:bogus>x</click>"
				reasons:
				  a: { display: A, types: [BAN] }
				""");

		assertTrue(all.contains("defaults.templates.ban-screen[1]"), all);
		assertTrue(all.contains("defaults.templates.ban-screen[2]"), all);
		assertFalse(all.contains("ban-screen[0]"), all);
	}

	@Test
	void everyDocumentedPlaceholderIsAccepted() throws Exception {
		parse("""
				defaults:
				  templates:
				    staff-alert:
				      - "<target> <actor> <reason> <id> <type> <type_past> <scope> <server> <created> <expires> <remaining> <duration> <warn_count>"
				reasons:
				  a: { display: A, types: [BAN] }
				""");
	}

	@Test
	void ignoresUnknownTopLevelKeysWithAWarning() throws Exception {
		final java.util.List<String> warnings = new java.util.ArrayList<>();
		final Logger logger = Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		logger.addHandler(new java.util.logging.Handler() {
			@Override
			public void publish(java.util.logging.LogRecord record) {
				warnings.add(record.getMessage());
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		});

		ReasonsLoader.parse(pl.kingdomcraft.justbans.velocity.TestYaml.parse("warn-escalation: []\nreasons:\n  a: { display: A, types: [BAN] }\n"), logger);

		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("warn-escalation"));
	}
}
