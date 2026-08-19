#!/usr/bin/env python3
"""Generate mechanical counting overrides for every abstract PlayerController method.

Reads forge-game/.../PlayerController.java, emits Java override bodies of the form

    @Override public RET name(ARGS) { count("name"); return super.name(argnames); }

Methods handled specially by PlayerControllerBridge (the strategic set) are skipped
here and hand-written instead.
"""
import re, sys

SRC = "/Users/lbo/Documents/GitHub/forge/forge-game/src/main/java/forge/game/player/PlayerController.java"
# strategic set: hand-written in PlayerControllerBridge (they still count themselves)
SKIP = {
    "chooseSpellAbilityToPlay", "declareAttackers", "declareBlockers", "mulliganKeepHand",
    "chooseCardsToDiscardToMaximumHandSize", "chooseTargetsFor", "chooseSingleEntityForEffect",
    "chooseEntitiesForEffect", "choosePermanentsToSacrifice", "chooseCardsForEffect",
    "chooseNumber", "chooseModeForAbility", "confirmAction", "chooseBinary",
    "arrangeForScry", "orderBlockers", "assignCombatDamage",
}

text = open(SRC).read()
# strip block comments and line comments so declarations parse cleanly
text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
text = re.sub(r"//[^\n]*", "", text)

decls = re.findall(r"public\s+abstract\s+([^;{}]*?)\s*;", text, flags=re.S)

out = []
count = 0
for d in decls:
    d = " ".join(d.split())
    # split off params
    m = re.match(r"^(.*?)\(([^()]*)\)$", d)
    if not m:
        sys.exit("unparsed: " + d)
    head, params = m.group(1), m.group(2)
    # head = [generic] rettype name
    gen = ""
    gm = re.match(r"^(<[^>]*>)\s*(.*)$", head)
    if gm:
        gen, head = gm.group(1) + " ", gm.group(2)
    parts = head.rsplit(" ", 1)
    ret, name = parts[0], parts[1]
    if name in SKIP:
        continue
    # parse param names, respecting generics in types
    argnames = []
    if params.strip():
        depth = 0
        cur = ""
        pieces = []
        for ch in params:
            if ch == "<":
                depth += 1
            elif ch == ">":
                depth -= 1
            if ch == "," and depth == 0:
                pieces.append(cur); cur = ""
            else:
                cur += ch
        pieces.append(cur)
        for p in pieces:
            argnames.append(p.strip().split(" ")[-1].strip())
    call = "super.%s(%s)" % (name, ", ".join(argnames))
    body = ('count("%s"); %s;' % (name, call)) if ret == "void" else ('count("%s"); return %s;' % (name, call))
    out.append("    @Override\n    public %s%s %s(%s) { %s }\n" % (gen, ret, name, params, body))
    count += 1

sys.stderr.write("generated %d overrides (%d skipped as strategic)\n" % (count, len(decls) - count))
print("".join(out))
