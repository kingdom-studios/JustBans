package pl.kingdomcraft.justbans.common.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.EventType;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ScopeType;
import pl.kingdomcraft.justbans.common.json.PunishmentJson;
import pl.kingdomcraft.justbans.common.repository.EventRepository;
import pl.kingdomcraft.justbans.common.repository.PlayerRepository;
import pl.kingdomcraft.justbans.common.repository.PunishmentRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.common.repository.ServerRepository;
import pl.kingdomcraft.justbans.common.time.DurationParser;

/**
 * The single write path for punishments (in-game commands, menus, proxy console and web commands).
 *
 * <p>Every change and its outbox event are written in one database transaction. Concurrent changes for the
 * same target are serialized by a row lock on {@code jb_players}.
 */
public final class PunishmentService {

	private static final int MAX_REASON_LENGTH = 512;

	private final Database database;
	private final String nodeId;
	private final Clock clock;
	private final ExemptionChecker exemptions;

	private final PlayerRepository players = new PlayerRepository();
	private final PunishmentRepository punishments = new PunishmentRepository();
	private final EventRepository events = new EventRepository();
	private final ReasonRepository reasons = new ReasonRepository();
	private final ServerRepository servers = new ServerRepository();

	private volatile Runnable eventsAppendedHook = () -> {
	};

	public PunishmentService(Database database, String nodeId, Clock clock, ExemptionChecker exemptions) {
		this.database = database;
		this.nodeId = nodeId;
		this.clock = clock;
		this.exemptions = exemptions;
	}

	/**
	 * Called after a transaction that appended outbox events has committed (for example to wake the
	 * {@code OutboxPublisher}). Runs on the calling database thread; keep it fast and non-throwing.
	 */
	public void onEventsAppended(Runnable hook) {
		this.eventsAppendedHook = hook;
	}

	private void notifyEventsAppended() {
		try {
			this.eventsAppendedHook.run();
		} catch (final RuntimeException ignored) {
			// the periodic drain will pick the events up
		}
	}

	// ------------------------------------------------------------------------------------------
	// Issue
	// ------------------------------------------------------------------------------------------

	public CompletableFuture<Result<Punishment>> issue(PunishmentRequest request) {
		return this.database.supplyAsync(() -> this.issueBlocking(request));
	}

	/** Blocking form of {@link #issue}. Only call from a database or other non-main thread. */
	public Result<Punishment> issueBlocking(PunishmentRequest request) throws SQLException {
		final Result<Punishment> result = this.issueTransaction(request);

		if (result.isOk())
			this.notifyEventsAppended();

		return result;
	}

	private Result<Punishment> issueTransaction(PunishmentRequest request) throws SQLException {
		final Instant now = this.clock.instant();

		final Result<Plan> planned = this.database.query(connection -> this.plan(connection, request));

		if (!planned.isOk())
			return Result.error(planned.error(), planned.message());

		final Plan plan = planned.value();

		if (this.exemptions.isExempt(request.actor(), request.targetUuid(), plan.type))
			return Result.error(ErrorCode.TARGET_EXEMPT, request.targetName() + " cannot be punished");

		// The lock needs a row. Create it in its own statement so concurrent first-time punishments cannot deadlock.
		this.database.query(connection -> {
			this.players.ensureExists(connection, request.targetUuid(), request.targetName(), now);
			return null;
		});

		return this.database.transaction(connection -> {
			this.players.lock(connection, request.targetUuid());

			if (request.requestId() != null) {
				final Optional<Punishment> previous = this.punishments.findByRequestId(connection, request.requestId());

				if (previous.isPresent())
					return Result.ok(previous.get());
			}

			if (plan.type == PunishmentType.BAN) {
				final Optional<Punishment> existing = this.punishments.findActiveBan(connection, request.targetUuid(), plan.scope, now);

				if (existing.isPresent()) {
					if (!request.force())
						return Result.error(ErrorCode.ALREADY_BANNED, request.targetName() + " already has an active ban in this scope (#" + existing.get().publicId() + ")");

					this.revokeInTransaction(connection, existing.get(), request.actor(), "Replaced by a new ban", now);
				}
			}

			final Instant expiresAt = plan.type == PunishmentType.KICK ? null : DurationParser.expiry(now, plan.duration);

			final Punishment draft = new Punishment(0, this.newPublicId(connection), plan.type, plan.scope, request.targetUuid(), request.targetName(),
					request.actor(), plan.reasonId, plan.reasonText, request.note(), now, expiresAt, request.originServer(), request.silent(), plan.ladderStep, null);

			final Punishment saved = this.punishments.insert(connection, draft, request.requestId());

			this.events.append(connection, EventType.PUNISHMENT_CREATED, saved.id(), PunishmentJson.eventPayload(saved), this.nodeId, now);

			return Result.ok(saved);
		});
	}

	/** The resolved outcome of a request before anything is written. */
	private record Plan(PunishmentType type, Scope scope, Duration duration, String reasonId, String reasonText, Integer ladderStep) {
	}

	private Result<Plan> plan(Connection connection, PunishmentRequest request) throws SQLException {
		final PunishmentKind kind = request.kind();

		// Scope
		Scope scope = Scope.network();

		if (kind.requiresScopeTarget()) {
			scope = request.scope();

			if (scope == null || scope.type() == ScopeType.NETWORK)
				return Result.error(ErrorCode.INVALID_REQUEST, kind + " requires a server or group scope");

			final boolean known = scope.type() == ScopeType.SERVER ? this.servers.exists(connection, scope.target()) : this.servers.groupExists(connection, scope.target());

			if (!known)
				return Result.error(ErrorCode.UNKNOWN_SERVER, "Unknown " + scope.type().name().toLowerCase() + " '" + scope.target() + "'");
		}

		// Reason
		ReasonDefinition reason = null;
		final String reasonText;

		if (request.reasonId() != null) {
			reason = this.reasons.find(connection, request.reasonId()).filter(ReasonDefinition::enabled).orElse(null);

			if (reason == null)
				return Result.error(ErrorCode.UNKNOWN_REASON, "Unknown reason '" + request.reasonId() + "'");

			if (!reason.allows(kind))
				return Result.error(ErrorCode.REASON_TYPE_NOT_ALLOWED, "Reason '" + reason.id() + "' cannot be used for " + kind);

			reasonText = reason.displayName();

		} else if (request.customReason() != null && !request.customReason().isBlank()) {
			reasonText = request.customReason().strip().length() > MAX_REASON_LENGTH ? request.customReason().strip().substring(0, MAX_REASON_LENGTH) : request.customReason().strip();

		} else
			return Result.error(ErrorCode.INVALID_REQUEST, "A reason is required");

		// Type, duration and ladder
		PunishmentType type = kind.type();
		Duration duration = request.duration();
		Integer ladderStep = null;

		if (reason != null && reason.hasLadder()) {
			final int prior = this.punishments.countPriorOffences(connection, request.targetUuid(), reason.id());
			final ReasonDefinition.LadderStep step = reason.stepFor(prior);

			type = step.type();
			ladderStep = Math.min(prior, reason.ladder().size() - 1);

			if (duration == null)
				duration = step.duration() == null ? DurationParser.PERMANENT : step.duration();

			if (type != PunishmentType.BAN)
				scope = Scope.network();

		} else if (kind.isTemporary()) {
			if (duration == null && reason != null)
				duration = reason.defaultDuration();

			if (duration == null || DurationParser.isPermanent(duration))
				return Result.error(ErrorCode.INVALID_DURATION, kind + " requires a finite duration");

		} else if (type == PunishmentType.BAN) {
			if (duration != null && !DurationParser.isPermanent(duration))
				return Result.error(ErrorCode.INVALID_DURATION, kind + " is permanent; use the tempban variant for a duration");

			duration = DurationParser.PERMANENT;
		}

		if (duration != null && !DurationParser.isPermanent(duration) && (duration.isZero() || duration.isNegative() || duration.compareTo(DurationParser.MAX) > 0))
			return Result.error(ErrorCode.INVALID_DURATION, "Duration out of range");

		return Result.ok(new Plan(type, scope, duration, reason == null ? null : reason.id(), reasonText, ladderStep));
	}

	private String newPublicId(Connection connection) throws SQLException {
		for (int attempt = 0; attempt < 10; attempt++) {
			final String id = PublicIdGenerator.next();

			if (!this.punishments.publicIdExists(connection, id))
				return id;
		}

		throw new IllegalStateException("Could not generate a unique public punishment id");
	}

	// ------------------------------------------------------------------------------------------
	// Revoke
	// ------------------------------------------------------------------------------------------

	public CompletableFuture<Result<Punishment>> revoke(RevokeRequest request) {
		return this.database.supplyAsync(() -> this.revokeBlocking(request));
	}

	/** Blocking form of {@link #revoke}. Only call from a database or other non-main thread. */
	public Result<Punishment> revokeBlocking(RevokeRequest request) throws SQLException {
		final Result<Punishment> result = this.revokeTransaction(request);

		if (result.isOk())
			this.notifyEventsAppended();

		return result;
	}

	private Result<Punishment> revokeTransaction(RevokeRequest request) throws SQLException {
		final Instant now = this.clock.instant();

		return this.database.transaction(connection -> {
			Punishment target;

			if (request.punishmentId() != null) {
				final Optional<Punishment> found = this.punishments.findById(connection, request.punishmentId());

				if (found.isEmpty())
					return Result.error(ErrorCode.UNKNOWN_PUNISHMENT, "No punishment with id " + request.punishmentId());

				target = found.get();
				this.players.lock(connection, target.targetUuid());

				// Re-read under the lock so we never act on a stale view.
				target = this.punishments.findById(connection, target.id()).orElse(target);

				if (!target.isActive(now))
					return Result.error(ErrorCode.NOT_ACTIVE, "Punishment #" + target.publicId() + " is not active");

			} else {
				this.players.lock(connection, request.targetUuid());

				if (request.type() == PunishmentType.BAN) {
					if (request.scope() == null)
						return Result.error(ErrorCode.INVALID_REQUEST, "A scope is required to pardon a ban");

					final Optional<Punishment> ban = this.punishments.findActiveBan(connection, request.targetUuid(), request.scope(), now);

					if (ban.isEmpty())
						return Result.error(ErrorCode.NOT_BANNED, "Player has no active ban in this scope");

					target = ban.get();

				} else if (request.type() == PunishmentType.WARN) {
					final List<Punishment> warns = this.punishments.findActiveWarns(connection, request.targetUuid(), now);

					if (warns.isEmpty())
						return Result.error(ErrorCode.NOT_ACTIVE, "Player has no active warns");

					target = warns.get(warns.size() - 1);

				} else
					return Result.error(ErrorCode.INVALID_REQUEST, "Kicks cannot be revoked");
			}

			return Result.ok(this.revokeInTransaction(connection, target, request.actor(), request.reason(), now));
		});
	}

	/** Marks the row revoked and appends the event; returns the updated snapshot. */
	private Punishment revokeInTransaction(Connection connection, Punishment target, Actor by, String reason, Instant now) throws SQLException {
		if (!this.punishments.revoke(connection, target.id(), now, by, reason))
			throw new SQLException("Punishment " + target.id() + " was revoked concurrently despite holding the target lock");

		final Punishment revoked = this.punishments.findById(connection, target.id()).orElseThrow();

		this.events.append(connection, EventType.PUNISHMENT_REVOKED, revoked.id(), PunishmentJson.eventPayload(revoked), this.nodeId, now);

		return revoked;
	}
}
