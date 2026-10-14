package pl.kingdomcraft.justbans.paper.settings;

import java.util.EnumMap;
import java.util.Map;

import org.mineacademy.fo.settings.SimpleLocalization;

import pl.kingdomcraft.justbans.common.service.ErrorCode;

/**
 * localization/messages_LOCALE.yml: every message staff see. Loaded by Foundation like {@link Settings}. Defaults here
 * apply to keys missing from the file, so an old messages file keeps working after an update.
 */
public final class Localization extends SimpleLocalization {

	@Override
	protected int getConfigVersion() {
		return 1;
	}

	public static final class Punish {

		public static String BANNED = "&aBanned &f{target} &7({scope}, {expires}&7) &8- &7ID &e#{id}";
		public static String WARNED = "&aWarned &f{target} &8- &7ID &e#{id}";
		public static String KICKED = "&aKicked &f{target} &8- &7ID &e#{id}";
		public static String PARDONED = "&aPardoned &f{target} &8- &7ID &e#{id}";
		public static String UNWARNED = "&aRemoved the last warn of &f{target} &8- &7ID &e#{id}";
		public static String CANNOT_PUNISH_SELF = "&cYou cannot do that to yourself.";
		public static String NOT_ONLINE = "&c{target} is not online on the network.";
		public static String UNKNOWN_REASON = "&cUnknown reason '{input}'. Available: &f{reasons}";
		public static String CUSTOM_REASON_DENIED = "&cYou may only use predefined reasons: &f{reasons}";
		public static String REASON_REQUIRED = "&cSpecify a reason.";
		public static String DURATION_REQUIRED = "&cSpecify a duration such as 7d, 12h or 1d12h.";
		public static String INVALID_SCOPE = "&cUse either server:<name> or group:<name>, not both, and give a name.";
		public static String STARTING = "&cJustBans is still starting, or failed to start (see the console). Try again in a moment.";
		public static String SCOPE_NETWORK = "network";
		public static String SCOPE_SERVER = "server {target}";
		public static String SCOPE_GROUP = "group {target}";
		public static String EXPIRES_NEVER = "&cpermanent";
		public static String EXPIRES_IN = "&7in &f{remaining}";

		private static void init() {
			setPathPrefix("Punish");

			BANNED = read("Banned", BANNED);
			WARNED = read("Warned", WARNED);
			KICKED = read("Kicked", KICKED);
			PARDONED = read("Pardoned", PARDONED);
			UNWARNED = read("Unwarned", UNWARNED);
			CANNOT_PUNISH_SELF = read("Cannot_Punish_Self", CANNOT_PUNISH_SELF);
			NOT_ONLINE = read("Not_Online", NOT_ONLINE);
			UNKNOWN_REASON = read("Unknown_Reason", UNKNOWN_REASON);
			CUSTOM_REASON_DENIED = read("Custom_Reason_Denied", CUSTOM_REASON_DENIED);
			REASON_REQUIRED = read("Reason_Required", REASON_REQUIRED);
			DURATION_REQUIRED = read("Duration_Required", DURATION_REQUIRED);
			INVALID_SCOPE = read("Invalid_Scope", INVALID_SCOPE);
			STARTING = read("Starting", STARTING);
			SCOPE_NETWORK = read("Scope_Network", SCOPE_NETWORK);
			SCOPE_SERVER = read("Scope_Server", SCOPE_SERVER);
			SCOPE_GROUP = read("Scope_Group", SCOPE_GROUP);
			EXPIRES_NEVER = read("Expires_Never", EXPIRES_NEVER);
			EXPIRES_IN = read("Expires_In", EXPIRES_IN);
		}
	}

	public static final class Errors {

		private static final Map<ErrorCode, String> BY_CODE = new EnumMap<>(ErrorCode.class);

		/** Used for any code the file does not define. */
		public static String FALLBACK = "&c{message}";

		private static void init() {
			setPathPrefix("Errors");

			BY_CODE.clear();
			BY_CODE.put(ErrorCode.ALREADY_BANNED, read("Already_Banned", "&c{message} &7Use -f to replace it."));
			BY_CODE.put(ErrorCode.INTERNAL, read("Internal", "&cSomething went wrong, see the console."));

			for (final ErrorCode code : ErrorCode.values())
				if (!BY_CODE.containsKey(code))
					BY_CODE.put(code, read(pascal(code), FALLBACK));
		}

		/** The localized template for an error code; contains {@code {message}}. */
		public static String forCode(ErrorCode code) {
			return BY_CODE.getOrDefault(code, FALLBACK);
		}

		/** {@code REASON_TYPE_NOT_ALLOWED} to {@code Reason_Type_Not_Allowed}. */
		static String pascal(ErrorCode code) {
			final StringBuilder out = new StringBuilder();

			for (final String part : code.name().toLowerCase().split("_"))
				out.append(out.length() == 0 ? "" : "_").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));

			return out.toString();
		}
	}

	public static final class Check {

		public static String HEADER = "&8&m   &r &6Punishments of &f{target} &8&m   ";
		public static String NONE = "&aNo active punishments.";
		public static String BAN_LINE = " &c✖ &fBan &e#{id} &8| &7{scope} &8| &f{reason} &8| &7by {actor} &8| {expires}";
		public static String WARN_LINE = " &6⚠ &fWarn &e#{id} &8| &f{reason} &8| &7by {actor} &8| {expires}";
		public static String WARN_TOTAL = "&7Active warns: &f{count}";

		private static void init() {
			setPathPrefix("Check");

			HEADER = read("Header", HEADER);
			NONE = read("None", NONE);
			BAN_LINE = read("Ban_Line", BAN_LINE);
			WARN_LINE = read("Warn_Line", WARN_LINE);
			WARN_TOTAL = read("Warn_Total", WARN_TOTAL);
		}
	}

	/** The value of the key under the current path prefix, or the default if the file lacks it. */
	private static String read(String key, String defaultValue) {
		return isSetDefault(key) ? getString(key) : defaultValue;
	}
}
