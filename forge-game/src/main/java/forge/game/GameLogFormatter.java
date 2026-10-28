package forge.game;

import java.util.Collection;
import java.util.Map.Entry;

import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;

import forge.game.card.CardView;
import forge.game.card.CounterEnumType;
import forge.game.event.*;
import forge.game.event.GameEventCardDamaged.DamageType;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.util.*;

public class GameLogFormatter extends IGameEventVisitor.Base<GameLogEntry> {
    private final Localizer localizer = Localizer.getInstance();
    private final GameLog log;
    public GameLogFormatter(GameLog gameLog) {
        log = gameLog;
    }

    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%dh : %02dm : %02ds", hours, minutes, seconds)
                : String.format("%dm : %02ds", minutes, seconds);
    }

    @Override
    public GameLogEntry visit(GameEventGameOutcome ev) {
        // Match Duration
        if (log.getMatchStartMillis() >= 0) { // defend against it somehow still never being set
            long durationMillis = System.currentTimeMillis() - log.getMatchStartMillis();
            log.add(GameLogEntryType.GAME_OUTCOME, localizer.getMessage("lblMatchDuration") + " " + formatDuration(durationMillis));
        }

        // Turn number counted from the starting player
        int lastTurn = (int)Math.ceil((float)ev.lastTurnNumber() / 2.0);
        log.add(GameLogEntryType.GAME_OUTCOME, localizer.getMessage("lblTurn") + " " + lastTurn);

        for (String outcome : ev.outcomeStrings()) {
            log.add(GameLogEntryType.GAME_OUTCOME, outcome);
        }
        return new GameLogEntry(GameLogEntryType.MATCH_RESULTS, ev.matchSummary());
    }

    @Override
    public GameLogEntry visit(GameEventScry ev) {
        String scryOutcome;
        if (ev.toTop() > 0 && ev.toBottom() > 0) {
            scryOutcome = localizer.getMessage("lblLogScryTopBottomLibrary").replace("%s", ev.player().toString()).replace("%top", String.valueOf(ev.toTop())).replace("%bottom", String.valueOf(ev.toBottom()));
        } else if (ev.toBottom() == 0) {
            scryOutcome = localizer.getMessage("lblLogScryTopLibrary").replace("%s", ev.player().toString()).replace("%top", String.valueOf(ev.toTop()));
        } else {
            scryOutcome = localizer.getMessage("lblLogScryBottomLibrary").replace("%s", ev.player().toString()).replace("%bottom", String.valueOf(ev.toBottom()));
        }

        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, scryOutcome);
    }

    @Override
    public GameLogEntry visit(GameEventSurveil ev) {
        String surveilOutcome;
        if (ev.toLibrary() > 0 && ev.toGraveyard() > 0) {
            surveilOutcome = localizer.getMessage("lblLogSurveiledToLibraryGraveyard", ev.player(), ev.toLibrary(), ev.toGraveyard());
        } else if (ev.toGraveyard() == 0) {
            surveilOutcome = localizer.getMessage("lblLogSurveiledToLibrary", ev.player(), ev.toLibrary());
        } else {
            surveilOutcome = localizer.getMessage("lblLogSurveiledToGraveyard", ev.player(), ev.toGraveyard());
        }

        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, surveilOutcome);
    }

    @Override
    public GameLogEntry visit(GameEventSpellResolved ev) {
        String messageForLog = ev.hasFizzled() ? localizer.getMessage("lblLogCardAbilityFizzles", ev.spell().getHostCard().getName()) : ev.stackDescription();
        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, messageForLog, ev.spell().getHostCard());
    }

    @Override
    public GameLogEntry visit(GameEventSpellAbilityCast event) {
        String player = event.si().getActivatingPlayer().getName();
        String action = event.sa().isSpell() ? localizer.getMessage("lblCast")
                : event.si().isTrigger() ? localizer.getMessage("lblTriggered")
                        : localizer.getMessage("lblActivated");
        String siText = event.si() != null ? event.si().getText() : "";
        String object = siText != null && siText.startsWith("Morph ")
                ? localizer.getMessage("lblMorph")
                : event.sa().getHostCard().getName();

        String messageForLog;

        if (event.targetDescription() != null) {
            messageForLog = localizer.getMessage("lblLogPlayerActionObjectWitchTarget", player, action, object, event.targetDescription());
        } else {
            messageForLog = localizer.getMessage("lblLogPlayerActionObject", player, action, object);
        }

        return new GameLogEntry(GameLogEntryType.STACK_ADD, messageForLog, event.sa().getHostCard());
    }

    @Override
    public GameLogEntry visit(GameEventCardModeChosen ev) {
        if (!ev.log()) {
            return null;
        }

        String modeChoiceOutcome;
        if (ev.random()) {
            modeChoiceOutcome = localizer.getMessage("lblLogRandomMode", ev.cardName(), ev.mode());
        } else {
            modeChoiceOutcome = localizer.getMessage("lblLogPlayerChosenModeForCard",
                    ev.player().toString(), ev.mode(), ev.cardName());
        }
        String name = CardTranslation.getTranslatedName(ev.cardName());
        modeChoiceOutcome = TextUtil.fastReplace(modeChoiceOutcome, "CARDNAME", name);
        modeChoiceOutcome = TextUtil.fastReplace(modeChoiceOutcome, "NICKNAME",
                Lang.getInstance().getNickName(name));
        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, modeChoiceOutcome);
    }

    @Override
    public GameLogEntry visit(GameEventRandomLog ev) {
        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, ev.message());
    }

    @Override
    public GameLogEntry visit(final GameEventPlayerControl event) {
        final String newLobbyPlayerName = event.newLobbyPlayerName();
        final PlayerView p = event.player();

        final String message;
        if (newLobbyPlayerName == null) {
            message = localizer.getMessage("lblLogPlayerHasRestoredControlThemself", p.getName());
        } else {
            if (newLobbyPlayerName.equals(p.getName())) return null;
            message = localizer.getMessage("lblLogPlayerControlledTargetPlayer", p.getName(), newLobbyPlayerName);
        }
        return new GameLogEntry(GameLogEntryType.PLAYER_CONTROL, message);
    }

    @Override
    public GameLogEntry visit(GameEventTurnPhase ev) {
        PlayerView p = ev.playerTurn();
        return new GameLogEntry(GameLogEntryType.PHASE, ev.phaseDesc() + Lang.getInstance().getPossessedObject(p.getName(), ev.phase().nameForUi));
    }

    @Override
    public GameLogEntry visit(GameEventCardDamaged event) {
        String additionalLog = "";
        if (event.type() == DamageType.Deathtouch) {
            additionalLog = localizer.getMessage("lblDeathtouch");
        }
        if (event.type() == DamageType.M1M1Counters) {
            additionalLog = localizer.getMessage("lblAsM1M1Counters");
        }
        if (event.type() == DamageType.LoyaltyLoss) {
            additionalLog = localizer.getMessage("lblRemovingNLoyaltyCounter", event.amount());
        }
        String message = localizer.getMessage("lblSourceDealsNDamageToDest", event.source(), event.amount(), additionalLog.isEmpty() ? "" : " (" + additionalLog + ")", event.card().toString());
        return new GameLogEntry(GameLogEntryType.DAMAGE, message, event.source());
    }

    /* (non-Javadoc)
     * @see forge.game.event.IGameEventVisitor.Base#visit(forge.game.event.GameEventLandPlayed)
     */
    @Override
    public GameLogEntry visit(GameEventLandPlayed ev) {
        String message = localizer.getMessage("lblLogPlayerPlayedLand", ev.player(), ev.land());
        return new GameLogEntry(GameLogEntryType.LAND, message, ev.land());
    }

    @Override
    public GameLogEntry visit(GameEventTurnBegan event) {
        log.markMatchStartIfNeeded();
        String message = localizer.getMessage("lblLogTurnNOwnerByPlayer", event.turnNumber(), event.turnOwner());
        return new GameLogEntry(GameLogEntryType.TURN, message);
    }

    @Override
    public GameLogEntry visit(GameEventPlayerDamaged ev) {
        String extra = ev.infect() ? localizer.getMessage("lblLogAsPoisonCounters") : "";
        String damageType = ev.combat() ? localizer.getMessage("lblCombat") : localizer.getMessage("lblNonCombat");
        String message = localizer.getMessage("lblLogSourceDealsNDamageOfTypeToDest", ev.source(),
                            ev.amount(), damageType, ev.target(), extra);
        return new GameLogEntry(GameLogEntryType.DAMAGE, message, ev.source());
    }

    @Override
    public GameLogEntry visit(GameEventPlayerLivesChanged ev) {
        String message = localizer.getMessage("lblLogPlayerLifeChange", ev.player(), ev.oldLives(), ev.newLives());
        return new GameLogEntry(GameLogEntryType.LIFE, message);
    }

    @Override
    public GameLogEntry visit(GameEventPlayerPoisoned ev) {
        String message = localizer.getMessage("lblLogPlayerReceivesNPosionCounterFrom",
                            ev.receiver(), ev.amount(), ev.source());
        return new GameLogEntry(GameLogEntryType.DAMAGE, message);
    }

    @Override
    public GameLogEntry visit(GameEventPlayerRadiation ev) {
        String message;
        final int change = ev.change();
        String radCtr = CounterEnumType.RAD.getName().toLowerCase() + " " +
                Localizer.getInstance().getMessage("lblCounter").toLowerCase();
        if (change >= 0) message = localizer.getMessage("lblLogPlayerRadiation",
                ev.receiver().toString(), Lang.nounWithNumeralExceptOne(String.valueOf(change), radCtr),
                ev.source().toString());
        else message = localizer.getMessage("lblLogPlayerRadRemove",
                ev.receiver().toString(), Lang.nounWithNumeralExceptOne(String.valueOf(Math.abs(change)), radCtr));
        return new GameLogEntry(GameLogEntryType.DAMAGE, message);
    }

    @Override
    public GameLogEntry visit(final GameEventAttackersDeclared ev) {
        final StringBuilder sb = new StringBuilder();

        // Loop through Defenders
        // Append Defending Player/Planeswalker

        // Not a big fan of the triple nested loop here
        for (GameEntityView k : ev.attackersMap().keySet()) {
            Collection<CardView> attackers = ev.attackersMap().get(k);
            if (attackers == null || attackers.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(localizer.getMessage("lblLogPlayerAssignedAttackerToAttackTarget", ev.player(), Lang.joinHomogenous(attackers), k));
        }
        if (sb.length() == 0) return null;
        return new GameLogEntry(GameLogEntryType.COMBAT, sb.toString());
    }

    @Override
    public GameLogEntry visit(final GameEventBlockersDeclared ev) {
        final StringBuilder sb = new StringBuilder();

        // Loop through Defenders
        // Append Defending Player/Planeswalker

        for (Entry<GameEntityView, Multimap<CardView, CardView>> kv : ev.blockers().entrySet()) {
            GameEntityView defender = kv.getKey();
            Multimap<CardView, CardView> attackers = kv.getValue();
            if (attackers == null || attackers.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }

            String controllerName;
            if (defender instanceof CardView c && c.getController() != null) {
                controllerName = c.getCurrentState().isBattle() ? c.getProtectingPlayer().getName() : c.getController().getName();
            } else {
                controllerName = defender.getName();
            }

            boolean firstAttacker = true;
            for (final Entry<CardView, Collection<CardView>> att : attackers.asMap().entrySet()) {
                if (!firstAttacker) sb.append("\n");

                Collection<CardView> blockers = att.getValue();
                if (blockers.isEmpty() || Iterables.get(blockers, 0) == att.getKey()) {
                    sb.append(localizer.getMessage("lblLogPlayerDidntBlockAttacker", controllerName, att.getKey()));
                } else {
                    sb.append(localizer.getMessage("lblLogPlayerAssignedBlockerToBlockAttacker", controllerName, Lang.joinHomogenous(blockers), att.getKey()));
                }
                firstAttacker = false;
            }
        }

        return new GameLogEntry(GameLogEntryType.COMBAT, sb.toString());
    }

    @Override
    public GameLogEntry visit(GameEventMulligan ev) {
        String message = localizer.getMessage("lblPlayerHasMulliganedDownToNCards").replace("%d", String.valueOf(ev.player().getZoneSize(ZoneType.Hand))).replace("%s", ev.player().toString());
        return new GameLogEntry(GameLogEntryType.MULLIGAN, message);
    }

    @Override
    public GameLogEntry visit(GameEventCardForetold ev) {
        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, ev.toString());
    }

    @Override
    public GameLogEntry visit(GameEventCardPlotted ev) {
        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, ev.toString(), ev.card());
    }

    @Override
    public GameLogEntry visit(GameEventDoorChanged ev) {
        return new GameLogEntry(GameLogEntryType.STACK_RESOLVE, ev.toString());
    }

    @Override
    public GameLogEntry visit(GameEventCardChangeZone ev) {
        if (ev.from() == null || ev.to() == null) {
            return null;
        }
        final ZoneType from = ev.from().zoneType();
        final ZoneType to = ev.to().zoneType();
        // Log mid-game ante additions (e.g. Contract from Below, Demonic Attorney)
        if (to == ZoneType.Ante && from != ZoneType.Ante) {
            final CardView c = ev.card();
            return new GameLogEntry(GameLogEntryType.ANTE,
                    (c != null ? c.getOwner() + " anted " + c : "a card was anted"));
        }
        // Only log Battlefield -> Graveyard/Exile to avoid duplicating entries
        // already covered by other events (land played, spell cast, discard, etc.)
        if (from != ZoneType.Battlefield || (to != ZoneType.Graveyard && to != ZoneType.Exile)) {
            return null;
        }
        final String cardName = ev.card() != null ? ev.card().toString() : "a card";
        final String message = localizer.getMessage("lblLogZoneChange", cardName, to, from);
        return new GameLogEntry(GameLogEntryType.ZONE_CHANGE, message, ev.card());
    }

    @Override
    public GameLogEntry visit(GameEventAddLog ev) {
        return new GameLogEntry(ev.type(), ev.message(), ev.sourceCard());
    }

    @Override
    public GameLogEntry visit(GameEventZoneChangeReplaced ev) {
        return new GameLogEntry(GameLogEntryType.ZONE_CHANGE, describeReplacedZoneChange(ev), ev.source());
    }

    /** Zones whose face-up cards everyone sees (CR 400.2). */
    private static final java.util.Set<ZoneType> PUBLIC_ZONES = java.util.EnumSet.of(ZoneType.Battlefield,
            ZoneType.Graveyard, ZoneType.Exile, ZoneType.Stack, ZoneType.Command);

    /**
     * The player-facing sentence for a replaced or prevented zone change, in US English, e.g. "Containment Priest
     * exiles Hullbreacher instead of letting it enter the battlefield." The moved card is named only when it is face
     * up and was in, or ended up in, a public zone; otherwise it is "a card". A card that replaces its own move
     * (flashback, a commander going to the command zone) gets the passive form: "Faithless Looting is exiled instead
     * of going to its owner's graveyard."
     */
    public static String describeReplacedZoneChange(final GameEventZoneChangeReplaced ev) {
        final CardView card = ev.card();
        final boolean cardPublic = card != null && !card.isFaceDown()
                && (PUBLIC_ZONES.contains(ev.from()) || PUBLIC_ZONES.contains(ev.actual()));
        final String cardName = cardPublic && !card.getName().isEmpty() ? card.getName() : "a card";
        final CardView source = ev.source();
        final boolean self = source == null || card != null && source.getId() == card.getId();
        final String sourceName = self ? null
                : source.isFaceDown() || source.getName().isEmpty() ? "A face-down permanent" : source.getName();

        if (ev.prevented()) {
            return self ? capitalize(cardName) + " stays where it is instead of " + gerund(ev.intended()) + "."
                    : sourceName + " keeps " + cardName + " from " + gerund(ev.intended()) + ".";
        }
        return self ? capitalize(cardName) + " " + passive(ev.actual()) + " instead of " + gerund(ev.intended()) + "."
                : sourceName + " " + active(ev.actual(), cardName) + " instead of letting it "
                        + infinitive(ev.intended()) + ".";
    }

    private static String capitalize(final String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String infinitive(final ZoneType zone) {
        if (zone == null) {
            return "change zones";
        }
        return switch (zone) {
            case Battlefield -> "enter the battlefield";
            case Graveyard -> "go to its owner's graveyard";
            case Hand -> "go to its owner's hand";
            case Library -> "be put into its owner's library";
            case Exile -> "be exiled";
            case Stack -> "go on the stack";
            case Command -> "go to the command zone";
            default -> "change zones";
        };
    }

    private static String gerund(final ZoneType zone) {
        if (zone == null) {
            return "changing zones";
        }
        return switch (zone) {
            case Battlefield -> "entering the battlefield";
            case Graveyard -> "going to its owner's graveyard";
            case Hand -> "going to its owner's hand";
            case Library -> "being put into its owner's library";
            case Exile -> "being exiled";
            case Stack -> "going on the stack";
            case Command -> "going to the command zone";
            default -> "changing zones";
        };
    }

    private static String active(final ZoneType zone, final String card) {
        if (zone == null) {
            return "moves " + card;
        }
        return switch (zone) {
            case Exile -> "exiles " + card;
            case Graveyard -> "puts " + card + " into its owner's graveyard";
            case Hand -> "returns " + card + " to its owner's hand";
            case Library -> "puts " + card + " into its owner's library";
            case Battlefield -> "puts " + card + " onto the battlefield";
            case Command -> "puts " + card + " into the command zone";
            case Stack -> "puts " + card + " on the stack";
            default -> "moves " + card;
        };
    }

    private static String passive(final ZoneType zone) {
        if (zone == null) {
            return "is moved";
        }
        return switch (zone) {
            case Exile -> "is exiled";
            case Graveyard -> "is put into its owner's graveyard";
            case Hand -> "is returned to its owner's hand";
            case Library -> "is put into its owner's library";
            case Battlefield -> "is put onto the battlefield";
            case Command -> "is put into the command zone";
            case Stack -> "is put on the stack";
            default -> "is moved";
        };
    }

    @Subscribe
    public void recieve(GameEvent ev) {
        GameLogEntry le = ev.visit(this);
        if (le != null) {
            log.add(le);
        }
    }
}