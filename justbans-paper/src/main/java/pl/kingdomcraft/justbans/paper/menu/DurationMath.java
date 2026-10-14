package pl.kingdomcraft.justbans.paper.menu;

import java.time.Duration;

import pl.kingdomcraft.justbans.common.time.DurationParser;

/** Arithmetic behind the custom-duration buttons. */
public final class DurationMath {

	/** The shortest duration the editor will produce. */
	public static final Duration MIN = Duration.ofHours(1);

	private DurationMath() {
	}

	/**
	 * Adds (or, when negative, subtracts) whole units and keeps the result between {@link #MIN} and the global
	 * {@link DurationParser#MAX}, so repeated clicking can never produce zero, a negative or an absurd ban.
	 */
	public static Duration adjust(Duration current, Duration unit, long steps) {
		Duration result = current.plus(unit.multipliedBy(steps));

		if (result.compareTo(MIN) < 0)
			result = MIN;

		if (result.compareTo(DurationParser.MAX) > 0)
			result = DurationParser.MAX;

		return result;
	}

	/** Where the editor starts: the given duration if it is a usable finite one, otherwise one day. */
	public static Duration startingPoint(Duration preferred) {
		return preferred == null || DurationParser.isPermanent(preferred) || preferred.compareTo(MIN) < 0 ? Duration.ofDays(1) : preferred.compareTo(DurationParser.MAX) > 0 ? DurationParser.MAX : preferred;
	}
}
