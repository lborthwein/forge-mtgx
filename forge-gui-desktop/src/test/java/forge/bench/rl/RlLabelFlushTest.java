package forge.bench.rl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/** unified-jar-1106: a row reaches the partial files at once; two rows read back as one gzip stream. */
public class RlLabelFlushTest {
    @Test
    public void partialRowsAppend() throws Exception {
        final Path d = Files.createTempDirectory("uj1106-flush");
        final RlReplayLabeller.Sink s = new RlReplayLabeller.Sink(d);
        final JsonObject a = new JsonObject();
        a.addProperty("game_uid", "1");
        s.writePartial(new byte[] {1, 2}, a);
        s.writePartial(new byte[] {3}, a);
        final long pid = ProcessHandle.current().pid();
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(d.resolve("partial-" + pid + ".mxl.gz")))) {
            Assert.assertEquals(in.readAllBytes(), new byte[] {1, 2, 3});
        }
        Assert.assertEquals(Files.readAllLines(d.resolve("partial-index-" + pid + ".jsonl")).size(), 2);
    }
}
