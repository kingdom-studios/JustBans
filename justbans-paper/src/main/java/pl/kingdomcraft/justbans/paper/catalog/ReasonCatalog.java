package pl.kingdomcraft.justbans.paper.catalog;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;

/**
 * In-memory copy of the enabled rows of {@code jb_reasons}. Readers (commands, tab completion, the login gate) see an
 * immutable snapshot; {@link #replace} swaps it atomically, so a reload never exposes a half-updated catalog.
 */
public final class ReasonCatalog {

	private final AtomicReference<Map<String, ReasonDefinition>> snapshot = new AtomicReference<>(Map.of());

	/** Replaces the whole catalog. Disabled reasons are dropped; the given order (sort order) is kept. */
	public void replace(Collection<ReasonDefinition> reasons) {
		final Map<String, ReasonDefinition> fresh = new LinkedHashMap<>();

		for (final ReasonDefinition reason : reasons)
			if (reason.enabled())
				fresh.put(reason.id(), reason);

		this.snapshot.set(Collections.unmodifiableMap(fresh));
	}

	public Optional<ReasonDefinition> find(String id) {
		return id == null ? Optional.empty() : Optional.ofNullable(this.snapshot.get().get(id.toLowerCase()));
	}

	public boolean contains(String id) {
		return this.find(id).isPresent();
	}

	public int size() {
		return this.snapshot.get().size();
	}

	/** Every usable reason, in configured order. */
	public List<ReasonDefinition> all() {
		return List.copyOf(this.snapshot.get().values());
	}

	/** Ids of every reason, in configured order. */
	public List<String> ids() {
		return List.copyOf(this.snapshot.get().keySet());
	}

	/** Ids of the reasons usable with the given kind, in configured order. */
	public List<String> idsFor(PunishmentKind kind) {
		return this.snapshot.get().values().stream().filter(reason -> reason.allows(kind)).map(ReasonDefinition::id).toList();
	}
}
