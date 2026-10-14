package pl.kingdomcraft.justbans.velocity.command;

import java.util.List;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** {@code /jbproxy reload}: re-reads reasons.yml and syncs it. Everything else is done through the Paper commands or the web panel. */
public final class JbProxyCommand implements SimpleCommand {

	public static final String PERMISSION = "justbans.proxy.admin";

	/** Reloads reasons.yml; implemented by the runtime. Runs off the command thread. */
	@FunctionalInterface
	public interface Reloader {
		/** @return a short human-readable result, or throws with a message for the admin */
		String reload() throws Exception;
	}

	private final Reloader reloader;
	private final java.util.function.Consumer<Runnable> async;

	public JbProxyCommand(Reloader reloader, java.util.function.Consumer<Runnable> async) {
		this.reloader = reloader;
		this.async = async;
	}

	@Override
	public void execute(Invocation invocation) {
		final CommandSource source = invocation.source();
		final String[] args = invocation.arguments();

		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			source.sendMessage(Component.text("Reloading reasons.yml...", NamedTextColor.GRAY));

			this.async.accept(() -> {
				try {
					source.sendMessage(Component.text(this.reloader.reload(), NamedTextColor.GREEN));
				} catch (final Exception ex) {
					source.sendMessage(Component.text("Reload failed, the previous reasons stay active:", NamedTextColor.RED));

					for (final String line : String.valueOf(ex.getMessage()).split("\n"))
						source.sendMessage(Component.text(line, NamedTextColor.RED));
				}
			});

			return;
		}

		source.sendMessage(Component.text("Usage: /jbproxy reload", NamedTextColor.YELLOW));
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		return invocation.source().hasPermission(PERMISSION);
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		return invocation.arguments().length <= 1 ? List.of("reload") : List.of();
	}
}
