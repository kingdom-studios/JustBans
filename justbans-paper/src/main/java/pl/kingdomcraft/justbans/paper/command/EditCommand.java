package pl.kingdomcraft.justbans.paper.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.bukkit.command.CommandSender;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleSubCommand;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.Punishment;
import pl.kingdomcraft.justbans.common.service.ErrorCode;
import pl.kingdomcraft.justbans.common.service.Result;
import pl.kingdomcraft.justbans.common.service.UpdateRequest;
import pl.kingdomcraft.justbans.common.time.DurationParser;
import pl.kingdomcraft.justbans.paper.Core;
import pl.kingdomcraft.justbans.paper.JustBans;
import pl.kingdomcraft.justbans.paper.service.Msg;
import pl.kingdomcraft.justbans.paper.settings.Localization;

/**
 * {@code /justbans edit <id> <duration|reason|note> <value...>}
 *
 * <ul>
 * <li>{@code duration <7d|perm>}: sets the expiry to that long from now (only for active bans and warns);</li>
 * <li>{@code reason <text>}: replaces the reason text shown to the player;</li>
 * <li>{@code note <text|clear>}: sets or removes the internal staff note.</li>
 * </ul>
 * {@code <id>} is the short code from {@code /check} and {@code /history} (with or without {@code #}) or the numeric id. The change goes
 * through the same service as the web panel's {@code UPDATE_PUNISHMENT}, so it is validated, written together with its event, and
 * every server and the web panel hear about it.
 *
 * <p>Like every command object this is a shared singleton: the sender and arguments are copied into locals before the work
 * leaves the main thread.
 */
public final class EditCommand extends SimpleSubCommand {

	private static final List<String> FIELDS = List.of("duration", "reason", "note");
	private static final List<String> DURATION_HINTS = List.of("perm", "1d", "7d", "30d");

	public EditCommand() {
		super("edit");

		this.setPermission("justbans.edit");
		this.setUsage("<id> <duration|reason|note> <value...>");
		this.setDescription("Change the expiry, reason or note of a punishment.");
		this.setMinArguments(3);
	}

	@Override
	protected void onCommand() {
		final Core core = JustBans.core();

		if (core == null)
			this.returnTell(Localization.Punish.STARTING);

		final String reference = this.args[0];
		final String field = this.args[1].toLowerCase(Locale.ROOT);
		final String value = String.join(" ", Arrays.copyOfRange(this.args, 2, this.args.length)).strip();

		if (value.isEmpty())
			this.returnTell(Localization.Edit.VALUE_REQUIRED);

		final Change change = this.parse(field, value);

		final CommandSender sender = this.sender;
		final Actor actor = PunishmentCommand.actorOf(sender);

		Common.runAsync(() -> {
			try {
				final Optional<Punishment> found = core.service().findBlocking(reference);

				if (found.isEmpty()) {
					Reports.error(sender, Msg.fill(Localization.Edit.NOT_FOUND, "input", reference));
					return;
				}

				final Punishment current = found.get();
				final UpdateRequest.Builder request = UpdateRequest.builder(actor, current.id());
				change.apply(request, core.clock().instant());

				final Result<Punishment> result = core.service().updateBlocking(request.build());

				if (!result.isOk()) {
					Reports.error(sender, Reports.errorText(result));
					return;
				}

				Reports.success(sender, change.confirmation(core, current, result.value()));

			} catch (final Throwable t) {
				Common.error(t, "Failed to run /justbans edit " + reference + " " + field);
				Reports.error(sender, Msg.fill(Localization.Errors.forCode(ErrorCode.INTERNAL), "message", ""));
			}
		});
	}

	/** Validates the field and value on the main thread, so typos are reported before any background work. */
	private Change parse(String field, String value) {
		switch (field) {
			case "duration", "expiry", "expires" -> {
				final Duration duration;

				try {
					duration = DurationParser.parse(value);

				} catch (final IllegalArgumentException ex) {
					this.returnTell(Msg.fill(Localization.Edit.INVALID_DURATION, "message", ex.getMessage()));
					return null; // unreachable, returnTell throws
				}

				return new Change.Expiry(DurationParser.isPermanent(duration) ? null : duration);
			}

			case "reason" -> {
				return new Change.Reason(value);
			}

			case "note" -> {
				return new Change.Note(value.equalsIgnoreCase("clear") || value.equalsIgnoreCase("none") || value.equals("-") ? "" : value);
			}

			default -> {
				this.returnTell(Msg.fill(Localization.Edit.UNKNOWN_FIELD, "input", field));
				return null;
			}
		}
	}

	/** One requested edit, and the sentence that confirms it. */
	private sealed interface Change {

		void apply(UpdateRequest.Builder request, Instant now);

		String confirmation(Core core, Punishment before, Punishment after);

		/** @param duration time from now, or null for permanent */
		record Expiry(Duration duration) implements Change {
			@Override
			public void apply(UpdateRequest.Builder request, Instant now) {
				request.expiresAt(this.duration == null ? null : now.plus(this.duration));
			}

			@Override
			public String confirmation(Core core, Punishment before, Punishment after) {
				return Msg.fill(Localization.Edit.UPDATED_EXPIRY, "id", after.publicId(), "target", after.targetName(), "expires", Reports.expiresText(core, after));
			}
		}

		record Reason(String text) implements Change {
			@Override
			public void apply(UpdateRequest.Builder request, Instant now) {
				request.reasonText(this.text);
			}

			@Override
			public String confirmation(Core core, Punishment before, Punishment after) {
				return Msg.fill(Localization.Edit.UPDATED_REASON, "id", after.publicId(), "target", after.targetName(), "reason", after.reasonText());
			}
		}

		/** @param text the new note, empty to remove it */
		record Note(String text) implements Change {
			@Override
			public void apply(UpdateRequest.Builder request, Instant now) {
				request.note(this.text);
			}

			@Override
			public String confirmation(Core core, Punishment before, Punishment after) {
				return Msg.fill(this.text.isEmpty() ? Localization.Edit.NOTE_REMOVED : Localization.Edit.UPDATED_NOTE, "id", after.publicId(), "target", after.targetName());
			}
		}
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 2)
			return this.completeLastWord(FIELDS);

		if (this.args.length == 3 && this.args[1].equalsIgnoreCase("duration"))
			return this.completeLastWord(DURATION_HINTS);

		if (this.args.length == 3 && this.args[1].equalsIgnoreCase("note"))
			return this.completeLastWord(List.of("clear"));

		return List.of();
	}
}
