package pl.kingdomcraft.justbans.velocity.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;

/**
 * Parsed reasons.yml.
 *
 * @param defaultTemplates templates from {@code defaults.templates}; may be partial
 * @param reasons          in file order
 */
public record ReasonsFile(Map<String, List<String>> defaultTemplates, List<ReasonDefinition> reasons) {

	public ReasonsFile {
		defaultTemplates = Map.copyOf(defaultTemplates);
		reasons = List.copyOf(reasons);
	}

	public Optional<ReasonDefinition> find(String id) {
		if (id == null)
			return Optional.empty();

		return this.reasons.stream().filter(reason -> reason.id().equals(id)).findFirst();
	}

	/**
	 * Lines of a template for a punishment: the reason's own override, else the file default, else the built-in.
	 *
	 * @param reasonId reason id of the punishment, or null for a free-text reason
	 */
	public List<String> template(String reasonId, String name) {
		final List<String> own = this.find(reasonId).map(reason -> reason.templates().get(name)).orElse(null);

		if (own != null)
			return own;

		final List<String> fileDefault = this.defaultTemplates.get(name);

		return fileDefault != null ? fileDefault : TemplateNames.BUILT_IN.get(name);
	}
}
