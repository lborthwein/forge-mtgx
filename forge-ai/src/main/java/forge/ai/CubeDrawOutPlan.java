package forge.ai;

import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** v66 - the catalogue's draw-out finishes OUTSIDE Doomsday: matrix families U
 * (Oath of Druids + Jace, Wielder of Mysteries) and V (Griselbrand + Jace /
 * Laboratory Maniac, and the Sheoldred-class drain).
 *
 * <p>Everything is recognised by PRINTED PROPERTY, never by card name, and every
 * forecast is made from own-visible information only: our own battlefield and
 * hand, our library SIZE, our life, and both public battlefields and life
 * totals. No library is ever iterated, and no opponent hand, library order or
 * decklist fact is read.</p>
 *
 * <p><b>Disjointness from v51's route J.</b> {@link CubeDoomsdayPlan}'s route J
 * already owns a win-on-empty-draw replacement live on our own battlefield
 * finished by ONE resolution of a draw ability, and the controller consults that
 * plan FIRST. So route G below declines outright whenever the replacement is
 * live and one activation would suffice ({@code single-draw-owned-elsewhere}).
 * The two plans therefore never contend for a board, and this plan is invisible
 * on every board route J already owns.</p>
 *
 * <p>The route-J property detectors are {@code private} in that class and this
 * increment may not edit it, so the two small detectors below are duplicated,
 * held to the same printed allow-lists word for word.</p> */
public final class CubeDrawOutPlan {
    private final Player player;
    private int turn = -1, actions, failedTurn = -1;
    private SpellAbility selected;
    private String selectedRoute = "none";
    private SpellAbility pending;
    private String pendingRoute = "none";
    private int libraryBefore, opponentLifeBefore;

    /** Diagnostic only, never read by a decision: how many actions this plan
     * proposed, how often the Oath override actually changed the ordinary
     * answer, and how often it saw its own shape and left the ordinary answer
     * alone. Test-visible statics, read and reset reflectively by the fixture,
     * exactly like {@link CubeDoomsdayPlan#jaceFinishes}. */
    static int drawOutActions, oathAccepts, oathDeclines;

    /** Observability only: the token for the check that already declined the
     * most recent {@link #nextAction}. Never read by a decision, exactly like
     * {@link CubeMonolithPlan#declineReason}.
     *
     * <p>Grammar, one token per (turn, phase): {@code phase},
     * {@code stack-not-empty}, {@code cant-win}, {@code multiplayer},
     * {@code action-cap}, {@code failed-this-turn}, {@code stopped-no-progress},
     * {@code no-finisher}, {@code no-engine}, {@code draw-capped},
     * {@code single-draw-owned-elsewhere}, {@code library-too-large},
     * {@code life-budget}, {@code unpayable:engine}, {@code unpayable:finisher},
     * {@code unpayable:combined}, {@code finisher-uncastable}, {@code no-burst},
     * {@code cant-drain}, {@code drain-no-engine}, {@code drain-short},
     * {@code better-attack}, {@code oath:*} or the default
     * {@code other check=drawout-plan}. Every one names our own zones, our own
     * library SIZE, a public life total or a public board shape; none names an
     * opponent hand, library content, library order or decklist fact. */
    private String decline = "other check=drawout-plan";
    public String declineReason() { return decline; }
    private SpellAbility decline(String reason) { decline = reason; return null; }

    /** Names the first true clause of the opening guard, re-reading only the
     * same pure getters in the same order. Called only after that guard has
     * already decided to decline, so it can change nothing. */
    private String gateReason(int cap) {
        var phase = player.getGame().getPhaseHandler();
        if (failedTurn == turn) return "failed-this-turn";
        if (actions >= cap) return "action-cap";
        if (player.cantWin()) return "cant-win";
        if (!player.getGame().getStack().isEmpty()) return "stack-not-empty";
        if (!(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))) return "phase";
        return "multiplayer";
    }

    public CubeDrawOutPlan(Player player) { this.player = player; }

    // ------------------------------------------------------------- detectors

    /** v51's {@code WIN_REPLACEMENT_PARAMS}, duplicated. The effect's WHOLE
     * parameter map must be a subset of this printed set, so a replacement
     * carrying any requirement this plan cannot forecast is refused outright
     * rather than approximated. */
    private static final java.util.Set<String> WIN_REPLACEMENT_PARAMS = java.util.Set.of(
            "Event", "ActiveZones", "ValidPlayer", "IsPresent", "PresentZone", "PresentCompare",
            "ReplaceWith", "Description");

    /** Does this card carry the printed "if you would draw from an empty library
     * you win instead" replacement? {@code live} additionally requires the
     * native {@code zonesCheck} for a permanent that is already on the
     * battlefield; for a card still in our hand it instead requires the printed
     * {@code ActiveZones} to contain {@code Battlefield}, which is the printed
     * promise that the replacement turns on once the card resolves. Matches
     * Jace, Wielder of Mysteries and Laboratory Maniac - identical {@code R:}
     * lines - and matches no card by name. */
    private boolean emptyDrawWin(Card card, boolean live) {
        if (card == null || card.isFaceDown()) return false;
        for (var replacement : card.getReplacementEffects()) {
            if (replacement.isSuppressed() || replacement.getMode() != ReplacementType.Draw) continue;
            if (!WIN_REPLACEMENT_PARAMS.containsAll(replacement.getMapParams().keySet())) continue;
            if (!"You".equals(replacement.getParam("ValidPlayer"))
                    || !"Card.YouOwn".equals(replacement.getParam("IsPresent"))
                    || !"Library".equals(replacement.getParam("PresentZone"))
                    || !"EQ0".equals(replacement.getParam("PresentCompare"))) continue;
            SpellAbility win = replacement.getOverridingAbility();
            if (win == null || win.getApi() != ApiType.WinsGame || !"You".equals(win.getParam("Defined"))) continue;
            if (win.getSubAbility() != null) continue;
            if (live) {
                if (!replacement.zonesCheck(card.getZone())) continue;
            } else if (!java.util.List.of(replacement.getParamOrDefault("ActiveZones", "").split(","))
                    .contains("Battlefield")) continue;
            return true;
        }
        return false;
    }

    /** The win replacement live on our own battlefield, or null. Pure read. */
    private Card winReplacementLive() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield))
            if (card.getController() == player && emptyDrawWin(card, true)) return card;
        return null;
    }

    /** The same printed replacement on a card still in our own hand, or null.
     * v51's route J is live-battlefield only by its own registered scope choice;
     * this is the narrow extension route F owns. Pure read. */
    private Card winReplacementInHand() {
        for (Card card : player.getCardsIn(ZoneType.Hand))
            if (emptyDrawWin(card, false)) return card;
        return null;
    }

    /** The printed parameter allow-list for a draw trigger, shared by the drain
     * and life-gain shapes below. Same idiom as v50's
     * {@code CubeTopPlan.drainPerOwnDraw}. */
    private static final java.util.Set<String> DRAW_TRIGGER_PARAMS = java.util.Set.of(
            "Mode", "ValidCard", "TriggerZones", "Execute", "TriggerDescription");

    /** The {@code Execute} SVar of a trigger, parsed into its printed key/value
     * pairs. Reads the host card's own printed script and nothing else. */
    private static java.util.Map<String, String> effectOf(Card card, forge.game.trigger.Trigger trigger) {
        java.util.Map<String, String> effect = new java.util.HashMap<>();
        for (String piece : card.getSVar(trigger.getParamOrDefault("Execute", "")).split("\\|")) {
            String[] pair = piece.trim().split("\\$", 2);
            if (pair.length == 2) effect.put(pair[0].trim(), pair[1].trim());
        }
        return effect;
    }

    /** A trigger on a card we control that reads, by printed property, "whenever
     * an OPPONENT draws a card, that opponent loses N life" - Sheoldred, the
     * Apocalypse's drain half. Returns N, or 0.
     *
     * <p>This is v50's {@code drainPerOwnDraw} with the ownership of the draw
     * FLIPPED: v50 matches {@code ValidCard$ Card.YouCtrl} (Psychosis Crawler,
     * "whenever YOU draw, each opponent loses 1"), this matches
     * {@code ValidCard$ Card.OppCtrl}. The two shapes are disjoint, so neither
     * card can ever reach the other plan's route. Pure read of our own
     * battlefield.</p> */
    private int opponentDrawDrain() {
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || card.getController() != player) continue;
            for (var trigger : card.getTriggers()) {
                if (trigger.isSuppressed()
                        || !DRAW_TRIGGER_PARAMS.containsAll(trigger.getMapParams().keySet())
                        || !"Drawn".equals(trigger.getParam("Mode"))
                        || !"Card.OppCtrl".equals(trigger.getParamOrDefault("ValidCard", ""))
                        || !java.util.List.of(trigger.getParamOrDefault("TriggerZones", "").split(","))
                            .contains("Battlefield")) continue;
                var effect = effectOf(card, trigger);
                if (!"LoseLife".equals(effect.get("DB")) || effect.containsKey("ValidTgts")
                        || effect.containsKey("UnlessCost")
                        || !java.util.List.of("TriggeredCardController", "Player.Opponent", "Opponent")
                            .contains(effect.getOrDefault("Defined", ""))) continue;
                int amount = literal(effect.get("LifeAmount"));
                if (amount > 0) return amount;
            }
        }
        return 0;
    }

    /** The mirror shape: "whenever YOU draw a card, you gain N life" - Sheoldred's
     * first trigger. Credited ONLY in route G's life budget, and only while
     * native {@link Player#canGainLife} allows it. Pure read of our own
     * battlefield. */
    private int ownDrawLifeGain() {
        if (!player.canGainLife()) return 0;
        int total = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || card.getController() != player) continue;
            for (var trigger : card.getTriggers()) {
                if (trigger.isSuppressed()
                        || !DRAW_TRIGGER_PARAMS.containsAll(trigger.getMapParams().keySet())
                        || !"Drawn".equals(trigger.getParam("Mode"))
                        || !java.util.List.of("Card.YouCtrl", "Card.YouOwn")
                            .contains(trigger.getParamOrDefault("ValidCard", ""))
                        || !java.util.List.of(trigger.getParamOrDefault("TriggerZones", "").split(","))
                            .contains("Battlefield")) continue;
                var effect = effectOf(card, trigger);
                if (!"GainLife".equals(effect.get("DB")) || effect.containsKey("ValidTgts")
                        || effect.containsKey("UnlessCost")
                        || !"You".equals(effect.getOrDefault("Defined", ""))) continue;
                int amount = literal(effect.get("LifeAmount"));
                if (amount > 0) total += amount;
            }
        }
        return total;
    }

    /** A literal non-negative integer, or -1. A computed amount is refused
     * rather than evaluated. */
    private static int literal(String amount) {
        if (amount == null || amount.isEmpty() || !amount.chars().allMatch(Character::isDigit)) return -1;
        try { return Integer.parseInt(amount); } catch (NumberFormatException ignored) { return -1; }
    }

    /** How many cards this ability's whole {@code SubAbility} chain would draw
     * for a player in {@code accepted}, in one resolution, or 0 when it is not a
     * usable draw burst. v51's {@code drawsInChain}, duplicated with the
     * accepted {@code Defined} set as a parameter.
     *
     * <p>The chain is refused outright if any link carries
     * {@code Destination$ Library} or {@code Shuffle$ True} - that one clause is
     * what separates Wheel of Fortune and Memory Jar, which draw past the pile,
     * from Timetwister, Time Spiral and Echo of Eons, which refill the library
     * first. The refusal is applied to the drain route as well, where a refill
     * would in fact still drain: one walker, one rule, and the conservatism is a
     * stated limitation rather than a second code path.</p> */
    private int chainDraws(SpellAbility ability, java.util.List<String> accepted) {
        int draws = 0;
        for (SpellAbility link = ability; link != null; link = link.getSubAbility()) {
            if ("True".equals(link.getParam("Shuffle")) || "Library".equals(link.getParam("Destination"))) return 0;
            if (link.getApi() != ApiType.Draw) continue;
            if (!accepted.contains(link.getParamOrDefault("Defined", "You"))) return 0;
            int cards = literal(link.getParamOrDefault("NumCards", ""));
            if (cards <= 0) return 0;
            draws += cards;
        }
        return draws;
    }

    private static final java.util.List<String> DRAWS_FOR_US = java.util.List.of("You", "Player");
    private static final java.util.List<String> DRAWS_FOR_OPPONENT =
            java.util.List.of("Player", "Opponent", "Player.Opponent", "Opponents");

    /** Total literal {@link forge.game.cost.CostPayLife} in this ability's own
     * printed cost, or -1 when any life part is not a literal amount. */
    private static int lifeCostOf(SpellAbility ability) {
        int paid = 0;
        for (var part : ability.getPayCosts().getCostParts()) {
            if (!(part instanceof forge.game.cost.CostPayLife)) continue;
            Integer amount = part.convertAmount();
            if (amount == null) return -1;
            paid += amount;
        }
        return paid;
    }

    /** v51's public-battlefield read, duplicated: with unblocked lethal already
     * on board there is no reason to pay life and empty our own library. Any
     * untapped creature the opponent controls counts as a potential blocker and
     * suppresses the gate. */
    private boolean lethalOnBoard(boolean sameTurn) {
        if (sameTurn && !player.getGame().getPhaseHandler().is(PhaseType.MAIN1, player)) return false;
        for (Player opponent : player.getOpponents()) {
            boolean blocker = false;
            for (Card card : opponent.getCardsIn(ZoneType.Battlefield))
                if (card.isCreature() && card.isUntapped()) { blocker = true; break; }
            if (blocker) continue;
            int power = 0;
            for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
                if (!card.isCreature()) continue;
                if (sameTurn ? CombatUtil.canAttack(card, opponent) : CombatUtil.canAttackNextTurn(card, opponent))
                    power += Math.max(0, card.getNetPower());
            }
            if (power > 0 && power >= opponent.getLife()) return true;
        }
        return false;
    }

    private boolean payable(SpellAbility sa) {
        return sa != null && CubeComboAi.canPlayNative(sa, player) && CubeComboAi.canPayCost(sa, player, false);
    }

    /** Would both halves be payable out of one board? One native probe over the
     * combined printed mana, exactly v51's {@code bothPayable} idiom, so a
     * single source cannot pay for both halves. Each half's own full cost is
     * probed separately and the results are ANDed, so this can only ever decline
     * more than the combined probe alone. Nothing is cast, tapped or changed. */
    private boolean bothPayable(SpellAbility first, SpellAbility second) {
        ManaCost extra = second.getPayCosts().getTotalMana();
        ManaCostBeingPaid combined = new ManaCostBeingPaid(first.getPayCosts().getTotalMana());
        for (ManaCostShard shard : extra) combined.increaseShard(shard, 1);
        combined.increaseGenericMana(extra.getGenericCost());
        return CubeComboAi.canPayManaCost(combined, first, player, false);
    }

    private SpellAbility select(SpellAbility sa, String route) {
        selected = sa; selectedRoute = route; actions++; drawOutActions++;
        // An earlier route may have set a token on its way past; a pass that
        // ACTS declined nothing, so the observability token goes back to the
        // neutral default rather than naming a check the plan did not stop at.
        decline = "other check=drawout-plan";
        libraryBefore = player.getCardsIn(ZoneType.Library).size();
        System.err.println("CUBE_DRAWOUT route=" + route + " card=" + sa.getHostCard().getName().replace(' ', '_')
                + " api=" + sa.getApi() + " library=" + libraryBefore + " life=" + player.getLife()
                + " turn=" + player.getGame().getPhaseHandler().getTurn());
        return sa;
    }

    // ------------------------------------------------------------- the routes

    public SpellAbility nextAction() {
        var game = player.getGame();
        var phase = game.getPhaseHandler();
        if (turn != phase.getTurn()) { turn = phase.getTurn(); actions = 0; selected = null; pending = null; }
        if (failedTurn == turn || actions >= 32 || player.cantWin() || !game.getStack().isEmpty()
                || !(phase.is(PhaseType.MAIN1, player) || phase.is(PhaseType.MAIN2, player))
                || player.getOpponents().size() != 1) return decline(gateReason(32));
        decline = "other check=drawout-plan";
        if (pending != null) {
            // Replacements, prevention, counterspells or interaction may defeat
            // the route. Observe the resolved native result, then stop rather
            // than repeat an action that made no progress toward this route's
            // own objective. Own-visible or public quantities only: our library
            // SIZE, the public life total, our own battlefield.
            boolean stalled = switch (pendingRoute) {
                case "engine" -> player.getCardsIn(ZoneType.Library).size() >= libraryBefore;
                case "drain" -> player.getOpponents().get(0).getLife() >= opponentLifeBefore;
                case "finisher-cast" -> winReplacementLive() == null;
                default -> false;
            };
            pending = null; pendingRoute = "none";
            if (stalled) {
                failedTurn = turn;
                System.err.println("CUBE_DRAWOUT stopped-no-progress turn=" + turn);
                return decline("stopped-no-progress");
            }
        }
        // Pure reads only, so a board with none of the three printed shapes
        // leaves the caller's receipt byte for byte: no payment probe, no RNG,
        // no mana-pool touch and no stderr line can happen before this decline.
        Card live = winReplacementLive();
        Card inHand = winReplacementInHand();
        int drain = opponentDrawDrain();
        if (live == null && inHand == null && drain == 0) return decline("no-finisher");
        int librarySize = player.getCardsIn(ZoneType.Library).size(); // count, never identities/order
        if (drain > 0) {
            SpellAbility action = drainRoute(drain);
            if (action != null) return action;
        }
        if (live != null) {
            SpellAbility action = engineRoute(librarySize);
            if (action != null) return action;
        }
        if (inHand != null) {
            SpellAbility action = handFinisherRoute(inHand, librarySize);
            if (action != null) return action;
        }
        return null;
    }

    /** Route D. A printed per-opponent-draw drain on our own battlefield plus an
     * engine that makes the OPPONENT draw enough cards for the arithmetic to be
     * lethal on the public life total. Griselbrand is refused here: its chain
     * draws for {@code You} only, so no opponent draw ever happens and the
     * catalogue's own {@code produces} list for that row carries no win. */
    private SpellAbility drainRoute(int drain) {
        Player opponent = player.getOpponents().get(0);
        if (!opponent.canLoseLife() || opponent.getLife() <= 0) return decline("cant-drain");
        SpellAbility engine = null;
        int draws = 0;
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            engine = drainEngineOn(card, true, drain, opponent);
            if (engine != null) break;
        }
        if (engine == null) for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            engine = drainEngineOn(card, false, drain, opponent);
            if (engine != null) break;
        }
        if (engine == null) return decline(decline.equals("drain-short") ? "drain-short" : "drain-no-engine");
        draws = chainDraws(engine, DRAWS_FOR_OPPONENT);
        if (lethalOnBoard(true)) return decline("better-attack");
        System.err.println("CUBE_DRAWOUT drain=" + drain + " oppDraws=" + draws
                + " oppLife=" + opponent.getLife());
        return select(engine, "drain");
    }

    private SpellAbility drainEngineOn(Card card, boolean spell, int drain, Player opponent) {
        if (card.isFaceDown() || !spell && card.getController() != player) return null;
        for (SpellAbility original : card.getSpellAbilities()) {
            if (original.isSpell() != spell) continue;
            if (!spell && original.isManaAbility()) continue;
            SpellAbility ability = original.copy(player);
            // This plan makes no targeting decision, so an ability that needs
            // one is not one it may take.
            if (ability.usesTargeting()) continue;
            int draws = chainDraws(ability, DRAWS_FOR_OPPONENT);
            if (draws <= 0) continue;
            if (!opponent.canDrawAmount(draws)) continue;
            if ((long) drain * draws < opponent.getLife()) { decline = "drain-short"; continue; }
            int life = lifeCostOf(ability);
            if (life < 0 || player.getLife() - life < 1) continue;
            if (!payable(ability)) continue;
            return ability;
        }
        return null;
    }

    /** Route G. A repeatable pay-life draw engine on our own battlefield, run the
     * exact number of times an own-visible forecast says empties our library
     * under a life budget that never reaches zero.
     *
     * <p>Every cost part of the engine must be a literal
     * {@link forge.game.cost.CostPayLife}: that is what makes it repeatable, and
     * it is what refuses Memory Jar ({@code Cost$ T Sac<1/CARDNAME>}) and
     * Sensei's Divining Top ({@code Cost$ T}, and a {@code Destination$ Library}
     * link) - both of which are one-shots that belong to route J.</p>
     *
     * <p>When ONE activation would already suffice the route declines: that case
     * is v51 route J's, the controller consults it first, and this decline is
     * what makes the two plans provably disjoint.</p> */
    private SpellAbility engineRoute(int librarySize) {
        int need = librarySize + 1;
        if (!player.canDrawAmount(need)) return decline("draw-capped");
        SpellAbility engine = null;
        int draws = 0, lifeCost = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || card.getController() != player) continue;
            for (SpellAbility original : card.getSpellAbilities()) {
                if (original.isSpell() || original.isManaAbility()) continue;
                SpellAbility ability = original.copy(player);
                if (ability.usesTargeting()) continue;
                int chain = chainDraws(ability, DRAWS_FOR_US);
                if (chain <= 0) continue;
                if (!repeatablePayLife(ability)) continue;
                int cost = lifeCostOf(ability);
                if (cost < 0) continue;
                engine = ability; draws = chain; lifeCost = cost;
                break;
            }
            if (engine != null) break;
        }
        if (engine == null) return decline("no-engine");
        int activations = (need + draws - 1) / draws;
        if (activations <= 1) return decline("single-draw-owned-elsewhere");
        if (activations > 12) return decline("library-too-large");
        int gain = ownDrawLifeGain();
        int life = player.getLife(), drawn = 0;
        for (int k = 0; k < activations; k++) {
            // The whole cost is paid before any card is drawn, so this is the
            // low-water mark of the activation and state-based actions apply.
            if (life - lifeCost < 1) return decline("life-budget");
            life -= lifeCost;
            int real = Math.max(0, Math.min(draws, librarySize - drawn));
            life += gain * real;
            drawn += draws;
            if (life < 1) return decline("life-budget");
        }
        if (!payable(engine)) return decline("unpayable:engine");
        if (lethalOnBoard(true)) return decline("better-attack");
        System.err.println("CUBE_DRAWOUT forecast activations=" + activations + " draws=" + draws
                + " lifeCost=" + lifeCost + " gain=" + gain + " library=" + librarySize
                + " life=" + player.getLife() + " lifeAfter=" + life);
        return select(engine, "engine");
    }

    /** Repeatable means: every printed cost part is a pay-life part. An ability
     * that taps or sacrifices its own host can be used once, which is route J's
     * case, not this one. */
    private static boolean repeatablePayLife(SpellAbility ability) {
        var parts = ability.getPayCosts().getCostParts();
        if (parts.isEmpty()) return false;
        for (var part : parts) if (!(part instanceof forge.game.cost.CostPayLife)) return false;
        return true;
    }

    /** Route F. The win replacement is still in our HAND - the case v51's route J
     * deliberately excluded - and a single draw burst would empty the library
     * once it is on the battlefield. Only the finisher is proposed; the burst is
     * route J's business on the next pass, once the replacement is live. */
    private SpellAbility handFinisherRoute(Card card, int librarySize) {
        int need = librarySize + 1;
        if (!player.canDrawAmount(need)) return decline("draw-capped");
        SpellAbility finisher = null;
        for (SpellAbility original : card.getSpellAbilities()) {
            if (!original.isSpell()) continue;
            SpellAbility spell = original.copy(player);
            if (spell.usesTargeting()) continue;
            if (!payable(spell)) continue;
            finisher = spell;
            break;
        }
        if (finisher == null) return decline("finisher-uncastable");
        SpellAbility burst = null;
        for (Card source : player.getCardsIn(ZoneType.Hand)) {
            burst = burstOn(source, true, need, finisher);
            if (burst != null) break;
        }
        if (burst == null) for (Card source : player.getCardsIn(ZoneType.Battlefield)) {
            burst = burstOn(source, false, need, finisher);
            if (burst != null) break;
        }
        if (burst == null) return decline(decline.equals("life-budget") || decline.equals("unpayable:combined")
                ? decline : "no-burst");
        if (lethalOnBoard(true)) return decline("better-attack");
        System.err.println("CUBE_DRAWOUT finisher=" + card.getName().replace(' ', '_')
                + " burst=" + burst.getHostCard().getName().replace(' ', '_')
                + " draws=" + chainDraws(burst, DRAWS_FOR_US) + " library=" + librarySize);
        return select(finisher, "finisher-cast");
    }

    private SpellAbility burstOn(Card card, boolean spell, int need, SpellAbility finisher) {
        if (card.isFaceDown() || !spell && card.getController() != player) return null;
        if (card == finisher.getHostCard()) return null;
        for (SpellAbility original : card.getSpellAbilities()) {
            if (original.isSpell() != spell) continue;
            if (!spell && original.isManaAbility()) continue;
            SpellAbility ability = original.copy(player);
            if (ability.usesTargeting()) continue;
            if (chainDraws(ability, DRAWS_FOR_US) < need) continue;
            int life = lifeCostOf(ability);
            if (life < 0 || player.getLife() - life < 1) { decline = "life-budget"; continue; }
            if (!payable(ability)) continue;
            if (!bothPayable(finisher, ability)) { decline = "unpayable:combined"; continue; }
            return ability;
        }
        return null;
    }

    // -------------------------------------------------- route O, Oath of Druids

    /** Zones a creature card of ours can be identified in. The library itself is
     * never iterated. */
    private static final ZoneType[] OUTSIDE_LIBRARY = {
        ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
        ZoneType.Command, ZoneType.Stack
    };

    /** A card with a creature face on EITHER side. {@code unreadable} is the
     * answer when the printed rules cannot be read at all, and the two callers
     * pass opposite values deliberately: counting the deck it is {@code true}
     * (an unreadable deck entry is assumed to be a creature, which can only make
     * the plan refuse), and identifying a card outside the library it is
     * {@code false} (an unreadable card is never credited with having removed a
     * creature from the library, which can also only make the plan refuse). */
    private static boolean creatureFace(forge.card.CardRules rules, boolean unreadable) {
        if (rules == null) return unreadable;
        if (rules.getType().isCreature()) return true;
        if (rules.getMainPart() != null && rules.getMainPart().getType().isCreature()) return true;
        return rules.getOtherPart() != null && rules.getOtherPart().getType().isCreature();
    }

    /** Could a creature card still be in our own library? The
     * {@link CubeComboAi#ownCopyOutside} idiom with the conservative direction
     * FLIPPED, exactly as design-v51 section C requires: start from the creature
     * count of our own REGISTERED main deck (a card with a creature face on
     * either side counts), then subtract only creature cards we can actually
     * IDENTIFY outside the library. An own face-down card whose face we may not
     * legally see is never credited with having removed a creature from the
     * library, so an unseen card can only make this refuse. Our own registered
     * deck composition and our own identified cards only - the library is never
     * read. */
    private boolean libraryCreatureFree() {
        int remaining = 0;
        for (var entry : player.getRegisteredPlayer().getDeck().getMain())
            if (creatureFace(entry.getKey().getRules(), true)) remaining += entry.getValue();
        if (remaining <= 0) return true;
        for (ZoneType zone : OUTSIDE_LIBRARY) {
            for (Card card : player.getGame().getCardsIn(zone)) {
                if (card.getOwner() != player || card.isToken()) continue;
                if (card.isFaceDown() && !card.getView().canFaceDownBeShownTo(player.getView())) continue;
                if (creatureFace(card.getRules(), false)) remaining--;
            }
        }
        return remaining <= 0;
    }

    /** Public read: does an opponent control strictly more creatures than we do?
     * The Oath trigger's own
     * {@code ValidTgts$ Player.OpponentToActive+withMoreCreaturesThanActive}
     * enforces this natively; reading it here only keeps the plan from accepting
     * a dig that could never have been offered. */
    private boolean opponentHasMoreCreatures() {
        int mine = player.getCreaturesInPlay().size();
        for (Player opponent : player.getOpponents())
            if (opponent.getCreaturesInPlay().size() > mine) return true;
        return false;
    }

    /** design-v51 section C, item 3. Oath of Druids' optional dig is declined by
     * the ordinary {@code DigUntilAi.confirmAction} on exactly the board where it
     * wins ({@code creaturesInLibrary > 2 || (creaturesInBattlefield == 0 &&
     * creaturesInLibrary > 0)} is false at zero), so the cube seat needs an
     * override.
     *
     * <p><b>This method can only ever answer TRUE.</b> It returns {@code null} -
     * "not ours, use the unchanged ordinary answer" - in every other case,
     * including every refusal. So the ordinary path is not merely preserved, it
     * is the only path that can produce a {@code false}, and the
     * must-not-intervene evidence is simply {@link #oathAccepts} at zero.</p>
     *
     * <p>The shape gate is printed property only, never the name Oath of Druids,
     * and it refuses any parameter that would break the "a creature-free library
     * is revealed in full and moved to the graveyard" reading
     * ({@code DigUntilEffect.java:163,287-297}). The forecast gate is
     * own-visible: our own battlefield, our own registered deck composition, our
     * own library SIZE, and both public battlefields.</p> */
    public Boolean confirmDig(SpellAbility sa) {
        if (sa == null || sa.getApi() != ApiType.DigUntil) return null;
        if (!"Creature".equals(sa.getParam("Valid"))
                || !"Battlefield".equals(sa.getParam("FoundDestination"))
                || !"Graveyard".equals(sa.getParam("RevealedDestination"))
                || !"True".equals(sa.getParamOrDefault("Optional", ""))
                || !"TriggeredPlayer".equals(sa.getParam("Defined"))) return null;
        for (String forbidden : new String[]{"NoneFoundDestination", "NoMoveRevealed", "MaxRevealed",
                "Sequential", "OptionalFoundMove", "RevealRandomOrder", "OptionalNoDestination"})
            if (sa.hasParam(forbidden)) return null;
        Card host = sa.getHostCard();
        if (host == null || host.getController() != player || sa.getActivatingPlayer() != player) return null;
        // Defined$ TriggeredPlayer is the ACTIVE player, so the dig is ours only
        // on our own upkeep. On the opponent's upkeep their controller is asked,
        // not this one, and this clause makes that independent of that fact.
        if (!player.getGame().getPhaseHandler().is(PhaseType.UPKEEP, player)) return null;
        String reason = oathReason();
        if (reason != null) {
            oathDeclines++;
            decline = reason;
            System.err.println("CUBE_DRAWOUT oath=decline reason=" + reason
                    + " turn=" + player.getGame().getPhaseHandler().getTurn());
            return null; // the ordinary DigUntilAi answer stands, untouched
        }
        oathAccepts++;
        System.err.println("CUBE_DRAWOUT oath=accept library=" + player.getCardsIn(ZoneType.Library).size()
                + " life=" + player.getLife()
                + " turn=" + player.getGame().getPhaseHandler().getTurn());
        return Boolean.TRUE;
    }

    /** The first failing clause of route O's forecast, or null when it holds. */
    private String oathReason() {
        if (player.cantWin()) return "oath:cant-win";
        if (winReplacementLive() == null) return "oath:no-win-replacement";
        int librarySize = player.getCardsIn(ZoneType.Library).size();
        if (librarySize <= 0) return "oath:empty-library";
        if (!player.canDrawAmount(1)) return "oath:draw-capped";
        if (!libraryCreatureFree()) return "oath:library-creatures";
        if (!opponentHasMoreCreatures()) return "oath:precondition";
        if (lethalOnBoard(false)) return "oath:better-attack";
        return null;
    }

    // ------------------------------------------------------------- execution

    public boolean owns(SpellAbility sa) { return sa == selected; }

    public boolean play(SpellAbility sa) {
        libraryBefore = player.getCardsIn(ZoneType.Library).size();
        opponentLifeBefore = player.getOpponents().isEmpty() ? 0 : player.getOpponents().get(0).getLife();
        boolean played = ComputerUtil.handlePlayingSpellAbility(player, sa, null,
                current -> new AiCostDecision(player, current, false));
        if (played) { pending = sa; pendingRoute = selectedRoute; } else failedTurn = turn;
        System.err.println("CUBE_DRAWOUT " + (played ? "played" : "native-payment-failed")
                + " turn=" + turn + " card=" + sa.getHostCard().getName().replace(' ', '_')
                + " api=" + sa.getApi() + " life=" + player.getLife()
                + " library=" + player.getCardsIn(ZoneType.Library).size());
        return played;
    }

    public boolean waitingForOwnSpell() {
        var stack = player.getGame().getStack();
        if (selected == null || turn != player.getGame().getPhaseHandler().getTurn() || stack.isEmpty()) return false;
        var top = stack.peekAbility();
        return top != null && top.getActivatingPlayer() == player && top.getHostCard() == selected.getHostCard();
    }
}
