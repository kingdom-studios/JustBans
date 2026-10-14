package pl.kingdomcraft.justbans.paper.menu;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import pl.kingdomcraft.justbans.common.domain.Actor;
import pl.kingdomcraft.justbans.common.domain.PunishmentKind;
import pl.kingdomcraft.justbans.common.domain.ReasonDefinition;
import pl.kingdomcraft.justbans.common.domain.Scope;
import pl.kingdomcraft.justbans.common.domain.ScopeType;
import pl.kingdomcraft.justbans.common.service.PunishmentRequest;
import pl.kingdomcraft.justbans.common.service.TargetResolver.Target;

/**
 * Everything staff have chosen so far while walking through the punish menus. It is one mutable object shared by the
 * menus of a flow, because a conversation (the note prompt) re-opens a menu through {@code newInstance()} and the choices
 * must survive that. Only touched on the main thread.
 *
 * <p>{@link #duration()} null means "let the reason decide" (its default duration, or the next step of its ladder).
 */
public final class PunishDraft {

	private final Target target;
	private final ReasonDefinition reason;
	private final List<ActionType> usable;

	private ActionType action;
	private Scope scope = Scope.network();
	private Duration duration;
	private String note;

	/**
	 * @param hasPermission the staff member's permission check
	 * @throws IllegalArgumentException if staff cannot use this reason for any action
	 */
	public PunishDraft(Target target, ReasonDefinition reason, Predicate<String> hasPermission) {
		this.target = target;
		this.reason = reason;
		this.usable = ActionRules.usableActions(reason, hasPermission);

		if (this.usable.isEmpty())
			throw new IllegalArgumentException("No usable action for reason " + reason.id());

		this.action = this.usable.get(0);
	}

	public Target target() {
		return this.target;
	}

	public ReasonDefinition reason() {
		return this.reason;
	}

	/** The actions staff may pick for this reason. */
	public List<ActionType> usableActions() {
		return this.usable;
	}

	public ActionType action() {
		return this.action;
	}

	public Scope scope() {
		return this.scope;
	}

	public Duration duration() {
		return this.duration;
	}

	public String note() {
		return this.note;
	}

	public boolean isServerScoped() {
		return this.scope.type() != ScopeType.NETWORK;
	}

	public PunishmentKind kind() {
		return this.action.kind(this.isServerScoped());
	}

	/**
	 * Picks an action. Choices that do not apply to it are dropped so a stale scope or duration is never sent.
	 */
	public void select(ActionType newAction) {
		if (!this.usable.contains(newAction))
			throw new IllegalArgumentException(newAction + " is not usable for this reason");

		this.action = newAction;

		if (!newAction.allowsScopeFor(this.reason))
			this.scope = Scope.network();

		if (!newAction.needsDuration())
			this.duration = null;
	}

	/** @return false if the reason does not allow the current action limited to a server, in which case nothing changes */
	public boolean selectScope(Scope newScope) {
		if (newScope.type() != ScopeType.NETWORK && !this.action.allowsScopeFor(this.reason))
			return false;

		this.scope = newScope;

		return true;
	}

	/** Sets the duration, or null to let the reason decide. Ignored for actions without a duration. */
	public void setDuration(Duration newDuration) {
		this.duration = this.action.needsDuration() ? newDuration : null;
	}

	public void setNote(String newNote) {
		this.note = newNote == null || newNote.isBlank() ? null : newNote.strip();
	}

	/**
	 * Whether the punishment can be applied as chosen. A temporary ban needs a duration from somewhere: one picked in the
	 * menu, the reason's default, or its escalation ladder.
	 */
	public boolean isComplete() {
		return !this.action.needsDuration() || this.duration != null || this.reason.defaultDuration() != null || this.reason.hasLadder();
	}

	public PunishmentRequest toRequest(Actor actor, String originServer) {
		return PunishmentRequest.builder(this.kind(), this.target.uuid(), this.target.name(), actor)
				.scope(this.isServerScoped() ? this.scope : null)
				.reasonId(this.reason.id())
				.duration(this.duration)
				.note(this.note)
				.originServer(originServer)
				.build();
	}
}
