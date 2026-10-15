package pl.kingdomcraft.justbans.paper.command;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleCommand;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.service.PunishmentRequest;
import pl.kingdomcraft.justbans.common.service.Result;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.JustBans;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/**
 * Shared flow of every command that targets a player:
 * <ol>
 * <li><b>main thread</b>: check the plugin is running, split off options ({@link ArgParser}), validate and build a {@link Job};</li>
 * <li><b>background thread</b>: resolve the target by name or UUID (works for offline players), refuse self-targeting and
 * offline kicks, then run the job, which calls the {@code PunishmentService} (exemptions are enforced there);</li>
 * <li>the result is reported back on the main thread by {@link Reports}.</li>
 * </ol>
 * Command objects are shared singletons, so nothing about one invocation may be stored in a field; the sender and
 * parsed arguments are passed along in a {@link Context} instead.
 */
public abstract class PunishmentCommand extends SimpleCommand {

	/** Everything a background job needs about one invocation. */
	protected record Context(Core core, CommandSender sender, Actor actor, Target target, ArgParser.Parsed parsed) {
	}

	/** Work done on a background thread once the target is known. */
	@FunctionalInterface
	protected interface Job {
		void run(Context context) throws Exception;
	}

	/** A reason typed by staff: a catalog id, or free text. */
	protected record ReasonInput(String id, String text) {
	}

	protected PunishmentCommand(String labels, String permission, String usage, String description, int minArguments) {
		super(labels);

		this.setPermission(permission);
		this.setUsage(usage);
		this.setDescription(description);
		this.setMinArguments(minArguments);
	}

	// ------------------------------------------------------------------------------------------
	// For subclasses
	// ------------------------------------------------------------------------------------------

	/**
	 * Validates the arguments on the main thread and decides what to do. Throw a {@code CommandException} (for example
	 * through {@code returnTell}) to refuse with a message.
	 */
	protected abstract Job prepare(Core core, ArgParser.Parsed parsed);

	/** Whether the target must currently be online somewhere on the network (kick). */
	protected boolean requiresOnlineTarget() {
		return false;
	}

	/** Whether staff may run this on themselves. Punishing commands do not allow it. */
	protected boolean allowsSelf() {
		return false;
	}

	/** Whether {@code server:}/{@code group:} options make sense, for tab completion. */
	protected boolean supportsScope() {
		return false;
	}

	/** Whether {@code -s} makes sense, for tab completion. */
	protected boolean supportsSilent() {
		return true;
	}

	/** Whether {@code -f} makes sense, for tab completion. */
	protected boolean supportsForce() {
		return false;
	}

	/**
	 * Extra completions for the argument being typed.
	 *
	 * @param position 1-based position of that argument (1 is the player)
	 */
	protected List<String> suggest(Core core, int position) {
		return List.of();
	}

	/**
	 * Reads the reason at {@code words[from]}: a configured reason id, or, for staff allowed to, free text running to
	 * the end of the words.
	 */
	protected final ReasonInput reasonFrom(Core core, ArgParser.Parsed parsed, int from) {
		final String first = parsed.word(from);

		if (first == null)
			this.returnTell(Localization.Punish.REASON_REQUIRED);

		if (core.reasons().contains(first))
			return new ReasonInput(first.toLowerCase(), null);

		return this.customReason(core, first, parsed.join(from));
	}

	/**
	 * Free text, only for staff with {@code justbans.reason.custom}. A single word that is not a configured id is
	 * almost certainly a mistyped id, so it is rejected instead of being recorded as the reason.
	 */
	protected final ReasonInput customReason(Core core, String firstWord, String text) {
		final String available = String.join(", ", core.reasons().ids());

		if (text.indexOf(' ') < 0)
			this.returnTell(Msg.fill(Localization.Punish.UNKNOWN_REASON, "input", firstWord, "reasons", available));

		if (!this.hasPerm("justbans.reason.custom"))
			this.returnTell(Msg.fill(Localization.Punish.CUSTOM_REASON_DENIED, "reasons", available));

		return new ReasonInput(null, text);
	}

	/** Issues a punishment of the given kind and reports the outcome. */
	protected static void issue(Context context, PunishmentKind kind, ReasonInput reason, Duration duration) throws Exception {
		final PunishmentRequest request = PunishmentRequest.builder(kind, context.target().uuid(), context.target().name(), context.actor())
				.scope(context.parsed().scope())
				.reasonId(reason.id())
				.customReason(reason.text())
				.duration(duration)
				.silent(context.parsed().silent())
				.force(context.parsed().force())
				.originServer(context.core().serverName())
				.build();

		Reports.result(context.core(), context.sender(), context.core().service().issueBlocking(request));
	}

	// ------------------------------------------------------------------------------------------
	// Execution
	// ------------------------------------------------------------------------------------------

	@Override
	protected final void onCommand() {
		final Core core = JustBans.core();

		if (core == null)
			this.returnTell(Localization.Punish.STARTING);

		final ArgParser.Parsed parsed = ArgParser.parse(this.args);

		if (parsed.hasScopeError())
			this.returnTell(Localization.Punish.INVALID_SCOPE);

		final String input = parsed.word(0);

		if (input == null)
			this.returnUsage();

		final Job job = this.prepare(core, parsed);

		// Everything below must be captured now: after this method returns the command object is reused by other invocations.
		final CommandSender sender = this.sender;
		final Actor actor = actorOf(sender);
		final Target onlineHere = localPlayer(input);

		if (!this.allowsSelf() && sender instanceof Player player && onlineHere != null && player.getUniqueId().equals(onlineHere.uuid()))
			this.returnTell(Localization.Punish.CANNOT_PUNISH_SELF);

		final boolean needsOnline = this.requiresOnlineTarget();
		final boolean selfAllowed = this.allowsSelf();

		Common.runAsync(() -> {
			try {
				final Result<Target> resolved = core.resolver().resolve(input, onlineHere);

				if (!resolved.isOk()) {
					Reports.error(sender, Reports.errorText(resolved));
					return;
				}

				final Target target = resolved.value();

				if (!selfAllowed && sender instanceof Player player && player.getUniqueId().equals(target.uuid())) {
					Reports.error(sender, Localization.Punish.CANNOT_PUNISH_SELF);
					return;
				}

				if (needsOnline && onlineHere == null && !core.onlineNames().isOnline(target.uuid())) {
					Reports.error(sender, Msg.fill(Localization.Punish.NOT_ONLINE, "target", target.name()));
					return;
				}

				job.run(new Context(core, sender, actor, target, parsed));

			} catch (final Throwable t) {
				Common.error(t, "Failed to run /" + this.getLabel() + " " + String.join(" ", parsed.words()));
				Reports.error(sender, Msg.fill(Localization.Errors.forCode(pl.kingdomcraft.justbans.common.service.ErrorCode.INTERNAL), "message", ""));
			}
		});
	}

	/** The staff member as an actor: a player, or the console for anything else (including command blocks). */
	static Actor actorOf(CommandSender sender) {
		return sender instanceof Player player ? Actor.player(player.getUniqueId(), player.getName()) : Actor.console();
	}

	/** The player of that exact name if online on this server. Main thread only. */
	private static Target localPlayer(String name) {
		final Player player = Bukkit.getPlayerExact(name);

		return player == null ? null : new Target(player.getUniqueId(), player.getName());
	}

	// ------------------------------------------------------------------------------------------
	// Tab completion
	// ------------------------------------------------------------------------------------------

	@Override
	protected final List<String> tabComplete() {
		final Core core = JustBans.core();

		if (core == null)
			return Collections.emptyList();

		if (this.args.length == 1) {
			final Set<String> names = new TreeSet<>(core.onlineNames().names());
			names.addAll(Common.getPlayerNames());

			return this.completeLastWord(names);
		}

		final List<String> options = new ArrayList<>(this.suggest(core, this.args.length));

		if (this.supportsSilent())
			options.add("-s");

		if (this.supportsForce())
			options.add("-f");

		if (this.supportsScope()) {
			for (final String server : core.servers().servers())
				options.add("server:" + server);

			for (final String group : core.servers().groups())
				options.add("group:" + group);
		}

		return this.completeLastWord(options);
	}
}
