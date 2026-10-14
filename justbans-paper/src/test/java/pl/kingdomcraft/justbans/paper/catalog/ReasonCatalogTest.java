package pl.kingdomcraft.justbans.paper.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.ServerRecord;

class ReasonCatalogTest {

	private static ReasonDefinition reason(String id, boolean enabled, PunishmentKind... kinds) {
		return new ReasonDefinition(id, id.toUpperCase(), Set.of(kinds), null, List.of(), null, Map.of(), 0, enabled);
	}

	@Test
	void startsEmpty() {
		final ReasonCatalog catalog = new ReasonCatalog();

		assertEquals(0, catalog.size());
		assertTrue(catalog.ids().isEmpty());
		assertFalse(catalog.contains("cheats"));
		assertFalse(catalog.contains(null));
	}

	@Test
	void lookupIsCaseInsensitiveAndKeepsConfiguredOrder() {
		final ReasonCatalog catalog = new ReasonCatalog();
		catalog.replace(List.of(reason("zebra", true, PunishmentKind.WARN), reason("apple", true, PunishmentKind.WARN)));

		assertEquals(List.of("zebra", "apple"), catalog.ids());
		assertTrue(catalog.contains("APPLE"));
		assertEquals("APPLE", catalog.find("Apple").orElseThrow().displayName());
	}

	@Test
	void allReturnsTheUsableReasonsInOrder() {
		final ReasonCatalog catalog = new ReasonCatalog();
		catalog.replace(List.of(reason("b", true, PunishmentKind.BAN), reason("off", false, PunishmentKind.BAN), reason("a", true, PunishmentKind.BAN)));

		assertEquals(List.of("b", "a"), catalog.all().stream().map(ReasonDefinition::id).toList());
		assertThrows(UnsupportedOperationException.class, () -> catalog.all().clear());
	}

	@Test
	void disabledReasonsAreNotUsable() {
		final ReasonCatalog catalog = new ReasonCatalog();
		catalog.replace(List.of(reason("on", true, PunishmentKind.BAN), reason("off", false, PunishmentKind.BAN)));

		assertEquals(List.of("on"), catalog.ids());
		assertFalse(catalog.contains("off"));
	}

	@Test
	void idsForFiltersByKind() {
		final ReasonCatalog catalog = new ReasonCatalog();
		catalog.replace(List.of(reason("cheats", true, PunishmentKind.BAN, PunishmentKind.TEMPBAN), reason("spam", true, PunishmentKind.WARN, PunishmentKind.KICK),
				reason("toxic", true, PunishmentKind.WARN, PunishmentKind.TEMPBAN)));

		assertEquals(List.of("cheats", "toxic"), catalog.idsFor(PunishmentKind.TEMPBAN));
		assertEquals(List.of("spam", "toxic"), catalog.idsFor(PunishmentKind.WARN));
		assertEquals(List.of("cheats"), catalog.idsFor(PunishmentKind.BAN));
		assertTrue(catalog.idsFor(PunishmentKind.SERVER_BAN).isEmpty());
	}

	@Test
	void replaceSwapsTheWholeSnapshotAtomically() {
		final ReasonCatalog catalog = new ReasonCatalog();
		catalog.replace(List.of(reason("old", true, PunishmentKind.BAN)));

		final List<String> before = catalog.ids();
		catalog.replace(List.of(reason("new", true, PunishmentKind.BAN)));

		assertEquals(List.of("old"), before, "a list handed out earlier is a stable snapshot");
		assertEquals(List.of("new"), catalog.ids());
		assertNotSame(before, catalog.ids());
	}

	@Test
	void handedOutListsAreImmutable() {
		final ReasonCatalog catalog = new ReasonCatalog();
		catalog.replace(List.of(reason("a", true, PunishmentKind.BAN)));

		assertThrows(UnsupportedOperationException.class, () -> catalog.ids().add("hack"));
		assertThrows(UnsupportedOperationException.class, () -> catalog.idsFor(PunishmentKind.BAN).add("hack"));
	}

	@Test
	void serverCatalogListsServersAndGroups() {
		final ServerCatalog catalog = new ServerCatalog();
		catalog.replace(List.of(new ServerRecord("survival", null, null, null), new ServerRecord("skyblock-2", "skyblock", null, null), new ServerRecord("skyblock-1", "skyblock", null, null),
				new ServerRecord("lobby", " ", null, null)));

		assertEquals(List.of("lobby", "skyblock-1", "skyblock-2", "survival"), catalog.servers());
		assertEquals(List.of("skyblock"), catalog.groups());
		assertTrue(catalog.hasServer("lobby"));
		assertFalse(catalog.hasServer("nowhere"));

		catalog.replace(List.of());
		assertTrue(catalog.servers().isEmpty());
	}
}
