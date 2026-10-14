package pl.kingdomcraft.justbans.velocity;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.database.Migrator;
import pl.kingdomcraft.justbans.common.domain.EventType;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.json.PunishmentJson;
import pl.kingdomcraft.justbans.common.messaging.HmacSigner;
import pl.kingdomcraft.justbans.common.messaging.OutboxPublisher;
import pl.kingdomcraft.justbans.common.messaging.RedisBus;
import pl.kingdomcraft.justbans.common.messaging.WebCommandHandler;
import pl.kingdomcraft.justbans.common.repository.EventRepository;
import pl.kingdomcraft.justbans.common.repository.PunishmentRepository;
import pl.kingdomcraft.justbans.common.repository.ServerRepository;
import pl.kingdomcraft.justbans.common.service.ErrorCode;
import pl.kingdomcraft.justbans.common.service.ExemptionChecker;
import pl.kingdomcraft.justbans.common.service.PunishmentService;
import pl.kingdomcraft.justbans.common.service.Result;
import pl.kingdomcraft.justbans.velocity.command.JbProxyCommand;
import pl.kingdomcraft.justbans.velocity.config.ConfigException;
import pl.kingdomcraft.justbans.velocity.config.ConfigLoader;
import pl.kingdomcraft.justbans.velocity.config.PluginConfig;
import pl.kingdomcraft.justbans.velocity.config.ReasonsFile;
import pl.kingdomcraft.justbans.velocity.config.ReasonsLoader;
import pl.kingdomcraft.justbans.velocity.enforce.Enforcer;
import pl.kingdomcraft.justbans.velocity.enforce.ScreenRenderer;
import pl.kingdomcraft.justbans.velocity.gate.ActiveBanCache;
import pl.kingdomcraft.justbans.velocity.gate.ConnectionGate;
import pl.kingdomcraft.justbans.velocity.gate.ServerDirectory;
import pl.kingdomcraft.justbans.velocity.reasons.ReasonSync;
import pl.kingdomcraft.justbans.velocity.track.OnlineDirectory;
import pl.kingdomcraft.justbans.velocity.track.PlayerTracker;
import pl.kingdomcraft.justbans.velocity.util.Slf4jHandler;

/**
 * Wires the whole plugin together and owns everything that must be closed on shutdown. Startup order matters: database
 * and schema first, then Redis and the outbox, then reasons, and only then the listeners, so no player is ever judged
 * before the bans can be read.
 */
public final class VelocityRuntime implements AutoCloseable {

	private final Logger logger;
	private final Database database;
	private final RedisBus bus;
	private final OutboxPublisher outbox;
	private final AtomicReference<ReasonsFile> reasons;
	private final ReasonSync reasonSync;
	private final Path reasonsFile;

	private VelocityRuntime(Logger logger, Database database, RedisBus bus, OutboxPublisher outbox, AtomicReference<ReasonsFile> reasons, ReasonSync reasonSync, Path reasonsFile) {
		this.logger = logger;
		this.database = database;
		this.bus = bus;
		this.outbox = outbox;
		this.reasons = reasons;
		this.reasonSync = reasonSync;
		this.reasonsFile = reasonsFile;
	}

	/**
	 * @throws ConfigException if config.yml or reasons.yml is invalid (nothing is started)
	 * @throws Exception       if the database cannot be reached or migrated
	 */
	public static VelocityRuntime start(Object plugin, ProxyServer proxy, org.slf4j.Logger slf4j, Path dataDirectory) throws Exception {
		final Logger logger = Slf4jHandler.bridge("JustBans", slf4j);
		final Clock clock = Clock.systemUTC();

		ConfigLoader.ensureDefault(dataDirectory, "config.yml");
		ConfigLoader.ensureDefault(dataDirectory, "reasons.yml");

		final PluginConfig config = ConfigLoader.load(dataDirectory.resolve("config.yml"));
		final Path reasonsPath = dataDirectory.resolve("reasons.yml");
		final ReasonsFile initialReasons = ReasonsLoader.load(reasonsPath, logger);
		final AtomicReference<ReasonsFile> reasons = new AtomicReference<>(initialReasons);

		final Database database = new Database(config.database());
		RedisBus bus = null;
		OutboxPublisher outbox = null;

		try {
			new Migrator(database, logger).migrate();

			bus = new RedisBus(config.redis(), new HmacSigner(config.webSecret()), config.nodeId(), clock, logger);
			outbox = new OutboxPublisher(database, bus, config.nodeId(), clock, logger, config.outboxIntervalMs());

			final ExemptionChecker exemptions = (target, type) -> proxy.getPlayer(target).map(player -> player.hasPermission("justbans.exempt")).orElse(false);
			final PunishmentService service = new PunishmentService(database, config.nodeId(), clock, exemptions);
			service.onEventsAppended(outbox::wake);

			registerServers(proxy, config, database);

			final ReasonSync reasonSync = new ReasonSync(database, config.nodeId(), clock);
			final int count = reasonSync.sync(initialReasons);
			logger.info("Synced " + count + " reasons to the database");

			final VelocityRuntime runtime = new VelocityRuntime(logger, database, bus, outbox, reasons, reasonSync, reasonsPath);

			final ScreenRenderer screens = new ScreenRenderer(reasons::get, config, clock);
			final ServerDirectory servers = new ServerDirectory(proxy, config);

			final PunishmentRepository punishments = new PunishmentRepository();
			final ActiveBanCache cache = new ActiveBanCache(uuid -> database.query(connection -> punishments.findActiveBans(connection, uuid, clock.instant())),
					Duration.ofSeconds(config.banCacheSeconds()));

			final OnlineDirectory directory = new OnlineDirectory(bus, logger);
			final OutboxPublisher outboxRef = outbox;

			// Listeners
			proxy.getEventManager().register(plugin, new ConnectionGate(config, cache, screens, servers, clock, logger, joinDeniedRecorder(config, database, clock, outboxRef)));
			proxy.getEventManager().register(plugin, new PlayerTracker(database, directory, cache, uuid -> proxy.getPlayer(uuid).isPresent(), clock, logger));

			// Redis
			final Enforcer enforcer = new Enforcer(proxy, cache, screens, servers, database, clock, logger);
			bus.onVerified(bus.channels().events(), enforcer::handle);

			if (config.webCommandsEnabled()) {
				final WebCommandHandler web = new WebCommandHandler(database, service, bus, config.nodeId(), clock, logger, config.webMaxSkew());

				web.register("RELOAD_REASONS", command -> {
					try {
						final JsonObject result = new JsonObject();
						result.addProperty("reloaded", runtime.reloadReasons());

						return Result.ok(result);

					} catch (final ConfigException ex) {
						return Result.error(ErrorCode.INVALID_REQUEST, ex.getMessage());
					}
				});
				web.start();
				logger.info("Web panel commands enabled");
			}

			bus.start();
			outbox.start();

			// Players already online (e.g. the plugin was loaded late): publish them and clear stale entries from a previous run.
			final Map<UUID, OnlineDirectory.Entry> online = new HashMap<>();

			for (final Player player : proxy.getAllPlayers())
				online.put(player.getUniqueId(), new OnlineDirectory.Entry(player.getUsername(), ServerDirectory.currentServerOf(player)));

			database.supplyAsync(() -> {
				directory.rebuild(online);
				return null;
			});

			proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("jbproxy").plugin(plugin).build(),
					new JbProxyCommand(runtime::reloadReasonsMessage, task -> proxy.getScheduler().buildTask(plugin, task).schedule()));

			logger.info("JustBans started as node '" + config.nodeId() + "'");

			return runtime;

		} catch (final Exception | Error ex) {
			if (outbox != null)
				outbox.close();

			if (bus != null)
				bus.close();

			database.close();

			throw ex;
		}
	}

	/**
	 * Re-reads reasons.yml and syncs it to the database. If the file is invalid nothing changes and the previous reasons stay active.
	 *
	 * @return number of reasons now active
	 */
	public int reloadReasons() throws ConfigException {
		final ReasonsFile fresh = ReasonsLoader.load(this.reasonsFile, this.logger);

		try {
			final int count = this.reasonSync.sync(fresh);
			this.reasons.set(fresh);
			this.outbox.wake();

			return count;

		} catch (final SQLException ex) {
			this.logger.log(Level.SEVERE, "Could not sync reasons to the database", ex);
			throw new ConfigException("Reasons were valid but the database sync failed: " + ex.getMessage(), ex);
		}
	}

	private String reloadReasonsMessage() throws ConfigException {
		return "Reloaded " + this.reloadReasons() + " reasons and notified the other servers.";
	}

	@Override
	public void close() {
		try {
			this.outbox.drainOnce(); // best effort: get queued events out before going down
		} catch (final SQLException | RuntimeException ex) {
			this.logger.warning("Could not flush queued events on shutdown; they stay in the database for the next start: " + ex.getMessage());
		}

		this.outbox.close();
		this.bus.close();
		this.database.close();
	}

	// ------------------------------------------------------------------------------------------

	/** Makes {@code jb_servers} list every server the proxy knows, so Paper nodes and the web panel can validate server names. */
	private static void registerServers(ProxyServer proxy, PluginConfig config, Database database) throws SQLException {
		final ServerRepository servers = new ServerRepository();

		database.query(connection -> {
			for (final RegisteredServer server : proxy.getAllServers()) {
				final String name = ServerDirectory.nameOf(server);
				servers.upsert(connection, name, config.groupOf(name), null);
			}

			return null;
		});
	}

	/** Records a JOIN_DENIED event, at most once a minute per player so a client retrying in a loop cannot flood the table. */
	private static java.util.function.Consumer<Punishment> joinDeniedRecorder(PluginConfig config, Database database, Clock clock, OutboxPublisher outbox) {
		final Cache<UUID, Boolean> recent = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(10_000).build();
		final EventRepository events = new EventRepository();

		return ban -> {
			if (!config.joinDeniedEvents() || recent.asMap().putIfAbsent(ban.targetUuid(), Boolean.TRUE) != null)
				return;

			database.supplyAsync(() -> database.query(connection -> events.append(connection, EventType.JOIN_DENIED, ban.id(), PunishmentJson.eventPayload(ban), config.nodeId(), clock.instant())))
					.thenRun(outbox::wake)
					.exceptionally(ex -> null);
		};
	}
}
