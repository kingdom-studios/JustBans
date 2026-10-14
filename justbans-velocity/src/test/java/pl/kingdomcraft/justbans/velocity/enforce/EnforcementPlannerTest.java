package pl.kingdomcraft.justbans.velocity.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.velocity.enforce.EnforcementPlanner.TargetAction;

class EnforcementPlannerTest {

	private static final Function<String, String> GROUPS = name -> name.startsWith("skyblock") ? "skyblock" : null;

	private static Punishment p(PunishmentType type, Scope scope, boolean silent) {
		return new Punishment(1, "ABCDEFGH", type, scope, UUID.randomUUID(), "T", Actor.console(), null, "r", null, Instant.EPOCH, null, "proxy", silent, null, null);
	}

	private static TargetAction action(Punishment p, boolean online, String server) {
		return EnforcementPlanner.plan(p, online, server, GROUPS).target();
	}

	@Test
	void networkBanDisconnectsAnOnlinePlayerWherever() {
		assertEquals(TargetAction.DISCONNECT_BAN, action(p(PunishmentType.BAN, Scope.network(), false), true, "survival"));
		assertEquals(TargetAction.DISCONNECT_BAN, action(p(PunishmentType.BAN, Scope.network(), false), true, null), "even while still connecting");
	}

	@Test
	void serverBanOnlyMovesPlayersOnThatServer() {
		final Punishment ban = p(PunishmentType.BAN, Scope.server("survival"), false);

		assertEquals(TargetAction.REDIRECT, action(ban, true, "survival"));
		assertEquals(TargetAction.NONE, action(ban, true, "lobby"));
		assertEquals(TargetAction.NONE, action(ban, true, null));
	}

	@Test
	void groupBanMovesPlayersOnAnyServerOfTheGroup() {
		final Punishment ban = p(PunishmentType.BAN, Scope.group("skyblock"), false);

		assertEquals(TargetAction.REDIRECT, action(ban, true, "skyblock-1"));
		assertEquals(TargetAction.REDIRECT, action(ban, true, "skyblock-2"));
		assertEquals(TargetAction.NONE, action(ban, true, "survival"));
	}

	@Test
	void kickAndWarnReachOnlinePlayers() {
		assertEquals(TargetAction.DISCONNECT_KICK, action(p(PunishmentType.KICK, Scope.network(), false), true, "lobby"));
		assertEquals(TargetAction.WARN_MESSAGE, action(p(PunishmentType.WARN, Scope.network(), false), true, "lobby"));
	}

	@Test
	void offlineTargetsAreNeverTouched() {
		for (final PunishmentType type : PunishmentType.values())
			assertEquals(TargetAction.NONE, action(p(type, Scope.network(), false), false, null));
	}

	@Test
	void silentSuppressesTheBroadcastButNotTheStaffAlert() {
		final EnforcementPlanner.Plan loud = EnforcementPlanner.plan(p(PunishmentType.BAN, Scope.network(), false), true, "lobby", GROUPS);
		final EnforcementPlanner.Plan silent = EnforcementPlanner.plan(p(PunishmentType.BAN, Scope.network(), true), true, "lobby", GROUPS);

		assertTrue(loud.broadcast());
		assertTrue(loud.staffAlert());
		assertFalse(silent.broadcast());
		assertTrue(silent.staffAlert());
	}

	@Test
	void broadcastAndAlertHappenEvenWhenTheTargetIsOffline() {
		final EnforcementPlanner.Plan plan = EnforcementPlanner.plan(p(PunishmentType.BAN, Scope.network(), false), false, null, GROUPS);

		assertTrue(plan.broadcast());
		assertTrue(plan.staffAlert());
	}
}
