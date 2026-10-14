package pl.kingdomcraft.justbans.paper.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import pl.kingdomcraft.justbans.common.domain.ServerRecord;

/** The servers and server groups registered by the proxy in {@code jb_servers}, for tab completion. Immutable snapshots, swapped atomically. */
public final class ServerCatalog {

	private record Snapshot(Set<String> servers, Set<String> groups) {
	}

	private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Set.of(), Set.of()));

	public void replace(Collection<ServerRecord> records) {
		final Set<String> servers = new TreeSet<>();
		final Set<String> groups = new TreeSet<>();

		for (final ServerRecord record : records) {
			servers.add(record.id());

			if (record.groupName() != null && !record.groupName().isBlank())
				groups.add(record.groupName());
		}

		this.snapshot.set(new Snapshot(Set.copyOf(servers), Set.copyOf(groups)));
	}

	public boolean hasServer(String name) {
		return this.snapshot.get().servers().contains(name);
	}

	public List<String> servers() {
		return this.snapshot.get().servers().stream().sorted().toList();
	}

	public List<String> groups() {
		return this.snapshot.get().groups().stream().sorted().toList();
	}
}
