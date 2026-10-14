package pl.kingdomcraft.justbans.common.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import pl.kingdomcraft.justbans.common.database.Sql;
import pl.kingdomcraft.justbans.common.domain.ServerRecord;

/** Stateless JDBC access to {@code jb_servers}. */
public final class ServerRepository {

	public void upsert(Connection connection, String id, String groupName, String displayName) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO jb_servers (id, group_name, display_name) VALUES (?, ?, ?) "
						+ "ON DUPLICATE KEY UPDATE group_name = VALUES(group_name), display_name = VALUES(display_name)")) {
			statement.setString(1, id);
			Sql.setString(statement, 2, groupName);
			Sql.setString(statement, 3, displayName);
			statement.executeUpdate();
		}
	}

	public void heartbeat(Connection connection, String id, Instant now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("UPDATE jb_servers SET last_heartbeat = ? WHERE id = ?")) {
			Sql.setInstant(statement, 1, now);
			statement.setString(2, id);
			statement.executeUpdate();
		}
	}

	public Optional<ServerRecord> find(Connection connection, String id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM jb_servers WHERE id = ?")) {
			statement.setString(1, id);

			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? Optional.of(map(result)) : Optional.empty();
			}
		}
	}

	public boolean exists(Connection connection, String id) throws SQLException {
		return find(connection, id).isPresent();
	}

	public List<ServerRecord> findAll(Connection connection) throws SQLException {
		final List<ServerRecord> servers = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM jb_servers ORDER BY id");
				ResultSet result = statement.executeQuery()) {
			while (result.next())
				servers.add(map(result));
		}

		return servers;
	}

	/** Whether any registered server belongs to this group. */
	public boolean groupExists(Connection connection, String groupName) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM jb_servers WHERE group_name = ? LIMIT 1")) {
			statement.setString(1, groupName);

			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	private static ServerRecord map(ResultSet result) throws SQLException {
		return new ServerRecord(result.getString("id"), result.getString("group_name"), result.getString("display_name"), Sql.instant(result, "last_heartbeat"));
	}
}
