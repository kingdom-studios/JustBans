package pl.kingdomcraft.justbans.common.json;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition.LadderStep;

/** (De)serializes the {@code definition} column of {@code jb_reasons}. */
public final class ReasonJson {

	private static final Gson GSON = new Gson();

	private ReasonJson() {
	}

	public static String toJson(ReasonDefinition reason) {
		final JsonObject json = new JsonObject();

		final JsonArray kinds = new JsonArray();
		reason.allowedKinds().stream().map(Enum::name).sorted().forEach(kinds::add);
		json.add("types", kinds);

		json.addProperty("defaultDurationSeconds", reason.defaultDuration() == null ? null : reason.defaultDuration().getSeconds());
		json.addProperty("icon", reason.icon());

		final JsonArray ladder = new JsonArray();
		for (final LadderStep step : reason.ladder()) {
			final JsonObject entry = new JsonObject();
			entry.addProperty("type", step.type().name());
			entry.addProperty("durationSeconds", step.duration() == null ? null : step.duration().getSeconds());
			ladder.add(entry);
		}
		json.add("ladder", ladder);

		final JsonObject templates = new JsonObject();
		reason.templates().forEach((name, lines) -> {
			final JsonArray array = new JsonArray();
			lines.forEach(array::add);
			templates.add(name, array);
		});
		json.add("templates", templates);

		return GSON.toJson(json);
	}

	public static ReasonDefinition fromJson(String id, String displayName, int sortOrder, boolean enabled, String definition) {
		final JsonObject json = GSON.fromJson(definition, JsonObject.class);

		final Set<PunishmentKind> kinds = EnumSet.noneOf(PunishmentKind.class);
		json.getAsJsonArray("types").forEach(e -> kinds.add(PunishmentKind.valueOf(e.getAsString())));

		final List<LadderStep> ladder = new ArrayList<>();
		for (final JsonElement element : json.getAsJsonArray("ladder")) {
			final JsonObject entry = element.getAsJsonObject();
			ladder.add(new LadderStep(PunishmentType.valueOf(entry.get("type").getAsString()), seconds(entry, "durationSeconds")));
		}

		final Map<String, List<String>> templates = new LinkedHashMap<>();
		json.getAsJsonObject("templates").entrySet().forEach(entry -> {
			final List<String> lines = new ArrayList<>();
			entry.getValue().getAsJsonArray().forEach(line -> lines.add(line.getAsString()));
			templates.put(entry.getKey(), lines);
		});

		final JsonElement icon = json.get("icon");

		return new ReasonDefinition(id, displayName, kinds, seconds(json, "defaultDurationSeconds"), ladder,
				icon == null || icon.isJsonNull() ? null : icon.getAsString(), templates, sortOrder, enabled);
	}

	private static Duration seconds(JsonObject json, String key) {
		final JsonElement element = json.get(key);

		return element == null || element.isJsonNull() ? null : Duration.ofSeconds(element.getAsLong());
	}
}
