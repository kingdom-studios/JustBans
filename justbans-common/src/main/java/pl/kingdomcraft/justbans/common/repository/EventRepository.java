package pl.kingdomcraft.justbans.common.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import pl.kingdomcraft.justbans.common.database.Sql;
import pl.kingdomcraft.justbans.common.domain.EventRecord;
import pl.kingdomcraft.justbans.common.domain.EventType;

/** Stateless JDBC access to {@code jb_events}: audit log and transactional outbox. */
public final class EventRepository {

	/** @return the new event id (also the {@code seq} used in the Redis envelope) */
	public long append(Connection connection, EventType type, Long punishmentId, String payloadJson, String originNode, Instant now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO jb_events (event_type, punishment_id, payload, origin_node, created_at) VALUES (?, ?, ?, ?, ?)",
				Statement.RETURN_GENERATED_KEYS)) {
			statement.setString(1, type.name());

			if (punishmentId == null)
				statement.setNull(2, java.sql.Types.BIGINT);
			else
				statement.setLong(2, punishmentId);

			statement.setString(3, payloadJson);
			statement.setString(4, originNode);
			Sql.setInstant(statement, 5, now);
			statement.executeUpdate();

			try (ResultSet keys = statement.getGeneratedKeys()) {
				keys.next();

				return keys.getLong(1);
			}
		}
	}

	/** This node's events not yet published to Redis, oldest first. */
	public List<EventRecord> unpublished(Connection connection, String originNode, int limit) throws SQLException {
		return query(connection, "WHERE origin_node = ? AND published_at IS NULL ORDER BY id LIMIT ?",
				statement -> {
					statement.setString(1, originNode);
					statement.setInt(2, limit);
				});
	}

	/** Events with an id greater than {@code afterId}, for replay and for polling while Redis is down. */
	public List<EventRecord> since(Connection connection, long afterId, int limit) throws SQLException {
		return query(connection, "WHERE id > ? ORDER BY id LIMIT ?",
				statement -> {
					statement.setLong(1, afterId);
					statement.setInt(2, limit);
				});
	}

	public void markPublished(Connection connection, long id, Instant at) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE jb_events SET published_at = ? WHERE id = ? AND published_at IS NULL")) {
			Sql.setInstant(statement, 1, at);
			statement.setLong(2, id);
			statement.executeUpdate();
		}
	}

	/** Deletes events older than the cutoff (retention). */
	public int deleteOlderThan(Connection connection, Instant cutoff) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("DELETE FROM jb_events WHERE created_at < ? AND published_at IS NOT NULL")) {
			Sql.setInstant(statement, 1, cutoff);

			return statement.executeUpdate();
		}
	}

	@FunctionalInterface
	private interface Binder {
		void bind(PreparedStatement statement) throws SQLException;
	}

	private List<EventRecord> query(Connection connection, String clause, Binder binder) throws SQLException {
		final List<EventRecord> events = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM jb_events " + clause)) {
			binder.bind(statement);

			try (ResultSet result = statement.executeQuery()) {
				while (result.next()) {
					final long punishmentId = result.getLong("punishment_id");
					final Long punishment = result.wasNull() ? null : punishmentId;

					events.add(new EventRecord(result.getLong("id"), EventType.valueOf(result.getString("event_type")), punishment,
							result.getString("payload"), result.getString("origin_node"), Sql.instant(result, "created_at"),
							Sql.instant(result, "published_at")));
				}
			}
		}

		return events;
	}
}
