package pl.kingdomcraft.justbans.paper.menu;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.time.DurationParser;

/** The quick duration choices offered for a reason. */
public final class DurationPresets {

	private static final List<Duration> COMMON = List.of(Duration.ofHours(1), Duration.ofHours(12), Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(14),
			Duration.ofDays(30));

	private DurationPresets() {
	}

	/**
	 * Finite durations, most relevant first: the reason's default, then its ladder steps in order, then common lengths.
	 * No duplicates, at most {@code max} entries. (The "use the reason's own choice" button is separate.)
	 */
	public static List<Duration> forReason(ReasonDefinition reason, int max) {
		final Set<Duration> presets = new LinkedHashSet<>();

		if (reason.defaultDuration() != null && !DurationParser.isPermanent(reason.defaultDuration()))
			presets.add(reason.defaultDuration());

		for (final ReasonDefinition.LadderStep step : reason.ladder())
			if (step.duration() != null && !DurationParser.isPermanent(step.duration()))
				presets.add(step.duration());

		presets.addAll(COMMON);

		final List<Duration> result = new ArrayList<>(presets);

		return result.size() > max ? List.copyOf(result.subList(0, Math.max(0, max))) : List.copyOf(result);
	}
}
