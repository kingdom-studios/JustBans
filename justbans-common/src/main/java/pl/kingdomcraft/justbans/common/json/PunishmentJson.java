package pl.kingdomcraft.justbans.common.json;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;

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

	private static JsonObject actor(Actor actor) {
		final JsonObject json = new JsonObject();

		json.addProperty("type", actor.type().name());
		json.addProperty("uuid", actor.uuid() == null ? null : actor.uuid().toString());
		json.addProperty("ref", actor.ref());
		json.addProperty("name", actor.name());

		return json;
	}
}
