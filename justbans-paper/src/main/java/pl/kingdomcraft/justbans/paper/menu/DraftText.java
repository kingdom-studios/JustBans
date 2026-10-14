package pl.kingdomcraft.justbans.paper.menu;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.render.RemainingTimeFormatter;
import pl.kingdomcraft.justbans.common.time.DurationParser;

/** Wording for menu items, kept free of Bukkit so it can be tested. */
public final class DraftText {

	private DraftText() {
	}

	/**
	 * What the duration of the draft will be, for display: the chosen length, otherwise what the reason decides.
	 */
	public static String duration(PunishDraft draft) {
		switch (draft.action()) {
			case WARN:
			case KICK:
				return "-";

			case BAN:
				return "permanent";

			default:
				if (draft.duration() != null)
					return DurationParser.format(draft.duration());

				if (draft.reason().hasLadder())
					return "escalation ladder";

				return draft.reason().defaultDuration() != null ? DurationParser.format(draft.reason().defaultDuration()) + " (default)" : "not set";
		}
	}

	/** {@code Ban}, {@code Tempban}, {@code Warn} or {@code Kick}: a ban with an end date is a tempban. */
	public static String typeLabel(Punishment p) {
		if (p.type() == PunishmentType.BAN)
			return p.expiresAt() == null ? "Ban" : "Tempban";

		return p.type() == PunishmentType.WARN ? "Warn" : "Kick";
	}

	/**
	 * When a punishment ends, for the {@code {expires}} placeholder: the given text if it never does, the date and time
	 * left while it is running, and just the date once it is over.
	 */
	public static String expires(Punishment p, Instant now, DateTimeFormatter format, String never) {
		if (p.expiresAt() == null)
			return never;

		final String date = format.format(p.expiresAt());

		return p.expiresAt().isAfter(now) && !p.isRevoked() ? date + " (" + RemainingTimeFormatter.defaults().format(now, p.expiresAt()) + ")" : date;
	}
}
