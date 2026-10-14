package pl.kingdomcraft.justbans.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition.LadderStep;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;
import pl.kingdomcraft.justbans.common.time.DurationParser;

class DraftTextTest {

	private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("UTC"));
	private static final Target TARGET = new Target(UUID.randomUUID(), "Notch");

	private static ReasonDefinition reason(Duration def, List<LadderStep> ladder, PunishmentKind... kinds) {
		return new ReasonDefinition("r", "R", Set.of(kinds), def, ladder, null, Map.of(), 0, true);
	}

	private static Punishment p(PunishmentType type, Instant expires, boolean revoked) {
		return new Punishment(1, "ABCDEFGH", type, Scope.network(), UUID.randomUUID(), "T", Actor.console(), null, "r", null, NOW.minusSeconds(60), expires, "proxy", false, null,
				revoked ? new Punishment.Revocation(NOW, Actor.console(), null) : null);
	}

	// ---- duration text -------------------------------------------------------------------

	@Test
	void durationTextByAction() {
		final ReasonDefinition all = reason(Duration.ofDays(30), List.of(), PunishmentKind.BAN, PunishmentKind.TEMPBAN, PunishmentKind.WARN, PunishmentKind.KICK);
		final PunishDraft draft = new PunishDraft(TARGET, all, permission -> true);

		assertEquals("permanent", DraftText.duration(draft));

		draft.select(ActionType.TEMPBAN);
		assertEquals("30d (default)", DraftText.duration(draft));

		draft.setDuration(Duration.ofHours(36));
		assertEquals("1d 12h", DraftText.duration(draft));

		draft.select(ActionType.WARN);
		assertEquals("-", DraftText.duration(draft));

		draft.select(ActionType.KICK);
		assertEquals("-", DraftText.duration(draft));
	}

	@Test
	void ladderAndMissingDefaults() {
		final PunishDraft ladder = new PunishDraft(TARGET, reason(null, List.of(new LadderStep(PunishmentType.BAN, Duration.ofDays(1))), PunishmentKind.TEMPBAN), permission -> true);
		final PunishDraft none = new PunishDraft(TARGET, reason(null, List.of(), PunishmentKind.TEMPBAN), permission -> true);

		assertEquals("escalation ladder", DraftText.duration(ladder));
		assertEquals("not set", DraftText.duration(none));
	}

	// ---- draft completeness --------------------------------------------------------------

	@Test
	void aTempBanNeedsADurationFromSomewhere() {
		final PunishDraft none = new PunishDraft(TARGET, reason(null, List.of(), PunishmentKind.TEMPBAN), permission -> true);
		assertEquals(false, none.isComplete());

		none.setDuration(Duration.ofDays(2));
		assertEquals(true, none.isComplete());

		assertEquals(true, new PunishDraft(TARGET, reason(Duration.ofDays(1), List.of(), PunishmentKind.TEMPBAN), permission -> true).isComplete());
		assertEquals(true, new PunishDraft(TARGET, reason(null, List.of(new LadderStep(PunishmentType.BAN, null)), PunishmentKind.TEMPBAN), permission -> true).isComplete());
		assertEquals(true, new PunishDraft(TARGET, reason(null, List.of(), PunishmentKind.BAN), permission -> true).isComplete());
		assertEquals(true, new PunishDraft(TARGET, reason(null, List.of(), PunishmentKind.WARN), permission -> true).isComplete());
	}

	// ---- history wording -----------------------------------------------------------------

	@Test
	void typeLabels() {
		assertEquals("Ban", DraftText.typeLabel(p(PunishmentType.BAN, null, false)));
		assertEquals("Tempban", DraftText.typeLabel(p(PunishmentType.BAN, NOW.plusSeconds(60), false)));
		assertEquals("Warn", DraftText.typeLabel(p(PunishmentType.WARN, null, false)));
		assertEquals("Kick", DraftText.typeLabel(p(PunishmentType.KICK, null, false)));
	}

	@Test
	void expiryText() {
		assertEquals("never", DraftText.expires(p(PunishmentType.BAN, null, false), NOW, FORMAT, "never"));
		assertEquals("14.10.2026 13:00 (1h 0m)", DraftText.expires(p(PunishmentType.BAN, NOW.plusSeconds(3600), false), NOW, FORMAT, "never"));
		assertEquals("14.10.2026 11:00", DraftText.expires(p(PunishmentType.BAN, NOW.minusSeconds(3600), false), NOW, FORMAT, "never"), "once over, only the date");
		assertEquals("14.10.2026 13:00", DraftText.expires(p(PunishmentType.BAN, NOW.plusSeconds(3600), true), NOW, FORMAT, "never"), "a revoked ban is not counting down");
	}

	// ---- presets -------------------------------------------------------------------------

	@Test
	void presetsStartWithTheReasonsOwnDurations() {
		final ReasonDefinition cheats = reason(Duration.ofDays(30), List.of(new LadderStep(PunishmentType.BAN, Duration.ofDays(7)), new LadderStep(PunishmentType.BAN, Duration.ofDays(30)),
				new LadderStep(PunishmentType.BAN, null)), PunishmentKind.TEMPBAN);

		assertEquals(List.of(Duration.ofDays(30), Duration.ofDays(7), Duration.ofHours(1), Duration.ofHours(12), Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(14)),
				DurationPresets.forReason(cheats, 7), "30d once, then ladder 7d, then common lengths; permanent steps are skipped");
	}

	@Test
	void presetsAreCappedAndNeverPermanent() {
		final ReasonDefinition r = reason(DurationParser.PERMANENT, List.of(new LadderStep(PunishmentType.WARN, null)), PunishmentKind.TEMPBAN);

		assertEquals(3, DurationPresets.forReason(r, 3).size());
		assertEquals(List.of(), DurationPresets.forReason(r, 0));
		assertEquals(7, DurationPresets.forReason(r, 99).size());
		assertEquals(false, DurationPresets.forReason(r, 99).contains(DurationParser.PERMANENT));
	}
}
