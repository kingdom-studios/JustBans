package pl.kingdomcraft.justbans.paper.settings;

import java.util.List;

import org.mineacademy.fo.Valid;
import org.mineacademy.fo.settings.SimpleSettings;

/**
 * settings.yml. Foundation loads this class automatically (it extends {@link SimpleSettings}) before the plugin starts and
 * again on {@code /justbans reload}; every {@code private static void init()} below is invoked by reflection. All fields
 * must end up non-null, which is why they are boxed types.
 */
public final class Settings extends SimpleSettings {

	@Override
	protected int getConfigVersion() {
		return 1;
	}

	/** This server's identity on the network. */
	public static final class Server {

		public static String NAME;
		public static String GROUP;

		private static void init() {
			setPathPrefix("Server");

			NAME = getString("Name").trim();
			GROUP = getString("Group").trim();

			Valid.checkBoolean(!NAME.isEmpty(), "Server.Name in settings.yml must be this server's name from the proxy's velocity.toml");
		}

		/** Group or null. */
		public static String groupOrNull() {
			return GROUP.isEmpty() ? null : GROUP;
		}
	}

	public static final class Database {

		public static String HOST;
		public static Integer PORT;
		public static String NAME;
		public static String USER;
		public static String PASSWORD;
		public static Integer POOL_SIZE;
		public static Integer CONNECTION_TIMEOUT_MILLIS;

		private static void init() {
			setPathPrefix("Database");

			HOST = getString("Host");
			PORT = getInteger("Port");
			NAME = getString("Name");
			USER = getString("User");
			PASSWORD = getString("Password");
			POOL_SIZE = getInteger("Pool_Size");
			CONNECTION_TIMEOUT_MILLIS = getInteger("Connection_Timeout_Millis");

			Valid.checkBoolean(PORT > 0 && PORT <= 65535, "Database.Port must be between 1 and 65535");
			Valid.checkBoolean(POOL_SIZE >= 1 && POOL_SIZE <= 100, "Database.Pool_Size must be between 1 and 100");
		}
	}

	public static final class Redis {

		public static String HOST;
		public static Integer PORT;
		public static String USER;
		public static String PASSWORD;
		public static Boolean SSL;
		public static Integer DATABASE;
		public static String PREFIX;
		public static Integer CONNECT_TIMEOUT_MILLIS;
		public static String WEB_SECRET;

		private static void init() {
			setPathPrefix("Redis");

			HOST = getString("Host");
			PORT = getInteger("Port");
			USER = getString("User");
			PASSWORD = getString("Password");
			SSL = getBoolean("Ssl");
			DATABASE = getInteger("Database");
			PREFIX = getString("Prefix");
			CONNECT_TIMEOUT_MILLIS = getInteger("Connect_Timeout_Millis");
			WEB_SECRET = getString("Web_Secret");

			Valid.checkBoolean(PORT > 0 && PORT <= 65535, "Redis.Port must be between 1 and 65535");
		}
	}

	public static final class WebCommands {

		public static Boolean ENABLED;
		public static Integer MAX_SKEW_SECONDS;

		private static void init() {
			setPathPrefix("Web_Commands");

			ENABLED = getBoolean("Enabled");
			MAX_SKEW_SECONDS = getInteger("Max_Skew_Seconds");

			Valid.checkBoolean(MAX_SKEW_SECONDS >= 1, "Web_Commands.Max_Skew_Seconds must be at least 1");
		}
	}

	public static final class Punishments {

		public static Boolean LOCAL_GATE;
		public static Boolean DENY_ON_DATABASE_ERROR;
		public static List<String> GATE_BAN_SCREEN;
		public static List<String> GATE_DATABASE_ERROR_SCREEN;
		public static Boolean USE_WEIGHTS;
		public static String DATE_FORMAT_PATTERN;
		public static String TIME_ZONE;
		public static Integer OUTBOX_INTERVAL_MILLIS;

		private static void init() {
			setPathPrefix("Punishments");

			LOCAL_GATE = getBoolean("Local_Gate");

			final String policy = getString("Gate_On_Database_Error").trim();
			Valid.checkBoolean(policy.equalsIgnoreCase("Allow") || policy.equalsIgnoreCase("Deny"), "Punishments.Gate_On_Database_Error must be Allow or Deny, got '" + policy + "'");
			DENY_ON_DATABASE_ERROR = policy.equalsIgnoreCase("Deny");

			GATE_BAN_SCREEN = getStringList("Gate_Ban_Screen");
			GATE_DATABASE_ERROR_SCREEN = getStringList("Gate_Database_Error_Screen");
			USE_WEIGHTS = getBoolean("Use_Weights");
			DATE_FORMAT_PATTERN = getString("Date_Format_Pattern");
			TIME_ZONE = getString("Time_Zone");
			OUTBOX_INTERVAL_MILLIS = getInteger("Outbox_Interval_Millis");

			Valid.checkBoolean(!GATE_BAN_SCREEN.isEmpty(), "Punishments.Gate_Ban_Screen needs at least one line");
			Valid.checkBoolean(OUTBOX_INTERVAL_MILLIS >= 100, "Punishments.Outbox_Interval_Millis must be at least 100");
		}
	}
}
