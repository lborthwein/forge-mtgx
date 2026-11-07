package forge.bench.rl;

import forge.card.mana.ManaAtom;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * v-inputs-1107 (key {@code -Dforge.bench.rl.obsManaAvail=true}, default off = every frame unchanged): the mana a player
 * could have right now, read from the rules objects only (no card names): its floating pool plus, for each untapped
 * permanent it controls, the most mana one of its mana abilities makes now (net of that ability's own mana cost; a tap
 * ability needs an untapped, non-summoning-sick source). Also by color: for W, U, B, R, G, C, the pool's amount of that
 * color plus, per source, the most one ability that can make that color produces. Public information for both seats
 * (battlefield and pools are public), so no InfoState question arises. Fills scal[7] / scal[8] (manaUser / manaOpp, /10),
 * written as 0 before (RlFeaturizer.scalars); the by-color vector is in-process only until an obs version carries it.
 */
public final class RlManaAvail {
    public static volatile boolean ON = "true".equals(System.getProperty("forge.bench.rl.obsManaAvail"));

    static final String[] CODES = {"W", "U", "B", "R", "G", "C"};
    static final byte[] ATOMS = {(byte) ManaAtom.WHITE, (byte) ManaAtom.BLUE, (byte) ManaAtom.BLACK, (byte) ManaAtom.RED,
        (byte) ManaAtom.GREEN, (byte) ManaAtom.COLORLESS};

    private RlManaAvail() {
    }

    /** [total, W, U, B, R, G, C] for {@code p}; zeros for null. */
    public static int[] of(final Player p) {
        final int[] out = new int[7];
        if (p == null) {
            return out;
        }
        for (int i = 0; i < 6; i++) {
            final int pool = p.getManaPool().getAmountOfColor(ATOMS[i]);
            out[1 + i] += pool;
            out[0] += pool;
        }
        for (Card src : p.getCardsIn(ZoneType.Battlefield)) {
            if (src.getManaAbilities().isEmpty()) {
                continue;
            }
            int best = 0;
            final int[] bestColor = new int[6];
            for (SpellAbility ma : src.getManaAbilities()) {
                try {
                    if (!usableNow(src, ma, p)) {
                        continue;
                    }
                    final AbilityManaPart mp = ma.getManaPart();
                    if (mp == null) {
                        continue;
                    }
                    final Cost pc = ma.getPayCosts();
                    final int cost = pc != null && pc.getCostMana() != null ? pc.getCostMana().convertAmount() : 0;
                    final String produced = ma.getParamOrDefault("Produced", mp.getOrigProduced() == null ? ""
                            : mp.getOrigProduced());
                    final int kinds = produced.isEmpty() ? 1 : produced.split(" ").length;
                    final int amount = AbilityUtils.calculateAmount(src, ma.getParamOrDefault("Amount", "1"), ma);
                    final int net = Math.max(0, kinds * amount - cost);
                    best = Math.max(best, net);
                    for (int i = 0; i < 6; i++) {
                        if (net > 0 && mp.canProduce(CODES[i], ma)) {
                            bestColor[i] = Math.max(bestColor[i], net);
                        }
                    }
                } catch (RuntimeException e) {
                    // an ability that cannot be read now counts as nothing
                }
            }
            out[0] += best;
            for (int i = 0; i < 6; i++) {
                out[1 + i] += bestColor[i];
            }
        }
        return out;
    }

    static boolean usableNow(final Card src, final SpellAbility ma, final Player p) {
        if (src.getController() != p || src.isPhasedOut()) {
            return false;
        }
        final Cost pc = ma.getPayCosts();
        if (pc != null && pc.hasTapCost() && (src.isTapped() || src.isSick())) {
            return false;
        }
        ma.setActivatingPlayer(p);
        return ma.getRestrictions() == null || ma.getRestrictions().canPlay(src, ma);
    }
}
