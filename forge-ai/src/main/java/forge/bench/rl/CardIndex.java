package forge.bench.rl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The card-name index (interfaces.md §3.2): the TSV {@code name\tindex} exported by {@code tools/ml/rl/cardindex.py}
 * from {@code cards-ext.npz}. Index 0 = {@code <pad>}, 1 = {@code <unk>}. Read-only after construction and safe to
 * share across game threads.
 *
 * <p>Lookup, in order (identical to {@code tools/ml/rl/cardindex.py lookup}): (1) the exact name; (2) the name with
 * a trailing {@code " Token"} stripped; (3) the front face of a split or MDFC name (the part of the asked name before
 * {@code " // "}); (4) otherwise 1, counted. Duplicate TSV names keep their first (lowest) index.
 */
public final class CardIndex {
    public static final int PAD = 0;
    public static final int UNK = 1;
    private static final String TOKEN = " Token";
    private static final String FACES = " // ";

    private final Map<String, Integer> exact = new HashMap<>();
    private final String sha;
    private final int size;
    /** Every distinct unknown name met (diagnostics; bounded). */
    private final Set<String> unknown = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicLong unknownLookups = new AtomicLong();

    private CardIndex(final byte[] tsv) {
        this.sha = sha256(tsv);
        int n = 0;
        final String text = new String(tsv, StandardCharsets.UTF_8);
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(start, end);
            start = end + 1;
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                continue;
            }
            final int tab = line.lastIndexOf('\t');
            if (tab < 0) {
                throw new IllegalArgumentException("card index: no tab in line: " + line);
            }
            final String name = line.substring(0, tab);
            final int idx;
            try {
                idx = Integer.parseInt(line.substring(tab + 1).trim());
            } catch (NumberFormatException e) {
                if (n == 0) {
                    continue; // a header line
                }
                throw new IllegalArgumentException("card index: bad index in line: " + line);
            }
            n++;
            exact.putIfAbsent(name, idx);
        }
        this.size = n;
    }

    public static CardIndex load(final Path tsv) throws IOException {
        return new CardIndex(Files.readAllBytes(tsv));
    }

    public static CardIndex of(final byte[] tsv) {
        return new CardIndex(tsv);
    }

    /** sha256 hex of the TSV bytes, as sent in HELLO. */
    public String sha() {
        return sha;
    }

    public int size() {
        return size;
    }

    /** The index of a visible card's name; {@link #UNK} when nothing matches (counted). */
    public int lookup(final String name) {
        final int i = resolve(name);
        if (i == UNK) {
            unknownLookups.incrementAndGet();
            if (name != null && unknown.size() < 10_000) {
                unknown.add(name);
            }
        }
        return i;
    }

    /** As {@link #lookup} without counting. */
    public int resolve(final String name) {
        if (name == null || name.isEmpty()) {
            return UNK;
        }
        Integer i = exact.get(name);
        if (i != null) {
            return i;
        }
        final String stripped = name.endsWith(TOKEN) ? name.substring(0, name.length() - TOKEN.length()) : null;
        if (stripped != null && (i = exact.get(stripped)) != null) {
            return i;
        }
        final int f = name.indexOf(FACES);
        if (f > 0 && (i = exact.get(name.substring(0, f))) != null) {
            return i;
        }
        return UNK;
    }

    public Set<String> unknownNames() {
        return Collections.unmodifiableSet(unknown);
    }

    public long unknownLookups() {
        return unknownLookups.get();
    }

    public static String sha256(final byte[] b) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(b);
            final StringBuilder sb = new StringBuilder(64);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
