package pl.kingdomcraft.justbans.paper.menu;

import org.bukkit.conversations.ConversationContext;
import org.bukkit.conversations.Prompt;
import org.mineacademy.fo.conversation.SimplePrompt;

/**
 * Asks in chat for the optional staff note of a punishment. Typing {@code -} clears the note; {@code cancel}, {@code quit}
 * or {@code exit} aborts (handled by Foundation). When the conversation ends Foundation re-opens the confirm menu through
 * its {@code newInstance()}, which is why the note is stored in the shared {@link PunishDraft}.
 */
final class NotePrompt extends SimplePrompt {

	/** {@code jb_punishments.note} holds 512 characters; stay well below it. */
	static final int MAX_LENGTH = 400;

	private final PunishDraft draft;

	NotePrompt(PunishDraft draft) {
		this.draft = draft;
	}

	@Override
	protected String getPrompt(ConversationContext context) {
		return MenuConfig.current().confirm().notePrompt();
	}

	@Override
	protected boolean isInputValid(ConversationContext context, String input) {
		return input.length() <= MAX_LENGTH;
	}

	@Override
	protected String getFailedValidationText(ConversationContext context, String invalidInput) {
		return "That note is too long (" + invalidInput.length() + " of at most " + MAX_LENGTH + " characters). Try a shorter one.";
	}

	@Override
	protected Prompt acceptValidatedInput(ConversationContext context, String input) {
		this.draft.setNote(input.trim().equals("-") ? null : input);

		this.tell(context, MenuConfig.current().confirm().noteSaved());

		return Prompt.END_OF_CONVERSATION;
	}
}
