package pl.kingdomcraft.justbans.common.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP forwarder that can make the connections it already carries go silent without closing them, which is what a NAT or
 * firewall dropping an idle connection looks like to a client: writes succeed, reads hang until the client's read timeout.
 * Connections opened after {@link #stallExisting()} work normally.
 */
public final class FlakyProxy implements AutoCloseable {

	private final ServerSocket server;
	private final String targetHost;
	private final int targetPort;
	private final long latencyMs;
	private final List<Link> links = new CopyOnWriteArrayList<>();
	private final AtomicInteger accepted = new AtomicInteger();

	private volatile boolean running = true;

	private static final class Link {
		final Socket client;
		final Socket upstream;
		volatile boolean stalled;

		Link(Socket client, Socket upstream) {
			this.client = client;
			this.upstream = upstream;
		}
	}

	/**
	 * @param latencyMs delay added to every chunk of data, so that concurrent callers overlap and the client opens several connections
	 */
	public FlakyProxy(String targetHost, int targetPort, long latencyMs) throws IOException {
		this.targetHost = targetHost;
		this.targetPort = targetPort;
		this.latencyMs = latencyMs;
		this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());

		final Thread acceptor = new Thread(this::acceptLoop, "flaky-proxy-accept");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	public int port() {
		return this.server.getLocalPort();
	}

	/** How many client connections were ever opened through this proxy. */
	public int connectionsAccepted() {
		return this.accepted.get();
	}

	/** Makes every connection open right now swallow all traffic from here on, without closing it. */
	public void stallExisting() {
		for (final Link link : this.links)
			link.stalled = true;
	}

	private void acceptLoop() {
		while (this.running) {
			try {
				final Socket client = this.server.accept();
				final Socket upstream = new Socket(this.targetHost, this.targetPort);
				final Link link = new Link(client, upstream);

				this.links.add(link);
				this.accepted.incrementAndGet();

				pump(link, client, upstream);
				pump(link, upstream, client);

			} catch (final IOException ex) {
				if (!this.running)
					return;
			}
		}
	}

	private void pump(Link link, Socket from, Socket to) {
		final Thread thread = new Thread(() -> {
			final byte[] buffer = new byte[8192];

			try {
				final InputStream in = from.getInputStream();
				final OutputStream out = to.getOutputStream();
				int read;

				while ((read = in.read(buffer)) >= 0) {
					if (link.stalled)
						continue; // black hole: accept the bytes, deliver nothing

					if (this.latencyMs > 0)
						Thread.sleep(this.latencyMs);

					out.write(buffer, 0, read);
					out.flush();
				}

			} catch (final IOException | InterruptedException ignored) {
				// the other side went away
			} finally {
				closeQuietly(from);
				closeQuietly(to);
			}
		}, "flaky-proxy-pump");

		thread.setDaemon(true);
		thread.start();
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (final IOException ignored) {
			// closing
		}
	}

	/** Stops accepting and closes every connection, like Redis going away. */
	@Override
	public void close() {
		this.running = false;

		try {
			this.server.close();
		} catch (final IOException ignored) {
			// closing
		}

		for (final Link link : this.links) {
			closeQuietly(link.client);
			closeQuietly(link.upstream);
		}
	}
}
