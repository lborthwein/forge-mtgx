package forge.bench.rl;

import java.io.IOException;
import java.util.Collections;

import com.google.gson.JsonObject;

/** Lane lki-fix-port-1027 (tests only): a finished game's games row, written as {@link RlReplayLabeller#finish} does. */
public final class SinkTestAccess {
    private SinkTestAccess() {
    }

    public static void writeGame(final RlReplayLabeller.Sink sink, final JsonObject gamesRow) throws IOException {
        sink.write(null, Collections.emptyList(), gamesRow);
    }
}
