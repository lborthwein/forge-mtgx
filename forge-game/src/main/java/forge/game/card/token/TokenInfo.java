package forge.game.card.token;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardType;
import forge.card.ColorSet;
import forge.card.GamePieceType;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardFactoryUtil;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.item.PaperToken;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class TokenInfo {
    // Per-game pin so same-type tokens share art. Weak keys GC finished games.
    private static final Map<Game, Map<String, String>> TOKEN_EDITION_PINS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static Map<String, String> getPinsFor(Game game) {
        return TOKEN_EDITION_PINS.computeIfAbsent(game, g -> new ConcurrentHashMap<>());
    }

    final String name;
    final String imageName;
    final String manaCost;
    final String[] types;
    final String[] intrinsicKeywords;
    final int basePower;
    final int baseToughness;
    final ColorSet color;

    public TokenInfo(Card c) {
        // TODO: Figure out how to handle legacy images?
        this.name = c.getName();
        this.imageName = ImageKeys.getTokenImageName(c.getImageKey());
        this.manaCost = c.getManaCost().toString();
        this.color = c.getCurrentState().getColor();
        this.types = getCardTypes(c);

        List<String> list = Lists.newArrayList();
        for (KeywordInterface inst : c.getKeywords()) {
            list.add(inst.getOriginal());
        }

        this.intrinsicKeywords   = list.toArray(new String[0]);
        this.basePower = c.getBasePower();
        this.baseToughness = c.getBaseToughness();
    }

    public TokenInfo(String str) {
        final String[] tokenInfo = str.split(",");
        int power = 0;
        int toughness = 0;
        String manaCost = "0";
        String[] types = null;
        String[] keywords = null;
        String imageName = null;
        ColorSet color = null;
        for (String info : tokenInfo) {
            int index = info.indexOf(':');
            if (index == -1) {
                continue;
            }
            String remainder = info.substring(index + 1);
            if (info.startsWith("P:")) {
                power = parseIntOr(remainder, 0);
            } else if (info.startsWith("T:")) {
                toughness = parseIntOr(remainder, 0);
            } else if (info.startsWith("Cost:")) {
                manaCost = remainder;
            } else if (info.startsWith("Types:")) {
                types = splitList(remainder);
            } else if (info.startsWith("Keywords:")) {
                keywords = splitList(remainder);
            } else if (info.startsWith("Image:")) {
                imageName = "null".equals(remainder) || remainder.isEmpty() ? null : remainder;
            } else if (info.startsWith("Color:")) {
                color = parseColor(remainder);
            }
        }

        this.name = tokenInfo[0];
        this.imageName = imageName;
        this.manaCost = manaCost;
        // Never null: toCard() and makeOneToken() both iterate these, so a
        // token written without a Types: or Keywords: field used to be an NPE
        // in the middle of installing a game state rather than a bad token.
        this.types = types == null ? new String[0] : types;
        this.intrinsicKeywords = keywords == null ? new String[0] : keywords;
        this.basePower = power;
        this.baseToughness = toughness;
        this.color = color;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * `Types:`/`Keywords:` are `-`-joined and may be empty. `"".split("-")`
     * yields `[""]`, which reached `addType("")` and `addIntrinsicKeyword("")`;
     * empty entries are dropped here instead.
     */
    private static String[] splitList(String remainder) {
        if (remainder == null || remainder.isEmpty()) {
            return new String[0];
        }
        List<String> out = Lists.newArrayList();
        for (String part : remainder.split("-")) {
            if (!part.trim().isEmpty()) {
                out.add(part);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * Read a `Color:` field.
     *
     * The writer used to emit `ColorSet`'s inherited {@code Object.toString()}
     * — `forge.card.ColorSet@1f2e3d` — so every token written before this
     * change came back colourless. Such a value is recognised and treated as
     * absent (falling back to the mana cost) rather than being fed to
     * {@link ColorSet#fromNames}, where its stray `r`/`g` characters would
     * otherwise be read as colours.
     */
    private static ColorSet parseColor(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || t.indexOf('@') >= 0 || t.indexOf('.') >= 0) {
            return null;
        }
        byte mask = 0;
        boolean shorthand = true;
        for (char ch : t.toCharArray()) {
            byte b = MagicColor.fromName(ch);
            if (b == 0) {
                shorthand = false;
                break;
            }
            mask |= b;
        }
        if (shorthand) {
            return ColorSet.fromMask(mask);
        }
        if ("c".equalsIgnoreCase(t) || "colorless".equalsIgnoreCase(t)) {
            return ColorSet.fromMask(0);
        }
        return ColorSet.fromNames(t);
    }

    /** `WUBRG` shorthand, or `c` when the token has no colour. */
    private static String colorShort(ColorSet cs) {
        if (cs == null) {
            return "c";
        }
        StringBuilder sb = new StringBuilder();
        if (cs.hasWhite()) sb.append('W');
        if (cs.hasBlue()) sb.append('U');
        if (cs.hasBlack()) sb.append('B');
        if (cs.hasRed()) sb.append('R');
        if (cs.hasGreen()) sb.append('G');
        return sb.length() == 0 ? "c" : sb.toString();
    }

    /**
     * Rebuild this token from its token SCRIPT, or null when it does not name
     * one the token database holds.
     *
     * `t:<TokenInfo>` carries a body — name, P/T, colours, types, keywords —
     * and no rules whatsoever. That is the whole content of the standing
     * *"Make sure Game State conversion works with new tokens"* TODO in
     * {@code GameState.processCardsForZone}: a Food written as `t:` comes back
     * unable to be sacrificed, and a `c_0_0_a_construct_total_artifacts` comes
     * back a literal 0/0 and is put into the graveyard by CR 704.5f before
     * anyone gets priority. The `Image:` field is the script name, so when the
     * database knows it the real token is built instead.
     */
    public Card makeScriptedToken(final Player controller) {
        if (imageName == null || imageName.isEmpty()) {
            return null;
        }
        int bar = imageName.indexOf('|');
        String script = bar < 0 ? imageName : imageName.substring(0, bar);
        String edition = bar < 0 ? null : imageName.substring(bar + 1);
        int nextBar = edition == null ? -1 : edition.indexOf('|');
        if (nextBar >= 0) {
            edition = edition.substring(0, nextBar);
        }
        if (script.isEmpty() || !StaticData.instance().getAllTokens().containsRule(script)) {
            return null;
        }
        PaperToken paper;
        try {
            paper = edition == null || edition.isEmpty()
                    ? StaticData.instance().getAllTokens().getToken(script)
                    : StaticData.instance().getAllTokens().getToken(script, edition);
        } catch (RuntimeException e) {
            paper = StaticData.instance().getAllTokens().getToken(script);
        }
        if (paper == null) {
            return null;
        }
        return CardFactory.getCard(paper, controller, controller.getGame());
    }

    private static String[] getCardTypes(Card c) {
        List<String> relevantTypes = Lists.newArrayList();
        for (CardType.CoreType t : c.getType().getCoreTypes()) {
            relevantTypes.add(t.name());
        }
        c.getType().getSubtypes().forEach(relevantTypes::add);
        if (c.getType().isLegendary()) {
            relevantTypes.add("Legendary");
        }
        return relevantTypes.toArray(new String[0]);
    }

    private Card toCard(Game game) {
        return toCard(game, game.nextCardId());
    }

    private Card toCard(Game game, int id) {
        final Card c = new Card(id, game);
        c.setName(name);
        c.setImageKey(ImageKeys.getTokenKey(imageName));

        c.setColor(color == null ? ColorSet.fromManaCost(new ManaCost(manaCost)) : color);
        c.setGamePieceType(GamePieceType.TOKEN);

        for (final String t : types) {
            c.addType(t);
        }

        c.setBasePower(basePower);
        c.setBaseToughness(baseToughness);
        return c;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(',');
        sb.append("P:").append(basePower).append(',');
        sb.append("T:").append(baseToughness).append(',');
        sb.append("Cost:").append(manaCost).append(',');
        sb.append("Color:").append(colorShort(color)).append(",");
        sb.append("Types:").append(Joiner.on('-').join(types)).append(',');
        sb.append("Keywords:").append(Joiner.on('-').join(intrinsicKeywords)).append(',');
        sb.append("Image:").append(imageName);
        return sb.toString();
    }

    public Card makeOneToken(final Player controller) {
        return makeOneToken(controller, controller.getGame().nextCardId());
    }
    public Card makeOneToken(final Player controller, int id) {
        final Game game = controller.getGame();
        final Card c = toCard(game, id);

        c.setOwner(controller);
        c.setGamePieceType(GamePieceType.TOKEN);
        CardFactoryUtil.setupKeywordedAbilities(c);
        // add them later to prevent setupKeywords from adding them multiple times
        for (final String kw : intrinsicKeywords) {
            c.addIntrinsicKeyword(kw);
        }
        return c;
    }

    static protected void protoTypeApplyTextChange(final Card result, final SpellAbility sa) {
        // update Token with CardTextChanges
        Map<String, String> colorMap = sa.getChangedTextColors();
        Map<String, String> typeMap = sa.getChangedTextTypes();
        if (!colorMap.isEmpty()) {
            if (!result.isColorless()) {
                // change Token Colors
                byte color = result.getColor().getColor();

                for (final Map.Entry<String, String> e : colorMap.entrySet()) {
                    byte v = MagicColor.fromName(e.getValue());
                    // Any used by Swirl the Mists
                    if ("Any".equals(e.getKey())) {
                        for (final byte c : MagicColor.WUBRG) {
                            // try to replace color flips
                            if ((color & c) != 0) {
                                color &= ~c;
                                color |= v;
                            }
                        }
                    } else {
                        byte c = MagicColor.fromName(e.getKey());
                        // try to replace color flips
                        if ((color & c) != 0) {
                            color &= ~c;
                            color |= v;
                        }
                    }
                }

                result.setColor(ColorSet.fromMask(color));
            }
        }
        if (!typeMap.isEmpty()) {
            CardType type = new CardType(result.getType());
            final boolean nameGenerated = result.getName().endsWith(" Token");
            boolean typeChanged = false;

            if (!type.getSubtypes().isEmpty()) {
                for (final Map.Entry<String, String> e : typeMap.entrySet()) {
                    if (type.hasSubtype(e.getKey())) {
                        type.remove(e.getKey());
                        type.add(e.getValue());
                        typeChanged = true;
                    }
                }
            }

            if (typeChanged) {
                result.setType(type);

                // update generated Name
                if (nameGenerated) {
                    result.setName(StringUtils.join(type.getSubtypes(), " ") + " Token");
                }
            }
        }

        // replace Intrinsic Keyword
        List<KeywordInterface> toRemove = Lists.newArrayList();
        List<String> toAdd = Lists.newArrayList();
        for (final KeywordInterface k : result.getCurrentState().getIntrinsicKeywords()) {
            final String o = k.getOriginal();
            String r = AbilityUtils.applyKeywordTextChangeEffects(o, colorMap, typeMap);
            if (!r.equals(o)) {
                toRemove.add(k);
                toAdd.add(r);
            }
        }
        for (final KeywordInterface k : toRemove) {
            result.getCurrentState().removeIntrinsicKeyword(k);
        }
        result.addIntrinsicKeywords(toAdd);

        result.getCurrentState().changeTextIntrinsic(colorMap, typeMap);
    }

    static public Card getProtoType(final String script, final SpellAbility sa, final Player owner) {
        return getProtoType(script, sa, owner, !sa.hasParam("LockTokenScript"));
    }
    static public Card getProtoType(final String script, final SpellAbility sa, final Player owner, boolean applyTextChange) {
        // script might be null, or sa might be null
        if (script == null || sa == null) {
            return null;
        }
        final Card host = sa.getHostCard();
        final Game game = host.getGame();

        Card editionHost = sa.getOriginalHost();
        if (sa.getKeyword() != null && sa.getKeyword().getStatic() != null) {
            editionHost = sa.getKeyword().getStatic().getHostCard();
        }
        String edition = Objects.requireNonNullElse(editionHost, host).getSetCode();
        edition = Objects.requireNonNullElse(StaticData.instance().getCardEdition(edition).getTokenSet(script), edition);
        Map<String, String> pins = getPinsFor(game);
        String pinned = pins.get(script);
        if (pinned != null) edition = pinned;
        PaperToken token = StaticData.instance().getAllTokens().getToken(script, edition);
        if (token != null && pinned == null) {
            pins.put(script, token.getEdition());
        }

        if (token == null) {
            return null;
        }
        final Card result = CardFactory.getCard(token, owner, game);

        if (sa.hasParam("TokenPower")) {
            String str = sa.getParam("TokenPower");
            result.setBasePowerString(str);
            result.setBasePower(AbilityUtils.calculateAmount(host, str, sa));
        }

        if (sa.hasParam("TokenToughness")) {
            String str = sa.getParam("TokenToughness");
            result.setBaseToughnessString(str);
            result.setBaseToughness(AbilityUtils.calculateAmount(host, str, sa));
        }

        if (applyTextChange) {
            protoTypeApplyTextChange(result, sa);
        }

        // need to be done after text change so it isn't affected by that
        if (sa.hasParam("TokenTypes")) {
            String types = sa.getParam("TokenTypes");
            types = types.replace("ChosenType", host.getChosenType());
            result.addType(types);
            result.setName(types);
        }

        if (sa.hasParam("TokenColors")) {
            String colors = sa.getParam("TokenColors");
            colors = colors.replace("ChosenColor", host.getChosenColor());
            result.setColor(colors.split(","));
        }

        return result;
    }
}
