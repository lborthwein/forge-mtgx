package forge.bench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gate G4 (lane rl-obs-v2-1006): a digest of every DECIDE / RECORD frame an actor sent, per game in send order, so that
 * two jars can be compared byte for byte (obs-v1 with v2 off vs the reference built without the v2 code). Uses only
 * the wire/1 API, so the same class compiles against the reference jar. RlFakeRun spec keys {@code frameSha},
 * {@code frameShaOut} (one "uid frames sha" line per game).
 */
final class RlFrameSha {
    private final Map<Long, MessageDigest> byGame = new TreeMap<>();
    private final Map<Long, Integer> counts = new TreeMap<>();
    long frames;

    synchronized void add(final long uid, final byte[] frame) {
        byGame.computeIfAbsent(uid, k -> sha()).update(frame);
        counts.merge(uid, 1, Integer::sum);
        frames++;
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(final byte[] b) {
        final StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Per-game lines "uid frames sha", uid ascending. */
    synchronized String lines() {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, MessageDigest> e : byGame.entrySet()) {
            try {
                sb.append(Long.toUnsignedString(e.getKey())).append(' ').append(counts.get(e.getKey())).append(' ')
                        .append(hex(((MessageDigest) e.getValue().clone()).digest())).append('\n');
            } catch (CloneNotSupportedException x) {
                throw new IllegalStateException(x);
            }
        }
        return sb.toString();
    }

    /** One sha over every game's line. */
    synchronized String total() {
        return hex(sha().digest(lines().getBytes(StandardCharsets.UTF_8)));
    }
}
