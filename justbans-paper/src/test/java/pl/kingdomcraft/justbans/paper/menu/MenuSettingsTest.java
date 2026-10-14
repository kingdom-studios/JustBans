package pl.kingdomcraft.justbans.paper.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MenuSettingsTest {

	private static MenuSettings.Parsed parse(Map<String, Object> values) {
		return MenuSettings.parse(values::get);
	}

	@Test
	void builtInDefaultsAreValid() {
		final MenuSettings.Parsed parsed = parse(Map.of());

		assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());
		assertEquals(54, parsed.settings().punish().size());
		assertEquals(4, parsed.settings().punish().headSlot());
		assertEquals(14, parsed.settings().punish().reasonSlots().size());
		assertEquals("PLAYER_HEAD", parsed.settings().punish().head().material());
		assertEquals(27, parsed.settings().confirm().size());
	}

	@Test
	void defaultsFactoryMatchesAnEmptyConfig() {
		assertEquals(MenuSettings.defaults().punish(), parse(Map.of()).settings().punish());
		assertEquals(MenuSettings.defaults().action(), parse(Map.of()).settings().action());
	}

	@Test
	void everyDefaultMenuUsesEachSlotOnce() {
		final MenuSettings.Action a = MenuSettings.defaults().action();
		final Set<Integer> slots = new HashSet<>(List.of(a.summarySlot(), a.banSlot(), a.tempbanSlot(), a.warnSlot(), a.kickSlot(), a.scopeNetworkSlot(), a.scopeServerSlot(), a.durationDisplaySlot(),
				a.daysMinusSlot(), a.daysPlusSlot(), a.hoursMinusSlot(), a.hoursPlusSlot(), a.quantitySlot(), a.continueSlot()));
		slots.addAll(a.presetSlots());

		assertEquals(14 + a.presetSlots().size(), slots.size());
	}

	@Test
	void readsCustomValues() {
		final MenuSettings.Parsed parsed = parse(Map.of(
				"Punish.Title", "Custom {target}",
				"Punish.Size", 45,
				"Punish.Head_Slot", "13",
				"Punish.Reason_Slots", List.of(19, 20, 21),
				"Punish.Reason.Material", "DIAMOND",
				"Punish.Reason.Name", "&b{reason}",
				"Punish.Reason.Lore", List.of("one", "two"),
				"Punish.Empty_Message", "nothing"));

		assertTrue(parsed.problems().isEmpty(), parsed.problems().toString());

		final MenuSettings.Punish punish = parsed.settings().punish();
		assertEquals("Custom {target}", punish.title());
		assertEquals(45, punish.size());
		assertEquals(13, punish.headSlot());
		assertEquals(List.of(19, 20, 21), punish.reasonSlots());
		assertEquals("DIAMOND", punish.reason().material());
		assertEquals(List.of("one", "two"), punish.reason().lore());
		assertEquals("nothing", punish.empty());
	}

	@Test
	void aSingleStringIsAOneLineLore() {
		final MenuSettings.Parsed parsed = parse(Map.of("Punish.Reason.Lore", "just one line"));

		assertEquals(List.of("just one line"), parsed.settings().punish().reason().lore());
	}

	@Test
	void anEmptyLoreListIsNotShownAsALoreLine() {
		assertEquals(List.of(), parse(Map.of("Action.Days_Minus.Lore", "[]")).settings().action().daysMinus().lore());
		assertEquals(List.of(), parse(Map.of("Action.Days_Minus.Lore", List.of())).settings().action().daysMinus().lore());
	}

	@Test
	void slotsOutsideTheInventoryAreReportedAndDefaultsUsed() {
		final MenuSettings.Parsed parsed = parse(Map.of("Punish.Size", 27, "Punish.Head_Slot", 40));

		assertTrue(parsed.problems().stream().anyMatch(problem -> problem.contains("Punish.Head_Slot") && problem.contains("outside")), parsed.problems().toString());
		assertEquals(54, parsed.settings().punish().size(), "a faulty file falls back to the complete defaults");
		assertEquals(4, parsed.settings().punish().headSlot());
	}

	@Test
	void duplicateSlotsAreReported() {
		final MenuSettings.Parsed parsed = parse(Map.of("Action.Ban_Slot", 20));

		assertTrue(parsed.problems().stream().anyMatch(problem -> problem.contains("slot 20 is used twice")), parsed.problems().toString());
	}

	@Test
	void theReturnButtonSlotIsReserved() {
		final MenuSettings.Parsed parsed = parse(Map.of("Action.Continue_Slot", 53));

		assertTrue(parsed.problems().stream().anyMatch(problem -> problem.contains("slot 53 is used twice")), parsed.problems().toString());
	}

	@Test
	void badNumbersAndSizesAreReported() {
		final MenuSettings.Parsed parsed = parse(Map.of("Punish.Head_Slot", "abc", "History.Size", 50, "Punish.Reason_Slots", List.of(19, "x"), "Servers.Slots", "nope"));
		final String all = parsed.problems().toString();

		assertTrue(all.contains("Punish.Head_Slot must be a whole number"), all);
		assertTrue(all.contains("History.Size must be 9, 18"), all);
		assertTrue(all.contains("Punish.Reason_Slots contains 'x'"), all);
		assertTrue(all.contains("Servers.Slots must be a list"), all);
	}

	@Test
	void emptySlotListsAreReported() {
		assertTrue(parse(Map.of("History.Slots", List.of())).problems().toString().contains("History.Slots needs at least one slot"));
	}
}
