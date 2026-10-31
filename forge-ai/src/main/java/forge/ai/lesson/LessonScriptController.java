package forge.ai.lesson;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.ai.ComputerUtilMana;
import forge.ai.ForcedChoiceObserver;
import forge.ai.PlayerControllerAi;
import forge.ai.ScriptedManaSources;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * THE SCRIPTED LESSON SEAT (mtgx lane lessons-forge-jar-1027, J2): a Forge AI seat that plays a {@link LessonScript}.
 * Every decision the script names (a land, a cast with its named payment sources and target, the attackers, the blocks)
 * is the script's; every other priority decision is a pass ({@code rest=pass}) or the wrapped Forge AI's
 * ({@code rest=bot}); once {@code botfrom} holds, every decision is the wrapped Forge AI's for good. The wrapped Forge AI
 * is this controller's own {@link PlayerControllerAi} (its {@code super}), so a hand-over happens in place, in the same
 * game. Every question the script cannot name (a trigger's target, a damage order) is the wrapped Forge AI's.
 *
 * <p><b>Drift.</b> The player may take another line (owner, report 05-32-02). A scripted action whose objects are gone
 * when it is due is never forced: the step is spent, the seat is handed to the wrapped Forge AI, which answers that very
 * decision and every one after it, and a {@code [lesson-script]} line says so. Gone means: a named payment source not
 * on its battlefield untapped (or a creature source summoning sick), a named target not there or not a legal target, a
 * named attacker or blocker no longer on its battlefield, a cast the named sources cannot pay, a declaration Forge
 * would refuse. Not drift, as the client's lesson partner: a card to play or cast not in hand yet, or a spell not
 * castable at this moment (its timing): the step waits; a named attacker there but unable to attack now (tapped, sick):
 * it stays home and the rest attack; a block pair whose attacker is not attacking, or whose blocker is already
 * blocking, tapped or unable to block that attacker: the pair is skipped.</p>
 *
 * <p><b>Payment.</b> A scripted cast is paid with its named sources: {@link ComputerUtilMana}'s real payment loop asks
 * {@link #chooseManaSource} for each source it taps ({@link ScriptedManaSources}); a shard no named source can pay is Forge
 * AI's own choice (logged).</p>
 *
 * <p><b>The AI tape.</b> Recording, the tape wraps this controller (its inner) and records what it answers, so a forced
 * replay plays the script's actions from the record. Replaying (forced, resume, restore), the tape never asks this
 * controller, so it tells it of each choice first ({@link ForcedChoiceObserver}) and this controller re-plans that
 * decision without acting, so its spent steps stay where the live seat's were.</p>
 *
 * <p>Lines on stderr: {@code [lesson-script] {...}}, with step indexes and reasons, never card names (the script names
 * the AI's hidden cards).</p>
 */
public final class LessonScriptController extends PlayerControllerAi implements ScriptedManaSources, ForcedChoiceObserver {
    public static final String PREFIX = "[lesson-script] ";

    private LessonScript script;
    private Map<String, PhaseType> phases;
    private boolean[] spent;
    private int playsDone;
    private boolean handed;
    /** The scripted cast now being paid for: its card's id and its named sources' ids, in script order (unused ones). */
    private Pending pending;
    private final PrintStream log;
    private int acts;
    private int drifts;

    private record Pending(int cardId, List<Integer> sources) {
    }

    /** What a priority decision comes to: a play, a pass, or the wrapped Forge AI's. */
    private record Plan(SpellAbility play, boolean bot) {
        static final Plan PASS = new Plan(null, false);
        static final Plan BOT = new Plan(null, true);
    }

    public LessonScriptController(final Game game, final Player player, final LobbyPlayer lobby, final LessonScript script) {
        this(game, player, lobby, script, System.err);
    }

    public LessonScriptController(final Game game, final Player player, final LobbyPlayer lobby, final LessonScript script,
                                  final PrintStream log) {
        super(game, player, lobby);
        this.log = log;
        load(script);
    }

    /**
     * The seat {@code ai} plays {@code script} from now on: its current controller is replaced by a scripted one over
     * the same lobby player (the same name, profile and AI options). Call before the AI tape is installed, so the tape
     * wraps the scripted seat. Returns the new controller.
     */
    public static LessonScriptController seat(final Game game, final Player ai, final LessonScript script) {
        if (!(ai.getController() instanceof PlayerControllerAi was)) {
            throw new IllegalStateException("a lesson script needs a Forge AI seat");
        }
        final LessonScriptController c = new LessonScriptController(game, ai, was.getLobbyPlayer(), script);
        c.getAi().setUseSimulation(null);
        ai.dangerouslySetController(c);
        return c;
    }

    /**
     * A new script, with a fresh place in it (J3: a stage's position installed in a running game). The phase names are
     * resolved with Forge's phase table; an unknown one throws {@link LessonScript.Malformed}.
     */
    public void load(final LessonScript next) {
        final Map<String, PhaseType> resolved = new HashMap<>();
        for (String p : next.phases()) {
            final PhaseType t;
            try {
                t = PhaseType.smartValueOf(p);
            } catch (IllegalArgumentException e) {
                throw new LessonScript.Malformed("a trigger names no Forge phase");
            }
            if (t == null) {
                throw new LessonScript.Malformed("a trigger names no Forge phase");
            }
            resolved.put(p, t);
        }
        this.script = next;
        this.phases = resolved;
        this.spent = new boolean[next.steps().size()];
        this.playsDone = 0;
        this.handed = false;
        this.pending = null;
        say("loaded", -1, null, "\"steps\":" + next.steps().size() + ",\"rest\":\"" + next.rest().name().toLowerCase(Locale.ROOT)
                + "\",\"botfrom\":" + (next.botFrom() != null));
    }

    public LessonScript script() { return script; }
    /** True once the seat is the wrapped Forge AI's for good ({@code botfrom} held, or a scripted action drifted). */
    public boolean handedToBot() { return handed; }
    public int actsTaken() { return acts; }
    public int drifts() { return drifts; }

    // ------------------------------------------------------------------ the three scripted decisions

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        final Plan plan = planPriority(false);
        if (!plan.bot) {
            return plan.play == null ? null : new ArrayList<>(List.of(plan.play));
        }
        if (handed || script.rest() == LessonScript.Rest.PASS) {
            return super.chooseSpellAbilityToPlay();
        }
        // rest=bot, the script not done: the cards it still means to cast are held back from the bot (as the client's
        // partner holds them back), and a land it still means to play is never played by the bot
        final Set<String> held = reserved();
        if (held.isEmpty()) {
            return super.chooseSpellAbilityToPlay();
        }
        final Predicate<SpellAbility> veto = sa -> sa.getHostCard() != null && held.contains(sa.getHostCard().getName())
                && (sa.isSpell() || sa.isLandAbility());
        getAi().setPolicyVeto(veto);
        final List<SpellAbility> chosen;
        try {
            chosen = super.chooseSpellAbilityToPlay();
        } finally {
            getAi().setPolicyVeto(null);
        }
        if (chosen != null && chosen.stream().anyMatch(veto)) {
            say("held", -1, null, null);
            return null;
        }
        return chosen;
    }

    @Override
    public boolean playChosenSpellAbility(final SpellAbility sa) {
        try {
            return super.playChosenSpellAbility(sa);
        } finally {
            pending = null;
        }
    }

    @Override
    public void declareAttackers(final Player attacker, final Combat combat) {
        final List<Card> chosen = planAttack(combat, false);
        if (chosen == null) {
            super.declareAttackers(attacker, combat);
            return;
        }
        // never an invalid declaration (the phase handler would ask again forever): a requirement the script did not
        // meet ("attacks each combat if able") is the wrapped Forge AI's to meet
        if (!attackValid(combat, chosen)) {
            drift(-1, "attack", "invalid");
            super.declareAttackers(attacker, combat);
            return;
        }
        final GameEntity defender = defenderOf(combat);
        for (Card c : chosen) {
            combat.addAttacker(c, defender);
        }
    }

    /**
     * Would Forge take {@code chosen} as the declaration? Tried on the combat and undone, so the live seat and a forced
     * re-plan ({@link #beforeForcedChoice}) decide the same way and leave the combat as it was.
     */
    private boolean attackValid(final Combat combat, final List<Card> chosen) {
        final GameEntity defender = defenderOf(combat);
        for (Card c : chosen) {
            combat.addAttacker(c, defender);
        }
        final boolean valid = CombatUtil.validateAttackers(combat);
        for (Card c : chosen) {
            combat.removeFromCombat(c);
        }
        return valid;
    }

    /** As {@link #attackValid}, for the {blocker, attacker} pairs: tried, then undone. */
    private boolean blockValid(final Combat combat, final Player defender, final List<Card[]> chosen) {
        for (Card[] pair : chosen) {
            combat.addBlocker(pair[1], pair[0]);
        }
        final boolean valid = CombatUtil.validateBlocks(combat, defender) == null;
        for (Card[] pair : chosen) {
            combat.removeBlockAssignment(pair[1], pair[0]);
        }
        return valid;
    }

    @Override
    public void declareBlockers(final Player defender, final Combat combat) {
        final List<Card[]> chosen = planBlock(combat, false);
        if (chosen == null) {
            super.declareBlockers(defender, combat);
            return;
        }
        if (!blockValid(combat, defender, chosen)) {
            drift(-1, "block", "invalid");
            super.declareBlockers(defender, combat);
            return;
        }
        for (Card[] pair : chosen) {
            combat.addBlocker(pair[1], pair[0]);
        }
    }

    // ------------------------------------------------------------------ the AI tape's forced replay

    @Override
    public void beforeForcedChoice(final String method) {
        try {
            switch (method) {
                case "chooseSpellAbilityToPlay" -> planPriority(true);
                // the live seat's hand-over on an invalid declaration is re-planned too (review #139 S3-1), so the seat
                // is the wrapped Forge AI's from that decision on in the replay exactly as it was live
                case "declareAttackers" -> {
                    final Combat combat = getGame().getPhaseHandler().getCombat();
                    if (combat != null) {
                        final List<Card> chosen = planAttack(combat, true);
                        if (chosen != null && !attackValid(combat, chosen)) {
                            drift(-1, "attack", "invalid");
                        }
                    }
                }
                case "declareBlockers" -> {
                    final Combat combat = getGame().getPhaseHandler().getCombat();
                    if (combat != null) {
                        final List<Card[]> chosen = planBlock(combat, true);
                        if (chosen != null && !blockValid(combat, getPlayer(), chosen)) {
                            drift(-1, "block", "invalid");
                        }
                    }
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            // never break a replay: the record answers the decision whatever the script's place
            say("shadow-error", -1, null, "\"error\":\"" + e.getClass().getSimpleName() + "\"");
        }
    }

    // ------------------------------------------------------------------ payment

    @Override
    public SpellAbility chooseManaSource(final SpellAbility paying, final Collection<SpellAbility> options,
                                         final Supplier<SpellAbility> ai) {
        final Pending p = pending;
        if (p == null || paying == null || paying.getHostCard() == null || paying.getHostCard().getId() != p.cardId()) {
            return ai.get();
        }
        for (Integer id : p.sources()) {
            for (SpellAbility o : options) {
                if (o.getHostCard() != null && o.getHostCard().getId() == id) {
                    p.sources().remove(id);
                    return o;
                }
            }
        }
        say("payment", -1, null, "\"offScript\":true");
        return ai.get();
    }

    // ------------------------------------------------------------------ planning (shadow: change the script's place only)

    private Plan planPriority(final boolean shadow) {
        checkBotFrom();
        if (handed) {
            return Plan.BOT;
        }
        final Player me = getPlayer();
        final List<LessonScript.Step> steps = script.steps();
        for (int i = 0; i < steps.size(); i++) {
            final LessonScript.Step s = steps.get(i);
            if (spent[i] && s.once()) {
                continue;
            }
            if (s.kind() == LessonScript.Kind.ATTACK || s.kind() == LessonScript.Kind.BLOCK || !holds(s.when())) {
                continue;
            }
            switch (s.kind()) {
                case PASS -> {
                    spend(i, "pass");
                    return Plan.PASS;
                }
                case PLAY -> {
                    final SpellAbility land = landPlay(s.card());
                    if (land == null) {
                        continue; // not in hand yet, or no land drop now: the step waits
                    }
                    spend(i, "play");
                    return new Plan(land, false);
                }
                case CAST -> {
                    final Card card = first(me.getCardsIn(ZoneType.Hand), s.card(), c -> true);
                    final SpellAbility sa = card == null ? null : spellOf(card);
                    if (sa == null) {
                        continue; // not in hand yet, or not castable at this moment: the step waits
                    }
                    final List<Card> sources = sources(s.tap());
                    if (sources == null) {
                        return driftToBot(i, "cast", "source");
                    }
                    final GameObject target = s.target() == null ? null : target(s.target());
                    if (s.target() != null && (target == null || !sa.usesTargeting() || !sa.canTarget(target))) {
                        return driftToBot(i, "cast", "target");
                    }
                    if (s.target() == null && sa.usesTargeting()) {
                        return driftToBot(i, "cast", "target");
                    }
                    // the same in a shadow re-plan: a forced replay then sets the record's targets on it (AiTape cfg)
                    sa.resetTargets();
                    if (target != null) {
                        sa.getTargets().add(target);
                    }
                    if (!ComputerUtilMana.canPayManaCost(sa, me, 0, false)) {
                        sa.resetTargets();
                        return driftToBot(i, "cast", "unpayable");
                    }
                    spend(i, "cast");
                    if (!shadow) {
                        final List<Integer> ids = new ArrayList<>();
                        for (Card c : sources) {
                            ids.add(c.getId());
                        }
                        pending = new Pending(card.getId(), ids);
                    }
                    return new Plan(sa, false);
                }
                default -> { }
            }
        }
        // the shorthand: lands it plays on its own turn, in order
        final PhaseHandler ph = getGame().getPhaseHandler();
        if (playsDone < script.plays().size() && ph.isPlayerTurn(me) && ph.getPhase() != null && ph.getPhase().isMain()) {
            final SpellAbility land = landPlay(script.plays().get(playsDone));
            if (land != null) {
                playsDone++;
                say("act", -1, "play", null);
                acts++;
                return new Plan(land, false);
            }
        }
        return script.rest() == LessonScript.Rest.BOT ? Plan.BOT : Plan.PASS;
    }

    /** The attackers to declare; null: the wrapped Forge AI declares them (handed over, drift, or rest=bot unnamed). */
    private List<Card> planAttack(final Combat combat, final boolean shadow) {
        checkBotFrom();
        if (handed) {
            return null;
        }
        int step = -1;
        List<String> names = null;
        final List<LessonScript.Step> steps = script.steps();
        for (int i = 0; i < steps.size() && names == null; i++) {
            final LessonScript.Step s = steps.get(i);
            if (s.kind() == LessonScript.Kind.ATTACK && !(spent[i] && s.once()) && holds(s.when())) {
                step = i;
                names = s.names();
            }
        }
        if (names == null) {
            names = script.attack();
        }
        if (names == null) {
            return script.rest() == LessonScript.Rest.BOT ? null : List.of();
        }
        final GameEntity defender = defenderOf(combat);
        final List<Card> chosen = new ArrayList<>();
        final Set<Card> named = new HashSet<>();
        for (String name : names) {
            // gone from the battlefield (the player removed it): drift; there but unable to attack now (tapped, sick):
            // it stays home and the rest attack, as the client's partner does with the legal attackers it names
            final Card any = first(getPlayer().getCardsIn(ZoneType.Battlefield), name, x -> x.isCreature() && !named.contains(x));
            if (any == null || defender == null) {
                driftToBot(step, "attack", "attacker");
                return null;
            }
            final Card c = first(getPlayer().getCardsIn(ZoneType.Battlefield), name, x -> x.isCreature() && !named.contains(x)
                    && !combat.isAttacking(x) && CombatUtil.canAttack(x, defender));
            named.add(c != null ? c : any);
            if (c != null) {
                chosen.add(c);
            }
        }
        if (step >= 0) {
            spend(step, "attack");
        } else {
            say("act", -1, "attack", null);
            acts++;
        }
        return chosen;
    }

    /** The {blocker, attacker} pairs to declare; null: the wrapped Forge AI declares them. */
    private List<Card[]> planBlock(final Combat combat, final boolean shadow) {
        checkBotFrom();
        if (handed) {
            return null;
        }
        int step = -1;
        List<LessonScript.Pair> pairs = null;
        final List<LessonScript.Step> steps = script.steps();
        for (int i = 0; i < steps.size() && pairs == null; i++) {
            final LessonScript.Step s = steps.get(i);
            if (s.kind() == LessonScript.Kind.BLOCK && !(spent[i] && s.once()) && holds(s.when())) {
                step = i;
                pairs = s.pairs();
            }
        }
        if (pairs == null) {
            pairs = script.block();
        }
        if (pairs == null) {
            return script.rest() == LessonScript.Rest.BOT ? null : List.of();
        }
        final List<Card[]> chosen = new ArrayList<>();
        final Set<Card> usedBlockers = new HashSet<>();
        for (LessonScript.Pair pair : pairs) {
            Card attacker = null;
            for (Card a : combat.getAttackers()) {
                if (a.getName().equals(pair.attacker()) && combat.getDefenderPlayerByAttacker(a) == getPlayer()) {
                    attacker = a;
                    break;
                }
            }
            if (attacker == null) {
                continue; // the player did not attack with it: no block to make
            }
            final Card a = attacker;
            if (first(getPlayer().getCardsIn(ZoneType.Battlefield), pair.blocker(), Card::isCreature) == null) {
                driftToBot(step, "block", "blocker"); // gone from the battlefield (the player removed it)
                return null;
            }
            // there, but already blocking for an earlier pair, tapped, or unable to block this attacker (a flier): the
            // pair is skipped, as the client's partner skips a pair the engine does not offer
            final Card blocker = first(getPlayer().getCardsIn(ZoneType.Battlefield), pair.blocker(),
                    x -> x.isCreature() && !usedBlockers.contains(x) && !combat.isBlocking(x) && CombatUtil.canBlock(a, x, combat));
            if (blocker == null) {
                continue;
            }
            usedBlockers.add(blocker);
            chosen.add(new Card[] {blocker, a});
        }
        if (step >= 0) {
            spend(step, "block");
        } else {
            say("act", -1, "block", null);
            acts++;
        }
        return chosen;
    }

    // ------------------------------------------------------------------ helpers

    private void checkBotFrom() {
        if (!handed && script.botFrom() != null && holds(script.botFrom())) {
            handed = true;
            say("bot", -1, null, "\"why\":\"botfrom\"");
        }
    }

    private boolean holds(final LessonScript.Trigger t) {
        final PhaseHandler ph = getGame().getPhaseHandler();
        switch (t.at()) {
            case "always":
                return true;
            case "stack-empty":
                return getGame().getStack().isEmpty();
            case "turn":
                return ph.getTurn() >= t.turn();
            case "phase": {
                final PhaseType want = phases.get(t.phase());
                if (want == null || ph.getPhase() != want) {
                    return false;
                }
                final boolean mine = ph.isPlayerTurn(getPlayer());
                return t.whose() == LessonScript.Side.ANY || (t.whose() == LessonScript.Side.AI) == mine;
            }
            default:
                return false;
        }
    }

    /** The playable land ability of a land named {@code name} in hand, or null (none in hand, or no land drop now). */
    private SpellAbility landPlay(final String name) {
        final Player me = getPlayer();
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            if (!c.getName().equals(name) || !c.isLand()) {
                continue;
            }
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.isLandAbility()) {
                    sa.setActivatingPlayer(me);
                    if (sa.canPlay()) {
                        return sa;
                    }
                }
            }
        }
        return null;
    }

    /** The card's spell, castable at this moment (its timing; payment is checked apart), or null. */
    private SpellAbility spellOf(final Card card) {
        final Player me = getPlayer();
        for (SpellAbility sa : card.getSpells()) {
            if (sa.isLandAbility()) {
                continue;
            }
            sa.setActivatingPlayer(me);
            if (sa.canPlay()) {
                return sa;
            }
        }
        return null;
    }

    /** The named payment sources, one card each in script order (untapped, able to make mana now), or null: drift. */
    private List<Card> sources(final List<String> names) {
        final List<Card> out = new ArrayList<>();
        for (String name : names) {
            final Card c = first(getPlayer().getCardsIn(ZoneType.Battlefield), name,
                    x -> !out.contains(x) && x.isUntapped() && !x.getManaAbilities().isEmpty()
                            && !(x.isCreature() && x.isSick()));
            if (c == null) {
                return null;
            }
            out.add(c);
        }
        return out;
    }

    private GameObject target(final LessonScript.Target t) {
        final Player me = getPlayer();
        final Player human = me.getSingleOpponent();
        final Player seat = t.seat() == LessonScript.Side.AI ? me : human;
        if (t.card() == null) {
            return seat;
        }
        return first(seat == null ? List.of() : seat.getCardsIn(ZoneType.Battlefield), t.card(), c -> true);
    }

    /** The player it attacks: the browser seat (a lesson is a two-player game). */
    private GameEntity defenderOf(final Combat combat) {
        return getPlayer().getSingleOpponent();
    }

    private static Card first(final Iterable<Card> cards, final String name, final Predicate<Card> also) {
        for (Card c : cards) {
            if (c.getName().equals(name) && also.test(c)) {
                return c;
            }
        }
        return null;
    }

    /** Names the script still means to play or cast (rest=bot holds them back from the wrapped AI). */
    private Set<String> reserved() {
        final Set<String> out = new HashSet<>();
        final List<LessonScript.Step> steps = script.steps();
        for (int i = 0; i < steps.size(); i++) {
            final LessonScript.Step s = steps.get(i);
            if ((s.kind() == LessonScript.Kind.PLAY || s.kind() == LessonScript.Kind.CAST) && !(spent[i] && s.once())) {
                out.add(s.card());
            }
        }
        for (int i = playsDone; i < script.plays().size(); i++) {
            out.add(script.plays().get(i));
        }
        return out;
    }

    private void spend(final int i, final String kind) {
        if (i >= 0) {
            spent[i] = true;
        }
        acts++;
        say("act", i, kind, null);
    }

    private Plan driftToBot(final int step, final String kind, final String why) {
        drift(step, kind, why);
        return Plan.BOT;
    }

    private void drift(final int step, final String kind, final String why) {
        if (step >= 0) {
            spent[step] = true;
        }
        drifts++;
        handed = true;
        say("drift", step, kind, "\"why\":\"" + why + "\"");
    }

    private void say(final String event, final int step, final String kind, final String extra) {
        if (log == null) {
            return;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("event", event);
        if (step >= 0) {
            o.addProperty("step", step);
        }
        if (kind != null) {
            o.addProperty("kind", kind);
        }
        final PhaseHandler ph = getGame().getPhaseHandler();
        o.addProperty("turn", ph.getTurn());
        if (ph.getPhase() != null) {
            o.addProperty("phase", ph.getPhase().name());
        }
        String line = o.toString();
        if (extra != null) {
            line = line.substring(0, line.length() - 1) + "," + extra + "}";
        }
        log.println(PREFIX + line);
    }
}
