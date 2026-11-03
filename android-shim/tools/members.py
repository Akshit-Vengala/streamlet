import json, sys
# usage: members.py refs.json substring...   -> prints members whose owner contains any substring
d = json.load(open(sys.argv[1]))
subs = sys.argv[2:]
for m in d['members']:
    if any(s in m[1] for s in subs):
        print(m[1].split('/', 3)[-1] if False else m[1], m[2], m[3], 'S' if m[4] else '', m[0][0])
