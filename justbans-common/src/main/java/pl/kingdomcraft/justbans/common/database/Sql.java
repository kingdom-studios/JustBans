package pl.kingdomcraft.justbans.common.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/** Small JDBC helpers for nullable columns, UUIDs and UTC instants. */
public final class Sql {

	private Sql() {
	}

	public static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.OTHER);
		else
			statement.setObject(index, value);
	}

	public static void setString(PreparedStatement statement, int index, String value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.VARCHAR);
		else
			statement.setString(index, value);
	}

	public static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.TIMESTAMP);
		else
			statement.setTimestamp(index, Timestamp.from(value));
	}

	public static void setInt(PreparedStatement statement, int index, Integer value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.INTEGER);
		else
			statement.setInt(index, value);
	}

	public static UUID uuid(ResultSet result, String column) throws SQLException {
		return result.getObject(column, UUID.class);
	}

	public static Instant instant(ResultSet result, String column) throws SQLException {
		final Timestamp timestamp = result.getTimestamp(column);

		return timestamp == null ? null : timestamp.toInstant();
	}

	public static Integer integer(ResultSet result, String column) throws SQLException {
		final int value = result.getInt(column);

		return result.wasNull() ? null : value;
	}
}
