package forge.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * install-fidelity-1105 harness (not a unit test; skipped unless -Dforge.installRT.list is set): the install
 * round-trip check over position files. List: one path per line (a GameState text, or a .pzl whose [state] block is
 * taken). Each is installed into a fresh two-seat game with the JVM's {@code forge.gamestate.installAsIs} key, and
 * {@link GameState#installDiff} compares Forge's dump with the record (check mode {@code warn} unless
 * -Dforge.gamestate.installCheck says otherwise). Out: -Dforge.installRT.out, JSONL {file, ok, diff | threw}.
 */
public class InstallRoundTripRun extends AITest {

    static String stateBlock(final String text) {
        final int at = text.indexOf("[state]");
        if (at < 0) {
            return text;
        }
        final String rest = text.substring(at + "[state]".length());
        final int next = rest.indexOf("\n[");
        return next < 0 ? rest : rest.substring(0, next);
    }

    @Test
    public void run() throws Exception {
        final String list = System.getProperty("forge.installRT.list");
        if (list == null) {
            throw new SkipException("harness only");
        }
        final StringBuilder out = new StringBuilder();
        int n = 0, ok = 0;
        for (String path : Files.readAllLines(Paths.get(list), StandardCharsets.UTF_8)) {
            if (path.isBlank()) {
                continue;
            }
            n++;
            final JsonObject o = new JsonObject();
            o.addProperty("file", path.trim());
            try {
                final String text = stateBlock(new String(Files.readAllBytes(Paths.get(path.trim())), StandardCharsets.UTF_8));
                final Game game = initAndCreateGame();
                final GameState gs = new GameState();
                if (System.getProperty(GameState.INSTALL_CHECK_KEY) == null) {
                    gs.setInstallCheck("warn");
                }
                final List<String> lines = new ArrayList<>();
                for (String l : text.split("\\R")) {
                    if (!l.isBlank()) {
                        lines.add(l);
                    }
                }
                gs.parse(lines);
                gs.applyGameOnThread(game);
                final List<String> diff = gs.getInstallDiff() == null ? new ArrayList<>() : gs.getInstallDiff();
                o.addProperty("ok", diff.isEmpty());
                final JsonArray d = new JsonArray();
                diff.forEach(d::add);
                o.add("diff", d);
                if (diff.isEmpty()) {
                    ok++;
                }
            } catch (RuntimeException | Error ex) {
                o.addProperty("ok", false);
                o.addProperty("threw", String.valueOf(ex));
            }
            out.append(o).append('\n');
        }
        Files.write(Paths.get(System.getProperty("forge.installRT.out")), out.toString().getBytes(StandardCharsets.UTF_8));
        System.err.println("[install-rt] asIs=" + Boolean.getBoolean(GameState.INSTALL_AS_IS_KEY) + " ok " + ok + "/" + n);
    }
}
