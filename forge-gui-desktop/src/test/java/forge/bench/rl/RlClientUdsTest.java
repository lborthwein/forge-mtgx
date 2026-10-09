package forge.bench.rl;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/**
 * Lane cm-serve-1009: {@code RlClient.connect("unix:<path>")} speaks the same frames over a Unix-domain socket (the
 * server's {@code --transport uds}), and a silent server ends a read with {@link SocketTimeoutException} after the read
 * timeout, as SO_TIMEOUT does for TCP. No Forge boot.
 */
public class RlClientUdsTest {

    private static Path sockPath() throws Exception {
        final Path d = Files.createTempDirectory(Path.of("/tmp"), "rluds");
        d.toFile().deleteOnExit();
        return d.resolve("s.sock");
    }

    /** A one-connection server thread: HELLO -> HELLO_ACK, NEXT_GAME -> GAME, DECIDE -> DECISION (steps [2]). */
    private static Thread server(final ServerSocketChannel ss, final AtomicReference<Throwable> err,
            final boolean silent) {
        final Thread t = new Thread(() -> {
            try (SocketChannel c = ss.accept()) {
                final InputStream in = Channels.newInputStream(c);
                final OutputStream out = Channels.newOutputStream(c);
                final RlWire.Frame h = RlWire.readFrame(in);
                Assert.assertEquals(h.type, RlWire.T_HELLO);
                if (silent) {
                    Thread.sleep(3000);
                    return;
                }
                final JsonObject ack = new JsonObject();
                ack.addProperty("ok", true);
                RlWire.writeFrame(out, RlWire.T_HELLO_ACK, 0, RlWire.canonical(ack));
                final RlWire.Frame ng = RlWire.readFrame(in);
                Assert.assertEquals(ng.type, RlWire.T_NEXT_GAME);
                final JsonObject g = new JsonObject();
                g.addProperty("wait_ms", 5);
                RlWire.writeFrame(out, RlWire.T_GAME, 0, RlWire.canonical(g));
                final RlWire.Frame d = RlWire.readFrame(in);
                Assert.assertEquals(d.type, RlWire.T_DECIDE);
                Assert.assertEquals(d.payload.length, 70000);      // larger than one socket buffer write
                final RlWire.Decision x = new RlWire.Decision();
                x.gameUid = 77;
                x.decIdx = 3;
                x.policyVersion = 9;
                x.logp = -0.5f;
                x.steps = new short[] {2};
                RlWire.writeFrame(out, RlWire.T_DECISION, 0, RlWire.encodeDecision(x));
            } catch (Throwable e) {
                err.set(e);
            }
        }, "uds-test-server");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    public void framesOverAUnixSocket() throws Exception {
        final Path p = sockPath();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        try (ServerSocketChannel ss = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            ss.bind(UnixDomainSocketAddress.of(p));
            final Thread t = server(ss, err, false);
            try (RlClient cl = RlClient.connect("unix:" + p, 1000, 5000)) {
                final JsonObject hello = new JsonObject();
                hello.addProperty("proto", RlWire.PROTO);
                Assert.assertTrue(cl.hello(hello).get("ok").getAsBoolean());
                Assert.assertEquals(cl.nextGame("a00", 0).get("wait_ms").getAsInt(), 5);
                final RlWire.Decision d = cl.decide(new byte[70000]);
                Assert.assertEquals(d.gameUid, 77L);
                Assert.assertEquals(d.decIdx, 3);
                Assert.assertEquals(d.policyVersion, 9L);
                Assert.assertEquals(d.steps, new short[] {2});
                Assert.assertEquals(cl.frames(), 3L);
            }
            t.join(5000);
        } finally {
            Files.deleteIfExists(p);
        }
        if (err.get() != null) {
            throw new AssertionError("server side", err.get());
        }
    }

    @Test
    public void readTimeoutOnASilentServer() throws Exception {
        final Path p = sockPath();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        try (ServerSocketChannel ss = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            ss.bind(UnixDomainSocketAddress.of(p));
            server(ss, err, true);
            try (RlClient cl = RlClient.connect("unix:" + p, 1000, 300)) {
                final long t0 = System.nanoTime();
                try {
                    cl.hello(new JsonObject());
                    Assert.fail("no timeout");
                } catch (SocketTimeoutException e) {
                    final long ms = (System.nanoTime() - t0) / 1_000_000L;
                    Assert.assertTrue(ms >= 250 && ms < 2500, "timed out after " + ms + " ms");
                }
            }
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void tcpAddressesAreUnchanged() {
        try {
            RlClient.connect("127.0.0.1", 100, 100);
            Assert.fail("no port");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("host:port"));
        } catch (java.io.IOException e) {
            Assert.fail("parsed as an address: " + e);
        }
    }
}
