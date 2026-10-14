package pl.kingdomcraft.justbans.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;

class ActionRulesTest {

	private static ReasonDefinition reason(String id, PunishmentKind... kinds) {
		return new ReasonDefinition(id, id, Set.of(kinds), null, List.of(), null, Map.of(), 0, true);
	}

	private static Predicate<String> perms(String... granted) {
		return Set.of(granted)::contains;
	}

	@Test
	void usableActionsRequireBothTheReasonAndThePermission() {
		final ReasonDefinition cheats = reason("cheats", PunishmentKind.BAN, PunishmentKind.TEMPBAN, PunishmentKind.KICK);

		assertEquals(List.of(ActionType.BAN, ActionType.TEMPBAN, ActionType.KICK), ActionRules.usableActions(cheats, perms("justbans.ban", "justbans.tempban", "justbans.kick", "justbans.warn")));
		assertEquals(List.of(ActionType.TEMPBAN), ActionRules.usableActions(cheats, perms("justbans.tempban")), "a permission for an action the reason does not allow adds nothing");
		assertEquals(List.of(), ActionRules.usableActions(cheats, perms("justbans.warn")));
		assertEquals(List.of(), ActionRules.usableActions(cheats, perms()));
	}

	@Test
	void aBanPermissionIsEnoughToSeeAReasonThatAllowsBans() {
		final ReasonDefinition cheats = reason("cheats", PunishmentKind.BAN);

		assertTrue(ActionRules.isVisible(cheats, perms("justbans.ban")));
		assertFalse(ActionRules.isVisible(cheats, perms("justbans.warn")));
	}

	@Test
	void serverOnlyReasonsAreNotUsableAsNetworkActions() {
		final ReasonDefinition serverOnly = reason("grief", PunishmentKind.SERVER_BAN);

		assertTrue(ActionRules.usableActions(serverOnly, perms("justbans.ban")).isEmpty(), "ActionType.BAN is the network-wide kind; a reason allowing only SERVER_BAN is not offered");
	}

	@Test
	void visibleReasonsKeepOrderAndFilter() {
		final List<ReasonDefinition> all = List.of(reason("a", PunishmentKind.BAN), reason("b", PunishmentKind.WARN), reason("c", PunishmentKind.WARN, PunishmentKind.BAN), reason("d", PunishmentKind.KICK));

		assertEquals(List.of("b", "c"), ActionRules.visibleReasons(all, perms("justbans.warn")).stream().map(ReasonDefinition::id).toList());
		assertEquals(List.of("a", "c"), ActionRules.visibleReasons(all, perms("justbans.ban")).stream().map(ReasonDefinition::id).toList());
		assertEquals(List.of("a", "b", "c", "d"), ActionRules.visibleReasons(all, p -> true).stream().map(ReasonDefinition::id).toList());
		assertTrue(ActionRules.visibleReasons(all, p -> false).isEmpty());
	}

	@Test
	void actionKindsMapToTheServiceKinds() {
		assertEquals(PunishmentKind.BAN, ActionType.BAN.kind(false));
		assertEquals(PunishmentKind.SERVER_BAN, ActionType.BAN.kind(true));
		assertEquals(PunishmentKind.TEMPBAN, ActionType.TEMPBAN.kind(false));
		assertEquals(PunishmentKind.SERVER_TEMPBAN, ActionType.TEMPBAN.kind(true));
		assertEquals(PunishmentKind.WARN, ActionType.WARN.kind(true), "warns and kicks ignore scope");
		assertEquals(PunishmentKind.KICK, ActionType.KICK.kind(false));
	}

	@Test
	void scopeNeedsTheReasonToAllowTheServerKind() {
		final ReasonDefinition networkOnly = reason("x", PunishmentKind.BAN);
		final ReasonDefinition both = reason("y", PunishmentKind.BAN, PunishmentKind.SERVER_BAN);

		assertFalse(ActionType.BAN.allowsScopeFor(networkOnly));
		assertTrue(ActionType.BAN.allowsScopeFor(both));
		assertFalse(ActionType.WARN.allowsScopeFor(reason("w", PunishmentKind.WARN, PunishmentKind.SERVER_BAN)), "warns are never scoped");
	}

	@Test
	void durationAndScopeFlags() {
		assertTrue(ActionType.TEMPBAN.needsDuration());
		assertFalse(ActionType.BAN.needsDuration());
		assertTrue(ActionType.BAN.isScopeable());
		assertFalse(ActionType.KICK.isScopeable());
	}

	@Test
	void revokePermissions() {
		assertEquals("justbans.unban", ActionRules.revokePermission(PunishmentType.BAN));
		assertEquals("justbans.unwarn", ActionRules.revokePermission(PunishmentType.WARN));
		assertNull(ActionRules.revokePermission(PunishmentType.KICK));
	}

	@Test
	void everyActionPermissionMatchesTheCommandPermission() {
		assertEquals("justbans.ban", ActionType.BAN.permission());
		assertEquals("justbans.tempban", ActionType.TEMPBAN.permission());
		assertEquals("justbans.warn", ActionType.WARN.permission());
		assertEquals("justbans.kick", ActionType.KICK.permission());
	}
}
