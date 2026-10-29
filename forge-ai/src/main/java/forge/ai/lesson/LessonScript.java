package forge.ai.lesson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A LESSON SCRIPT (mtgx lane lessons-forge-jar-1027, format {@code lesson-script/1}): the scripted opponent of a lesson
 * position, read from the {@code [script]} section of a lesson's .pzl and played by {@link LessonScriptController}. It is
 * the Chapter 1 subset of the client's {@code OpponentScript} ({@code src/app/learn/lessonTypes.ts}): named land plays,
 * casts paid with named sources and aimed at a named target, named attacks and blocks, a pass for everything else, and
 * {@code rest}/{@code botfrom} handing the seat to the wrapped Forge AI.
 *
 * <p>The text is Forge-style {@code key=value} lines; {@code #} starts a comment. Fields are separated by {@code |},
 * lists by {@code ;}, names are Forge's card names (a card name never holds either character). Seats are named as the
 * position's own {@code [state]} keys name them: {@code ai} is the scripted seat, {@code human} the browser's.</p>
 *
 * <pre>
 * rest=pass                     pass (default) | bot: what the seat does with a decision the script does not name
 * botfrom=turn/7                from this trigger on, every decision is the wrapped Forge AI's
 * plays=Forest;Forest           lands it plays on its own turn (main phase), in order
 * attack=Steppe Bull            the attackers whenever it declares attackers
 * block=Steppe Gazelle&gt;Rampart Sentry   blocker&gt;attacker pairs whenever it declares blockers
 * step=play:Forest|when:MAIN1/ai
 * step=cast:Swell with Might|tap:Forest;Forest|target:ai/Steppe Bull|when:MAIN1/ai
 * step=attack:Steppe Bull;Steppe Gazelle|when:COMBAT_DECLARE_ATTACKERS/ai
 * step=block:Steppe Gazelle&gt;Rampart Sentry|when:COMBAT_DECLARE_BLOCKERS/human
 * step=pass|when:MAIN2/ai|once:false
 * </pre>
 *
 * <p>Triggers ({@code when:}, {@code botfrom=}): {@code always}; {@code <PHASE>/<ai|human|any>} (a Forge phase name as
 * {@code activephase=} takes it, during that seat's turn); {@code turn/<n>} (the game's own turn number, from n on);
 * {@code stack-empty}. Targets: {@code human} or {@code ai} (a player), {@code human/<card>} or {@code ai/<card>} (a
 * permanent that seat controls). Steps are tried in order and each is spent once taken ({@code once:false} keeps it).
 * Phase names are checked against Forge when the seat is bound ({@link LessonScriptController}), since Forge's phase
 * table needs its localizer; everything else is checked here.</p>
 */
public final class LessonScript {
    public static final String FORMAT = "lesson-script/1";
    /** A script is at most this long (the position itself is at most 64 KiB). */
    public static final int MAX_TEXT = 16384;
    public static final int MAX_STEPS = 64;
    public static final int MAX_NAMES = 16;

    public enum Rest { PASS, BOT }

    public enum Kind { PLAY, CAST, ATTACK, BLOCK, PASS }

    /** A seat as the script names it: the scripted seat ({@code ai}), the browser's ({@code human}), or either. */
    public enum Side { AI, HUMAN, ANY }

    /** When a step (or {@code botfrom}) holds. {@code phase} is Forge's phase name, resolved at bind time. */
    public record Trigger(String at, String phase, Side whose, int turn) {
        static final Trigger ALWAYS = new Trigger("always", null, null, 0);

        public String text() {
            switch (at) {
                case "phase": return phase + "/" + whose.name().toLowerCase(Locale.ROOT);
                case "turn": return "turn/" + turn;
                default: return at;
            }
        }
    }

    /** A target: a player ({@code card} null) or a permanent of that seat's by name. */
    public record Target(Side seat, String card) {
    }

    /** A blocker and the attacker it blocks, by name. */
    public record Pair(String blocker, String attacker) {
    }

    /** One scripted action: {@code card} (play, cast), {@code names} (attack), {@code pairs} (block). */
    public record Step(Kind kind, String card, List<String> tap, Target target, List<String> names, List<Pair> pairs,
                       Trigger when, boolean once) {
    }

    private final Rest rest;
    private final Trigger botFrom;
    private final List<String> plays;
    private final List<String> attack;
    private final List<Pair> block;
    private final List<Step> steps;

    private LessonScript(final Rest rest, final Trigger botFrom, final List<String> plays, final List<String> attack,
                         final List<Pair> block, final List<Step> steps) {
        this.rest = rest;
        this.botFrom = botFrom;
        this.plays = plays;
        this.attack = attack;
        this.block = block;
        this.steps = steps;
    }

    public Rest rest() { return rest; }
    /** Null: the seat is never handed over by a trigger (only by drift). */
    public Trigger botFrom() { return botFrom; }
    public List<String> plays() { return plays; }
    /** Null: no attack shorthand (an attack the script does not name is the rest's). */
    public List<String> attack() { return attack; }
    /** Null: no block shorthand. */
    public List<Pair> block() { return block; }
    public List<Step> steps() { return steps; }

    /** Every card name the script names, once each, in order (the game start checks them against Forge's database). */
    public List<String> names() {
        final Set<String> out = new LinkedHashSet<>(plays);
        if (attack != null) {
            out.addAll(attack);
        }
        if (block != null) {
            for (Pair p : block) {
                out.add(p.blocker());
                out.add(p.attacker());
            }
        }
        for (Step s : steps) {
            if (s.card() != null) {
                out.add(s.card());
            }
            out.addAll(s.tap());
            if (s.target() != null && s.target().card() != null) {
                out.add(s.target().card());
            }
            out.addAll(s.names());
            for (Pair p : s.pairs()) {
                out.add(p.blocker());
                out.add(p.attacker());
            }
        }
        return new ArrayList<>(out);
    }

    /** Every phase name a trigger names (the game start resolves each with Forge's phase table). */
    public List<String> phases() {
        final List<String> out = new ArrayList<>();
        if (botFrom != null && botFrom.phase() != null) {
            out.add(botFrom.phase());
        }
        for (Step s : steps) {
            if (s.when().phase() != null) {
                out.add(s.when().phase());
            }
        }
        return out;
    }

    /** A malformed script: the message names the line, never more than 80 characters of it. */
    public static final class Malformed extends IllegalArgumentException {
        Malformed(final String message) {
            super(message);
        }
    }

    /**
     * The script in {@code text} (the [script] section's lines, without its header). Throws {@link Malformed}. A blank
     * text is a script with no steps: every decision passes ({@code rest=pass}), as a lesson partner with an empty script.
     */
    public static LessonScript parse(final String text) {
        if (text == null) {
            throw new Malformed("script is missing");
        }
        if (text.length() > MAX_TEXT) {
            throw new Malformed("script is longer than " + MAX_TEXT + " characters");
        }
        Rest rest = null;
        Trigger botFrom = null;
        List<String> plays = null;
        List<String> attack = null;
        List<Pair> block = null;
        boolean format = false;
        final List<Step> steps = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                throw new Malformed("script is the [script] block only (no section headers): " + clip(line));
            }
            final int eq = line.indexOf('=');
            if (eq < 1) {
                throw new Malformed("script line is not key=value: " + clip(line));
            }
            final String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT);
            final String value = line.substring(eq + 1).strip();
            switch (key) {
                case "format" -> {
                    if (format || !FORMAT.equals(value)) {
                        throw new Malformed("script format must be " + FORMAT + " (once): " + clip(line));
                    }
                    format = true;
                }
                case "rest" -> {
                    if (rest != null) {
                        throw new Malformed("script names rest twice");
                    }
                    rest = switch (value.toLowerCase(Locale.ROOT)) {
                        case "pass" -> Rest.PASS;
                        case "bot" -> Rest.BOT;
                        default -> throw new Malformed("rest must be pass or bot: " + clip(line));
                    };
                }
                case "botfrom" -> {
                    if (botFrom != null) {
                        throw new Malformed("script names botfrom twice");
                    }
                    botFrom = trigger(value, line);
                }
                case "plays" -> {
                    if (plays != null) {
                        throw new Malformed("script names plays twice");
                    }
                    plays = names(value, line, true);
                }
                case "attack" -> {
                    if (attack != null) {
                        throw new Malformed("script names attack twice");
                    }
                    attack = names(value, line, true);
                }
                case "block" -> {
                    if (block != null) {
                        throw new Malformed("script names block twice");
                    }
                    block = pairs(value, line, true);
                }
                case "step" -> {
                    if (steps.size() >= MAX_STEPS) {
                        throw new Malformed("script has more than " + MAX_STEPS + " steps");
                    }
                    steps.add(step(value, line));
                }
                default -> throw new Malformed("script has an unknown key: " + clip(key));
            }
        }
        return new LessonScript(rest == null ? Rest.PASS : rest, botFrom,
                plays == null ? List.of() : plays, attack, block, Collections.unmodifiableList(steps));
    }

    private static Step step(final String value, final String line) {
        final String[] fields = value.split("\\|", -1);
        final String head = fields[0].strip();
        final int colon = head.indexOf(':');
        final String kindWord = (colon < 0 ? head : head.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
        final String arg = colon < 0 ? null : head.substring(colon + 1).strip();
        final Kind kind;
        try {
            kind = Kind.valueOf(kindWord.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new Malformed("step must start with play:, cast:, attack:, block: or pass: " + clip(line));
        }
        String card = null;
        List<String> names = List.of();
        List<Pair> pairs = List.of();
        switch (kind) {
            case PLAY, CAST -> card = name(arg, line);
            case ATTACK -> names = names(arg, line, false);
            case BLOCK -> pairs = pairs(arg, line, false);
            case PASS -> {
                if (arg != null) {
                    throw new Malformed("a pass step names nothing: " + clip(line));
                }
            }
        }
        List<String> tap = List.of();
        Target target = null;
        Trigger when = null;
        Boolean once = null;
        for (int i = 1; i < fields.length; i++) {
            final String f = fields[i].strip();
            final int c = f.indexOf(':');
            if (c < 1) {
                throw new Malformed("step field is not key:value: " + clip(line));
            }
            final String k = f.substring(0, c).strip().toLowerCase(Locale.ROOT);
            final String v = f.substring(c + 1).strip();
            switch (k) {
                case "tap" -> {
                    if (kind != Kind.CAST || !tap.isEmpty()) {
                        throw new Malformed("tap: is a cast step's, once: " + clip(line));
                    }
                    tap = names(v, line, false);
                }
                case "target" -> {
                    if (kind != Kind.CAST || target != null) {
                        throw new Malformed("target: is a cast step's, once: " + clip(line));
                    }
                    target = target(v, line);
                }
                case "when" -> {
                    if (when != null) {
                        throw new Malformed("step names when twice: " + clip(line));
                    }
                    when = trigger(v, line);
                }
                case "once" -> {
                    if (once != null || !(v.equals("true") || v.equals("false"))) {
                        throw new Malformed("once must be true or false (once): " + clip(line));
                    }
                    once = Boolean.valueOf(v);
                }
                default -> throw new Malformed("step has an unknown field: " + clip(k));
            }
        }
        if (when == null) {
            throw new Malformed("step needs when: " + clip(line));
        }
        return new Step(kind, card, tap, target, names, pairs, when, once == null || once);
    }

    private static Trigger trigger(final String value, final String line) {
        final String v = value.strip();
        if (v.equals("always")) {
            return Trigger.ALWAYS;
        }
        if (v.equals("stack-empty")) {
            return new Trigger("stack-empty", null, null, 0);
        }
        final int slash = v.indexOf('/');
        if (slash < 1) {
            throw new Malformed("trigger must be always, stack-empty, turn/<n> or <PHASE>/<ai|human|any>: " + clip(line));
        }
        final String a = v.substring(0, slash).strip();
        final String b = v.substring(slash + 1).strip();
        if (a.equals("turn")) {
            final int n;
            try {
                n = Integer.parseInt(b);
            } catch (NumberFormatException e) {
                throw new Malformed("turn/<n> needs a turn number: " + clip(line));
            }
            if (n < 1 || n > 1000) {
                throw new Malformed("turn/<n> must be from 1 to 1000: " + clip(line));
            }
            return new Trigger("turn", null, null, n);
        }
        if (!a.matches("[A-Za-z0-9_ ]{2,40}")) {
            throw new Malformed("a trigger's phase is a Forge phase name: " + clip(line));
        }
        final Side whose = switch (b.toLowerCase(Locale.ROOT)) {
            case "ai" -> Side.AI;
            case "human" -> Side.HUMAN;
            case "any" -> Side.ANY;
            default -> throw new Malformed("a phase trigger's seat is ai, human or any: " + clip(line));
        };
        return new Trigger("phase", a, whose, 0);
    }

    private static Target target(final String value, final String line) {
        final String v = value.strip();
        final int slash = v.indexOf('/');
        final String seat = (slash < 0 ? v : v.substring(0, slash)).strip().toLowerCase(Locale.ROOT);
        final Side side = switch (seat) {
            case "ai" -> Side.AI;
            case "human" -> Side.HUMAN;
            default -> throw new Malformed("a target is human, ai, human/<card> or ai/<card>: " + clip(line));
        };
        return new Target(side, slash < 0 ? null : name(v.substring(slash + 1), line));
    }

    private static List<Pair> pairs(final String value, final String line, final boolean mayBeEmpty) {
        final List<Pair> out = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            for (String p : value.split(";")) {
                final int gt = p.indexOf('>');
                if (gt < 0) {
                    throw new Malformed("a block pair is blocker>attacker: " + clip(line));
                }
                out.add(new Pair(name(p.substring(0, gt), line), name(p.substring(gt + 1), line)));
            }
        }
        if (out.isEmpty() && !mayBeEmpty) {
            throw new Malformed("a block step names at least one pair: " + clip(line));
        }
        if (out.size() > MAX_NAMES) {
            throw new Malformed("more than " + MAX_NAMES + " pairs: " + clip(line));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> names(final String value, final String line, final boolean mayBeEmpty) {
        final List<String> out = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            for (String n : value.split(";")) {
                out.add(name(n, line));
            }
        }
        if (out.isEmpty() && !mayBeEmpty) {
            throw new Malformed("a list here names at least one card: " + clip(line));
        }
        if (out.size() > MAX_NAMES) {
            throw new Malformed("more than " + MAX_NAMES + " names: " + clip(line));
        }
        return Collections.unmodifiableList(out);
    }

    private static String name(final String value, final String line) {
        final String n = value == null ? "" : value.strip();
        if (n.isEmpty() || n.length() > 141 || n.indexOf('>') >= 0 || n.indexOf('|') >= 0 || n.indexOf(';') >= 0) {
            throw new Malformed("a card name is missing or malformed: " + clip(line));
        }
        return n;
    }

    private static String clip(final String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
