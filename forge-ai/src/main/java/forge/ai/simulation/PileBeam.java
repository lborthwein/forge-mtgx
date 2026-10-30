package forge.ai.simulation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.game.card.Card;
import forge.game.card.CardCollection;

/**
 * r2-choose-a-card-1029 (DESIGN-r2 §6): the route-guided beam over Doomsday-size piles (N cards from the pool, ordered,
 * top first). The route library's roles ({@code cac-route-roles.json}) only PROPOSE: each template instantiated over the
 * pool, and a completion of every partial pile; the value tower scores every completed pile, one batched pass per depth.
 * Work = proposals + N x beamWidth x beamBranch applied piles at most; then the best few, with Forge's own pile, go
 * through the ordinary decision (solver check on the top-m, logging).
 */
final class PileBeam {
    static final class Roles {
        final Map<String, String> role = new HashMap<>();
        final List<String> rank = new ArrayList<>();
        final List<List<String>> templates = new ArrayList<>();

        static Roles load() {
            final Roles r = new Roles();
            try (InputStream in = PileBeam.class.getResourceAsStream("/forge/ai/simulation/cac-route-roles.json")) {
                if (in == null) {
                    return r;
                }
                final JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("roles").entrySet()) {
                    r.role.put(e.getKey(), e.getValue().getAsString());
                }
                for (JsonElement e : o.getAsJsonArray("rank")) {
                    r.rank.add(e.getAsString());
                }
                for (JsonElement t : o.getAsJsonArray("templates")) {
                    final List<String> l = new ArrayList<>();
                    for (JsonElement x : (JsonArray) t) {
                        l.add(x.getAsString());
                    }
                    r.templates.add(l);
                }
            } catch (Exception e) {
                throw new IllegalStateException("cac-route-roles.json: " + e, e);
            }
            return r;
        }

        String of(final String name) {
            return role.getOrDefault(name, "other");
        }

        int rankOf(final String name) {
            final int i = rank.indexOf(of(name));
            return i < 0 ? rank.size() : i;
        }
    }

    static final Roles ROLES = Roles.load();

    private final ChooseACard cac;
    private final ChooseACard.Decision d;
    private final int n;
    /** Pool keys in role-rank order, each with its cards (lowest id first). */
    private final List<String> keys = new ArrayList<>();
    private final Map<String, List<Card>> cards = new LinkedHashMap<>();
    int depth = 0;
    /** Diagnostics: piles applied, batches. */
    int evaluated = 0, batches = 0;

    PileBeam(final ChooseACard cac, final ChooseACard.Decision d, final CardCollection fetchList, final int n) {
        this.cac = cac;
        this.d = d;
        this.n = n;
        final List<Card> sorted = new ArrayList<>(fetchList);
        sorted.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        for (Card c : sorted) {
            cards.computeIfAbsent(ChooseACard.rkey(c), k -> new ArrayList<>()).add(c);
        }
        keys.addAll(cards.keySet());
        keys.sort((a, b) -> {
            final int c = Integer.compare(ROLES.rankOf(name(a)), ROLES.rankOf(name(b)));
            return c != 0 ? c : Integer.compare(cards.get(a).get(0).getId(), cards.get(b).get(0).getId());
        });
    }

    private static String name(final String key) {
        return key.substring(0, key.lastIndexOf('@'));
    }

    private int used(final List<String> seq, final String k) {
        int u = 0;
        for (String s : seq) {
            if (s.equals(k)) u++;
        }
        return u;
    }

    /** The prefix completed by its best-matching template's greedy fill (null if the pool is too small). */
    List<String> complete(final List<String> prefix) {
        List<String> best = null;
        int bestMatch = -1;
        for (List<String> t : ROLES.templates.isEmpty() ? List.of(List.<String>of()) : ROLES.templates) {
            int m = 0;
            for (int i = 0; i < prefix.size() && i < t.size(); i++) {
                final String r = t.get(i);
                if ("other".equals(r) || r.equals(ROLES.of(name(prefix.get(i))))) m++;
            }
            if (m > bestMatch) {
                bestMatch = m;
                best = t;
            }
        }
        final List<String> out = new ArrayList<>(prefix);
        for (int i = prefix.size(); i < n; i++) {
            final String r = best != null && i < best.size() ? best.get(i) : "other";
            String pick = null;
            for (String k : keys) {
                if (used(out, k) < cards.get(k).size() && ("other".equals(r) || r.equals(ROLES.of(name(k))))) {
                    pick = k;
                    break;
                }
            }
            if (pick == null) {
                for (String k : keys) {
                    if (used(out, k) < cards.get(k).size()) {
                        pick = k;
                        break;
                    }
                }
            }
            if (pick == null) return null;
            out.add(pick);
        }
        return out;
    }

    private ChooseACard.Answer answer(final List<String> seq) {
        final List<Card> cs = new ArrayList<>();
        final Map<String, Integer> u = new HashMap<>();
        for (String k : seq) {
            final int i = u.merge(k, 1, Integer::sum) - 1;
            cs.add(cards.get(k).get(i));
        }
        return ChooseACard.pileAnswer(cs);
    }

    /** Run the beam; {@code seed} holds Forge's answer first. The answer to play (null = Forge's). */
    ChooseACard.Answer run(final List<ChooseACard.Answer> seed) {
        final ChooseACard.Answer forge = seed.get(0);
        final Map<String, Double> score = new LinkedHashMap<>();
        final Map<String, ChooseACard.Answer> byKey = new HashMap<>();
        // depth 0: the route templates instantiated over the pool
        final List<ChooseACard.Answer> batch = new ArrayList<>();
        final List<String> batchKeys = new ArrayList<>();
        for (int t = 0; t < Math.max(1, ROLES.templates.size()); t++) {
            final List<String> full = completeWith(t);
            if (full != null && byKey.putIfAbsent(full.toString(), answer(full)) == null) {
                batch.add(byKey.get(full.toString()));
                batchKeys.add(full.toString());
            }
        }
        eval(batch, batchKeys, score);
        List<List<String>> prefixes = new ArrayList<>();
        prefixes.add(new ArrayList<>());
        for (depth = 1; depth <= n; depth++) {
            final List<ChooseACard.Answer> b = new ArrayList<>();
            final List<String> bk = new ArrayList<>();
            final Map<List<String>, String> prefixFull = new LinkedHashMap<>();
            for (List<String> p : prefixes) {
                int branched = 0;
                for (String k : keys) {
                    if (branched >= cac.config().beamBranch) break;
                    if (used(p, k) >= cards.get(k).size()) continue;
                    branched++;
                    final List<String> q = new ArrayList<>(p);
                    q.add(k);
                    final List<String> full = complete(q);
                    if (full == null) continue;
                    prefixFull.put(q, full.toString());
                    if (!byKey.containsKey(full.toString())) {
                        byKey.put(full.toString(), answer(full));
                        b.add(byKey.get(full.toString()));
                        bk.add(full.toString());
                    }
                }
            }
            eval(b, bk, score);
            final List<Map.Entry<List<String>, String>> es = new ArrayList<>(prefixFull.entrySet());
            es.sort((x, y) -> Double.compare(score.getOrDefault(y.getValue(), Double.NEGATIVE_INFINITY),
                    score.getOrDefault(x.getValue(), Double.NEGATIVE_INFINITY)));
            final List<List<String>> next = new ArrayList<>();
            final Set<String> seenFull = new HashSet<>();
            for (Map.Entry<List<String>, String> e : es) {
                if (next.size() >= cac.config().beamWidth) break;
                if (seenFull.add(e.getValue() + "|" + e.getKey().size())) next.add(e.getKey());
            }
            prefixes = next;
            if (prefixes.isEmpty()) break;
        }
        // the final decision: Forge's pile and the beam's best piles (solver check, log)
        final List<Map.Entry<String, Double>> ranked = new ArrayList<>(score.entrySet());
        ranked.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        final List<ChooseACard.Answer> fin = new ArrayList<>();
        fin.add(forge);
        for (Map.Entry<String, Double> e : ranked) {
            if (fin.size() >= Math.max(2, cac.config().topM + 2)) break;
            if (e.getValue() == Double.NEGATIVE_INFINITY) continue;
            final ChooseACard.Answer a = byKey.get(e.getKey());
            final ChooseACard.Answer copy = ChooseACard.pileAnswerOf(a);
            fin.add(copy);
        }
        d.entry.addProperty("beam_evaluated", evaluated);
        d.entry.addProperty("beam_batches", batches);
        d.entry.addProperty("beam_pool", keys.size());
        // review S2-4: the route roles order the pool; names past beamBranch are never tried as a prefix extension
        // (they can still enter a pile through a template's completion): disclosed here
        if (keys.size() > cac.config().beamBranch) {
            final JsonArray pruned = new JsonArray();
            for (int i = cac.config().beamBranch; i < keys.size(); i++) {
                pruned.add(name(keys.get(i)));
            }
            d.entry.add("beam_branch_pruned", pruned);
        }
        depth = n + 1;
        return cac.decide(d, fin);
    }

    private List<String> completeWith(final int t) {
        if (ROLES.templates.isEmpty()) return complete(new ArrayList<>());
        final List<String> tpl = ROLES.templates.get(t);
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String r = i < tpl.size() ? tpl.get(i) : "other";
            String pick = null;
            for (String k : keys) {
                if (used(out, k) < cards.get(k).size() && ("other".equals(r) || r.equals(ROLES.of(name(k))))) {
                    pick = k;
                    break;
                }
            }
            if (pick == null) return complete(out);
            out.add(pick);
        }
        return out;
    }

    private void eval(final List<ChooseACard.Answer> batch, final List<String> keysOf, final Map<String, Double> score) {
        if (batch.isEmpty()) return;
        batches++;
        evaluated += batch.size();
        final double[] s = cac.evaluate(d, batch, false);
        for (int i = 0; i < batch.size(); i++) {
            score.put(keysOf.get(i), s == null ? Double.NEGATIVE_INFINITY : s[i]);
        }
    }
}
