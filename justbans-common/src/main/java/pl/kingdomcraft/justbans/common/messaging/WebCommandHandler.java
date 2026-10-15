package pl.kingdomcraft.justbans.common.messaging;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.PunishmentType;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ScopeType;
import pl.kingdomcraft.justbans.common.repository.CommandLogRepository;
import pl.kingdomcraft.justbans.common.service.ErrorCode;
import pl.kingdomcraft.justbans.common.service.PunishmentRequest;
import pl.kingdomcraft.justbans.common.service.PunishmentService;
import pl.kingdomcraft.justbans.common.service.Result;
import pl.kingdomcraft.justbans.common.service.RevokeRequest;
import pl.kingdomcraft.justbans.common.service.TargetResolver;
import pl.kingdomcraft.justbans.common.service.UpdateRequest;
import pl.kingdomcraft.justbans.common.time.DurationParser;

/**
 * Executes signed commands from the web panel exactly as if an admin had typed them, through {@link PunishmentService}.
 *
 * <p>Pipeline per message on {@code <prefix>commands}:
 * <ol>
 * <li>signature must verify, otherwise it is dropped (the sender cannot be trusted, so not even answered);</li>
 * <li>the request is <b>claimed</b> by inserting its {@code requestId} into {@code jb_command_log}; whichever node's
 * insert succeeds owns it, every other node ignores the message (exactly-once across all nodes);</li>
 * <li>the timestamp must be within {@code maxSkew}, otherwise {@code EXPIRED_REQUEST};</li>
 * <li>the command runs, the result is stored in the log and published on {@code <prefix>command-results}.</li>
 * </ol>
 * A web retry with the same {@code requestId} makes the owning node re-publish the stored result instead of running
 * the command again.
 *
 * <p>Commands are typed actions only: nothing received over Redis is ever executed as console text.
 */
public final class WebCommandHandler {

	public static final String RESULT_TYPE = "COMMAND_RESULT";

	/** Extra command types (e.g. {@code RELOAD_REASONS} on Velocity). Runs on a database thread. */
	@FunctionalInterface
	public interface CommandAction {
		/**
		 * @return fields to merge into the OK result, or an error result
		 */
		Result<JsonObject> execute(Envelope command) throws SQLException;
	}

	private final Database database;
	private final PunishmentService service;
	private final RedisBus bus;
	private final String nodeId;
	private final Clock clock;
	private final Logger logger;
	private final Duration maxSkew;

	private final CommandLogRepository commandLog = new CommandLogRepository();
	private final TargetResolver targets = new TargetResolver();
	private final Map<String, CommandAction> actions = new ConcurrentHashMap<>();

	public WebCommandHandler(Database database, PunishmentService service, RedisBus bus, String nodeId, Clock clock, Logger logger, Duration maxSkew) {
		this.database = database;
		this.service = service;
		this.bus = bus;
		this.nodeId = nodeId;
		this.clock = clock;
		this.logger = logger;
		this.maxSkew = maxSkew;

		this.register("ISSUE_PUNISHMENT", this::issue);
		this.register("REVOKE_PUNISHMENT", this::revoke);
		this.register("UPDATE_PUNISHMENT", this::update);
		this.register("PING", command -> {
			final JsonObject pong = new JsonObject();
			pong.addProperty("node", this.nodeId);
			pong.addProperty("pong", true);

			return Result.ok(pong);
		});
	}

	public void register(String type, CommandAction action) {
		this.actions.put(type, action);
	}

	/** Starts listening on the commands channel. */
	public void start() {
		this.bus.listen(this.bus.channels().commands(), this::onDelivery);
	}

	// ------------------------------------------------------------------------------------------

	private void onDelivery(RedisBus.Delivery delivery) {
		if (!delivery.signatureValid())
			return; // RedisBus already logged it

		final Envelope command = delivery.envelope();
		final UUID requestId = requestId(command);

		if (requestId == null) {
			this.logger.warning("Dropped web command " + command.type() + " without a valid requestId");
			return;
		}

		this.database.supplyAsync(() -> {
			this.process(command, requestId);
			return null;

		}).exceptionally(ex -> {
			this.logger.log(Level.SEVERE, "Web command " + requestId + " failed unexpectedly", ex);
			return null;
		});
	}

	private void process(Envelope command, UUID requestId) throws SQLException {
		final boolean claimed = this.database.query(connection -> this.commandLog.claim(connection, requestId, command.type(), this.nodeId, this.clock.instant()));

		if (!claimed) {
			this.resendIfOurs(requestId);
			return;
		}

		JsonObject result;
		CommandLogRepository.Status status;

		try {
			final Result<JsonObject> outcome = this.run(command);

			result = base(requestId, command.type(), outcome.isOk());
			status = outcome.isOk() ? CommandLogRepository.Status.OK : CommandLogRepository.Status.ERROR;

			if (outcome.isOk())
				for (final Map.Entry<String, JsonElement> entry : outcome.value().entrySet())
					result.add(entry.getKey(), entry.getValue());
			else
				error(result, outcome.error(), outcome.message());

		} catch (final RuntimeException | SQLException ex) {
			this.logger.log(Level.SEVERE, "Web command " + requestId + " (" + command.type() + ") crashed", ex);

			result = base(requestId, command.type(), false);
			status = CommandLogRepository.Status.ERROR;
			error(result, ErrorCode.INTERNAL, "Internal error, see the server log");
		}

		final String resultJson = result.toString();
		final CommandLogRepository.Status finalStatus = status;

		this.database.query(connection -> {
			this.commandLog.finish(connection, requestId, finalStatus, resultJson);
			return null;
		});

		this.publishResult(result);
	}

	private Result<JsonObject> run(Envelope command) throws SQLException {
		if (!HmacSigner.isFresh(command, this.clock, this.maxSkew))
			return Result.error(ErrorCode.EXPIRED_REQUEST, "Request timestamp is more than " + this.maxSkew.toSeconds() + "s away from this node's clock");

		final CommandAction action = this.actions.get(command.type());

		if (action == null)
			return Result.error(ErrorCode.INVALID_REQUEST, "Unsupported command type '" + command.type() + "'");

		try {
			return action.execute(command);

		} catch (final MalformedPayloadException ex) {
			return Result.error(ErrorCode.INVALID_REQUEST, ex.getMessage());
		}
	}

	private void resendIfOurs(UUID requestId) throws SQLException {
		final Optional<CommandLogRepository.Entry> entry = this.database.query(connection -> this.commandLog.find(connection, requestId));

		if (entry.isEmpty() || !this.nodeId.equals(entry.get().handledBy()) || entry.get().status() == CommandLogRepository.Status.PROCESSING || entry.get().result() == null)
			return; // another node owns it, or it is still running

		this.publishResult(com.google.gson.JsonParser.parseString(entry.get().result()).getAsJsonObject());
	}

	private void publishResult(JsonObject result) {
		try {
			this.bus.publish(this.bus.channels().commandResults(), this.bus.newEnvelope(RESULT_TYPE, null, result));

		} catch (final BusException ex) {
			this.logger.warning("Could not publish command result (the web panel can re-send the request to get it again): " + ex.getMessage());
		}
	}

	// ------------------------------------------------------------------------------------------
	// ISSUE_PUNISHMENT / REVOKE_PUNISHMENT
	// ------------------------------------------------------------------------------------------

	private Result<JsonObject> issue(Envelope command) throws SQLException {
		final JsonObject p = command.payload();
		final UUID requestId = requestId(command);

		final Actor actor = actor(p);
		final JsonObject target = object(p, "target");
		final Scope scope = scope(p);
		final PunishmentKind kind = kind(string(p, "kind"), scope);

		Duration duration = null;
		final String durationText = string(p, "duration");

		if (durationText != null)
			try {
				duration = DurationParser.parse(durationText);
			} catch (final IllegalArgumentException ex) {
				return Result.error(ErrorCode.INVALID_DURATION, ex.getMessage());
			}

		final Result<TargetResolver.Target> resolved = this.database.query(connection -> this.targets.resolve(connection, string(target, "uuid"), string(target, "name")));

		if (!resolved.isOk())
			return Result.error(resolved.error(), resolved.message());

		final PunishmentRequest request = PunishmentRequest.builder(kind, resolved.value().uuid(), resolved.value().name(), actor)
				.requestId(requestId)
				.scope(scope)
				.reasonId(string(p, "reasonId"))
				.customReason(string(p, "customReason"))
				.duration(duration)
				.silent(bool(p, "silent"))
				.note(string(p, "note"))
				.force(bool(p, "force"))
				.originServer("web")
				.build();

		return this.summarize(this.service.issueBlocking(request));
	}

	private Result<JsonObject> revoke(Envelope command) throws SQLException {
		final JsonObject p = command.payload();
		final Actor actor = actor(p);
		final String reason = string(p, "reason");

		final RevokeRequest request;

		if (p.has("punishmentId") && !p.get("punishmentId").isJsonNull()) {
			request = RevokeRequest.byId(actor, number(p, "punishmentId"), reason);

		} else {
			final JsonObject target = object(p, "target");
			final Result<TargetResolver.Target> resolved = this.database.query(connection -> this.targets.resolve(connection, string(target, "uuid"), string(target, "name")));

			if (!resolved.isOk())
				return Result.error(resolved.error(), resolved.message());

			final String kind = string(p, "kind") == null ? "BAN" : string(p, "kind").toUpperCase();

			request = switch (kind) {
				case "BAN", "TEMPBAN" -> {
					final Scope scope = scope(p);
					yield RevokeRequest.ban(actor, resolved.value().uuid(), scope == null ? Scope.network() : scope, reason);
				}
				case "WARN" -> RevokeRequest.lastWarn(actor, resolved.value().uuid(), reason);
				default -> throw new MalformedPayloadException("Cannot revoke kind '" + kind + "'");
			};
		}

		return this.summarize(this.service.revokeBlocking(request));
	}

	/**
	 * {@code UPDATE_PUNISHMENT}: {@code punishmentId} plus any of {@code expiresAt} (epoch milliseconds, or JSON {@code null}
	 * for permanent), {@code reasonText} and {@code note} (JSON {@code null} or an empty string removes it). Fields that are
	 * absent stay as they are.
	 */
	private Result<JsonObject> update(Envelope command) throws SQLException {
		final JsonObject p = command.payload();
		final UpdateRequest.Builder builder = UpdateRequest.builder(actor(p), number(p, "punishmentId"));

		if (p.has("expiresAt"))
			builder.expiresAt(p.get("expiresAt").isJsonNull() ? null : Instant.ofEpochMilli(number(p, "expiresAt")));

		if (p.has("reasonText"))
			builder.reasonText(requireString(p, "reasonText"));

		if (p.has("note"))
			builder.note(p.get("note").isJsonNull() ? "" : string(p, "note"));

		return this.summarize(this.service.updateBlocking(builder.build()));
	}

	private Result<JsonObject> summarize(Result<Punishment> result) {
		if (!result.isOk())
			return Result.error(result.error(), result.message());

		final JsonObject json = new JsonObject();
		json.addProperty("punishmentId", result.value().id());
		json.addProperty("publicId", result.value().publicId());

		return Result.ok(json);
	}

	// ------------------------------------------------------------------------------------------
	// Payload parsing
	// ------------------------------------------------------------------------------------------

	private static final class MalformedPayloadException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		MalformedPayloadException(String message) {
			super(message);
		}
	}

	private static UUID requestId(Envelope command) {
		try {
			final String text = string(command.payload(), "requestId");

			return text == null ? null : UUID.fromString(text);

		} catch (final RuntimeException ex) {
			return null;
		}
	}

	private static Actor actor(JsonObject payload) {
		final JsonObject actor = object(payload, "actor");
		final String name = string(actor, "name");

		if (name == null || name.isBlank())
			throw new MalformedPayloadException("actor.name is required");

		return Actor.web(string(actor, "ref"), name);
	}

	private static Scope scope(JsonObject payload) {
		final JsonElement element = payload.get("scope");

		if (element == null || element.isJsonNull())
			return null;

		if (!element.isJsonObject())
			throw new MalformedPayloadException("scope must be an object");

		final JsonObject scope = element.getAsJsonObject();

		try {
			return new Scope(ScopeType.valueOf(requireString(scope, "type").toUpperCase()), string(scope, "target") == null ? Scope.ALL : string(scope, "target"));
		} catch (final IllegalArgumentException ex) {
			throw new MalformedPayloadException("Invalid scope: " + ex.getMessage());
		}
	}

	private static PunishmentKind kind(String text, Scope scope) {
		if (text == null)
			throw new MalformedPayloadException("kind is required");

		final boolean scoped = scope != null && scope.type() != ScopeType.NETWORK;

		try {
			return switch (text.toUpperCase()) {
				case "BAN" -> scoped ? PunishmentKind.SERVER_BAN : PunishmentKind.BAN;
				case "TEMPBAN" -> scoped ? PunishmentKind.SERVER_TEMPBAN : PunishmentKind.TEMPBAN;
				default -> PunishmentKind.valueOf(text.toUpperCase());
			};
		} catch (final IllegalArgumentException ex) {
			throw new MalformedPayloadException("Unknown kind '" + text + "'");
		}
	}

	private static JsonObject object(JsonObject parent, String key) {
		final JsonElement element = parent.get(key);

		if (element == null || !element.isJsonObject())
			throw new MalformedPayloadException("'" + key + "' must be an object");

		return element.getAsJsonObject();
	}

	private static String requireString(JsonObject parent, String key) {
		final String value = string(parent, key);

		if (value == null)
			throw new MalformedPayloadException("'" + key + "' is required");

		return value;
	}

	private static String string(JsonObject parent, String key) {
		final JsonElement element = parent.get(key);

		if (element == null || element.isJsonNull())
			return null;

		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
			throw new MalformedPayloadException("'" + key + "' must be a string");

		return element.getAsString();
	}

	private static boolean bool(JsonObject parent, String key) {
		final JsonElement element = parent.get(key);

		if (element == null || element.isJsonNull())
			return false;

		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean())
			throw new MalformedPayloadException("'" + key + "' must be a boolean");

		return element.getAsBoolean();
	}

	private static long number(JsonObject parent, String key) {
		final JsonElement element = parent.get(key);

		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
			throw new MalformedPayloadException("'" + key + "' must be a number");

		return element.getAsLong();
	}

	private static JsonObject base(UUID requestId, String action, boolean ok) {
		final JsonObject json = new JsonObject();

		json.addProperty("requestId", requestId.toString());
		json.addProperty("action", action);
		json.addProperty("status", ok ? "OK" : "ERROR");

		return json;
	}

	private static void error(JsonObject json, ErrorCode code, String message) {
		json.addProperty("error", code.name());
		json.addProperty("message", message);
	}
}
