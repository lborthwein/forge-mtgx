#!/usr/bin/python3
"""Cube card drift between the fork merge-base and an upstream ref.
usage: cube_drift.py <forge-repo> <base-ref> <target-ref> <cube.json> <out.json>"""
import json, subprocess, sys, re, unicodedata
repo, base, target, cubef, outf = sys.argv[1:6]
def git(*a, inp=None):
    return subprocess.run(['git', '-C', repo, *a], input=inp, capture_output=True, check=True).stdout
def tree(ref):
    out = git('ls-tree', '-r', ref, '--', 'forge-gui/res/cardsfolder').decode()
    m = {}
    for line in out.splitlines():
        meta, path = line.split('\t', 1)
        m[path] = meta.split()[2]
    return m
def blobs(shas):
    data = git('cat-file', '--batch', inp=('\n'.join(shas) + '\n').encode())
    res, i = {}, 0
    for s in shas:
        nl = data.index(b'\n', i); hdr = data[i:nl].decode().split(); size = int(hdr[2])
        res[s] = data[nl+1:nl+1+size].decode('utf-8', 'replace'); i = nl + 1 + size + 1
    return res
def norm(n):
    n = unicodedata.normalize('NFKD', n).encode('ascii', 'ignore').decode().lower()
    return re.sub(r'\s+', ' ', n).strip()
def names_of(text):
    return [norm(l.split(':', 1)[1]) for l in text.splitlines() if l.startswith('Name:')]
B, T = tree(base), tree(target)
bb = blobs(sorted(set(B.values())))
idxB = {}
for p, s in B.items():
    for n in names_of(bb[s]): idxB.setdefault(n, []).append(p)
changed = sorted(p for p in T if B.get(p) != T[p])
tb = blobs(sorted(set(T[p] for p in changed)))
idxT = {}
for p in changed:
    for n in names_of(tb[T[p]]): idxT.setdefault(n, []).append(p)
removed = sorted(p for p in B if p not in T)
cube = json.load(open(cubef))
rows = []
for c in cube['cards']:
    faces = [norm(x) for x in c['name'].split(' // ')]
    pathsB = sorted({p for f in faces for p in idxB.get(f, [])})
    pathsT = sorted({p for f in faces for p in idxT.get(f, [])})
    row = {'name': c['name'], 'baseScripts': pathsB}
    if not pathsB and pathsT: row['status'] = 'GAINED_SUPPORT'
    elif not pathsB: row['status'] = 'MISSING_BOTH'
    else:
        ch = [p for p in pathsB if p in T and T[p] != B[p]]
        gone = [p for p in pathsB if p not in T]
        row['status'] = 'CHANGED' if ch else ('MOVED' if gone else 'SAME')
        if ch: row['changedScripts'] = ch
        if gone: row['movedFrom'] = gone; row['movedTo'] = pathsT
    rows.append(row)
summary = {}
for r in rows: summary[r['status']] = summary.get(r['status'], 0) + 1
json.dump({'base': base, 'target': target, 'cube': cube.get('shortId'), 'cubeCards': len(rows), 'summary': summary,
           'scriptsChangedOrAdded': len(changed), 'scriptsRemoved': len(removed),
           'cards': [r for r in rows if r['status'] != 'SAME']}, open(outf, 'w'), indent=1)
print(summary, 'changed/added scripts', len(changed), 'removed', len(removed))
