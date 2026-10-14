package pl.kingdomcraft.justbans.common.json;

import java.time.Instant;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.ActorType;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ScopeType;

/** Builds the snapshot payload described in the Redis protocol (docs/DEVELOPMENT_PLAN.md section 4.3). */
public final class PunishmentJson {

	private static final Gson GSON = new Gson();

	private PunishmentJson() {
	}

	/** Event payload: {@code {"punishment": {...}}}. */
	public static String eventPayload(Punishment punishment) {
		final JsonObject root = new JsonObject();
		root.add("punishment", toJson(punishment));

		return GSON.toJson(root);
	}

	public static JsonObject toJson(Punishment p) {
		final JsonObject json = new JsonObject();

		json.addProperty("id", p.id());
		json.addProperty("publicId", p.publicId());
		json.addProperty("type", p.type().name());

		final JsonObject scope = new JsonObject();
		scope.addProperty("type", p.scope().type().name());
		scope.addProperty("target", p.scope().target());
		json.add("scope", scope);

		final JsonObject target = new JsonObject();
		target.addProperty("uuid", p.targetUuid().toString());
		target.addProperty("name", p.targetName());
		json.add("target", target);

		json.add("actor", actor(p.actor()));

		final JsonObject reason = new JsonObject();
		reason.addProperty("id", p.reasonId());
		reason.addProperty("text", p.reasonText());
		json.add("reason", reason);

		json.addProperty("createdAt", p.createdAt().toEpochMilli());
		json.addProperty("expiresAt", p.expiresAt() == null ? null : p.expiresAt().toEpochMilli());
		json.addProperty("silent", p.silent());
		json.addProperty("originServer", p.originServer());
		json.addProperty("ladderStep", p.ladderStep());

		if (p.revocation() != null) {
			final JsonObject revoked = new JsonObject();
			revoked.addProperty("at", p.revocation().at().toEpochMilli());
			revoked.add("by", actor(p.revocation().by()));
			revoked.addProperty("reason", p.revocation().reason());
			json.add("revoked", revoked);
		}

		return json;
	}

	/**
	 * Reads the snapshot written by {@link #toJson}. The internal staff note is never serialized, so it is null here.
	 *
	 * @throws IllegalArgumentException if a required field is missing or malformed
	 */
	public static Punishment fromJson(JsonObject json) {
		try {
			final JsonObject scope = json.getAsJsonObject("scope");
			final JsonObject target = json.getAsJsonObject("target");
			final JsonObject reason = json.getAsJsonObject("reason");

			Punishment.Revocation revocation = null;

			if (json.has("revoked") && json.get("revoked").isJsonObject()) {
				final JsonObject revoked = json.getAsJsonObject("revoked");
				revocation = new Punishment.Revocation(Instant.ofEpochMilli(revoked.get("at").getAsLong()), actorFrom(revoked.getAsJsonObject("by")), string(revoked, "reason"));
			}

			return new Punishment(json.get("id").getAsLong(), json.get("publicId").getAsString(), PunishmentType.valueOf(json.get("type").getAsString()),
					new Scope(ScopeType.valueOf(scope.get("type").getAsString()), scope.get("target").getAsString()),
					UUID.fromString(target.get("uuid").getAsString()), target.get("name").getAsString(), actorFrom(json.getAsJsonObject("actor")),
					string(reason, "id"), reason.get("text").getAsString(), null, Instant.ofEpochMilli(json.get("createdAt").getAsLong()),
					json.has("expiresAt") && !json.get("expiresAt").isJsonNull() ? Instant.ofEpochMilli(json.get("expiresAt").getAsLong()) : null,
					string(json, "originServer"), json.has("silent") && json.get("silent").getAsBoolean(),
					json.has("ladderStep") && !json.get("ladderStep").isJsonNull() ? json.get("ladderStep").getAsInt() : null, revocation);

		} catch (final RuntimeException ex) {
			throw new IllegalArgumentException("Malformed punishment snapshot: " + ex.getMessage(), ex);
		}
	}

	/** Reads {@code {"punishment": {...}}}, the payload of PUNISHMENT_CREATED / PUNISHMENT_REVOKED events. */
	public static Punishment fromEventPayload(JsonObject payload) {
		final JsonElement element = payload.get("punishment");

		if (element == null || !element.isJsonObject())
			throw new IllegalArgumentException("Event payload has no 'punishment' object");

		return fromJson(element.getAsJsonObject());
	}

	private static Actor actorFrom(JsonObject json) {
		return new Actor(ActorType.valueOf(json.get("type").getAsString()), json.has("uuid") && !json.get("uuid").isJsonNull() ? UUID.fromString(json.get("uuid").getAsString()) : null,
				string(json, "ref"), json.get("name").getAsString());
	}

	private static String string(JsonObject json, String key) {
		final JsonElement element = json.get(key);

		return element == null || element.isJsonNull() ? null : element.getAsString();
	}

	private static JsonObject actor(Actor actor) {
		final JsonObject json = new JsonObject();

		json.addProperty("type", actor.type().name());
		json.addProperty("uuid", actor.uuid() == null ? null : actor.uuid().toString());
		json.addProperty("ref", actor.ref());
		json.addProperty("name", actor.name());

		return json;
	}
}
