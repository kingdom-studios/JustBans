package pl.kingdomcraft.justbans.common.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import pl.kingdomcraft.justbans.common.database.Sql;

/** Stateless JDBC access to {@code jb_command_log}: exactly-once claiming of web commands. */
public final class CommandLogRepository {

	public enum Status {
		PROCESSING,
		OK,
		ERROR
	}

	/** A stored command. {@code result} is the JSON published as the command result, once finished. */
	public record Entry(UUID requestId, String action, Instant receivedAt, String handledBy, Status status, String result) {
	}

	/**
	 * Tries to claim the request by inserting its row. The primary key makes this atomic across nodes.
	 *
	 * @return true if this call created the row (this node owns the command), false if it already existed
	 */
	public boolean claim(Connection connection, UUID requestId, String action, String node, Instant now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT IGNORE INTO jb_command_log (request_id, action, received_at, handled_by, status) VALUES (?, ?, ?, ?, 'PROCESSING')")) {
			Sql.setUuid(statement, 1, requestId);
			statement.setString(2, action);
			Sql.setInstant(statement, 3, now);
			statement.setString(4, node);

			return statement.executeUpdate() == 1;
		}
	}

	public void finish(Connection connection, UUID requestId, Status status, String resultJson) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE jb_command_log SET status = ?, result = ? WHERE request_id = ?")) {
			statement.setString(1, status.name());
			statement.setString(2, resultJson);
			Sql.setUuid(statement, 3, requestId);
			statement.executeUpdate();
		}
	}

	public Optional<Entry> find(Connection connection, UUID requestId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM jb_command_log WHERE request_id = ?")) {
			Sql.setUuid(statement, 1, requestId);

			try (ResultSet result = statement.executeQuery()) {
				if (!result.next())
					return Optional.empty();

				return Optional.of(new Entry(Sql.uuid(result, "request_id"), result.getString("action"), Sql.instant(result, "received_at"),
						result.getString("handled_by"), Status.valueOf(result.getString("status")), result.getString("result")));
			}
		}
	}

	/** Deletes finished entries older than the cutoff (retention). */
	public int deleteOlderThan(Connection connection, Instant cutoff) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("DELETE FROM jb_command_log WHERE received_at < ? AND status <> 'PROCESSING'")) {
			Sql.setInstant(statement, 1, cutoff);

			return statement.executeUpdate();
		}
	}
}
