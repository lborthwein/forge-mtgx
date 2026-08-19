#!/usr/bin/env python3
"""Minimal protocol host: spawns run-bench.sh, answers a few kinds, delegates the rest.

Proves the ask/answer round trip, the legality validation, and the separation of
requested-delegate from refusal. Not a pilot -- the policy here is deliberately dumb
(all-out attacks, never block, keep every hand).
"""
import json, subprocess, sys, os, collections

HERE = os.path.dirname(os.path.abspath(__file__))
D = "/private/tmp/claude-501/-Users-lbo-Documents-GitHub-mtgx/98333e9b-08c0-46aa-80fe-2d36b8b9ef13/scratchpad/forge-build/decks"

cfg = {
    "decks": [f"{D}/SimGreen.dck", f"{D}/SimRed.dck"],
    "games": int(sys.argv[1]) if len(sys.argv) > 1 else 2,
    "seed": 123,
    "seats": {"0": "bridge", "1": "forge"},
    "aiProfile": "Default",
    "timeoutSec": 120,
}

p = subprocess.Popen([f"{HERE}/run-bench.sh"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=open(f"{HERE}/bridge-smoke.err", "wb"), text=True, bufsize=1)
p.stdin.write(json.dumps(cfg) + "\n")
p.stdin.flush()

kinds = collections.Counter()
samples = {}
trace = open(f"{HERE}/bridge-smoke.jsonl", "w")

for line in p.stdout:
    trace.write(line)
    msg = json.loads(line)
    t = msg.get("type")
    if t != "ask":
        continue
    kind = msg["kind"]
    kinds[kind] += 1
    if kind not in samples:
        samples[kind] = line.strip()
    ans = {"type": "answer", "id": msg["id"]}
    if kind == "mulligan":
        ans["keep"] = True
    elif kind == "attackers":
        pairs = []
        legal = msg.get("legalPairs", {})
        for fid, defs in legal.items():
            if defs:
                pairs.append([int(fid), defs[0]])
        ans["pairs"] = pairs
    elif kind == "blockers":
        ans["pairs"] = []
    elif kind == "cardsChoice":
        pool = [c["fid"] for c in msg.get("menu", [])]
        ans["choices"] = pool[: msg.get("min", 0)]
    else:
        ans["delegate"] = True
    p.stdin.write(json.dumps(ans) + "\n")
    p.stdin.flush()

p.stdin.close()
p.wait()
trace.close()

print("asks by kind:", dict(kinds))
print()
for k in ("mulligan", "attackers", "blockers"):
    if k in samples:
        s = samples[k]
        print(f"--- first {k} ask ({len(s)} bytes):")
        print(s[:1200] + ("..." if len(s) > 1200 else ""))
        print()
for line in open(f"{HERE}/bridge-smoke.jsonl"):
    if '"type":"result"' in line:
        print("RESULT:", line.strip()[:900])
