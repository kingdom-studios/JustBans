package pl.kingdomcraft.justbans.common.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import pl.kingdomcraft.justbans.common.database.Sql;
import pl.kingdomcraft.justbans.common.domain.PlayerRecord;

/** Stateless JDBC access to {@code jb_players} and {@code jb_player_names}. */
public final class PlayerRepository {

	/**
	 * Records that a player was seen: creates the row if needed, updates the current name and
	 * last-seen time, and adds the name to the history.
	 */
	public void touch(Connection connection, UUID uuid, String name, Instant now, String server) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO jb_players (uuid, last_name, last_name_lower, first_seen, last_seen, last_server) VALUES (?, ?, ?, ?, ?, ?) "
						+ "ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), last_name_lower = VALUES(last_name_lower), "
						+ "last_seen = VALUES(last_seen), last_server = COALESCE(VALUES(last_server), last_server)")) {
			Sql.setUuid(statement, 1, uuid);
			statement.setString(2, name);
			statement.setString(3, lower(name));
			Sql.setInstant(statement, 4, now);
			Sql.setInstant(statement, 5, now);
			Sql.setString(statement, 6, server);
			statement.executeUpdate();
		}

		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO jb_player_names (uuid, name, name_lower, first_seen, last_seen) VALUES (?, ?, ?, ?, ?) "
						+ "ON DUPLICATE KEY UPDATE name = VALUES(name), last_seen = VALUES(last_seen)")) {
			Sql.setUuid(statement, 1, uuid);
			statement.setString(2, name);
			statement.setString(3, lower(name));
			Sql.setInstant(statement, 4, now);
			Sql.setInstant(statement, 5, now);
			statement.executeUpdate();
		}
	}

	/**
	 * Inserts a placeholder row for a player who has never joined, so punishments can lock it.
	 * Does nothing if the row exists. Run this outside the punishment transaction.
	 */
	public void ensureExists(Connection connection, UUID uuid, String name, Instant now) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT IGNORE INTO jb_players (uuid, last_name, last_name_lower, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)")) {
			Sql.setUuid(statement, 1, uuid);
			statement.setString(2, name);
			statement.setString(3, lower(name));
			Sql.setInstant(statement, 4, now);
			Sql.setInstant(statement, 5, now);
			statement.executeUpdate();
		}
	}

	/**
	 * Row-locks the player for the rest of the transaction, serializing concurrent punishments for one target.
	 *
	 * @return false if the row does not exist
	 */
	public boolean lock(Connection connection, UUID uuid) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT uuid FROM jb_players WHERE uuid = ? FOR UPDATE")) {
			Sql.setUuid(statement, 1, uuid);

			try (ResultSet result = statement.executeQuery()) {
				return result.next();
			}
		}
	}

	public Optional<PlayerRecord> findByUuid(Connection connection, UUID uuid) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM jb_players WHERE uuid = ?")) {
			Sql.setUuid(statement, 1, uuid);

			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? Optional.of(map(result)) : Optional.empty();
			}
		}
	}

	/**
	 * Players who currently have or previously had this name (case-insensitive), most recently seen first.
	 * More than one result means the name is ambiguous.
	 */
	public List<PlayerRecord> findByName(Connection connection, String name) throws SQLException {
		final List<PlayerRecord> players = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT DISTINCT p.* FROM jb_players p "
						+ "LEFT JOIN jb_player_names n ON n.uuid = p.uuid "
						+ "WHERE p.last_name_lower = ? OR n.name_lower = ? "
						+ "ORDER BY p.last_seen DESC")) {
			statement.setString(1, lower(name));
			statement.setString(2, lower(name));

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					players.add(map(result));
			}
		}

		return players;
	}

	public List<String> nameHistory(Connection connection, UUID uuid) throws SQLException {
		final List<String> names = new ArrayList<>();

		try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM jb_player_names WHERE uuid = ? ORDER BY last_seen DESC")) {
			Sql.setUuid(statement, 1, uuid);

			try (ResultSet result = statement.executeQuery()) {
				while (result.next())
					names.add(result.getString(1));
			}
		}

		return names;
	}

	private static PlayerRecord map(ResultSet result) throws SQLException {
		return new PlayerRecord(Sql.uuid(result, "uuid"), result.getString("last_name"), Sql.instant(result, "first_seen"),
				Sql.instant(result, "last_seen"), result.getString("last_server"));
	}

	private static String lower(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
