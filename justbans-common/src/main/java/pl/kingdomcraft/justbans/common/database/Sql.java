package pl.kingdomcraft.justbans.common.database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

	/**
	 * Writes an instant as its UTC date and time. {@code setTimestamp} and {@code setObject(Instant)} must not be used: the MariaDB
	 * driver then writes the wall-clock time of the JVM's default time zone, whatever {@code connectionTimeZone} says, so on a
	 * machine that is not set to UTC the rows would hold the wrong time and disagree with columns the server fills in itself.
	 */
	public static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
		if (value == null)
			statement.setNull(index, Types.TIMESTAMP);
		else
			statement.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
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

	/** Reads a {@code TIMESTAMP} or {@code DATETIME} column as the UTC instant it holds; see {@link #setInstant}. */
	public static Instant instant(ResultSet result, String column) throws SQLException {
		final LocalDateTime value = result.getObject(column, LocalDateTime.class);

		return value == null ? null : value.toInstant(ZoneOffset.UTC);
	}

	public static Integer integer(ResultSet result, String column) throws SQLException {
		final int value = result.getInt(column);

		return result.wasNull() ? null : value;
	}
}
