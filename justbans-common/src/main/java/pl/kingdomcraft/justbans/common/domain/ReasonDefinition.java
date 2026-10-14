package pl.kingdomcraft.justbans.common.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A predefined reason an admin can reference by id, e.g. {@code /tempban Notch cheats}.
 * Loaded from reasons.yml on Velocity and mirrored into {@code jb_reasons}.
 *
 * @param id              short lower-case id
 * @param displayName     text stored as the reason snapshot
 * @param allowedKinds    kinds this reason may be used with
 * @param defaultDuration used for temporary kinds when the admin gives no duration; may be null
 * @param ladder          optional escalation steps, the Nth offence with this reason uses step N
 * @param icon            material name for the Paper GUI
 * @param templates       template name to MiniMessage lines; empty means "use defaults"
 */
public record ReasonDefinition(
		String id,
		String displayName,
		Set<PunishmentKind> allowedKinds,
		Duration defaultDuration,
		List<LadderStep> ladder,
		String icon,
		Map<String, List<String>> templates,
		int sortOrder,
		boolean enabled) {

	public ReasonDefinition {
		if (id == null || id.isBlank())
			throw new IllegalArgumentException("Reason id cannot be empty");

		allowedKinds = Set.copyOf(allowedKinds);
		ladder = List.copyOf(ladder);
		templates = Map.copyOf(templates);
	}

	/**
	 * One escalation step. A null {@code duration} means permanent.
	 */
	public record LadderStep(PunishmentType type, Duration duration) {

		public LadderStep {
			if (type == null)
				throw new IllegalArgumentException("Ladder step requires a type");
		}
	}

	public boolean allows(PunishmentKind kind) {
		return allowedKinds.contains(kind);
	}

	public boolean hasLadder() {
		return !ladder.isEmpty();
	}

	/**
	 * Step for the given zero-based offence count; repeats the last step once exhausted.
	 */
	public LadderStep stepFor(int priorOffences) {
		if (ladder.isEmpty())
			throw new IllegalStateException("Reason " + id + " has no ladder");

		return ladder.get(Math.min(Math.max(priorOffences, 0), ladder.size() - 1));
	}
}
