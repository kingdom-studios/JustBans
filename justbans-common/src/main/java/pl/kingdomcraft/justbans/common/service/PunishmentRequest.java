package pl.kingdomcraft.justbans.common.service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.Scope;

/**
 * Everything needed to issue a punishment. Build with {@link #builder(PunishmentKind, UUID, String, Actor)}.
 *
 * @param requestId    idempotency key for web commands, may be null
 * @param scope        required for server kinds (server or group), ignored (network) otherwise
 * @param reasonId     configured reason id; null when {@code customReason} is used
 * @param customReason free text; only used when no reason id is given
 * @param duration     explicit duration, {@code DurationParser.PERMANENT}, or null to use the reason default
 * @param force        replace an existing active ban in the same scope instead of failing
 */
public record PunishmentRequest(
		UUID requestId,
		PunishmentKind kind,
		UUID targetUuid,
		String targetName,
		Actor actor,
		Scope scope,
		String reasonId,
		String customReason,
		Duration duration,
		boolean silent,
		String note,
		boolean force,
		String originServer) {

	public PunishmentRequest {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(targetUuid, "targetUuid");
		Objects.requireNonNull(targetName, "targetName");
		Objects.requireNonNull(actor, "actor");
	}

	public static Builder builder(PunishmentKind kind, UUID targetUuid, String targetName, Actor actor) {
		return new Builder(kind, targetUuid, targetName, actor);
	}

	public static final class Builder {
		private final PunishmentKind kind;
		private final UUID targetUuid;
		private final String targetName;
		private final Actor actor;
		private UUID requestId;
		private Scope scope;
		private String reasonId;
		private String customReason;
		private Duration duration;
		private boolean silent;
		private String note;
		private boolean force;
		private String originServer;

		private Builder(PunishmentKind kind, UUID targetUuid, String targetName, Actor actor) {
			this.kind = kind;
			this.targetUuid = targetUuid;
			this.targetName = targetName;
			this.actor = actor;
		}

		public Builder requestId(UUID value) {
			this.requestId = value;
			return this;
		}

		public Builder scope(Scope value) {
			this.scope = value;
			return this;
		}

		public Builder reasonId(String value) {
			this.reasonId = value;
			return this;
		}

		public Builder customReason(String value) {
			this.customReason = value;
			return this;
		}

		public Builder duration(Duration value) {
			this.duration = value;
			return this;
		}

		public Builder silent(boolean value) {
			this.silent = value;
			return this;
		}

		public Builder note(String value) {
			this.note = value;
			return this;
		}

		public Builder force(boolean value) {
			this.force = value;
			return this;
		}

		public Builder originServer(String value) {
			this.originServer = value;
			return this;
		}

		public PunishmentRequest build() {
			return new PunishmentRequest(requestId, kind, targetUuid, targetName, actor, scope, reasonId, customReason, duration, silent, note, force, originServer);
		}
	}
}
