package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Consumer;

/** Configuration-only checks: does not initialize Forge or play games. */
public final class BenchNativeBatchSmoke {
    private static JsonObject valid() {
        return JsonParser.parseString("{\"gameConfigs\":[{\"id\":\"a\",\"config\":{"
            + "\"decks\":[\"/a.dck\",\"/b.dck\"],\"games\":1,\"seed\":123,"
            + "\"seats\":{\"0\":\"forge\",\"1\":\"forge\"},\"cubeComboSeats\":[0],"
            + "\"aiProfile\":\"Default\",\"aiInformationPolicy\":\"closed-decklist-repair-v1\","
            + "\"useSimulation\":false,\"aiCanUseTimeout\":false,\"aiTimeoutSec\":600,\"timeoutSec\":600}}]}").getAsJsonObject();
    }
    private static JsonObject config(JsonObject e) {
        return e.getAsJsonArray("gameConfigs").get(0).getAsJsonObject().getAsJsonObject("config");
    }
    private static void rejects(Consumer<JsonObject> mutate) {
        JsonObject e = valid(); mutate.accept(e);
        try { BenchNativeBatch.parse(e); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid batch accepted");
    }
    public static void main(String[] args) {
        JsonObject e=valid();
        if(BenchNativeBatch.parse(e).size()!=1)throw new AssertionError();
        for(int i=1;i<500;i++) {
            JsonObject job=e.getAsJsonArray("gameConfigs").get(0).getAsJsonObject().deepCopy();
            job.addProperty("id","job"+i);job.getAsJsonObject("config").addProperty("seed",123+i);
            e.getAsJsonArray("gameConfigs").add(job);
        }
        if(BenchNativeBatch.parse(e).size()!=500)throw new AssertionError();
        rejects(x->x.add("gameConfigs",new JsonArray()));
        rejects(x->x.getAsJsonArray("gameConfigs").add(x.getAsJsonArray("gameConfigs").get(0).deepCopy()));
        rejects(x->x.addProperty("seed",123));
        rejects(x->config(x).addProperty("games",100));
        rejects(x->config(x).addProperty("seed",1.5));
        rejects(x->config(x).addProperty("seed","123"));
        rejects(x->config(x).addProperty("aiCanUseTimeout",true));
        rejects(x->config(x).addProperty("useSimulation",true));
        rejects(x->config(x).addProperty("frameFile","/frame"));
        rejects(x->config(x).getAsJsonObject("seats").addProperty("1","bridge"));
        rejects(x->config(x).getAsJsonArray("cubeComboSeats").add(0));
        rejects(x->config(x).remove("seed"));
        System.out.println("PASS native batch configuration: 1 and 500 jobs; 12 invalid configurations rejected; no games");
    }
}
