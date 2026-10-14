package pl.kingdomcraft.justbans.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.service.PunishmentRequest;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;

class PunishDraftTest {

	private static final Target TARGET = new Target(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "Notch");
	private static final Actor STAFF = Actor.player(UUID.randomUUID(), "Admin");

	private static ReasonDefinition reason(PunishmentKind... kinds) {
		return new ReasonDefinition("cheats", "Cheating", Set.of(kinds), Duration.ofDays(30), List.of(), null, Map.of(), 0, true);
	}

	private static PunishDraft draft(ReasonDefinition reason) {
		return new PunishDraft(TARGET, reason, permission -> true);
	}

	@Test
	void startsOnTheFirstUsableAction() {
		final PunishDraft draft = draft(reason(PunishmentKind.BAN, PunishmentKind.TEMPBAN, PunishmentKind.WARN));

		assertEquals(ActionType.BAN, draft.action());
		assertEquals(List.of(ActionType.BAN, ActionType.TEMPBAN, ActionType.WARN), draft.usableActions());
		assertEquals(Scope.network(), draft.scope());
		assertNull(draft.duration());
		assertNull(draft.note());
		assertEquals(PunishmentKind.BAN, draft.kind());
	}

	@Test
	void cannotStartWithoutAnyUsableAction() {
		assertThrows(IllegalArgumentException.class, () -> new PunishDraft(TARGET, reason(PunishmentKind.BAN), permission -> false));
	}

	@Test
	void selectingAnActionChangesTheKind() {
		final PunishDraft draft = draft(reason(PunishmentKind.BAN, PunishmentKind.TEMPBAN, PunishmentKind.SERVER_TEMPBAN));

		draft.select(ActionType.TEMPBAN);
		assertEquals(PunishmentKind.TEMPBAN, draft.kind());

		assertTrue(draft.selectScope(Scope.server("survival")));
		assertEquals(PunishmentKind.SERVER_TEMPBAN, draft.kind());
		assertTrue(draft.isServerScoped());
	}

	@Test
	void cannotSelectAnActionTheStaffMemberLacks() {
		final PunishDraft draft = new PunishDraft(TARGET, reason(PunishmentKind.BAN, PunishmentKind.KICK), permission -> permission.equals("justbans.ban"));

		assertThrows(IllegalArgumentException.class, () -> draft.select(ActionType.KICK));
		assertEquals(ActionType.BAN, draft.action());
	}

	@Test
	void scopeIsRefusedWhenTheReasonDoesNotAllowServerBans() {
		final PunishDraft draft = draft(reason(PunishmentKind.BAN));

		assertFalse(draft.selectScope(Scope.server("survival")));
		assertEquals(Scope.network(), draft.scope());
		assertTrue(draft.selectScope(Scope.network()));
	}

	@Test
	void switchingToAnActionWithoutScopeOrDurationDropsThoseChoices() {
		final PunishDraft draft = draft(reason(PunishmentKind.TEMPBAN, PunishmentKind.SERVER_TEMPBAN, PunishmentKind.WARN));

		draft.select(ActionType.TEMPBAN);
		draft.selectScope(Scope.group("skyblock"));
		draft.setDuration(Duration.ofDays(7));
		assertEquals(Duration.ofDays(7), draft.duration());

		draft.select(ActionType.WARN);

		assertEquals(Scope.network(), draft.scope());
		assertNull(draft.duration());
		assertEquals(PunishmentKind.WARN, draft.kind());
	}

	@Test
	void durationIsOnlyKeptForTemporaryBans() {
		final PunishDraft draft = draft(reason(PunishmentKind.BAN, PunishmentKind.TEMPBAN));

		draft.setDuration(Duration.ofDays(3));
		assertNull(draft.duration(), "a permanent ban has no duration");

		draft.select(ActionType.TEMPBAN);
		draft.setDuration(Duration.ofDays(3));
		assertEquals(Duration.ofDays(3), draft.duration());

		draft.setDuration(null);
		assertNull(draft.duration(), "null means the reason decides");
	}

	@Test
	void notesAreTrimmedAndBlankMeansNone() {
		final PunishDraft draft = draft(reason(PunishmentKind.BAN));

		draft.setNote("  seen using xray  ");
		assertEquals("seen using xray", draft.note());

		draft.setNote("   ");
		assertNull(draft.note());

		draft.setNote("again");
		draft.setNote(null);
		assertNull(draft.note());
	}

	@Test
	void buildsTheRequestForANetworkTempBan() {
		final PunishDraft draft = draft(reason(PunishmentKind.TEMPBAN));
		draft.select(ActionType.TEMPBAN);
		draft.setDuration(Duration.ofHours(12));
		draft.setNote("repeat offender");

		final PunishmentRequest request = draft.toRequest(STAFF, "survival");

		assertEquals(PunishmentKind.TEMPBAN, request.kind());
		assertEquals(TARGET.uuid(), request.targetUuid());
		assertEquals("Notch", request.targetName());
		assertEquals(STAFF, request.actor());
		assertNull(request.scope());
		assertEquals("cheats", request.reasonId());
		assertNull(request.customReason());
		assertEquals(Duration.ofHours(12), request.duration());
		assertEquals("repeat offender", request.note());
		assertEquals("survival", request.originServer());
		assertFalse(request.force());
		assertFalse(request.silent());
	}

	@Test
	void buildsTheRequestForAServerBan() {
		final PunishDraft draft = draft(reason(PunishmentKind.BAN, PunishmentKind.SERVER_BAN));
		draft.selectScope(Scope.server("skyblock-1"));

		final PunishmentRequest request = draft.toRequest(STAFF, "lobby");

		assertEquals(PunishmentKind.SERVER_BAN, request.kind());
		assertEquals(Scope.server("skyblock-1"), request.scope());
		assertNull(request.duration());
	}

	@Test
	void aWarnRequestCarriesNoScopeOrDuration() {
		final PunishDraft draft = draft(reason(PunishmentKind.WARN));
		final PunishmentRequest request = draft.toRequest(STAFF, "lobby");

		assertEquals(PunishmentKind.WARN, request.kind());
		assertNull(request.scope());
		assertNull(request.duration());
	}
}
