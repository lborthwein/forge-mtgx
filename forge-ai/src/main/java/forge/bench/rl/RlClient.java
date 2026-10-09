package forge.bench.rl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import com.google.gson.JsonObject;

/**
 * One wire/1 connection, held by one game thread (interfaces.md §2.1): HELLO once, then NEXT_GAME → GAME →
 * (DECIDE → DECISION | RECORD → RECORD_ACK)* → GAME_END → GAME_END_ACK. Strictly synchronous. Any ERROR frame from the
 * server, or an unexpected frame type, throws {@link ServerError}; transport failures throw {@link IOException}.
 *
 * <p>Transport: {@code host:port} is loopback TCP (the default). {@code unix:<path>} is a Unix-domain socket (lane
 * cm-serve-1009, the server's {@code --transport uds}): the same frames on a cheaper channel, with the same read timeout
 * semantics (a {@link SocketTimeoutException} after {@code readTimeoutMs} without data).
 */
public final class RlClient implements Closeable {

    /** The server answered ERROR, or broke the protocol. */
    public static final class ServerError extends IOException {
        private static final long serialVersionUID = 1L;
        public final String code;

        public ServerError(final String code, final String msg) {
            super(code + ": " + msg);
            this.code = code;
        }
    }

    private final Socket socket;
    private final UdsChannel uds;
    private final InputStream in;
    private final OutputStream out;
    private long bytesOut, bytesIn, frames;

    public RlClient(final String host, final int port, final int connectTimeoutMs, final int readTimeoutMs)
            throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        uds = null;
        in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
        out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
    }

    private RlClient(final UdsChannel ch) {
        socket = null;
        uds = ch;
        in = new BufferedInputStream(ch.in, 1 << 16);
        out = new BufferedOutputStream(ch.out, 1 << 16);
    }

    /** Parse {@code host:port}, or {@code unix:<path>} for a Unix-domain socket. */
    public static RlClient connect(final String hostPort, final int connectTimeoutMs, final int readTimeoutMs)
            throws IOException {
        if (hostPort.startsWith("unix:")) {
            return new RlClient(UdsChannel.open(hostPort.substring(5), readTimeoutMs));
        }
        final int c = hostPort.lastIndexOf(':');
        if (c <= 0) {
            throw new IllegalArgumentException("server must be host:port, got " + hostPort);
        }
        return new RlClient(hostPort.substring(0, c), Integer.parseInt(hostPort.substring(c + 1)), connectTimeoutMs,
                readTimeoutMs);
    }

    private void send(final int type, final byte[] payload) throws IOException {
        final byte[] f = RlWire.frameBytes(type, 0, payload);
        out.write(f);
        out.flush();
        bytesOut += f.length;
        frames++;
    }

    private RlWire.Frame expect(final int type) throws IOException {
        final RlWire.Frame f = RlWire.readFrame(in);
        bytesIn += 8 + f.payload.length;
        if (f.type == RlWire.T_ERROR) {
            String code = "error", msg = "";
            try {
                final JsonObject e = f.json();
                code = e.has("code") ? e.get("code").getAsString() : code;
                msg = e.has("msg") ? e.get("msg").getAsString() : msg;
            } catch (RuntimeException ex) {
                msg = "unparseable ERROR payload";
            }
            throw new ServerError(code, msg);
        }
        if (f.type != type) {
            throw new ServerError("protocol", "expected " + RlWire.typeName(type) + ", got " + RlWire.typeName(f.type));
        }
        return f;
    }

    public JsonObject hello(final JsonObject hello) throws IOException {
        send(RlWire.T_HELLO, RlWire.canonical(hello));
        final JsonObject ack = expect(RlWire.T_HELLO_ACK).json();
        if (!ack.has("ok") || !ack.get("ok").getAsBoolean()) {
            throw new ServerError("hello", "HELLO_ACK not ok: " + ack);
        }
        return ack;
    }

    public JsonObject nextGame(final String actorId, final int thread) throws IOException {
        final JsonObject o = new JsonObject();
        o.addProperty("actor_id", actorId);
        o.addProperty("thread", thread);
        send(RlWire.T_NEXT_GAME, RlWire.canonical(o));
        return expect(RlWire.T_GAME).json();
    }

    public RlWire.Decision decide(final byte[] decidePayload) throws IOException {
        send(RlWire.T_DECIDE, decidePayload);
        try {
            return RlWire.decodeDecision(expect(RlWire.T_DECISION).payload);
        } catch (IllegalArgumentException e) {
            throw new ServerError("protocol", "bad DECISION: " + e.getMessage());
        }
    }

    public void record(final byte[] recordPayload) throws IOException {
        send(RlWire.T_RECORD, recordPayload);
        expect(RlWire.T_RECORD_ACK);
    }

    public void gameEnd(final JsonObject end) throws IOException {
        send(RlWire.T_GAME_END, RlWire.canonical(end));
        expect(RlWire.T_GAME_END_ACK);
    }

    /** Send ERROR, then close (the sender closes, §2.1). Never throws. */
    public void error(final String code, final String msg) {
        try {
            send(RlWire.T_ERROR, RlWire.canonical(RlWire.error(code, msg)));
        } catch (IOException e) {
            // closing anyway
        }
        close();
    }

    public long bytesOut() {
        return bytesOut;
    }

    public long bytesIn() {
        return bytesIn;
    }

    public long frames() {
        return frames;
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            } else {
                uds.close();
            }
        } catch (IOException e) {
            // nothing to do
        }
    }

    /**
     * A connected Unix-domain {@link SocketChannel} in non-blocking mode behind blocking streams: reads wait on a
     * selector for at most {@code readTimeoutMs} and then throw {@link SocketTimeoutException} (what SO_TIMEOUT does for
     * the TCP socket); writes wait for the channel to drain. One per connection, used by one thread.
     */
    static final class UdsChannel implements Closeable {
        final SocketChannel ch;
        final Selector rsel, wsel;
        final int readTimeoutMs;
        final InputStream in;
        final OutputStream out;

        static UdsChannel open(final String path, final int readTimeoutMs) throws IOException {
            final SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                ch.connect(UnixDomainSocketAddress.of(path));
                return new UdsChannel(ch, readTimeoutMs);
            } catch (IOException | RuntimeException e) {
                ch.close();
                throw e;
            }
        }

        private UdsChannel(final SocketChannel ch, final int readTimeoutMs) throws IOException {
            this.ch = ch;
            this.readTimeoutMs = readTimeoutMs;
            ch.configureBlocking(false);
            rsel = Selector.open();
            wsel = Selector.open();
            ch.register(rsel, SelectionKey.OP_READ);
            ch.register(wsel, SelectionKey.OP_WRITE);
            in = new InputStream() {
                @Override
                public int read() throws IOException {
                    final byte[] one = new byte[1];
                    final int n = read(one, 0, 1);
                    return n < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(final byte[] b, final int off, final int len) throws IOException {
                    if (len == 0) {
                        return 0;
                    }
                    final ByteBuffer bb = ByteBuffer.wrap(b, off, len);
                    final long deadline = readTimeoutMs > 0 ? System.nanoTime() + readTimeoutMs * 1_000_000L : 0L;
                    while (true) {
                        final int n = ch.read(bb);
                        if (n != 0) {
                            return n;               // bytes, or -1 at EOF
                        }
                        long waitMs = 0L;
                        if (readTimeoutMs > 0) {
                            waitMs = (deadline - System.nanoTime()) / 1_000_000L;
                            if (waitMs <= 0) {
                                throw new SocketTimeoutException("Read timed out (unix socket)");
                            }
                        }
                        rsel.select(waitMs);
                        rsel.selectedKeys().clear();
                    }
                }
            };
            out = new OutputStream() {
                @Override
                public void write(final int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(final byte[] b, final int off, final int len) throws IOException {
                    final ByteBuffer bb = ByteBuffer.wrap(b, off, len);
                    while (bb.hasRemaining()) {
                        if (ch.write(bb) == 0) {
                            wsel.select(1000L);
                            wsel.selectedKeys().clear();
                        }
                    }
                }
            };
        }

        @Override
        public void close() throws IOException {
            try {
                rsel.close();
                wsel.close();
            } finally {
                ch.close();
            }
        }
    }
}
