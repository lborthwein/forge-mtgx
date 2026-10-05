package forge.bench.rl;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The tape writer {@code mtgx-rl-tape/1} (interfaces.md §4.1; lane rl-r0-b1-1005): one canonical JSON line per game
 * in {@code $RUN/tapes/<actor_id>/tapes-<seq:05d>.jsonl.gz}, rotated every {@code rotate} games. Shared by the game
 * threads of one actor JVM. Every line is sync-flushed, so a file cut short by a killed JVM is still readable up to
 * its last whole line; {@link #close()} finishes the gzip stream.
 */
public final class RlTape implements Closeable {
    public static final String SCHEMA = "mtgx-rl-tape/1";

    private final Path dir;
    private final int rotate;
    private int seq = 0;
    private int inFile = 0;
    private long total = 0;
    private Writer w;

    public RlTape(final Path dir, final int rotate) throws IOException {
        this.dir = dir;
        this.rotate = Math.max(1, rotate);
        Files.createDirectories(dir);
        // never overwrite an earlier JVM's tapes in the same directory
        while (Files.exists(file(seq))) {
            seq++;
        }
    }

    private Path file(final int s) {
        return dir.resolve(String.format("tapes-%05d.jsonl.gz", s));
    }

    public synchronized void write(final JsonObject line) throws IOException {
        if (w == null) {
            final OutputStream out = Files.newOutputStream(file(seq));
            w = new OutputStreamWriter(new GZIPOutputStream(out, 1 << 16, true), StandardCharsets.UTF_8);
        }
        w.write(RlWire.canonicalString(line));
        w.write('\n');
        w.flush();
        total++;
        if (++inFile >= rotate) {
            w.close();
            w = null;
            inFile = 0;
            seq++;
        }
    }

    public synchronized long total() {
        return total;
    }

    @Override
    public synchronized void close() throws IOException {
        if (w != null) {
            w.close();
            w = null;
        }
    }

    /** All lines of a tape file (a truncated gzip yields the lines before the cut). */
    public static List<JsonObject> read(final Path f) throws IOException {
        final List<JsonObject> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)),
                StandardCharsets.UTF_8))) {
            String line;
            while (true) {
                try {
                    line = r.readLine();
                } catch (java.io.EOFException e) {
                    break;
                }
                if (line == null) {
                    break;
                }
                if (!line.isEmpty()) {
                    out.add(JsonParser.parseString(line).getAsJsonObject());
                }
            }
        }
        return out;
    }
}
