#!/usr/bin/python3
"""forge-upstream-1002: compare two bench row files by unit id (the id after its arm prefix).
usage: cmp_rows.py <ref.jsonl> <cand.jsonl> [fields...]   default fields: winner reason turns startingSeat digest
(+ phaseDigest logDigest when both rows have them). Prints identical/total and every mismatch; exit 1 on any mismatch."""
import json, sys
a, b = sys.argv[1], sys.argv[2]
fields = sys.argv[3:] or ['winner', 'reason', 'turns', 'startingSeat', 'digest', 'phaseDigest', 'logDigest']
key = lambda r: r['id'].split('-', 1)[1]  # rows of different arms share the unit id after the arm prefix
ra = {key(r): r for r in map(json.loads, open(a)) }
rb = {key(r): r for r in map(json.loads, open(b)) }
ids = [i for i in ra if i in rb]
bad = []
for i in ids:
    diff = [f for f in fields if f in ra[i] and f in rb[i] and ra[i][f] != rb[i][f]]
    if diff:
        bad.append((i, {f: (ra[i][f], rb[i][f]) for f in diff}))
used = [f for f in fields if any(f in ra[i] and f in rb[i] for i in ids)]
print(f'{len(ids) - len(bad)}/{len(ids)} identical on {used}; only-in-ref {len(set(ra) - set(rb))}, only-in-cand {len(set(rb) - set(ra))}')
for i, d in bad:
    print('  MISMATCH', i, d)
sys.exit(1 if bad or len(ids) == 0 else 0)
