package pl.kingdomcraft.justbans.velocity.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The MiniMessage templates a reason (or the file's defaults) may define, plus built-in fallbacks. */
public final class TemplateNames {

	public static final String BAN_SCREEN = "ban-screen";
	public static final String SERVER_BAN_MESSAGE = "server-ban-message";
	public static final String KICK_SCREEN = "kick-screen";
	public static final String WARN_MESSAGE = "warn-message";
	public static final String BROADCAST = "broadcast";
	public static final String STAFF_ALERT = "staff-alert";

	public static final Set<String> ALL = Set.of(BAN_SCREEN, SERVER_BAN_MESSAGE, KICK_SCREEN, WARN_MESSAGE, BROADCAST, STAFF_ALERT);

	/** Used when neither the reason nor the file's {@code defaults.templates} defines the template. */
	public static final Map<String, List<String>> BUILT_IN = Map.of(
			BAN_SCREEN, List.of(
					"<red><bold>You are banned</bold></red>",
					"",
					"<gray>Reason: <white><reason></white>",
					"<gray>Banned by: <white><actor></white>",
					"<gray>Expires: <white><expires></white> <dark_gray>(<remaining>)</dark_gray>",
					"",
					"<gray>Punishment ID: <yellow>#<id></yellow>"),
			SERVER_BAN_MESSAGE, List.of("<red>You are banned from <white><server></white> <gray>(<remaining>)</gray>: <white><reason></white> <dark_gray>#<id>"),
			KICK_SCREEN, List.of("<red>You were kicked", "", "<gray>Reason: <white><reason></white>"),
			WARN_MESSAGE, List.of("<gold>You have been warned: <white><reason></white> <gray>(<warn_count> active)"),
			BROADCAST, List.of("<red><target></red> <gray>was <type_past> by <red><actor></red> <gray>for <white><reason></white><gray>."),
			STAFF_ALERT, List.of("<dark_gray>[JB]</dark_gray> <gray><actor> <white>-></white> <target>: <type> <scope> <duration> (<reason>) <dark_gray>#<id>"));

	private TemplateNames() {
	}
}
