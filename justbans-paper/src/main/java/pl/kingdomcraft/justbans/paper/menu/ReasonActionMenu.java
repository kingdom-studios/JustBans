package pl.kingdomcraft.justbans.paper.menu;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.Messenger;
import org.mineacademy.fo.menu.Menu;
import org.mineacademy.fo.menu.MenuQuantitable;
import org.mineacademy.fo.menu.model.MenuQuantity;

import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.repository.ServerRepository;
import pl.kingdomcraft.justbans.common.time.DurationParser;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.command.Reports;
import pl.kingdomcraft.justbans.paper.service.Msg;

/**
 * Second step: pick the action, where it applies (the whole network, or one server or group from the list the proxy
 * registered) and, for temporary bans, how long (a quick preset, or the +/- buttons with an adjustable step through
 * {@link MenuQuantitable}). All choices are written to the shared {@link PunishDraft}.
 *
 * <p>Controls that do not apply to the chosen action (scope for a warn, duration for a permanent ban) are hidden and
 * ignore clicks.
 */
public final class ReasonActionMenu extends Menu implements MenuQuantitable {

	private static final Duration DAY = Duration.ofDays(1);
	private static final Duration HOUR = Duration.ofHours(1);

	private final Core core;
	private final Predicate<String> permissions;
	private final PunishDraft draft;

	private MenuQuantity quantity = MenuQuantity.ONE;

	public ReasonActionMenu(Core core, Menu parent, Predicate<String> permissions, PunishDraft draft) {
		super(parent);

		this.core = core;
		this.permissions = permissions;
		this.draft = draft;

		final MenuSettings.Action config = MenuConfig.current().action();

		this.setTitle(Msg.fill(config.title(), "reason", draft.reason().displayName(), "target", draft.target().name()));
		this.setSize(config.size());

		this.registerButton(MenuItems.display(config.summarySlot(), () -> MenuItems.item(config.summary(), false, this.placeholders())));

		// Actions the staff member can use for this reason
		for (final ActionType action : draft.usableActions())
			this.registerButton(MenuItems.button(this.slotOf(config, action), () -> MenuItems.item(this.specOf(config, action), draft.action() == action, this.placeholders()), (player, menu, click) -> {
				draft.select(action);
				this.redraw();
			}));

		// Scope
		this.registerButton(MenuItems.conditional(config.scopeNetworkSlot(), this::scopeChoiceAvailable, () -> MenuItems.item(config.scopeNetwork(), !draft.isServerScoped(), this.placeholders()), (player, menu, click) -> {
			draft.selectScope(Scope.network());
			this.redraw();
		}));

		this.registerButton(MenuItems.conditional(config.scopeServerSlot(), this::scopeChoiceAvailable, () -> MenuItems.item(config.scopeServer(), draft.isServerScoped(), this.placeholders()),
				(player, menu, click) -> this.openServerPicker(player)));

		// Duration
		this.registerButton(MenuItems.conditional(config.durationDisplaySlot(), this::editsDuration, () -> MenuItems.item(config.durationDisplay(), false, this.placeholders()), (player, menu, click) -> {
		}));

		this.registerButton(MenuItems.conditional(config.daysMinusSlot(), this::editsDuration, () -> MenuItems.item(config.daysMinus(), false, this.placeholders()), (player, menu, click) -> this.adjust(DAY, -1)));
		this.registerButton(MenuItems.conditional(config.daysPlusSlot(), this::editsDuration, () -> MenuItems.item(config.daysPlus(), false, this.placeholders()), (player, menu, click) -> this.adjust(DAY, 1)));
		this.registerButton(MenuItems.conditional(config.hoursMinusSlot(), this::editsDuration, () -> MenuItems.item(config.hoursMinus(), false, this.placeholders()), (player, menu, click) -> this.adjust(HOUR, -1)));
		this.registerButton(MenuItems.conditional(config.hoursPlusSlot(), this::editsDuration, () -> MenuItems.item(config.hoursPlus(), false, this.placeholders()), (player, menu, click) -> this.adjust(HOUR, 1)));

		// Presets: the first slot always means "let the reason decide", the rest are fixed lengths
		final List<Integer> presetSlots = config.presetSlots();
		final List<Duration> presets = DurationPresets.forReason(draft.reason(), presetSlots.size() - 1);

		this.registerButton(MenuItems.conditional(presetSlots.get(0), this::editsDuration, () -> MenuItems.item(config.presetDefault(), draft.duration() == null, this.placeholders()), (player, menu, click) -> {
			draft.setDuration(null);
			this.redraw();
		}));

		for (int i = 0; i < presets.size(); i++) {
			final Duration preset = presets.get(i);

			this.registerButton(MenuItems.conditional(presetSlots.get(i + 1), this::editsDuration,
					() -> MenuItems.item(config.preset(), preset.equals(draft.duration()), "duration", DurationParser.format(preset)), (player, menu, click) -> {
						draft.setDuration(preset);
						this.redraw();
					}));
		}

		// Continue
		this.registerButton(MenuItems.button(config.continueSlot(), () -> MenuItems.item(config.continueItem(), draft.isComplete(), this.placeholders()), (player, menu, click) -> {
			if (!draft.isComplete()) {
				Messenger.error(player, "Choose a duration first: this reason has no default.");
				return;
			}

			new ConfirmMenu(core, this, permissions, draft).displayTo(player);
		}));
	}

	// ------------------------------------------------------------------------------------------

	private boolean editsDuration() {
		return this.draft.action().needsDuration();
	}

	/** Server or group bans exist only for bans, and only if the reason allows the server kind. */
	private boolean scopeChoiceAvailable() {
		return this.draft.action().allowsScopeFor(this.draft.reason());
	}

	private void redraw() {
		this.restartMenu();
	}

	private String[] placeholders() {
		return new String[] { "target", this.draft.target().name(), "reason", this.draft.reason().displayName(), "action", MenuItems.label(this.draft.action()),
				"scope", MenuItems.scopeText(this.draft.scope()), "duration", DraftText.duration(this.draft), "step", String.valueOf(this.step()) };
	}

	private int slotOf(MenuSettings.Action config, ActionType action) {
		return switch (action) {
			case BAN -> config.banSlot();
			case TEMPBAN -> config.tempbanSlot();
			case WARN -> config.warnSlot();
			case KICK -> config.kickSlot();
		};
	}

	private MenuSettings.Item specOf(MenuSettings.Action config, ActionType action) {
		return switch (action) {
			case BAN -> config.banItem();
			case TEMPBAN -> config.tempbanItem();
			case WARN -> config.warnItem();
			case KICK -> config.kickItem();
		};
	}

	/** How much one click on a +/- button changes the duration (1, 2, 5, 10 or 20 units). */
	private int step() {
		return Math.max(1, (int) Math.round(this.quantity.getAmountPercent()));
	}

	private void adjust(Duration unit, int direction) {
		final Duration base = DurationMath.startingPoint(this.draft.duration() != null ? this.draft.duration() : this.draft.reason().defaultDuration());

		this.draft.setDuration(DurationMath.adjust(base, unit, (long) direction * this.step()));
		this.redraw();
	}

	/** Loads the registered servers off the main thread, then opens the picker. */
	private void openServerPicker(Player player) {
		Common.runAsync(() -> {
			try {
				final List<pl.kingdomcraft.justbans.common.domain.ServerRecord> servers = this.core.database().query(connection -> new ServerRepository().findAll(connection));

				Common.runLater(() -> {
					if (!player.isOnline())
						return;

					if (servers.isEmpty())
						Messenger.error(player, "The proxy has not registered any servers yet.");
					else
						new ServerPickMenu(this.core, this, this.draft, ServerPickMenu.choicesFrom(servers)).displayTo(player);
				});

			} catch (final Exception ex) {
				Common.error(ex, "Could not load the server list for the punish menu");
				Reports.error(player, "Could not load the server list, see the console.");
			}
		});
	}

	// ------------------------------------------------------------------------------------------
	// MenuQuantitable
	// ------------------------------------------------------------------------------------------

	@Override
	public MenuQuantity getQuantity() {
		return this.quantity;
	}

	@Override
	public void setQuantity(MenuQuantity newQuantity) {
		this.quantity = newQuantity;
	}

	/** The step button only makes sense next to the duration controls. */
	@Override
	public int getQuantityButtonPosition() {
		return this.editsDuration() ? MenuConfig.current().action().quantitySlot() : -1;
	}

	@Override
	public Menu newInstance() {
		return new ReasonActionMenu(this.core, this.getParent(), this.permissions, this.draft);
	}
}
