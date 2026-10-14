package pl.kingdomcraft.justbans.velocity.reasons;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.domain.EventType;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.repository.EventRepository;
import pl.kingdomcraft.justbans.common.repository.ReasonRepository;
import pl.kingdomcraft.justbans.velocity.config.ReasonsFile;

/**
 * Makes {@code jb_reasons} match reasons.yml and queues a REASONS_UPDATED event, in one transaction, so Paper servers
 * (and the web panel) reload exactly when the table really changed. The event goes out through the normal outbox.
 */
public final class ReasonSync {

	private final Database database;
	private final String nodeId;
	private final Clock clock;
	private final ReasonRepository reasons = new ReasonRepository();
	private final EventRepository events = new EventRepository();

	public ReasonSync(Database database, String nodeId, Clock clock) {
		this.database = database;
		this.nodeId = nodeId;
		this.clock = clock;
	}

	/**
	 * @return number of reasons now defined
	 */
	public int sync(ReasonsFile file) throws SQLException {
		final Instant now = this.clock.instant();

		final JsonArray ids = new JsonArray();
		file.reasons().stream().map(ReasonDefinition::id).forEach(ids::add);

		final JsonObject payload = new JsonObject();
		payload.add("reasons", ids);

		this.database.transaction(connection -> {
			this.reasons.sync(connection, file.reasons(), now);
			this.events.append(connection, EventType.REASONS_UPDATED, null, payload.toString(), this.nodeId, now);

			return null;
		});

		return file.reasons().size();
	}
}
