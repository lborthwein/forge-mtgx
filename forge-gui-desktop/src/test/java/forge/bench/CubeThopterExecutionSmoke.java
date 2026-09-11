package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Diagnostic of native decisions from assembled positions, not whole-game strength.
 * No action is supplied by the host. Setup is synthetic and exactly registered.
 */
public final class CubeThopterExecutionSmoke {
    private static final String URZA = "Urza, Lord High Artificer";
    private static final String FOUNDRY = "Thopter Foundry";
    private static final String SWORD = "Sword of the Meek";
    private static final List<String> POSITIVES = List.of("none", "one-island", "tapped-sword", "high-life", "flying-blocker");
    private static final List<String> CONTROLS = List.of("none", "one-island", "sword-exile", "cursed-totem", "token-anthem",
            "rest-in-peace", "torpor-orb", "null-rod", "missing-urza", "missing-foundry", "tapped-sword", "high-life", "flying-blocker", "remove-foundry");
    private static final List<String> ASSEMBLY = List.of("hand-urza", "hand-foundry", "hand-sword",
            "tutor-urza", "tutor-foundry", "tutor-sword");
    private static final List<String> ASSEMBLY_CONTROLS = List.of(
            "hand-foundry:no-mana", "hand-foundry:missing-two", "hand-foundry:null-rod",
            "hand-foundry:cursed-totem", "hand-foundry:rest-in-peace", "hand-foundry:torpor-orb",
            "hand-foundry:anthem", "hand-foundry:humility", "tutor-urza:no-mana",
            "tutor-urza:missing-two", "tutor-urza:cursed-totem", "tutor-foundry:null-rod",
            "tutor-sword:rest-in-peace", "tutor-sword:rule-of-law", "tutor-foundry:no-black",
            "tutor-foundry:restricted-search", "tutor-foundry:exiled-piece");
    private static final List<String> SACRIFICE_CONTROLS = List.of("hand-foundry:yasharn", "tutor-foundry:yasharn", "tutor-sword:yasharn");
    // v42: two pieces missing, both in hand (checkpoint-26-registration).
    private static final List<String> ASSEMBLY2 = List.of("hand2-urza-foundry", "hand2-urza-sword", "hand2-foundry-sword");
    private static final List<String> ASSEMBLY2_VARIANTS = List.of("hand2-urza-foundry:five-lands", "hand2-urza-sword:four-lands",
            "hand2-urza-foundry:three-lands", "hand2-foundry-sword:sigarda");
    private static final List<String> ASSEMBLY2_CONTROLS = List.of("hand2-urza-foundry:no-black", "hand2-foundry-sword:yasharn",
            "hand2-foundry-sword:rest-in-peace", "hand2-urza-foundry:null-rod", "hand2-urza-foundry:cursed-totem",
            "hand2-urza-foundry:kiki-hand", "hand2-urza-foundry:missing-two", "hand3-all");
    // Guard negatives on the one-piece path: the plan must start and finish.
    private static final List<String> GUARD_NEGATIVES = List.of("hand-foundry:sigarda", "hand-foundry:yasharn-graveyard");
    // v43: a public cast-count prohibition must rule the same-turn forecast out
    // and finish through two-turn deployment (checkpoint-27-registration).
    private static final List<String> ASSEMBLY2_FORECAST = List.of("hand2-urza-foundry:rule-of-law");
    private static final String FABLED = "Fabled Passage";
    // v43 defect control found by the v42 do-no-harm whole-game panel, game
    // dnh-wide-mtgx-s3066-16701373-o0-treat-p0-r0: the plan guard rejected the
    // ordinary AI's own land play. The three pieces sit in exile so no plan can
    // engage and the only decision under test is Default's land drop.
    private static final List<String> LAND_DROP = List.of("land-drop:fabled-passage", "land-drop:graveyard-mayplay");
    // Exact reproduction of the panel condition: Forge logged
    // "PhaseHandler: AI looped too much with: [Play land by Serra Paragon (1)]", i.e. the
    // rejected ability was a MayPlay land ability granted by Serra Paragon's static, whose
    // ValidAfterStack$ Spell.cmcLE3 is copied onto the land half of the permission. A
    // LandAbility never matches "Spell", so isLegalAfterStack() is false for it while
    // canPlay() - the only check Forge's own land path makes - is true.
    private static final List<String> LAND_DROP_MAYPLAY = List.of("land-drop:graveyard-mayplay");

    /** Diagnostic counters live in the policy class. Read and reset them
     * reflectively so this one test source compiles and runs against either
     * the candidate policy build or the older matched-control build. They are
     * diagnostics: no decision reads them. */
    private static final java.util.Map<String, Class<?>> DIAGNOSTICS = new java.util.LinkedHashMap<>();
    static {
        DIAGNOSTICS.put("assemblySelections", forge.ai.CubeThopterPlan.class);
        DIAGNOSTICS.put("assemblySameTurnForecasts", forge.ai.CubeThopterPlan.class);
        DIAGNOSTICS.put("assemblyPartialForecasts", forge.ai.CubeThopterPlan.class);
        DIAGNOSTICS.put("guardRejections", forge.ai.CubeComboPlayerController.class);
        DIAGNOSTICS.put("ordinaryGuardDisagreements", forge.ai.CubeComboPlayerController.class);
    }
    private static java.lang.reflect.Field diagnosticField(String name) {
        try {
            var field = DIAGNOSTICS.get(name).getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException absent) { return null; }
    }
    private static int diagnostic(String name) {
        var field = diagnosticField(name);
        if (field == null) throw new AssertionError("policy build exposes no diagnostic counter " + name);
        try { return field.getInt(null); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void resetDiagnostics() {
        for (String name : DIAGNOSTICS.keySet()) {
            var field = diagnosticField(name);
            if (field == null) continue;
            try { field.setInt(null, 0); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
    }

    private static boolean assemblyBase(String base) {
        return ASSEMBLY.contains(base) || base.startsWith("hand2-") || base.equals("hand3-all");
    }
    private static boolean mustAssemble(String control) {
        return ASSEMBLY.contains(control) || ASSEMBLY2.contains(control) || ASSEMBLY2_VARIANTS.contains(control)
                || ASSEMBLY2_FORECAST.contains(control)
                || GUARD_NEGATIVES.contains(control) || variant(control).equals("lethal-board") || variant(control).equals("kiki-hand");
    }
    /** Cases whose registered win requires casting a missing piece from hand,
     * so the plan must make at least one hand-assembly selection in play. */
    private static boolean handAssemblySelector(String control) {
        return ASSEMBLY.contains(control) && control.startsWith("hand-") || ASSEMBLY2.contains(control)
                || ASSEMBLY2_VARIANTS.contains(control) || ASSEMBLY2_FORECAST.contains(control)
                || GUARD_NEGATIVES.contains(control);
    }
    private static boolean blockedAssembly(String control) {
        return ASSEMBLY_CONTROLS.contains(control) || SACRIFICE_CONTROLS.contains(control)
                || ASSEMBLY2_CONTROLS.contains(control) && !variant(control).equals("kiki-hand");
    }
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand,
            ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone) {}

    private static List<Placement> placements(boolean owner, String control) {
        List<Placement> result = new ArrayList<>();
        String base=control.split(":")[0], variant=variant(control);
        if (owner) {
            result.add(new Placement(URZA, pieceZone(control,"urza")));
            result.add(new Placement(FOUNDRY, pieceZone(control,"foundry")));
            result.add(new Placement(SWORD, pieceZone(control,"sword")));
            if(assemblyBase(base)) {
                if(!variant.equals("no-mana")) {
                    int islands=switch(variant) { case "five-lands", "four-lands" -> 3; case "three-lands" -> 2; default -> 4; };
                    int swamps=switch(variant) { case "four-lands", "three-lands" -> 1; default -> 2; };
                    for(int i=0;i<islands;i++)result.add(new Placement("Island",ZoneType.Battlefield));
                    for(int i=0;i<swamps;i++)result.add(new Placement(variant.equals("no-black")?"Island":"Swamp",ZoneType.Battlefield));
                }
                if(variant.equals("sigarda"))result.add(new Placement("Sigarda, Host of Herons",ZoneType.Battlefield));
                // Two-turn deployment needs an ordinary land drop: the library's top card is a Forest.
                if(variant.equals("three-lands"))result.add(new Placement("Forest",ZoneType.Library));
                if(variant.equals("kiki-hand")) {
                    result.add(new Placement("Kiki-Jiki, Mirror Breaker",ZoneType.Battlefield));
                    result.add(new Placement("Pestermite",ZoneType.Hand));
                }
                if(base.startsWith("tutor-"))result.add(new Placement("Demonic Tutor",ZoneType.Hand));
                result.add(new Placement("Grave Titan",ZoneType.Library));
                result.add(new Placement("Wurmcoil Engine",ZoneType.Library));
                if(variant.equals("anthem"))result.add(new Placement("Intangible Virtue",ZoneType.Battlefield));
                if(variant.equals("kiki-tutor")) {
                    result.add(new Placement("Kiki-Jiki, Mirror Breaker",ZoneType.Battlefield));
                    result.add(new Placement("Pestermite",ZoneType.Library));
                }
            }
            // The only land Default can play, so chooseBestLandToPlay must pick it.
            if(LAND_DROP_MAYPLAY.contains(control)) {
                result.add(new Placement(FABLED,ZoneType.Graveyard));
                result.add(new Placement("Serra Paragon",ZoneType.Battlefield));
            } else if(control.split(":")[0].equals("land-drop"))result.add(new Placement(FABLED,ZoneType.Hand));
            if (control.equals("one-island")) result.add(new Placement("Island", ZoneType.Battlefield));
            if (control.equals("token-anthem")) result.add(new Placement("Intangible Virtue", ZoneType.Battlefield));
        } else if (control.equals("cursed-totem")) {
            result.add(new Placement("Cursed Totem", ZoneType.Battlefield));
        } else {
            String blocker = switch(variant.isEmpty()?control:variant) {
                case "rest-in-peace" -> "Rest in Peace";
                case "torpor-orb" -> "Torpor Orb";
                case "null-rod" -> "Null Rod";
                case "flying-blocker" -> "Serra Angel";
                case "cursed-totem" -> "Cursed Totem";
                case "humility" -> "Humility";
                case "rule-of-law" -> "Rule of Law";
                case "restricted-search" -> "Aven Mindcensor";
                case "yasharn" -> "Yasharn, Implacable Earth";
                default -> null;
            };
            if(blocker != null) result.add(new Placement(blocker, ZoneType.Battlefield));
            // Non-battlefield static source: the sweep visits the graveyard but the static is battlefield-only.
            if(variant.equals("yasharn-graveyard")) result.add(new Placement("Yasharn, Implacable Earth", ZoneType.Graveyard));
            if(control.equals("remove-foundry")) {
                result.add(new Placement("Disenchant", ZoneType.Hand));
                result.add(new Placement("Plains", ZoneType.Battlefield));
                result.add(new Placement("Plains", ZoneType.Battlefield));
            }
        }
        while (result.size() < 40) result.add(new Placement("Forest", ZoneType.Library));
        if(owner && variant.equals("restricted-search")) {
            var missing=result.stream().filter(p->p.name().equals(FOUNDRY)).findFirst().orElseThrow();
            result.remove(missing);result.add(missing);
        }
        return result;
    }

    private static String variant(String control) { return control.contains(":")?control.split(":",2)[1]:""; }

    private static ZoneType pieceZone(String control,String piece) {
        String variant=variant(control);control=control.split(":")[0];
        if(control.equals("land-drop"))return ZoneType.Exile;
        if(control.equals("hand3-all"))return ZoneType.Hand;
        if(control.startsWith("hand2-")) {
            String[] pair=control.substring(6).split("-");
            if(variant.equals("missing-two") && piece.equals(pair[1]))return ZoneType.Exile;
            return List.of(pair).contains(piece)?ZoneType.Hand:ZoneType.Battlefield;
        }
        if(variant.equals("missing-two") && piece.equals(control.endsWith("urza")?"foundry":"urza"))return ZoneType.Exile;
        if(variant.equals("exiled-piece") && control.equals("tutor-"+piece))return ZoneType.Exile;
        if(control.equals("missing-"+piece) || piece.equals("sword")&&control.equals("sword-exile"))return ZoneType.Exile;
        if(control.equals("hand-"+piece))return ZoneType.Hand;
        if(control.equals("tutor-"+piece))return ZoneType.Library;
        return ZoneType.Battlefield;
    }

    private static Deck deck(boolean owner, String control) {
        Deck result = new Deck("Thopter diagnostic");
        for (Placement p : placements(owner, control)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String control) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(owner, control)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name())), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(false);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch");
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }

    private static int tokens(Player player) {
        return (int) player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.isToken() && c.getType().hasSubtype("Thopter")).count();
    }

    private static String swordZone(Player player) {
        return zoneOf(player, SWORD);
    }

    private static String zoneOf(Player player, String name) {
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) if (c.getName().equals(name)) return z.name();
        return "missing";
    }

    private static void run(boolean improved, int seat, PhaseType phase, String control) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int s = 0; s < 2; s++) players.add(new RegisteredPlayer(deck(s == seat, control)).setPlayer(
                improved && s == seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-" + s)
                        : s != seat && control.equals("remove-foundry") ? removalOpponent(s) : recordingDefault(s)));
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        rules.setAllowCheatShuffle(false);
        Game game = new Match(rules, players, "native Thopter diagnostic").createGame();
        Player player = game.getPlayers().get(seat), opponent = game.getPlayers().get(1 - seat);
        populate(player, true, control);
        populate(opponent, false, control);
        if(control.equals("high-life")) opponent.setLife(40,null);
        if(variant(control).equals("lethal-board"))opponent.setLife(1,null);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().setupFirstTurn(player, () -> game.getPhaseHandler().devModeSet(phase, player));
        // setupFirstTurn performs untap before invoking the phase hook. Apply
        // this synthetic state afterwards so the control actually starts tapped.
        if(control.equals("tapped-sword")) player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c->c.getName().equals(SWORD)).findFirst().orElseThrow().setTapped(true);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(10811 + seat * 100 + control.length());
        if(improved && Boolean.getBoolean("forge.test.probeThopterPurity")) probePurity(player,control,phase);
        // The purity probe itself proposes actions; reset here so the counters
        // below measure only the selections this policy makes during the game.
        resetDiagnostics();
        String key = "arm=" + (improved ? "improved" : "baseline") + " seat=" + seat + " phase=" + phase + " control=" + control;
        System.out.println("THOPTER_FIXTURE " + key + " policy=" + forge.ai.CubeComboAi.VERSION + " infoPolicy=CLOSED_REPAIR registered=40 initialMana=0"
                + " swordStartsTapped=" + player.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals(SWORD)&&c.isTapped()));
        Set<Integer> stackIds = new HashSet<>();
        int steps = 0, activations = 0, returns = 0, resolvedReturns = 0, created = 0, maxMana = 0, maxTokens = 0, removals = 0;
        int fabledTurn = -1;
        String fabledStart = LAND_DROP_MAYPLAY.contains(control) ? "Graveyard" : "Hand";
        String previous = "";
        // Turns alternate: our turns are 1, 3, 5. Two-turn deployment needs our third turn for combat.
        // Keyed to the control, not the variant string: tutor-sword:rule-of-law keeps its turn-3 window.
        int turnBound = variant(control).equals("three-lands") || ASSEMBLY2_FORECAST.contains(control) ? 5 : 3;
        while (!game.isGameOver() && game.getPhaseHandler().getTurn() <= turnBound && steps < 600) {
            int before = tokens(player);
            String beforeSword = swordZone(player);
            game.getPhaseHandler().mainLoopStep();
            steps++;
            if (beforeSword.equals("Graveyard") && swordZone(player).equals("Battlefield")) {
                resolvedReturns++;
                Card sword = player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals(SWORD)).findFirst().orElseThrow();
                if (!sword.isEquipping() || sword.getEquipping().getController() != player
                        || !sword.getEquipping().getType().hasSubtype("Thopter"))
                    throw new AssertionError("Sword must return and attach through native resolution");
            }
            // Fabled Passage sacrifices itself to its own fetch ability, so it need not be
            // observable on the battlefield at a step boundary. The land drop is the turn it
            // leaves the zone it was played from; nothing here discards it instead.
            if(LAND_DROP.contains(control) && fabledTurn<0 && !fabledStart.equals(zoneOf(player,FABLED)))
                fabledTurn = game.getPhaseHandler().getTurn();
            created += Math.max(0, tokens(player) - before);
            maxTokens = Math.max(maxTokens, tokens(player));
            maxMana = Math.max(maxMana, player.getManaPool().totalMana());
            for (var item : game.getStack()) if (stackIds.add(item.getId())) {
                var sa = item.getSpellAbility();
                if (sa.getActivatingPlayer() == player && sa.getHostCard().getName().equals(FOUNDRY) && sa.isActivatedAbility()) activations++;
                if (sa.getHostCard().getName().equals(SWORD) && sa.isTrigger()) returns++;
                if(sa.getHostCard().getName().equals("Disenchant") && sa.isSpell() && sa.getActivatingPlayer()==opponent) removals++;
                System.out.println("THOPTER_STACK " + key + " step=" + steps + " source=" + sa.getHostCard().getName()
                        + " api=" + sa.getApi() + " trigger=" + sa.isTrigger() + " costs=" + sa.getPayCosts());
            }
            String state = "turn=" + game.getPhaseHandler().getTurn() + " currentPhase=" + game.getPhaseHandler().getPhase()
                    + " life=" + player.getLife() + " opponentLife=" + opponent.getLife() + " tokens=" + tokens(player)
                    + " mana=" + player.getManaPool().totalMana() + " sword=" + swordZone(player);
            if (!state.equals(previous)) System.out.println("THOPTER_STATE " + key + " step=" + steps + " " + state);
            previous = state;
        }
        if (steps >= 600) throw new AssertionError("native step budget exhausted " + key);
        if (control.equals("sword-exile") && returns != 0) throw new AssertionError("exiled Sword returned");
        if (control.equals("token-anthem") && returns != 0) throw new AssertionError("2/2 Thopter triggered Sword");
        if (improved && Boolean.getBoolean("forge.test.requireSwordReturn")
                && POSITIVES.contains(control) && resolvedReturns < 1)
            throw new AssertionError("Candidate must accept and resolve the free Sword return");
        if (improved && Boolean.getBoolean("forge.test.requireThopterWin")
                && POSITIVES.contains(control) && !player.hasWon())
            throw new AssertionError("Candidate must finish with the native Thopter army");
        if(improved && Boolean.getBoolean("forge.test.requireThopterAssembly") && mustAssemble(control) && !player.hasWon())
            throw new AssertionError("Candidate must assemble and finish the native Thopter army: "+key);
        if(improved && Boolean.getBoolean("forge.test.requireThopterAssembly") && variant(control).equals("kiki-hand")
                && phase==PhaseType.MAIN1 && game.getPhaseHandler().getTurn()!=1)
            throw new AssertionError("Two-piece assembly must not postpone the ready Kiki win: "+key);
        if(improved && Boolean.getBoolean("forge.test.requireThopterAssembly") && variant(control).equals("yasharn")
                && control.startsWith("hand2-") && activations!=0)
            throw new AssertionError("Foundry must not activate under a visible sacrifice prohibition: "+key);
        if(improved && Boolean.getBoolean("forge.test.requireThopterAssembly")
                && variant(control).equals("lethal-board") && phase==PhaseType.MAIN1 && game.getPhaseHandler().getTurn()!=1)
            throw new AssertionError("Assembly must not delay the available ordinary combat win");
        if(improved && Boolean.getBoolean("forge.test.requireThopterAssembly") && variant(control).equals("kiki-tutor")
                && (!player.hasWon() || phase==PhaseType.MAIN1 && game.getPhaseHandler().getTurn()!=1))
            throw new AssertionError("Slower Thopter selection must not postpone the ready Kiki win: "+key);
        if(LAND_DROP.contains(control)) {
            System.out.println("THOPTER_LANDDROP " + key + " fabledPassageTurn=" + fabledTurn
                    + " playedFrom=" + fabledStart + " fabledZone=" + zoneOf(player,FABLED)
                    + " lands=" + player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand).count());
            if(improved) System.out.println("THOPTER_LAND_GUARD " + key
                    + " guardRejections=" + diagnostic("guardRejections")
                    + " ordinaryGuardDisagreements=" + diagnostic("ordinaryGuardDisagreements"));
        }
        if(improved && Boolean.getBoolean("forge.test.requireThopterAssembly")) {
            int selections = diagnostic("assemblySelections");
            // The guard must never reject anything here: it applies only to actions this
            // policy proposed, and rejecting an ordinary land choice loops forever.
            if(diagnostic("guardRejections")!=0)
                throw new AssertionError("Plan guard rejected a chosen ability: "+key+" rejections="+diagnostic("guardRejections"));
            if(LAND_DROP.contains(control) && fabledTurn<0)
                throw new AssertionError("Ordinary land play must not be blocked by the plan guard: "+key);
            // The MayPlay variant must actually reproduce the panel condition, otherwise this
            // control would pass without exercising the guard-scope fix at all.
            if(LAND_DROP_MAYPLAY.contains(control) && diagnostic("ordinaryGuardDisagreements")<=0)
                throw new AssertionError("MayPlay land control did not reproduce the guard disagreement: "+key);
            // review-v42 §5c: the ready Kiki route must keep priority for the whole
            // MAIN1 case, not only at the single pre-game purity snapshot.
            if(variant(control).equals("kiki-hand") && phase==PhaseType.MAIN1 && selections!=0)
                throw new AssertionError("Ready Kiki route must keep priority over any hand assembly: "+key);
            // hand3-all is a first-decision control only: once ordinary Default play casts one
            // of the three pieces, two are missing and the two-piece path legitimately engages
            // (review-v42 §5a), so its in-play selection count is deliberately unconstrained.
            if(blockedAssembly(control) && !control.equals("hand3-all") && selections!=0)
                throw new AssertionError("Blocked assembly must make no hand selection in play: "+key+" selections="+selections);
            if(handAssemblySelector(control) && selections<=0)
                throw new AssertionError("Registered hand assembly made no selection in play: "+key);
            if(ASSEMBLY2_FORECAST.contains(control)) {
                if(diagnostic("assemblySameTurnForecasts")!=0)
                    throw new AssertionError("A public cast-count prohibition must not forecast a same-turn assembly: "+key);
                if(diagnostic("assemblyPartialForecasts")<=0)
                    throw new AssertionError("A public cast-count prohibition must take the partial two-turn path: "+key);
            }
        }
        if(control.equals("remove-foundry") && (removals!=1 || activations!=1 || player.hasWon()
                || player.getCardsIn(ZoneType.Graveyard).stream().noneMatch(c->c.getName().equals(FOUNDRY))))
            throw new AssertionError("Scripted native removal must destroy Foundry and halt the loop");
        System.out.println("THOPTER_RESULT " + key + " won=" + player.hasWon() + " gameOver=" + game.isGameOver()
                + " removals=" + removals
                + " steps=" + steps + " foundryActivations=" + activations + " swordTriggers=" + returns
                + " resolvedReturns=" + resolvedReturns
                + " created=" + created + " maxTokens=" + maxTokens + " maxMana=" + maxMana
                + " life=" + player.getLife() + " opponentLife=" + opponent.getLife());
    }

    private static java.util.Map<String,Object> snapshot(Player player) throws ReflectiveOperationException {
        var result=new java.util.LinkedHashMap<String,Object>();
        var field=Game.class.getDeclaredField("cardIdCounter"); field.setAccessible(true);
        result.put("nextCardId",field.getInt(player.getGame()));
        result.put("timestamp",player.getGame().getTimestamp());
        result.put("mana",player.getManaPool().totalMana());
        result.put("pool",java.util.Arrays.toString(new int[]{player.getManaPool().getPossibleColorUses((byte)1),player.getManaPool().getPossibleColorUses((byte)2),player.getManaPool().getPossibleColorUses((byte)4),player.getManaPool().getPossibleColorUses((byte)8),player.getManaPool().getPossibleColorUses((byte)16)}));
        for(var memory:forge.ai.AiCardMemory.MemorySet.values())
            result.put(memory.name(),forge.ai.AiCardMemory.getMemorySet(player,memory).stream().map(Card::getId).sorted().toList());
        result.put("board",player.getCardsIn(ZoneType.Battlefield).stream().map(c->c.getId()+":"+c.isTapped()+":"+c.getNetPower()+":"+c.getNetToughness()).toList());
        // Native source discovery sets actors on our hand cards' abilities too, so the
        // restoration this probe checks must cover both zones (review-v42 §1).
        List<Card> hosts=new ArrayList<>(player.getCardsIn(ZoneType.Battlefield));
        hosts.addAll(player.getCardsIn(ZoneType.Hand));
        result.put("actors",hosts.stream().flatMap(c->c.getSpellAbilities().stream())
                .map(sa->sa.getHostCard().getId()+":"+(sa.getActivatingPlayer()==null?"null":sa.getActivatingPlayer().getId())).toList());
        return result;
    }

    private static boolean previewDeclineChecked;

    /** The policy helper is package-private in forge.ai; invoke it reflectively so
     * this test source works against either policy build. */
    private static boolean previewRules(Card card,forge.item.PaperCard paper) {
        try {
            var method=forge.ai.CubeThopterPlan.class.getDeclaredMethod("addAssemblyPreviewRules",Card.class,forge.item.PaperCard.class);
            method.setAccessible(true);
            return (Boolean)method.invoke(null,card,paper);
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Design D3, previously dropped: the preview-rules helper must decline
     * anything that is not a detached preview of one of the three pieces —
     * a live card and a detached non-piece — without throwing and without
     * leaving rules behind. The helper declines on the live card identity, so
     * the live Urza's zone is immaterial; prefer the battlefield copy and
     * record the zone actually used. Runs once per JVM inside the purity
     * window, so it is also covered by the state and RNG checks. */
    private static void checkPreviewDecline(Player player) {
        Card live=null; String zone="none";
        for(ZoneType z:ZONES) { for(Card c:player.getCardsIn(z)) if(c.getName().equals(URZA)) { live=c; zone=z.name(); break; } if(live!=null)break; }
        if(live==null)return;
        var urzaPaper=Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(URZA));
        if(live.getId()<0)throw new AssertionError("live Urza must have a real card id");
        if(previewRules(live,urzaPaper))throw new AssertionError("preview rules must decline a live card");
        var titanPaper=Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard("Grave Titan"));
        Card detached=forge.game.card.CardFactory.getCard(titanPaper,player,-1,player.getGame());
        if(previewRules(detached,titanPaper))throw new AssertionError("preview rules must decline a non-piece preview");
        if(!detached.getTriggers().isEmpty())throw new AssertionError("declined preview must keep no triggers: "+detached.getTriggers());
        previewDeclineChecked=true;
        System.out.println("THOPTER_PREVIEW_DECLINE live="+URZA+" liveZone="+zone+" detached=Grave Titan declined=true triggers="+detached.getTriggers().size());
    }

    /** Reproduction probe for the panel defect: print each native sub-check of the
     * ordinary land ability separately, so a disagreement with the plan guard names
     * itself. The cached LandAbility is taken from the card, never enumerated, so no
     * id is allocated; setting the actor is restored by the payment probe, which the
     * purity snapshot around this call verifies. */
    private static void probeLandGuard(Player player,String control) {
        // Hand variant only: the MayPlay ability of the graveyard variant is built during
        // enumeration, which allocates ids, so that one is reported by the controller's own
        // CUBE_ORDINARY_GUARD_DISAGREE line at the real decision point instead.
        if(!LAND_DROP.contains(control) || LAND_DROP_MAYPLAY.contains(control))return;
        Card land=null;
        for(Card c:player.getCardsIn(ZoneType.Hand))if(c.getName().equals(FABLED)) { land=c; break; }
        if(land==null)throw new AssertionError("land-drop fixture must start with "+FABLED+" in hand");
        SpellAbility found=null;
        for(SpellAbility sa:land.getSpellAbilities())if(sa.isLandAbility()) { found=sa; break; }
        if(found==null) { System.out.println("THOPTER_LAND_PROBE control="+control+" landAbility=absent"); return; }
        final SpellAbility ability=found; final Card host=land;
        String line=forge.ai.CubeComboAi.probePayment(player,()->{
            ability.setActivatingPlayer(player);
            return " canPlay="+ability.canPlay()
                    +" legalAfterStack="+ability.isLegalAfterStack()
                    +" restrictions="+ability.checkRestrictions(host,player)
                    +" canPlayLand="+player.canPlayLand(host,false,ability)
                    +" mayPlay="+(ability.getMayPlay()!=null)
                    +" landsPlayed="+player.getLandsPlayedThisTurn()+"/"+player.getMaxLandPlays()
                    +" guard="+forge.ai.CubeComboAi.canPlayNative(ability,player);
        },ability);
        System.out.println("THOPTER_LAND_PROBE control="+control+" zone=Hand"+line);
    }

    private static void probePurity(Player player,String control,PhaseType phase) {
        try {
            var before=snapshot(player); var rng=BenchRandomAudit.begin();
            int selected=0;
            for(int i=0;i<3;i++)if(new forge.ai.CubeThopterPlan(player).nextAction()!=null)selected++;
            int tutors=0;
            if(assemblyBase(control.split(":")[0]))
                for(int i=0;i<3;i++)if(forge.ai.CubeComboAi.planTutor(player)!=null)tutors++;
            if(!previewDeclineChecked)checkPreviewDecline(player);
            probeLandGuard(player,control);
            if(!before.equals(snapshot(player)))throw new AssertionError("Thopter plan probe changed native state: "+before+" -> "+snapshot(player));
            BenchRandomAudit.assertUnchanged(rng,"thopter-plan-preview");
            if(POSITIVES.contains(control) && selected!=3)throw new AssertionError("Positive purity case did not reach a proposed action");
            if(!POSITIVES.contains(control) && !control.equals("remove-foundry")
                    && !assemblyBase(control.split(":")[0]) && selected!=0)
                throw new AssertionError("Blocked recursion must not propose a Thopter plan action: "+control);
            if((ASSEMBLY.contains(control) && control.startsWith("hand-") || ASSEMBLY2.contains(control)
                    || ASSEMBLY2_VARIANTS.contains(control) || ASSEMBLY2_FORECAST.contains(control)
                    || GUARD_NEGATIVES.contains(control)) && selected!=3)
                throw new AssertionError("Hand assembly purity must reach the planner: "+control+" hand="+selected);
            if(blockedAssembly(control) && (selected!=0 || tutors!=0))
                throw new AssertionError("Blocked assembly must not start a plan: "+control+" hand="+selected+" tutor="+tutors);
            // Kiki's haste copies can finish this combat only from MAIN1; in MAIN2 no faster route exists.
            if(variant(control).equals("kiki-hand") && phase==PhaseType.MAIN1 && selected!=0)
                throw new AssertionError("Ready Kiki route must keep priority over two-piece assembly: "+control);
            System.out.println("THOPTER_PURITY passed=true checks=3 selected="+selected+" tutors="+tutors+" control="+control);
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** Deliberate test response, not Default opponent behavior. */
    private static forge.ai.LobbyPlayerAi removalOpponent(int seat) {
        var lobby=new forge.ai.LobbyPlayerAi("Scripted-removal-"+seat,null) {
            @Override public Player createIngamePlayer(Game game,int id) {
                Player player=new Player(getName(),game,id);
                player.setFirstController(new forge.ai.PlayerControllerAi(game,player,this) {
                    @Override public List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
                        if(game.getStack().isEmpty() || !game.getStack().peekAbility().getHostCard().getName().equals(FOUNDRY))return null;
                        for(Card card:player.getCardsIn(ZoneType.Hand))if(card.getName().equals("Disenchant"))
                            for(var original:card.getSpellAbilities()) {
                                var spell=original.copy(player);
                                for(Player opponent:player.getOpponents())for(Card target:opponent.getCardsIn(ZoneType.Battlefield))
                                    if(target.getName().equals(FOUNDRY) && spell.canTarget(target)) {
                                        spell.resetTargets(); spell.getTargets().add(target);
                                        if(spell.isTargetNumberValid() && forge.ai.CubeComboAi.canPlayNative(spell,player)
                                                && forge.ai.CubeComboAi.canPayCost(spell,player,false))return List.of(spell);
                                    }
                            }
                        return null;
                    }
                }); return player;
            }
        }; lobby.setAiProfile("Default"); return lobby;
    }

    private static forge.ai.LobbyPlayerAi recordingDefault(int seat) {
        var lobby = new forge.ai.LobbyPlayerAi("Default-" + seat, null) {
            @Override
            public Player createIngamePlayer(Game game, int id) {
                Player result = new Player(getName(), game, id);
                result.setFirstController(new forge.ai.PlayerControllerAi(game, result, this) {
                    @Override
                    public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
                        boolean answer = super.confirmTrigger(wrapper);
                        if (wrapper.getHostCard().getName().equals(SWORD))
                            System.out.println("THOPTER_DEFAULT_CONFIRM answer=" + answer + " source=" + SWORD);
                        return answer;
                    }
                });
                return result;
            }
        };
        lobby.setAiProfile("Default");
        return lobby;
    }

    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (proxy, method, values) -> switch (method.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "thopter-native-diagnostic-v1";
                        default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                    }));
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                return null;
            });
            for (String name : List.of(URZA, FOUNDRY, SWORD, "Forest", "Island", "Cursed Totem", "Intangible Virtue", "Rest in Peace", "Torpor Orb", "Null Rod", "Serra Angel", "Disenchant", "Plains", "Swamp", "Demonic Tutor", "Grave Titan", "Wurmcoil Engine", "Humility", "Rule of Law", "Aven Mindcensor", "Kiki-Jiki, Mirror Breaker", "Pestermite", "Yasharn, Implacable Earth", "Sigarda, Host of Herons", FABLED, "Serra Paragon"))
                StaticData.instance().attemptToLoadCard(name);
            List<String> cases=args.length>2 ? switch(args[2]) {
                case "assembly" -> ASSEMBLY;
                case "assembly-controls" -> ASSEMBLY_CONTROLS;
                case "ordinary-win" -> List.of("hand-foundry:lethal-board");
                case "competing-route" -> List.of("tutor-foundry:kiki-tutor");
                case "sacrifice-blocked" -> SACRIFICE_CONTROLS;
                case "assembly2" -> ASSEMBLY2;
                case "assembly2-variants" -> ASSEMBLY2_VARIANTS;
                case "assembly2-controls" -> ASSEMBLY2_CONTROLS;
                case "guard-negatives" -> GUARD_NEGATIVES;
                case "assembly2-forecast" -> ASSEMBLY2_FORECAST;
                case "land-drop" -> LAND_DROP;
                case "ordinary-win2" -> List.of("hand2-foundry-sword:lethal-board");
                default -> CONTROLS;
            } : CONTROLS;
            for (int seat = 0; seat < 2; seat++) for (PhaseType phase : List.of(PhaseType.MAIN1, PhaseType.MAIN2))
                for (String control : cases)
                    run(args[1].equals("improved"), seat, phase, control);
            if(args[1].equals("improved") && Boolean.getBoolean("forge.test.probeThopterPurity") && !previewDeclineChecked)
                throw new AssertionError("design D3 preview-decline check never ran");
            System.out.println("THOPTER_SUITE_COMPLETE cases="+(4*cases.size()));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
