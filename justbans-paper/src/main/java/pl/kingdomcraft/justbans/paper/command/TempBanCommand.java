package pl.kingdomcraft.justbans.paper.command;

import java.time.Duration;
import java.util.List;

import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.time.DurationParser;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/**
 * {@code /tempban <player> <reason> [duration] [options]} with a configured reason (its default duration is used when none
 * is typed), or {@code /tempban <player> <duration> <text...>} with free text.
 */
@AutoRegister
public final class TempBanCommand extends PunishmentCommand {

	private static final List<String> DURATIONS = List.of("1h", "12h", "1d", "3d", "7d", "14d", "30d");

	public TempBanCommand() {
		super("tempban|tban", "justbans.tempban", "<player> <reason> [duration] [-s] [-f] [server:<name>|group:<name>]", "Temporarily ban a player from the network or one server.", 2);
	}

	@Override
	protected Job prepare(Core core, ArgParser.Parsed parsed) {
		final String second = parsed.word(1);

		if (second == null)
			this.returnTell(Localization.Punish.REASON_REQUIRED);

		final PunishmentKind kind = parsed.isScoped() ? PunishmentKind.SERVER_TEMPBAN : PunishmentKind.TEMPBAN;

		if (core.reasons().contains(second)) {
			final ReasonInput reason = new ReasonInput(second.toLowerCase(), null);
			final Duration duration = parsed.word(2) == null ? null : this.duration(parsed.word(2));

			return context -> issue(context, kind, reason, duration);
		}

		// No such reason: the second word must then be a duration, followed by free text.
		final Duration duration = DurationParser.tryParse(second);

		if (duration == null)
			this.returnTell(Msg.fill(Localization.Punish.UNKNOWN_REASON, "input", second, "reasons", String.join(", ", core.reasons().idsFor(PunishmentKind.TEMPBAN))));

		this.finite(duration);

		final String text = parsed.join(2);

		if (text.isEmpty())
			this.returnTell(Localization.Punish.REASON_REQUIRED);

		final ReasonInput reason = this.customReason(core, parsed.word(2), text);

		return context -> issue(context, kind, reason, duration);
	}

	private Duration duration(String text) {
		try {
			final Duration duration = DurationParser.parse(text);

			this.finite(duration);

			return duration;

		} catch (final IllegalArgumentException ex) {
			this.returnTell("&c" + ex.getMessage());
			return null; // unreachable, returnTell throws
		}
	}

	private void finite(Duration duration) {
		if (DurationParser.isPermanent(duration))
			this.returnTell(Localization.Punish.DURATION_REQUIRED);
	}

	@Override
	protected boolean supportsScope() {
		return true;
	}

	@Override
	protected boolean supportsForce() {
		return true;
	}

	@Override
	protected List<String> suggest(Core core, int position) {
		return switch (position) {
			case 2 -> core.reasons().idsFor(PunishmentKind.TEMPBAN);
			case 3 -> DURATIONS;
			default -> List.of();
		};
	}
}
