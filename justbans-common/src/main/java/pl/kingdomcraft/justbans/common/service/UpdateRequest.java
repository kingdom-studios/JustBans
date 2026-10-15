package pl.kingdomcraft.justbans.common.service;

import java.time.Instant;
import java.util.Objects;

import pl.kingdomcraft.justbans.common.domain.Actor;

/**
 * An edit of an existing punishment: its expiry, the reason text shown to the player, or the internal staff note.
 * Anything not asked for stays untouched.
 *
 * @param actor         who edits (recorded in the {@code PUNISHMENT_UPDATED} event)
 * @param punishmentId  {@code jb_punishments.id}
 * @param changeExpiry  whether {@code expiresAt} is to be applied
 * @param expiresAt     the new absolute expiry, or null for permanent; only meaningful with {@code changeExpiry}
 * @param reasonText    new reason text, or null to keep the current one
 * @param note          new staff note, an empty string to remove it, or null to keep the current one
 */
public record UpdateRequest(Actor actor, long punishmentId, boolean changeExpiry, Instant expiresAt, String reasonText, String note) {

	public UpdateRequest {
		Objects.requireNonNull(actor, "actor");
	}

	public static Builder builder(Actor actor, long punishmentId) {
		return new Builder(actor, punishmentId);
	}

	/** Whether the request changes anything at all. */
	public boolean hasChanges() {
		return this.changeExpiry || this.reasonText != null || this.note != null;
	}

	public static final class Builder {
		private final Actor actor;
		private final long punishmentId;
		private boolean changeExpiry;
		private Instant expiresAt;
		private String reasonText;
		private String note;

		private Builder(Actor actor, long punishmentId) {
			this.actor = actor;
			this.punishmentId = punishmentId;
		}

		/** @param value the new expiry, or null to make the punishment permanent */
		public Builder expiresAt(Instant value) {
			this.changeExpiry = true;
			this.expiresAt = value;
			return this;
		}

		public Builder reasonText(String value) {
			this.reasonText = value;
			return this;
		}

		/** @param value the new note; an empty string removes the note */
		public Builder note(String value) {
			this.note = value;
			return this;
		}

		public UpdateRequest build() {
			return new UpdateRequest(actor, punishmentId, changeExpiry, expiresAt, reasonText, note);
		}
	}
}
