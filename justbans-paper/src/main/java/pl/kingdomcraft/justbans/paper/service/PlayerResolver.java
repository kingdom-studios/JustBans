package pl.kingdomcraft.justbans.paper.service;

import java.sql.SQLException;

import pl.kingdomcraft.justbans.common.database.Database;
import pl.kingdomcraft.justbans.common.service.ErrorCode;
import pl.kingdomcraft.justbans.common.service.Result;
import pl.kingdomcraft.justbans.common.service.TargetResolver;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;

/**
 * Turns what staff typed into a player: a UUID, the name of someone online on this server, or any name the network has
 * ever seen (current or previous, from {@code jb_players}). Works for offline players because it uses the database, not
 * Bukkit's player list.
 */
public final class PlayerResolver {

	private final Database database;
	private final TargetResolver delegate = new TargetResolver();

	public PlayerResolver(Database database) {
		this.database = database;
	}

	/**
	 * Blocking; call from a background thread.
	 *
	 * @param input       what the staff member typed
	 * @param localOnline the player of that name if they are online on this server (looked up on the main thread by the caller), else null
	 */
	public Result<Target> resolve(String input, Target localOnline) throws SQLException {
		if (looksLikeUuid(input))
			return this.database.query(connection -> this.delegate.resolve(connection, input, null));

		if (localOnline != null)
			return Result.ok(localOnline);

		if (!isValidName(input))
			return Result.error(ErrorCode.UNKNOWN_PLAYER, "'" + input + "' is not a valid player name or UUID");

		return this.database.query(connection -> this.delegate.resolve(connection, null, input));
	}

	static boolean looksLikeUuid(String input) {
		return input.length() == 36 && input.indexOf('-') == 8;
	}

	/** Java usernames are 3-16 of letters, digits and underscore; Bedrock/floodgate prefixes add a dot. */
	static boolean isValidName(String input) {
		return input.matches("[.A-Za-z0-9_]{1,17}");
	}
}
