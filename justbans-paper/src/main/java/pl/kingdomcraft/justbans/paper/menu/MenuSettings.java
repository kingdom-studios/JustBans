package pl.kingdomcraft.justbans.paper.menu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Titles, slots, materials, names and lore of every menu, read from menus.yml. Anything missing from the file falls back
 * to the built-in default, so an old menus.yml keeps working after an update. If the file contains a mistake (a slot outside
 * the inventory, two buttons on one slot) the problems are reported and the complete defaults are used, so a typo can
 * never leave a menu half-drawn.
 *
 * <p>Titles and lore may use {@code &} colour codes and the placeholders documented in menus.yml.
 */
public final class MenuSettings {

	/** An item shown in a menu: a material name (a Bukkit/Foundation material), a display name and lore lines. */
	public record Item(String material, String name, List<String> lore) {

		public Item {
			lore = List.copyOf(lore);
		}
	}

	public record Punish(String title, int size, int headSlot, Item head, List<Integer> reasonSlots, Item reason, String actionLine, String empty) {
	}

	public record Action(String title, int size, int summarySlot, Item summary, int banSlot, int tempbanSlot, int warnSlot, int kickSlot, Item banItem, Item tempbanItem, Item warnItem, Item kickItem,
			int scopeNetworkSlot, int scopeServerSlot, Item scopeNetwork, Item scopeServer, int durationDisplaySlot, Item durationDisplay, int daysMinusSlot, int daysPlusSlot, int hoursMinusSlot,
			int hoursPlusSlot, Item daysMinus, Item daysPlus, Item hoursMinus, Item hoursPlus, int quantitySlot, List<Integer> presetSlots, Item preset, Item presetDefault, int continueSlot,
			Item continueItem) {
	}

	public record Servers(String title, int size, List<Integer> slots, Item server, Item group) {
	}

	public record Confirm(String title, int size, int summarySlot, Item summary, int noteSlot, Item note, int confirmSlot, Item confirm, int cancelSlot, Item cancel, String notePrompt,
			String noteSaved) {
	}

	public record History(String title, int size, List<Integer> slots, Item active, Item expired, Item revoked, Item kick, String hint, String empty) {
	}

	public record Detail(String title, int size, int infoSlot, int revokeSlot, Item revoke, int copySlot, Item copy, String copyMessage, String noteLine) {
	}

	private final Punish punish;
	private final Action action;
	private final Servers servers;
	private final Confirm confirm;
	private final History history;
	private final Detail detail;

	private MenuSettings(Punish punish, Action action, Servers servers, Confirm confirm, History history, Detail detail) {
		this.punish = punish;
		this.action = action;
		this.servers = servers;
		this.confirm = confirm;
		this.history = history;
		this.detail = detail;
	}

	public Punish punish() {
		return this.punish;
	}

	public Action action() {
		return this.action;
	}

	public Servers servers() {
		return this.servers;
	}

	public Confirm confirm() {
		return this.confirm;
	}

	public History history() {
		return this.history;
	}

	public Detail detail() {
		return this.detail;
	}

	/** The result of parsing: usable settings plus every problem found in the file. */
	public record Parsed(MenuSettings settings, List<String> problems) {
	}

	/** Built-in defaults, identical to the shipped menus.yml. */
	public static MenuSettings defaults() {
		final Parsed parsed = parse(path -> null);

		return parsed.settings();
	}

	/**
	 * Reads the settings. Problems never throw: when there are any, {@link Parsed#settings()} is the built-in default set.
	 */
	public static Parsed parse(ConfigSource source) {
		final List<String> problems = new ArrayList<>();
		final Reader in = new Reader(source, problems);
		final MenuSettings read = read(in);

		read.validate(problems);

		return new Parsed(problems.isEmpty() ? read : read(new Reader(path -> null, new ArrayList<>())), problems);
	}

	// ------------------------------------------------------------------------------------------
	// Reading
	// ------------------------------------------------------------------------------------------

	private static MenuSettings read(Reader in) {
		final Punish punish = new Punish(
				in.str("Punish.Title", "Punish {target}"),
				in.size("Punish.Size", 54),
				in.integer("Punish.Head_Slot", 4),
				in.item("Punish.Head", "PLAYER_HEAD", "&e{target}", "&7Active bans: &f{bans}", "&7Active warns: &f{warns}", "", "&7Choose a reason below."),
				in.ints("Punish.Reason_Slots", range(19, 25, 28, 34)),
				in.item("Punish.Reason", "PAPER", "&f{reason}", "&8id: {id}", "", "&7Available actions:", "{actions}", "", "&eClick to choose"),
				in.str("Punish.Action_Line", " &7- &f{action}"),
				in.str("Punish.Empty_Message", "&cThere are no reasons you may use."));

		final Action action = new Action(
				in.str("Action.Title", "{reason} &8> &0{target}"),
				in.size("Action.Size", 54),
				in.integer("Action.Summary_Slot", 4),
				in.item("Action.Summary", "BOOK", "&e{reason}", "&7Player: &f{target}", "&7Action: &f{action}", "&7Where: &f{scope}", "&7Duration: &f{duration}"),
				in.integer("Action.Ban_Slot", 19),
				in.integer("Action.Tempban_Slot", 20),
				in.integer("Action.Warn_Slot", 21),
				in.integer("Action.Kick_Slot", 22),
				in.item("Action.Ban", "RED_CONCRETE", "&cBan", "&7Permanent ban.", "", "&eClick to select"),
				in.item("Action.Tempban", "ORANGE_CONCRETE", "&6Temporary ban", "&7Ban that ends by itself.", "", "&eClick to select"),
				in.item("Action.Warn", "YELLOW_CONCRETE", "&eWarn", "&7A recorded warning.", "", "&eClick to select"),
				in.item("Action.Kick", "LIGHT_BLUE_CONCRETE", "&bKick", "&7Remove from the network.", "", "&eClick to select"),
				in.integer("Action.Scope_Network_Slot", 24),
				in.integer("Action.Scope_Server_Slot", 25),
				in.item("Action.Scope_Network", "COMPASS", "&aWhole network", "&7The ban applies everywhere.", "", "&eClick to select"),
				in.item("Action.Scope_Server", "CHEST", "&aOne server or group", "&7Current: &f{scope}", "", "&eClick to choose"),
				in.integer("Action.Duration_Display_Slot", 31),
				in.item("Action.Duration_Display", "CLOCK", "&eDuration: &f{duration}", "&7Use the buttons around to adjust it."),
				in.integer("Action.Days_Minus_Slot", 29),
				in.integer("Action.Days_Plus_Slot", 30),
				in.integer("Action.Hours_Minus_Slot", 32),
				in.integer("Action.Hours_Plus_Slot", 33),
				in.item("Action.Days_Minus", "RED_STAINED_GLASS_PANE", "&c- {step} day(s)"),
				in.item("Action.Days_Plus", "LIME_STAINED_GLASS_PANE", "&a+ {step} day(s)"),
				in.item("Action.Hours_Minus", "RED_STAINED_GLASS_PANE", "&c- {step} hour(s)"),
				in.item("Action.Hours_Plus", "LIME_STAINED_GLASS_PANE", "&a+ {step} hour(s)"),
				in.integer("Action.Quantity_Slot", 47),
				in.ints("Action.Preset_Slots", range(37, 43)),
				in.item("Action.Preset", "PAPER", "&f{duration}", "&7Click to use this duration"),
				in.item("Action.Preset_Default", "NETHER_STAR", "&fReason default", "&7Use the reason's own duration", "&7or its escalation ladder."),
				in.integer("Action.Continue_Slot", 49),
				in.item("Action.Continue", "EMERALD", "&aContinue", "&7Review and confirm."));

		final Servers servers = new Servers(
				in.str("Servers.Title", "Choose a server"),
				in.size("Servers.Size", 54),
				in.ints("Servers.Slots", range(0, 44)),
				in.item("Servers.Server", "GRASS_BLOCK", "&f{name}", "&7Server", "", "&eClick to select"),
				in.item("Servers.Group", "ENDER_CHEST", "&6{name}", "&7Server group", "", "&eClick to select"));

		final Confirm confirm = new Confirm(
				in.str("Confirm.Title", "Confirm {action}"),
				in.size("Confirm.Size", 27),
				in.integer("Confirm.Summary_Slot", 4),
				in.item("Confirm.Summary", "BOOK", "&e{target}", "&7Action: &f{action}", "&7Where: &f{scope}", "&7Duration: &f{duration}", "&7Reason: &f{reason}", "&7Note: &f{note}"),
				in.integer("Confirm.Note_Slot", 11),
				in.item("Confirm.Note", "WRITABLE_BOOK", "&eAdd a note", "&7Optional text only staff can see.", "", "&eClick, then type in chat"),
				in.integer("Confirm.Confirm_Slot", 13),
				in.item("Confirm.Confirm", "LIME_CONCRETE", "&a&lConfirm", "&7Apply this punishment now."),
				in.integer("Confirm.Cancel_Slot", 15),
				in.item("Confirm.Cancel", "RED_CONCRETE", "&c&lCancel", "&7Close without doing anything."),
				in.str("Confirm.Note_Prompt", "&eType the note in chat. Type &fcancel &eto abort, or &f- &eto clear it."),
				in.str("Confirm.Note_Saved", "&aNote saved."));

		final History history = new History(
				in.str("History.Title", "History of {target}"),
				in.size("History.Size", 54),
				in.ints("History.Slots", range(0, 44)),
				in.item("History.Active", "LIME_CONCRETE", "&a{type} &8#{id}", "&7Where: &f{scope}", "&7Reason: &f{reason}", "&7By: &f{actor}", "&7Given: &f{created}", "&7Ends: {expires}", "", "{hint}"),
				in.item("History.Expired", "GRAY_CONCRETE", "&7{type} &8#{id} &7(expired)", "&7Where: &f{scope}", "&7Reason: &f{reason}", "&7By: &f{actor}", "&7Given: &f{created}", "&7Ended: &f{expires}", "", "{hint}"),
				in.item("History.Revoked", "RED_CONCRETE", "&c{type} &8#{id} &c(revoked)", "&7Where: &f{scope}", "&7Reason: &f{reason}", "&7By: &f{actor}", "&7Given: &f{created}", "&7Revoked by: &f{revoked_by}", "&7Revoked: &f{revoked_at}", "", "{hint}"),
				in.item("History.Kick", "YELLOW_CONCRETE", "&e{type} &8#{id}", "&7Reason: &f{reason}", "&7By: &f{actor}", "&7Given: &f{created}", "", "{hint}"),
				in.str("History.Hint", "&eClick for details"),
				in.str("History.Empty_Message", "&7{target} has no punishments on record."));

		final Detail detail = new Detail(
				in.str("Detail.Title", "Punishment #{id}"),
				in.size("Detail.Size", 27),
				in.integer("Detail.Info_Slot", 4),
				in.integer("Detail.Revoke_Slot", 11),
				in.item("Detail.Revoke", "BARRIER", "&c&lRevoke", "&7Pardon / remove this punishment.", "", "&eShift-click to revoke"),
				in.integer("Detail.Copy_Slot", 15),
				in.item("Detail.Copy", "PAPER", "&eCopy ID", "&7Puts a clickable copy link in chat.", "", "&eClick"),
				in.str("Detail.Copy_Message", "&7Punishment ID &e#{id} &8- &bclick here to copy"),
				in.str("Detail.Note_Line", "&7Staff note: &f{note}"));

		return new MenuSettings(punish, action, servers, confirm, history, detail);
	}

	// ------------------------------------------------------------------------------------------
	// Validation
	// ------------------------------------------------------------------------------------------

	private void validate(List<String> problems) {
		final Punish p = this.punish;
		checkSlots("Punish", p.size, problems, single("Punish.Head_Slot", p.headSlot), many("Punish.Reason_Slots", p.reasonSlots));

		final Action a = this.action;
		checkSlots("Action", a.size, problems, single("Action.Summary_Slot", a.summarySlot), single("Action.Ban_Slot", a.banSlot), single("Action.Tempban_Slot", a.tempbanSlot),
				single("Action.Warn_Slot", a.warnSlot), single("Action.Kick_Slot", a.kickSlot), single("Action.Scope_Network_Slot", a.scopeNetworkSlot), single("Action.Scope_Server_Slot", a.scopeServerSlot),
				single("Action.Duration_Display_Slot", a.durationDisplaySlot), single("Action.Days_Minus_Slot", a.daysMinusSlot), single("Action.Days_Plus_Slot", a.daysPlusSlot),
				single("Action.Hours_Minus_Slot", a.hoursMinusSlot), single("Action.Hours_Plus_Slot", a.hoursPlusSlot), single("Action.Quantity_Slot", a.quantitySlot),
				many("Action.Preset_Slots", a.presetSlots), single("Action.Continue_Slot", a.continueSlot), single("(return button)", a.size - 1));

		checkSlots("Servers", this.servers.size, problems, many("Servers.Slots", this.servers.slots), single("(return button)", this.servers.size - 1));

		final Confirm c = this.confirm;
		checkSlots("Confirm", c.size, problems, single("Confirm.Summary_Slot", c.summarySlot), single("Confirm.Note_Slot", c.noteSlot), single("Confirm.Confirm_Slot", c.confirmSlot),
				single("Confirm.Cancel_Slot", c.cancelSlot));

		checkSlots("History", this.history.size, problems, many("History.Slots", this.history.slots), single("(return button)", this.history.size - 1));

		final Detail d = this.detail;
		checkSlots("Detail", d.size, problems, single("Detail.Info_Slot", d.infoSlot), single("Detail.Revoke_Slot", d.revokeSlot), single("Detail.Copy_Slot", d.copySlot),
				single("(return button)", d.size - 1));
	}

	private record Slots(String name, List<Integer> slots) {
	}

	private static Slots single(String name, int slot) {
		return new Slots(name, List.of(slot));
	}

	private static Slots many(String name, List<Integer> slots) {
		return new Slots(name, slots);
	}

	/** Every slot must be inside the inventory and used by only one thing. The return button (last slot) is reserved. */
	private static void checkSlots(String menu, int size, List<String> problems, Slots... groups) {
		final Set<Integer> used = new HashSet<>();

		for (final Slots group : groups)
			for (final int slot : group.slots) {
				if (slot < 0 || slot >= size)
					problems.add(menu + ": " + group.name + " has slot " + slot + ", outside the " + size + "-slot menu");
				else if (!used.add(slot))
					problems.add(menu + ": slot " + slot + " is used twice (" + group.name + ")");
			}
	}

	private static List<Integer> range(int... bounds) {
		final List<Integer> slots = new ArrayList<>();

		for (int i = 0; i < bounds.length; i += 2)
			IntStream.rangeClosed(bounds[i], bounds[i + 1]).forEach(slots::add);

		return slots;
	}

	// ------------------------------------------------------------------------------------------

	/** Reads typed values with defaults, recording problems for values that are present but unusable. */
	private static final class Reader {

		private final ConfigSource source;
		private final List<String> problems;

		Reader(ConfigSource source, List<String> problems) {
			this.source = source;
			this.problems = problems;
		}

		String str(String path, String def) {
			final Object value = this.source.get(path);

			return value == null ? def : String.valueOf(value);
		}

		int integer(String path, int def) {
			final Object value = this.source.get(path);

			if (value == null)
				return def;

			if (value instanceof Number number)
				return number.intValue();

			try {
				return Integer.parseInt(String.valueOf(value).trim());
			} catch (final NumberFormatException ex) {
				this.problems.add(path + " must be a whole number, got '" + value + "'");
				return def;
			}
		}

		/** Inventory sizes must be a multiple of 9 from 9 to 54. */
		int size(String path, int def) {
			final int size = this.integer(path, def);

			if (size < 9 || size > 54 || size % 9 != 0) {
				this.problems.add(path + " must be 9, 18, 27, 36, 45 or 54, got " + size);
				return def;
			}

			return size;
		}

		List<Integer> ints(String path, List<Integer> def) {
			final Object value = this.source.get(path);

			if (value == null)
				return def;

			if (!(value instanceof List<?> list)) {
				this.problems.add(path + " must be a list of slot numbers");
				return def;
			}

			final List<Integer> slots = new ArrayList<>();

			for (final Object element : list)
				try {
					slots.add(element instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(element).trim()));
				} catch (final NumberFormatException ex) {
					this.problems.add(path + " contains '" + element + "', which is not a slot number");
					return def;
				}

			if (slots.isEmpty()) {
				this.problems.add(path + " needs at least one slot");
				return def;
			}

			return slots;
		}

		List<String> strings(String path, List<String> def) {
			final Object value = this.source.get(path);

			if (value == null)
				return def;

			if (value instanceof List<?> list)
				return list.stream().map(String::valueOf).toList();

			// Foundation hands back an empty YAML list ("Lore: []") as the text "[]"
			return String.valueOf(value).trim().equals("[]") ? List.of() : List.of(String.valueOf(value));
		}

		Item item(String path, String defMaterial, String defName, String... defLore) {
			return new Item(this.str(path + ".Material", defMaterial), this.str(path + ".Name", defName), this.strings(path + ".Lore", List.of(defLore)));
		}
	}
}
